package com.yuanbao.autolyrics.lyric.source

import android.util.Base64
import com.yuanbao.autolyrics.data.TrackInfo
import com.yuanbao.autolyrics.lyric.Candidate
import com.yuanbao.autolyrics.lyric.LyricSource
import com.yuanbao.autolyrics.lyric.RawFormat
import com.yuanbao.autolyrics.lyric.RawLyric
import com.yuanbao.autolyrics.lyric.parser.LyricParser
import com.yuanbao.autolyrics.util.Http
import com.yuanbao.autolyrics.util.TextMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream

/**
 * 酷狗音乐。
 *
 * ## 检索：先用歌曲库拿 FileHash，再用 hash 精确取词元
 * 参考开源实现（lx-music `src/common/utils/lyricUtils/kg.js`、ESLyric `krc.js`）：
 *  1. `songsearch.kugou.com/song_search_v2?keyword=` → `FileHash`（音频指纹，唯一确定一首歌）
 *  2. `krcs.kugou.com/search?hash=<FileHash>` → 歌词的 `id` + `accesskey`
 *  3. `lyrics.kugou.com/download?fmt=krc` → 动感歌词（逐字）
 *
 * 旧实现直接用「歌名+歌手」关键词打 `krcs` 接口，返回的 `duration` 秒/毫秒混用、
 * 还夹着用户上传的脏数据（同一首歌有 223 / 234000 / 32000 三种值），
 * 而且部分条目 `song`/`singer` 还填反。走 hash 之后：时长统一准确、
 * 标题歌手顺序正常，同名歌（再见版 / 合唱版）也能靠时长区分。
 *
 * ## 取词：KRC 是加密的
 * 参考 [lyswhut/lx-music-desktop#296](https://github.com/lyswhut/lx-music-desktop/issues/296)
 * 与 ESLyric 的 `krc.js`，解密流程完全一致：
 * ```
 * base64 解码 → 去掉前 4 字节 "krc1" 头 → 逐字节与 16 字节固定密钥循环异或 → zlib 解压 → UTF-8 文本
 * ```
 * 解出来是 `[行起始,行时长]<字起始,字时长,0>字…`，交给 KrcParser 得到**逐字歌词**。
 * 另外 KRC 里的 `[language:<base64>]` 携带译文（type=0 原词 / type=1 译文），一并提取。
 */
object KugouSource : LyricSource {

    override val id = "kugou"
    override val displayName = "酷狗音乐"

    private val HEADERS = mapOf(
        "Referer" to "https://www.kugou.com/",
        "Accept" to "application/json, text/plain, */*",
    )

    /** KRC 解密密钥：酷狗固定的 16 字节，循环异或。 */
    private val KRC_XOR_KEY = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5E, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2D, 0xCE.toByte(), 0xD2.toByte(), 0x6E, 0x69.toByte(),
    )

    private val WORD_LINE = Regex("""\[\d{1,8}\s*,\s*\d{1,8}\s*]\s*<""")
    private val LINE_TIME = Regex("""^\s*\[(\d+)\s*,\s*\d+\s*]""", RegexOption.MULTILINE)
    private val LANGUAGE_TAG = Regex("""\[language:([A-Za-z0-9+/=]+)]""")
    private val HTML_TAG = Regex("<[^>]+>")

    override suspend fun search(track: TrackInfo): List<Candidate> = withContext(Dispatchers.IO) {
        val byHash = searchByHash(track)
        if (byHash.isNotEmpty()) return@withContext byHash
        // 兜底：歌曲库查不到时退回关键词检索（脏数据较多，做字段与时长纠错）
        searchByKeyword(track)
    }

    // ---------------- 检索 ----------------

    /** 歌曲库检索 → FileHash → krcs 精确取词元。 */
    private suspend fun searchByHash(track: TrackInfo): List<Candidate> {
        val keyword = if (track.artist.isBlank()) track.title else "${track.title} ${track.artist}"
        val url = "https://songsearch.kugou.com/song_search_v2?keyword=${Http.enc(keyword)}" +
                "&page=1&pagesize=8&userid=-1&platform=WebFilter&tag=em&filter=2&iscorrection=1&privilege_filter=0"
        val lists = try {
            JSONObject(Http.get(url, HEADERS)).optJSONObject("data")?.optJSONArray("lists")
        } catch (_: Throwable) {
            null
        } ?: return emptyList()

        val out = ArrayList<Candidate>()
        // 只给前 3 个候选补一次 hash 查询：既覆盖同名异版，又控制请求量
        for (i in 0 until minOf(lists.length(), 3)) {
            val s = lists.optJSONObject(i) ?: continue
            val hash = s.optString("FileHash").takeIf { it.isNotBlank() } ?: continue
            val title = HTML_TAG.replace(s.optString("SongName"), "").trim()
            val artist = HTML_TAG.replace(s.optString("SingerName"), "").trim()
            val durationMs = sanitizeDuration(s.optLong("Duration", 0L) * 1000L)

            val meta = fetchLyricMeta(hash) ?: continue
            out += Candidate(
                sourceId = id,
                id = meta.first,
                title = title.ifBlank { meta.second },
                artist = artist,
                durationMs = durationMs,
                extra = mapOf("accesskey" to meta.third),
            )
        }
        return out
    }

    /** 用 FileHash 换歌词的 id + accesskey；返回 (lyricId, songName, accesskey)。 */
    private suspend fun fetchLyricMeta(hash: String): Triple<String, String, String>? {
        val url = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=&duration=&hash=$hash"
        val arr = try {
            JSONObject(Http.get(url, HEADERS)).optJSONArray("candidates")
        } catch (_: Throwable) {
            null
        } ?: return null

        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val cid = c.optString("id").takeIf { it.isNotBlank() } ?: continue
            val ak = c.optString("accesskey").takeIf { it.isNotBlank() } ?: continue
            return Triple(cid, c.optString("song"), ak)
        }
        return null
    }

    /** 关键词兜底：字段可能填反、时长单位混乱，两处都做容错。 */
    private suspend fun searchByKeyword(track: TrackInfo): List<Candidate> {
        val keyword = if (track.artist.isBlank()) track.title else "${track.title} - ${track.artist}"
        val durationSec = (track.durationMs / 1000).coerceAtLeast(0)
        val url = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi" +
                "&keyword=${Http.enc(keyword)}&duration=$durationSec&hash="
        return try {
            val arr = JSONObject(Http.get(url, HEADERS)).optJSONArray("candidates")
            if (arr == null) return emptyList()
            val normTitle = TextMatch.normalize(track.title)
            val normArtist = TextMatch.normalize(track.artist, isArtist = true)
            val out = ArrayList<Candidate>(arr.length())
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val cid = c.optString("id").takeIf { it.isNotBlank() } ?: continue
                val song = c.optString("song")
                val singer = c.optString("singer")
                // 脏条目 song/singer 填反（如「稻香」条目 song=周杰伦）：按检索词判别
                val direct = TextMatch.similarity(normTitle, TextMatch.normalize(song)) +
                        TextMatch.similarity(normArtist, TextMatch.normalize(singer, isArtist = true))
                val flipped = TextMatch.similarity(normTitle, TextMatch.normalize(singer)) +
                        TextMatch.similarity(normArtist, TextMatch.normalize(song, isArtist = true))
                val (title, artist) = if (flipped > direct) singer to song else song to singer
                out += Candidate(
                    sourceId = id,
                    id = cid,
                    title = title,
                    artist = artist,
                    durationMs = sanitizeDuration(normalizeDuration(c.optLong("duration", 0L))),
                    extra = mapOf("accesskey" to c.optString("accesskey")),
                )
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ---------------- 取词 ----------------

    override suspend fun fetch(candidate: Candidate): RawLyric? = withContext(Dispatchers.IO) {
        val accessKey = candidate.extra["accesskey"] ?: return@withContext null
        fetchKrc(candidate.id, accessKey)?.let { return@withContext it }
        fetchLrc(candidate.id, accessKey)
    }

    private suspend fun fetchKrc(id: String, accessKey: String): RawLyric? = try {
        val url = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id" +
                "&accesskey=$accessKey&fmt=krc&charset=utf8"
        val jo = JSONObject(Http.get(url, HEADERS))
        if (jo.optInt("status", -1) != 200) null
        else {
            val content = jo.optString("content")
            if (content.isBlank()) null
            else decryptKrc(Base64.decode(content, Base64.DEFAULT))?.let { text ->
                if (!WORD_LINE.containsMatchIn(text)) null
                else RawLyric(main = text, translation = buildTranslation(text), format = RawFormat.KRC)
            }
        }
    } catch (_: Throwable) {
        null
    }

    private suspend fun fetchLrc(id: String, accessKey: String): RawLyric? = try {
        val url = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id" +
                "&accesskey=$accessKey&fmt=lrc&charset=utf8"
        val jo = JSONObject(Http.get(url, HEADERS))
        if (jo.optInt("status", -1) != 200) null
        else {
            val content = jo.optString("content")
            if (content.isBlank()) null
            else RawLyric(String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8))
        }
    } catch (_: Throwable) {
        null
    }

    // ---------------- KRC 解密 ----------------

    /** 去 krc1 头 → 循环异或 → zlib 解压 → 文本（UTF-8；出现替换符时改按 GBK 再试）。 */
    private fun decryptKrc(data: ByteArray): String? = try {
        if (data.size <= 4) null
        else {
            val payload = ByteArray(data.size - 4)
            for (i in payload.indices) {
                payload[i] = (data[i + 4].toInt() xor KRC_XOR_KEY[i and 15].toInt()).toByte()
            }
            val bytes = InflaterInputStream(ByteArrayInputStream(payload)).use { input ->
                val out = ByteArrayOutputStream(payload.size * 4)
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            var text = String(bytes, Charsets.UTF_8)
            if ('\uFFFD' in text) {
                val gbk = try { String(bytes, charset("GBK")) } catch (_: Throwable) { text }
                if (countReplacement(gbk) < countReplacement(text)) text = gbk
            }
            text
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * KRC 的译文藏在 `[language:<base64>]` 里，形如
     * `{"content":[{"type":0,"lyricContent":[["原","词"]]},{"type":1,"lyricContent":[["译","文"]]}]}`
     * type=0 是原词注音，type=1 是译文。这里拼成带时间轴的 LRC，供解析器按时间对齐。
     */
    private fun buildTranslation(krc: String): String? {
        val match = LANGUAGE_TAG.find(krc) ?: return null
        val lines = try {
            val json = String(Base64.decode(match.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
            val arr = JSONObject(json).optJSONArray("content") ?: return null
            var found: JSONArray? = null
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optInt("type", -1) == 1) {
                    found = item.optJSONArray("lyricContent")
                    break
                }
            }
            found ?: return null
        } catch (_: Throwable) {
            return null
        }

        val times = LINE_TIME.findAll(krc).mapNotNull { it.groupValues[1].toLongOrNull() }.toList()
        val count = minOf(times.size, lines.length())
        if (count == 0) return null
        return buildString {
            for (i in 0 until count) {
                val text = joinLine(lines.opt(i))
                if (text.isBlank()) continue
                if (isNotEmpty()) append('\n')
                append("[${LyricParser.formatTime(times[i])}]$text")
            }
        }.takeIf { it.isNotBlank() }
    }

    private fun joinLine(any: Any?): String = when (any) {
        is JSONArray -> (0 until any.length()).joinToString("") { i -> any.optString(i) }
        is String -> any
        else -> any?.toString().orEmpty()
    }

    private fun countReplacement(s: String): Int {
        var n = 0
        for (c in s) if (c == '\uFFFD') n++
        return n
    }

    // ---------------- 时长容错 ----------------

    /** 关键词检索返回的 `duration` 秒/毫秒混用：≥100_000 只可能是毫秒。 */
    private fun normalizeDuration(raw: Long): Long = when {
        raw <= 0L -> 0L
        raw >= 100_000L -> raw
        else -> raw * 1000L
    }

    /** 不在 30 秒 ~ 30 分钟视为不可信，置 0 让打分走中性分。 */
    private fun sanitizeDuration(ms: Long): Long =
        if (ms in 30_000L..1_800_000L) ms else 0L
}

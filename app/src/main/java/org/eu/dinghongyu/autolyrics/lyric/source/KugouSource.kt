/*
 * AutoLyrics — 安卓自动歌词
 * Copyright (C) 2026 丁宏宇
 *
 * 本程序遵循 GNU General Public License v3.0 或更高版本发布。
 * 详见仓库根目录的 LICENSE 文件。
 *
 * 部分歌词格式的解析流程参考了以下开源项目（详见 BUILD.md 的调研记录）：
 *   - lyswhut/lx-music-desktop (Apache-2.0)
 *   - Robotxm/ESLyric-LyricsSource (GPL-3.0)
 *   - jsososo/QQMusicApi (GPL-3.0)
 */

package org.eu.dinghongyu.autolyrics.lyric.source

import android.util.Base64
import org.eu.dinghongyu.autolyrics.data.TrackInfo
import org.eu.dinghongyu.autolyrics.lyric.Candidate
import org.eu.dinghongyu.autolyrics.lyric.LyricSource
import org.eu.dinghongyu.autolyrics.lyric.RawFormat
import org.eu.dinghongyu.autolyrics.lyric.RawLyric
import org.eu.dinghongyu.autolyrics.lyric.SearchOutcome
import org.eu.dinghongyu.autolyrics.lyric.parser.LyricParser
import org.eu.dinghongyu.autolyrics.util.Http
import org.eu.dinghongyu.autolyrics.util.TextMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Collections
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

    /**
     * v1.13.9：`FileHash → (歌词id, 曲名, accesskey)` 的内存缓存。
     *
     * ## 为什么值得缓存
     *
     * FileHash 是**音频指纹**，永久唯一 —— 同一首歌在任何时候、
     * 任何设备上查它，拿到的歌词 id 与 accesskey 都是一样的。
     * 而每次切歌都要为前 3 个候选各查一次（`fetchLyricMeta`），
     * 重播同一首歌就是把这3 跳网络原封不动再做一遍。
     *
     * 这是纯粹的重复劳动，缓存它零风险。
     *
     * ## 为什么用 LRU 而不是无限增长
     *
     * 一首歌约150 字节，一万首也只有 1.5MB，但会话内没必要留那么多。
     * 用 [LinkedHashMap] 的 accessOrder 模式，容量 512 首足够覆盖
     * 「随机听歌时反复切回最近几首」的实际模式。
     *
     * ## 只缓存成功结果
     *
     * 失败（null）**不写入**：那通常是网络抖动，一次失败不代表
     * 这首歌取不到词元，缓存下来会把瞬时故障固化成长期空结果 ——
     * 与歌词本身的负缓存同一个教训。
     *
     * ## 为什么必须包synchronizedMap（v1.13.9 踩过一次）
     *
     * 上面那3 次 hash 查询是 `async` 并发的，而 [search]整体被
     * `withContext(Dispatchers.IO)` 包着 —— `async` **继承**当前上下文，
     * 于是三个协程会真的跑在 IO 线程池的不同线程上。
     *
     * 而 accessOrder = true 的 LinkedHashMap 是**读操作也会改结构**
     *（get 命中时把节点挪到链表末尾）。并发 get/put 可能造成链表指针
     * 损坏，极端情况下 `get` 会陷入死循环把线程挂死。
     *
     * 所以用 [Collections.synchronizedMap] 整体加锁，
     * 既保住 LRU 淘汰语义，又让并发访问安全。
     * 代价是读写都要串行一瞬 —— 但这只是纳秒级的内存操作，
     * 相对它省下的三次网络请求完全可以忽略。
     */
    private val META_CACHE_SIZE = 512
    private val metaCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Triple<String, String, String>>(
            META_CACHE_SIZE, 0.75f, true,
        ) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Triple<String, String, String>>?): Boolean =
                size > META_CACHE_SIZE
        },
    )

    /** 缓存读取。命中即省掉一次网络请求。 */
    private fun metaCacheGet(hash: String): Triple<String, String, String>? = metaCache[hash]

    /** 缓存写入，仅在 [fetchLyricMeta] 成功时调用。 */
    private fun metaCachePut(hash: String, meta: Triple<String, String, String>) {
        metaCache[hash] = meta
    }

    override suspend fun search(track: TrackInfo): SearchOutcome = withContext(Dispatchers.IO) {
        val byHash = searchByHash(track)
        if (byHash.candidates.isNotEmpty()) return@withContext byHash
        // 兜底：歌曲库查不到时退回关键词检索（脏数据较多，做字段与时长纠错）
        // 注意：歌曲库这一步失败（failed）时不直接放弃，
        // 关键词那条路可能仍然通 —— 交给下一条路自己判断成败。
        searchByKeyword(track)
    }

    // ---------------- 检索 ----------------

    /**
     * 歌曲库检索 → FileHash → krcs 精确取词元。
     *
     * v1.12.7：异常不再吞成空列表。旧版这里 `catch { null } ?: return emptyList()`，
     * 于是「网络不通」与「确实没有」在上层完全无法区分 ——
     * 熄屏切歌时会把断网当成「没歌词」写进负缓存。
     */
    private suspend fun searchByHash(track: TrackInfo): SearchOutcome {
        val keyword = if (track.artist.isBlank()) track.title else "${track.title} ${track.artist}"
        val url = "https://songsearch.kugou.com/song_search_v2?keyword=${Http.enc(keyword)}" +
                "&page=1&pagesize=8&userid=-1&platform=WebFilter&tag=em&filter=2&iscorrection=1&privilege_filter=0"
        val lists = try {
            JSONObject(Http.get(url, HEADERS)).optJSONObject("data")?.optJSONArray("lists")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return SearchOutcome.failed()
        }
        // 有响应但结构不对（如风控返回的HTML/空对象）也算没查成，
        // 不能当成「这首歌不在库里」。
        if (lists == null) return SearchOutcome.failed()

        val out = ArrayList<Candidate>()
        // 只给前 3 个候选补一次 hash 查询：既覆盖同名异版，又控制请求量
        //
        // v1.13.9：这三跳改为**并发**。它们互不依赖（各自用自己的 hash），
        // 串行等于白等三倍时间。并发后耗时取最慢的那一个。
        val targets = (0 until minOf(lists.length(), 3)).mapNotNull { i ->
            val s = lists.optJSONObject(i) ?: return@mapNotNull null
            val hash = s.optString("FileHash").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MetaTarget(
                hash = hash,
                title = HTML_TAG.replace(s.optString("SongName"), "").trim(),
                artist = HTML_TAG.replace(s.optString("SingerName"), "").trim(),
                durationMs = sanitizeDuration(s.optLong("Duration", 0L) * 1000L),
            )
        }

        val metas = coroutineScope {
            targets.map { t -> async { fetchLyricMeta(t.hash) } }.awaitAll()
        }

        var anyMetaFailed = false
        for ((i, t) in targets.withIndex()) {
            val meta = metas[i]
            if (meta == null) { anyMetaFailed = true; continue }
            out += Candidate(
                sourceId = id,
                id = meta.first,
                title = t.title.ifBlank { meta.second },
                artist = t.artist,
                durationMs = t.durationMs,
                extra = mapOf("accesskey" to meta.third),
            )
        }
        // 一个候选都没拿到，但取词元那步全失败 ⇒ 是请求问题不是没歌词
        if (out.isEmpty() && anyMetaFailed) return SearchOutcome.failed()
        return SearchOutcome.of(out)
    }

    /**
     * 一次「补 hash」的目标：歌曲库给我们的元信息 + 它的 FileHash。
     *
     * v1.13.9 引入。此前这段是 `Pair<Triple<..>, Long>` 式的嵌套，
     * 解构时要数清第几个字段是哪个，读起来极易出错 —— 换成具名字段。
     */
    private class MetaTarget(
        val hash: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
    )

    /**
     * 用 FileHash 换歌词的 id + accesskey；返回 (lyricId, songName, accesskey)。
     *
     * v1.13.9：加了一层 [metaCache]。FileHash 是音频指纹，映射永久有效，
     * 所以命中缓存时**完全不发请求** —— 重播同一首歌能省掉 3 跳。
     *
     * 只缓存成功结果：失败多半是网络抖动，缓存下来会把瞬时故障
     * 固化成「这首歌没有词元」，与歌词负缓存同一个教训。
     */
    private suspend fun fetchLyricMeta(hash: String): Triple<String, String, String>? {
        metaCacheGet(hash)?.let { return it }

        val url = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=&duration=&hash=$hash"
        val arr = try {
            JSONObject(Http.get(url, HEADERS)).optJSONArray("candidates")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } ?: return null

        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val cid = c.optString("id").takeIf { it.isNotBlank() } ?: continue
            val ak = c.optString("accesskey").takeIf { it.isNotBlank() } ?: continue
            val meta = Triple(cid, c.optString("song"), ak)
            metaCachePut(hash, meta)
            return meta
        }
        return null
    }

    /** 关键词兜底：字段可能填反、时长单位混乱，两处都做容错。 */
    private suspend fun searchByKeyword(track: TrackInfo): SearchOutcome {
        val keyword = if (track.artist.isBlank()) track.title else "${track.title} - ${track.artist}"
        val durationSec = (track.durationMs / 1000).coerceAtLeast(0)
        val url = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi" +
                "&keyword=${Http.enc(keyword)}&duration=$durationSec&hash="
        return try {
            val arr = JSONObject(Http.get(url, HEADERS)).optJSONArray("candidates")
            // 有响应但没有 candidates 字段：可能是风控/结构变化，算没查成
            if (arr == null) return SearchOutcome.failed()
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
            SearchOutcome.of(out)
        } catch (e: CancellationException) {
            // 切歌导致的取消必须放行，不能当「没查到」
            throw e
        } catch (_: Throwable) {
            SearchOutcome.failed()
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

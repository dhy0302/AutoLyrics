package com.yuanbao.autolyrics.lyric.source

import com.yuanbao.autolyrics.data.TrackInfo
import com.yuanbao.autolyrics.lyric.Candidate
import com.yuanbao.autolyrics.lyric.LyricSource
import com.yuanbao.autolyrics.lyric.RawFormat
import com.yuanbao.autolyrics.lyric.RawLyric
import com.yuanbao.autolyrics.lyric.parser.LyricParser
import com.yuanbao.autolyrics.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * 网易云音乐。
 *
 * ## 为什么最终走公开 GET 接口而不是 eapi
 *
 * v1.5.0 曾把取词切到 eapi（`/eapi/song/lyric/v1`），**实测全域名404**
 * （`music.163.com` / `interface.music.163.com` 均返回 `{"code":404,"message":"接口未找到"}`），
 * eapi 路由已经下线。这正是用户反馈「能搜到但歌词为空，显示某某歌无歌词」的直接原因：
 * 搜索走的是公开 `/api/cloudsearch/pc`（能通），取词走 eapi（404 → null），
 * 于是 [LyricRepository] 把候选逐个标成「《xxx》无歌词」。
 *
 * 实测可用的通道（2026-10 复核）：
 *
 * | 用途 | 接口 | 结果 |
 * |------|------|------|
 * | 搜索 | `GET /api/search/get/web?s=&type=1&offset=0&limit=10` | 200，候选带`artists` + `duration` |
 * | 搜索 | `GET /api/cloudsearch/pc?s=...` | 200，但 `artists`/`duration` 常为 null |
 * | 取词 | `GET /api/song/lyric/v1?id=&lv=-1&tv=-1&rv=-1&yv=-1` | 200，`lrc` + `tlyric` |
 * | 取词 | `GET /api/song/lyric/v2` | 404 |
 * | 取词 | eapi 全路径 | 404 |
 *
 * 因此取词只用 `/api/song/lyric/v1`，搜索优先 `search/get/web`（字段更全），
 * 失败再退 `cloudsearch/pc`。
 *
 * ## 逐字的真相
 *
 * 网易云逐字歌词（YRC）需要登录 Cookie，且本次实测匿名 `yrc` 字段为 null。
 * 但 `/api/song/lyric/v1` 的 `lrc.lyric` 字段**本身就可能是逐字格式**：
 * 行首是 `{"t":0,"c":[{"tx":"作词: "},{"tx":"周杰伦"}]}` 这种YRC-JSON，
 * 后面又跟普通 `[mm:ss.xx]text` 行（同一首歌混排）。
 * 所以 [fetch] 会先探测是否含 YRC-JSON 行：含则走 YRC 解析（拿到行内逐字），
 * 否则按普通 LRC 处理。
 */
object NeteaseSource : LyricSource {

    override val id = "netease"
    override val displayName = "网易云音乐"

    private val HOSTS = listOf("music.163.com", "interface.music.163.com")

    /** YRC-JSON 行：`{"t":0,"c":[{"tx":"作词"},{"tx":"周杰伦"}]}` */
    private val YRC_JSON_LINE = Regex("""^\{"t":\s*(\d+)\s*,""")
    private val TX_FIELD = Regex(""""tx"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    /** 词级时长：`"t":1234`（在 `c` 数组元素内部） */
    private val WORD_DURATION = Regex(""""t"\s*:\s*\d+""")

    /** 进程内固定的随机 __csrf（32 位十六进制），避免每次请求都变动触发风控。 */
    private val csrfToken: String = buildString {
        val rnd = SecureRandom()
        repeat(16) { append("%02x".format(rnd.nextInt(256))) }
    }

    private val BASE_HEADERS = mapOf(
        "Referer" to "https://music.163.com/",
        "Accept" to "application/json, text/plain, */*",
    )

    /**
     * 请求 Cookie。
     *
     * v1.8.0：移除了网易云扫码登录（[NeteaseLogin]、[QrBitmap]、[WeapiCrypto] 已一并删除），
     * 取词改为**纯匿名**——只带一个进程内固定的 `__csrf`（比每次随机更不容易触发风控）。
     *
     * 匿名已能稳定拿到整行歌词 + 译文，登录态不再是取词的前置条件，
     * 因此这里不再读取 `Settings.sourceCookies`。
     */
    private fun cookie(): String = "appver=2.9.7; os=pc; __csrf=$csrfToken"

    // ---------------- 搜索 ----------------

    override suspend fun search(track: TrackInfo): List<Candidate> = withContext(Dispatchers.IO) {
        val keyword = if (track.artist.isBlank()) track.title else "${track.title} ${track.artist}"
        val enc = Http.enc(keyword)

        // 1) search/get/web：字段最全（artists + duration 都在），匹配打分靠它
        for (host in HOSTS) {
            val list = getJson(
                "https://$host/api/search/get/web?s=$enc&type=1&offset=0&limit=10"
            )?.optJSONObject("result")?.optJSONArray("songs")
            val out = parseSongs(list)
            if (out.isNotEmpty()) return@withContext out
        }

        // 2) cloudsearch/pc 兜底（能通但常缺 artist/duration，只在必要时用）
        for (host in HOSTS) {
            val jo = getJson("https://$host/api/cloudsearch/pc?s=$enc&type=1&offset=0&limit=10")
            val list = jo?.optJSONObject("result")?.optJSONArray("songs") ?: jo?.optJSONArray("songs")
            val out = parseSongs(list)
            if (out.isNotEmpty()) return@withContext out
        }
        emptyList()
    }

    private suspend fun getJson(url: String): JSONObject? = try {
        JSONObject(Http.get(url, BASE_HEADERS + ("Cookie" to cookie())))
    } catch (_: Throwable) {
        null
    }

    // ---------------- 取词 ----------------

    override suspend fun fetch(candidate: Candidate): RawLyric? = withContext(Dispatchers.IO) {
        val songId = candidate.id.toLongOrNull() ?: return@withContext null
        // lv/tv/rv/yv 全部 -1：一次请求同时要整行 / 翻译 / 音译 / 逐字
        val urlSuffix = "id=$songId&lv=-1&tv=-1&rv=-1&yv=-1"

        var jo: JSONObject? = null
        for (host in HOSTS) {
            jo = getJson("https://$host/api/song/lyric/v1?$urlSuffix")
            if (jo != null && jo.optInt("code", 0) == 200) break
            jo = null
        }
        if (jo == null) return@withContext null

        val lrc = jo.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val yrc = jo.optJSONObject("yrc")?.optString("lyric").orEmpty()
        val tlyric = jo.optJSONObject("tlyric")?.optString("lyric").orEmpty()
        val romalrc = jo.optJSONObject("romalrc")?.optString("lyric").orEmpty()

        // 译文优先 tlyric；没有则用音译兜底（部分纯外语歌只有 romalrc 可用）
        val translation = tlyric.takeIf { it.isNotBlank() }?.let { LyricParser.unescape(it) }
            ?: romalrc.takeIf { it.isNotBlank() }?.let { LyricParser.unescape(it) }

        // 1) 真正的 yrc 字段（登录态或该接口偶尔下发），带词级时长，走 YRC 解析
        if (yrc.isNotBlank() && yrc.contains("\"c\"")) {
            return@withContext RawLyric(yrc, translation, RawFormat.YRC)
        }
        if (lrc.isBlank()) return@withContext null

        // 2) lrc 字段可能是「YRC-JSON 行 + 普通 LRC 行」混排，两种情况要分开处理：
        //
        //  a) JSON 行带词级时长（c[].t 有值）→ 真正的逐字，整份走 YRC 解析。
        //     尾部可能跟的 LRC 行是同内容的整行副本，丢弃不影响。
        //
        //  b) JSON 行没有词级时长（c[].t 全缺省）→ 实测这种情况只是「作词/作曲/制作人」
        //     这类元信息，**正文仍在后面的普通 LRC 行里**。此时绝不能整份丢给 YrcParser，
        //     它只认 `{` 开头的行，会把正文全部丢掉。统一归一化成标准 LRC 交给 LyricParser。
        if (hasWordLevelYrcJson(lrc)) {
            return@withContext RawLyric(lrc, translation, RawFormat.YRC)
        }
        RawLyric(LyricParser.unescape(normalizeYrcJson(lrc)), translation, RawFormat.LRC)
    }

    /**
     * 判断 YRC-JSON 行里是否带**词级时长**。
     *
     * 判据：行内 `c` 数组的元素出现数值型 `"t"` 字段。
     * 顶层那个行起始 `"t"` 不算（每行都有，不能区分）。
     */
    private fun hasWordLevelYrcJson(src: String): Boolean {
        for (raw in src.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("{")) continue
            val cStart = line.indexOf("\"c\"")
            if (cStart < 0) continue
            val cEnd = line.lastIndexOf('}')
            if (cEnd <= cStart) continue
            val cPart = line.substring(cStart, cEnd)
            if (WORD_DURATION.containsMatchIn(cPart)) return true
        }
        return false
    }

    /**
     * 把 YRC-JSON 混排文本里的 JSON 行**转成标准 LRC 行**。
     *
     * 网易云返回的 `lrc.lyric` 常常是「JSON 行 + 普通 LRC 行」混排。普通 LRC 行原样保留，
     * JSON 行则把 `c[].tx` 拼成文本、`t` 转成 `[mm:ss.xx]`。这样 [LyricParser] 能统一处理，
     * 不必让主解析器同时认识两种格式。
     */
    fun normalizeYrcJson(src: String): String {
        val out = StringBuilder(src.length)
        for (raw in src.replace("\r\n", "\n").replace('\r', '\n').lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val m = YRC_JSON_LINE.find(line)
            if (m != null) {
                val tMs = m.groupValues[1].toLongOrNull() ?: continue
                val text = TX_FIELD.findAll(line)
                    .map { it.groupValues[1] }
                    .joinToString("") { unescapeJson(it) }
                    .trim()
                if (text.isNotEmpty()) {
                    out.append('[').append(LyricParser.formatTime(tMs)).append(']')
                    out.append(text).append('\n')
                }
            } else {
                out.append(line).append('\n')
            }
        }
        return out.toString()
    }

    private fun unescapeJson(s: String): String = s
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .replace("\\/", "/")

    private fun parseSongs(list: JSONArray?): List<Candidate> {
        if (list == null) return emptyList()
        val out = ArrayList<Candidate>(list.length())
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val songId = s.optLong("id", 0L).takeIf { it > 0 }?.toString() ?: continue
            val title = s.optString("name").takeIf { it.isNotBlank() } ?: continue
            val artists = s.optJSONArray("artists") ?: s.optJSONArray("ar")
            out += Candidate(
                sourceId = id,
                id = songId,
                title = title,
                artist = artistNames(artists),
                durationMs = s.optLong("duration", 0L),
                album = (s.optJSONObject("album") ?: s.optJSONObject("al"))?.optString("name").orEmpty(),
            )
        }
        return out
    }

    private fun artistNames(arr: JSONArray?): String {
        if (arr == null) return ""
        val names = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { names += it }
        }
        return names.joinToString(" / ")
    }
}

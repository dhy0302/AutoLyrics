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

import org.eu.dinghongyu.autolyrics.data.TrackInfo
import org.eu.dinghongyu.autolyrics.lyric.Candidate
import org.eu.dinghongyu.autolyrics.lyric.LyricSource
import org.eu.dinghongyu.autolyrics.lyric.RawFormat
import org.eu.dinghongyu.autolyrics.lyric.RawLyric
import org.eu.dinghongyu.autolyrics.lyric.SearchOutcome
import org.eu.dinghongyu.autolyrics.lyric.parser.LyricParser
import org.eu.dinghongyu.autolyrics.util.Http
import kotlinx.coroutines.CancellationException
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

    /**
     * v1.12.7：异常不再吞成空列表。
     *
     * 旧版两个接口全失败时返回 `emptyList()`，
     * 与「确实没有候选」无法区分 —— 熄屏切歌时断网会被上层
     * 当成「没歌词」写进负缓存（3 天），表现为亮屏后一直「没找到歌词」。
     * 现在任一接口请求失败即标记 [SearchOutcome.failed]。
     */
    override suspend fun search(track: TrackInfo): SearchOutcome = withContext(Dispatchers.IO) {
        val keyword = if (track.artist.isBlank()) track.title else "${track.title} ${track.artist}"
        val enc = Http.enc(keyword)
        var anyRequestFailed = false

        // 1) search/get/web：字段最全（artists + duration 都在），匹配打分靠它
        for (host in HOSTS) {
            val resp = getJson(
                "https://$host/api/search/get/web?s=$enc&type=1&offset=0&limit=10"
            )
            if (resp == null) { anyRequestFailed = true; continue }
            val out = parseSongs(resp.optJSONObject("result")?.optJSONArray("songs"))
            if (out.isNotEmpty()) return@withContext SearchOutcome.of(out)
        }

        // 2) cloudsearch/pc 兜底（能通但常缺 artist/duration，只在必要时用）
        for (host in HOSTS) {
            val jo = getJson("https://$host/api/cloudsearch/pc?s=$enc&type=1&offset=0&limit=10")
            if (jo == null) { anyRequestFailed = true; continue }
            val list = jo.optJSONObject("result")?.optJSONArray("songs") ?: jo.optJSONArray("songs")
            val out = parseSongs(list)
            if (out.isNotEmpty()) return@withContext SearchOutcome.of(out)
        }
        if (anyRequestFailed) SearchOutcome.failed() else SearchOutcome.empty()
    }

    /** null 表示请求失败（网络/风控/结构异常），与「查到了但没结果」区分。 */
    private suspend fun getJson(url: String): JSONObject? = try {
        JSONObject(Http.get(url, BASE_HEADERS + ("Cookie" to cookie())))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    // ---------------- 取词 ----------------

    /**
     * v1.18.0：改返回 [FetchOutcome]，把「没查成」与「确实没有」分开。
     *
     * 旧实现三个返回点全是 `null`，上层一律记成「无歌词」并写负缓存：
     *  - [148] id 解析不出来 → 候选本身有问题，算查成但没内容
     *  - [158] **三个 host 全失败**（网络/风控/code≠200）→ 这是**没查成**
     *  - [173] 请求成功但 lrc 为空 → **确实没有**（VIP/无版权/纯音乐）
     *
     * 第158 行是用户报「选了网易云既没歌词也没暂无歌词、重取也不行」的根因：
     * 网易云需要 Cookie，三个 host 常被风控，失败后走到这里，
     * 上层写负缓存 3 天，之后点重取也直接命中缓存返回，什么都不显示。
     */
    override suspend fun fetch(candidate: Candidate): FetchOutcome = withContext(Dispatchers.IO) {
        val songId = candidate.id.toLongOrNull() ?: return@withContext FetchOutcome.none()
        // lv/tv/rv/yv 全部 -1：一次请求同时要整行 / 翻译 / 音译 / 逐字
        val urlSuffix = "id=$songId&lv=-1&tv=-1&rv=-1&yv=-1"

        var jo: JSONObject? = null
        var anyHostFailed = false
        for (host in HOSTS) {
            val r = getJson("https://$host/api/song/lyric/v1?$urlSuffix")
            if (r != null && r.optInt("code", 0) == 200) { jo = r; break }
            // 拿到响应但 code 不对（风控/ 需要登录），也算这一host 没成
            anyHostFailed = true
        }
        // v1.18.0：全失败 ⇒ 上报 failed，上层不写负缓存并安排退避重试
        if (jo == null) {
            return@withContext if (anyHostFailed) FetchOutcome.failed() else FetchOutcome.none()
        }

        val lrc = jo.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val yrc = jo.optJSONObject("yrc")?.optString("lyric").orEmpty()
        val tlyric = jo.optJSONObject("tlyric")?.optString("lyric").orEmpty()
        val romalrc = jo.optJSONObject("romalrc")?.optString("lyric").orEmpty()

        // 译文优先 tlyric；没有则用音译兜底（部分纯外语歌只有 romalrc 可用）
        val translation = tlyric.takeIf { it.isNotBlank() }?.let { LyricParser.unescape(it) }
            ?: romalrc.takeIf { it.isNotBlank() }?.let { LyricParser.unescape(it) }

        // 1) 真正的 yrc 字段（登录态或该接口偶尔下发），带词级时长，走 YRC 解析
        if (yrc.isNotBlank() && yrc.contains("\"c\"")) {
            return@withContext FetchOutcome.of(RawLyric(yrc, translation, RawFormat.YRC))
        }
        // 请求成功、确实没歌词 —— 这才是「none」而不是「failed」
        if (lrc.isBlank()) return@withContext FetchOutcome.none()

        // 2) lrc 字段可能是「YRC-JSON 行 + 普通 LRC 行」混排，两种情况要分开处理：
        //
        //  a) JSON 行带词级时长（c[].t 有值）→ 真正的逐字，整份走 YRC 解析。
        //     尾部可能跟的 LRC 行是同内容的整行副本，丢弃不影响。
        //
        //  b) JSON 行没有词级时长（c[].t 全缺省）→ 实测这种情况只是「作词/作曲/制作人」
        //     这类元信息，**正文仍在后面的普通 LRC 行里**。此时绝不能整份丢给 YrcParser，
        //     它只认 `{` 开头的行，会把正文全部丢掉。统一归一化成标准 LRC 交给 LyricParser。
        if (hasWordLevelYrcJson(lrc)) {
            return@withContext FetchOutcome.of(RawLyric(lrc, translation, RawFormat.YRC))
        }
        FetchOutcome.of(RawLyric(LyricParser.unescape(normalizeYrcJson(lrc)), translation, RawFormat.LRC))
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

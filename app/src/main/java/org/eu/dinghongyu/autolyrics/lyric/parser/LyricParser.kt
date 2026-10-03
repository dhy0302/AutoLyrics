package org.eu.dinghongyu.autolyrics.lyric.parser

import org.eu.dinghongyu.autolyrics.data.Lyric
import org.eu.dinghongyu.autolyrics.data.LyricLine

/**
 * 普通 LRC 解析。
 *
 * 支持：
 *  - 一行多个时间戳：`[00:12.34][01:20.00]text`
 *  - 整体偏移：`[offset:-500]`
 *  - 逐字增强标签 `<00:12.34>`（本项目不做逐字，直接剥离）
 *  - 译文合并（网易 tlyric / QQ trans），按时间就近对齐（±300ms）
 *  - 无时间轴的纯文本歌词 → 落到 [Lyric.plainText]
 */
object LyricParser {

    /** 时间戳 `[mm:ss.xx]`，秒与毫秒分隔符可能是 . 也可能是 : */
    private val TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** 元数据行 `[ti:xxx]` `[offset:-500]` … */
    private val META = Regex(
        """^\[\s*(ti|ar|al|by|offset|re|ve|au|length|kana|total)\s*:\s*([^\]]*)]""",
        RegexOption.IGNORE_CASE
    )

    private val ENHANCED = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    /** 译文与原文允许的最大时间差 */
    private const val TRANS_TOLERANCE_MS = 300L

    fun parse(main: String?, translation: String? = null, sourceId: String = ""): Lyric {
        if (main.isNullOrBlank()) return Lyric(sourceId = sourceId)

        val (mainMap, offset) = collect(main)
        if (mainMap.isEmpty()) {
            return Lyric(
                sourceId = sourceId,
                offsetMs = offset,
                plainText = plainTextOf(main).ifBlank { null },
            )
        }

        val transIndex = buildTranslationIndex(translation)
        val lines = mainMap.entries
            .sortedBy { it.key }
            .map { (time, text) ->
                LyricLine(time, text, nearestTranslation(transIndex, time))
            }

        return Lyric(
            lines = lines,
            sourceId = sourceId,
            offsetMs = offset,
            hasTranslation = lines.any { !it.translation.isNullOrBlank() },
            wordLevel = false,
            instrumental = isInstrumental(lines),
        )
    }

    /** 把译文 LRC 解析成「时间 → 文本」表，供逐字/整行歌词共用。 */
    fun buildTranslationIndex(lrc: String?): Map<Long, String> {
        if (lrc.isNullOrBlank()) return emptyMap()
        return collect(lrc).first.filterValues { it.isNotBlank() }
    }

    /** 在译文表里找离 [time] 最近的一条；超过容差返回 null。 */
    fun nearestTranslation(index: Map<Long, String>, time: Long): String? {
        if (index.isEmpty()) return null
        index[time]?.let { return it }
        var bestTime: Long? = null
        for ((t, _) in index) {
            val d = kotlin.math.abs(t - time)
            if (d <= TRANS_TOLERANCE_MS && (bestTime == null || d < kotlin.math.abs(bestTime - time))) {
                bestTime = t
            }
        }
        return bestTime?.let { index[it] }
    }

    fun isInstrumental(lines: List<LyricLine>): Boolean =
        lines.size <= 1 && lines.firstOrNull()?.text.orEmpty().contains("纯音乐")

    /** 二分查找：返回 `timeMs <= positionMs` 的最后一行下标，找不到返回 -1。 */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty() || positionMs < 0) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** 把 LRC 文本序列化回去（缓存用）。 */
    fun serialize(lines: List<LyricLine>): String =
        lines.joinToString("\n") { "[${formatTime(it.timeMs)}]${it.text}" }

    fun formatTime(ms: Long): String {
        val m = ms / 60_000
        val s = (ms % 60_000) / 1_000
        val cs = (ms % 1_000) / 10
        return "%02d:%02d.%02d".format(m, s, cs)
    }

    /** 部分站点的歌词里有 HTML 实体，简单还原。 */
    fun unescape(s: String): String = s
        .replace("&#10;", "\n")
        .replace("&#13;", "")
        .replace("&#32;", " ")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private fun plainTextOf(src: String): String = src.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() && !META.containsMatchIn(it) && !TIME.containsMatchIn(it) }
        .joinToString("\n")

    /**
     * 逐行扫描，返回「时间 → 文本」表与整体偏移。
     * 用 Map 而非 List：一行多个时间戳时天然去重，且天然按插入顺序可再排序。
     */
    private fun collect(src: String): Pair<Map<Long, String>, Long> {
        val out = LinkedHashMap<Long, String>()
        var offset = 0L

        src.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach

            val meta = META.find(line)
            if (meta != null) {
                if (meta.groupValues[1].lowercase() == "offset") {
                    offset = meta.groupValues[2].trim().toLongOrNull() ?: 0L
                }
                return@forEach
            }

            val tags = TIME.findAll(line).toList()
            if (tags.isEmpty()) return@forEach

            val text = ENHANCED.replace(line.substring(tags.last().range.last + 1), "").trim()
            tags.forEach { t ->
                val min = t.groupValues[1].toLongOrNull() ?: return@forEach
                val sec = t.groupValues[2].toLongOrNull() ?: return@forEach
                val frac = t.groupValues[3]
                val ms = when {
                    frac.isEmpty() -> 0L
                    frac.length == 1 -> frac.toLong() * 100
                    frac.length == 2 -> frac.toLong() * 10
                    else -> frac.toLong().coerceAtMost(999)
                }
                out[min * 60_000 + sec * 1_000 + ms] = text
            }
        }
        return out to offset
    }
}

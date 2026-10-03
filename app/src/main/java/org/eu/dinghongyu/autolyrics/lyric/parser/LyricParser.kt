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

    /**
     * 部分站点的歌词里有 HTML 实体，简单还原。
     *
     * v1.12.1：从 8 次连续 `replace` 改为单遍扫描。
     *
     * ## 为什么要改
     * 旧写法是 8 个 `.replace()` 链式调用，而 Kotlin 的 `String.replace` 每次都会
     * **全串拷贝出一个新对象**——8 次就是 8 次分配 + 8 次全串复制。
     * `&#10;`（换行实体）在真实歌词里几乎必然存在，
     * 所以这 8 次拷贝是**每行歌词的稳定成本**，不是偶发。
     *
     * ## 正确性要点：优先级
     * `&amp;` 必须**最后**解码。旧实现正是把 `.replace("&amp;", "&")` 放在链尾，
     * 这样 `&amp;lt;` 会先被前7 次跳过（它不等于 `&lt;`），
     * 到第 8 次才变成 `&lt;`，**不会被二次解码成 `<`**。
     *
     * 单遍扫描天然满足这个顺序：扫描到 `&amp;` 时直接输出 `&`，
     * 不会回头再看自己刚吐出来的字符。这一点必须保住，
     * 否则 `&amp;lt;` 会变成 `<`，等于把歌词内容改了。
     */
    fun unescape(s: String): String {
        // 快路径：绝大多数歌词一行实体都没有，直接原样返回（零分配）
        if (s.indexOf('&') < 0) return s

        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            // 从 & 开始，尝试匹配下面这张表里的实体
            val matched = when {
                s.startsWith("&#10;", i) -> { sb.append('\n'); 5 }
                s.startsWith("&#13;", i) -> { 5 }   // CR 直接丢弃（旧的 replace 也是替换成空串）
                s.startsWith("&#32;", i) -> { sb.append(' '); 5 }
                s.startsWith("&#39;", i) -> { sb.append('\''); 5 }
                s.startsWith("&quot;", i) -> { sb.append('"'); 6 }
                s.startsWith("&lt;", i) -> { sb.append('<'); 4 }
                s.startsWith("&gt;", i) -> { sb.append('>'); 4 }
                // &amp; 放最后：与旧实现的优先级一致，防止 &amp;lt; 被二次解码
                s.startsWith("&amp;", i) -> { sb.append('&'); 5 }
                else -> 0
            }
            if (matched > 0) {
                i += matched
            } else {
                // 不是已知实体，原样吐出这个 &，继续往后扫
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

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

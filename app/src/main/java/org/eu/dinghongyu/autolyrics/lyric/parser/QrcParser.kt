package org.eu.dinghongyu.autolyrics.lyric.parser

import org.eu.dinghongyu.autolyrics.data.Lyric
import org.eu.dinghongyu.autolyrics.data.LyricLine
import org.eu.dinghongyu.autolyrics.data.LyricWord

/**
 * QQ 音乐逐字歌词（QRC）解析。
 *
 * 一行 QRC 长这样：
 * ```
 * [0,3210]<0,320,0>我<320,280,0>是<600,300,0>谁
 * ```
 * - `[行起始, 行时长]`：毫秒
 * - `<字起始, 字时长, 标记>`：个别版本用圆括号 `(字起始,字时长)`，这里两种都认
 *
 * 关于「字起始」是相对行首还是绝对时间：两种版本都出现过，所以这里用
 * 「行时长」做判据——把两种解释分别算出本行结束时间，取更贴近 `[行起始+行时长]` 的那个。
 * 这样无论接口返回哪种口径都能正确对齐。
 */
object QrcParser {

    private val LINE_TAG = Regex("""^\s*\[(\d+)\s*,\s*(\d+)\s*]\s*""")
    private val WORD_TAG = Regex("""[<(]\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*\d+\s*)?[)>]""")
    private val OFFSET_TAG = Regex("""\[\s*offset\s*:\s*(-?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val LYRIC_CONTENT_XML = Regex("LyricContent\\s*=\\s*\"([^\"]*)\"")

    fun parse(raw: String?, translation: String? = null, sourceId: String = ""): Lyric {
        val content = unwrap(raw)
        if (content.isBlank()) return Lyric(sourceId = sourceId)

        val transIndex = LyricParser.buildTranslationIndex(translation)
        val lines = ArrayList<LyricLine>()
        var offset = 0L

        content.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            OFFSET_TAG.find(line)?.let {
                offset = it.groupValues[1].toLongOrNull() ?: 0L
                return@forEach
            }

            val head = LINE_TAG.find(line) ?: return@forEach
            val lineStart = head.groupValues[1].toLongOrNull() ?: return@forEach
            val lineDuration = head.groupValues[2].toLongOrNull() ?: 0L
            val body = line.substring(head.range.last + 1)

            val parsed = parseLine(body, lineStart, lineDuration)
            val text = parsed.text.ifBlank { body.trim() }
            if (text.isBlank()) return@forEach

            lines += LyricLine(
                timeMs = lineStart,
                text = text,
                translation = LyricParser.nearestTranslation(transIndex, lineStart),
                words = parsed.words,
                durationMs = lineDuration,
            )
        }

        if (lines.isEmpty()) return Lyric(sourceId = sourceId, offsetMs = offset)

        lines.sortBy { it.timeMs }
        return Lyric(
            lines = lines,
            sourceId = sourceId,
            offsetMs = offset,
            hasTranslation = lines.any { !it.translation.isNullOrBlank() },
            wordLevel = lines.any { it.isWordLevel },
            instrumental = LyricParser.isInstrumental(lines),
        )
    }

    /**
     * 去掉 XML 外壳。部分接口返回的是：
     * `<Lyric_1 LyricType="1" LyricContent="[0,3210]&lt;0,320,0&gt;我…"/>`。
     */
    private fun unwrap(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val m = LYRIC_CONTENT_XML.find(raw) ?: return raw
        return m.groupValues[1]
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("\\n", "\n")
    }

    private data class ParsedLine(val text: String, val words: List<LyricWord>)

    private fun parseLine(body: String, lineStart: Long, lineDuration: Long): ParsedLine {
        val tags = WORD_TAG.findAll(body).toList()
        if (tags.isEmpty()) return ParsedLine(body.trim(), emptyList())

        // 逐个字：标签后面到下一个标签之间就是这个字的文本
        val raw = ArrayList<Triple<Long, Long, String>>(tags.size)
        for (i in tags.indices) {
            val start = tags[i].groupValues[1].toLongOrNull() ?: continue
            val duration = tags[i].groupValues[2].toLongOrNull() ?: continue
            val from = tags[i].range.last + 1
            val to = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
            val text = if (to > from) body.substring(from, to) else ""
            raw += Triple(start, duration, text)
        }
        if (raw.isEmpty()) return ParsedLine(body.trim(), emptyList())

        // 相对 / 绝对 时间口径判定
        val span = raw.last().first + raw.last().second
        val target = lineStart + lineDuration
        val asRelative = lineStart + span
        val asAbsolute = span
        val base = if (abs(asRelative - target) <= abs(asAbsolute - target)) lineStart else 0L

        val words = raw.map { (start, duration, text) ->
            LyricWord(startMs = base + start, durationMs = duration, text = text)
        }
        return ParsedLine(words.joinToString("") { it.text }, words)
    }

    private fun abs(v: Long): Long = if (v < 0) -v else v
}

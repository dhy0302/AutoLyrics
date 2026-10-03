package com.yuanbao.autolyrics.lyric.parser

import com.yuanbao.autolyrics.data.Lyric
import com.yuanbao.autolyrics.data.LyricLine
import com.yuanbao.autolyrics.data.LyricWord

/**
 * 酷狗逐字歌词（KRC）解析。
 *
 * KRC 与 QQ 的 QRC 同为「行头 [起始,时长] + 逐字 <起,长,标记>」结构，但有两处不同：
 *  1. 逐字时间标签是 `<起始,时长,标记>`，其中「起始」是**相对行首**的毫秒（0,250,430…），
 *     不是 QRC 那种可能绝对也可能相对的两可写法，所以这里直接 `行首 + 起始`。
 *  2. KRC 常在字里内嵌 `<1>原词<2>译词<3>注音` 等内容标签。这里只取 `<1>` 作为显示文本，
 *     `<2>` 拼接成该行译文，其余标签原样剥离，避免把 `<2>讓` 之类的标记混进歌词。
 *
 * 没有逐字时间标签的行（个别 KRC 是整行版）退化为整行歌词处理。
 */
object KrcParser {

    private val LINE_TAG = Regex("""^\s*\[(\d+)\s*,\s*(\d+)\s*]\s*""")
    private val TIME_TAG = Regex("""<\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*\d+\s*)?\s*>""")
    private val CONTENT_TAG = Regex("""<\s*[1-5]\s*>""")
    private val OFFSET_TAG = Regex("""\[\s*offset\s*:\s*(-?\d+)\s*]""", RegexOption.IGNORE_CASE)

    fun parse(raw: String?, translation: String? = null, sourceId: String = ""): Lyric {
        val content = raw ?: return Lyric(sourceId = sourceId)
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
            if (parsed == null) {
                // 无逐字时间标签：整行歌词（顺手剥掉可能残留的内容标签）
                //这里必须显式 trim：整行版KRC 的 body 首尾可能带空格，
                // 而 stripMarkers 已刻意不做 trim（英文逐字空格要保留）。
                val txt = stripMarkers(body).trim()
                if (txt.isNotEmpty()) {
                    lines += LyricLine(
                        timeMs = lineStart,
                        text = txt,
                        translation = LyricParser.nearestTranslation(transIndex, lineStart),
                    )
                }
                return@forEach
            }

            val text = parsed.text.ifBlank { stripMarkers(body).trim() }
            if (text.isBlank()) return@forEach
            lines += LyricLine(
                timeMs = lineStart,
                text = text,
                translation = parsed.trans.takeIf { it.isNotBlank() }
                    ?: LyricParser.nearestTranslation(transIndex, lineStart),
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

    private data class ParsedLine(val text: String, val words: List<LyricWord>, val trans: String)

    private fun parseLine(body: String, lineStart: Long, lineDuration: Long): ParsedLine? {
        val times = TIME_TAG.findAll(body).toList()
        if (times.isEmpty()) return null

        val words = ArrayList<LyricWord>()
        val transParts = ArrayList<String>()
        for (i in times.indices) {
            val startRel = times[i].groupValues[1].toLongOrNull() ?: continue
            val dur = times[i].groupValues[2].toLongOrNull() ?: continue
            val from = times[i].range.last + 1
            val to = if (i + 1 < times.size) times[i + 1].range.first else body.length
            val seg = body.substring(from, to)

            val disp = segDisplay(seg)
            // 注意：必须用 isNotEmpty() 而不是 isNotBlank()。
            //
            // 英文歌词里，KRC 把「空格」本身也当成一个逐字字段，例如：
            //   <0,176,0>Beautiful<176,176,0> <352,176,0>In<528,176,0> <704,176,0>White
            //                ↑Beautiful      ↑" "这个段全是空格      ↑In      ↑" "     ↑White
            // 用 isNotBlank() 会把所有纯空格段判为「空」直接丢弃，
            // 拼接结果就成了 `BeautifulInWhite` ——单词之间没有空格（用户报告的 bug）。
            if (disp.isNotEmpty()) {
                words += LyricWord(startMs = lineStart + startRel, durationMs = dur, text = disp)
            }
            val tr = segTrans(seg)
            if (tr.isNotBlank()) transParts += tr
        }
        if (words.isEmpty()) return null
        // 整行文本做一次 trim（KRC 行首/行尾偶尔会多出纯空格段），
        // 但**逐字 words 列表保持原样不动** —— 里面的空格段是单词间距的来源，动了就又粘连了。
        return ParsedLine(words.joinToString("") { it.text }.trim(), words, transParts.joinToString(""))
    }

    /** 取 `<1>` 之后的原词（截到下一个内容标签 `<2>..<5>` 为止）。 */
    private fun segDisplay(seg: String): String {
        val i = seg.indexOf("<1>")
        val b = if (i >= 0) seg.substring(i + 3) else seg
        val m = CONTENT_TAG.find(b)
        val disp = if (m == null) b else b.substring(0, m.range.first)
        return stripMarkers(disp)
    }

    /** 取 `<2>` 之后的译词。 */
    private fun segTrans(seg: String): String {
        val i = seg.indexOf("<2>")
        if (i < 0) return ""
        val b = seg.substring(i + 3)
        val m = CONTENT_TAG.find(b)
        val tr = if (m == null) b else b.substring(0, m.range.first)
        return stripMarkers(tr)
    }

    /**
     * 剥离内容标签，**但不做 trim**。
     *
     * 英文歌词里空格是独立的逐字段（`<176,176,0> <352,176,0>In`），
     * 一旦 trim 就会把那个纯空格的段变成空串，英文单词间就粘连了。
     * 只在调用方确认「整行无逐字数据」时才由 [parseLine] 的兜底分支另行处理空白。
     */
    private fun stripMarkers(s: String): String = CONTENT_TAG.replace(s, "")
}

package com.yuanbao.autolyrics.lyric.parser

import com.yuanbao.autolyrics.data.Lyric
import com.yuanbao.autolyrics.data.LyricLine
import com.yuanbao.autolyrics.data.LyricWord
import org.json.JSONObject

/**
 * 网易云逐字歌词（YRC）解析。
 *
 * 每行是一个独立 JSON：
 * ```
 * {"t":1234,"c":[{"t":120,"c":"我"},{"t":0,"c":"，"},{"t":200,"c":"爱"}]}
 * ```
 * - 行的 `t` 是行起始时间（毫秒）
 * - 每个字的 `t` 是该字的**持续时长**（不是起始时间），所以字起始时间要累加
 *
 * 解析失败或无内容时返回空 [Lyric]，调用方会自动退回普通 LRC。
 */
object YrcParser {

    fun parse(raw: String?, translation: String? = null, sourceId: String = ""): Lyric {
        if (raw.isNullOrBlank()) return Lyric(sourceId = sourceId)

        val transIndex = LyricParser.buildTranslationIndex(translation)
        val lines = ArrayList<LyricLine>()

        raw.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("{")) return@forEach
            val parsed = parseLine(line) ?: return@forEach

            lines += LyricLine(
                timeMs = parsed.timeMs,
                text = parsed.text,
                translation = LyricParser.nearestTranslation(transIndex, parsed.timeMs),
                words = parsed.words,
                durationMs = parsed.durationMs,
            )
        }

        if (lines.isEmpty()) return Lyric(sourceId = sourceId)
        lines.sortBy { it.timeMs }
        return Lyric(
            lines = lines,
            sourceId = sourceId,
            hasTranslation = lines.any { !it.translation.isNullOrBlank() },
            wordLevel = lines.any { it.isWordLevel },
            instrumental = LyricParser.isInstrumental(lines),
        )
    }

    private data class ParsedLine(
        val timeMs: Long,
        val text: String,
        val words: List<LyricWord>,
        val durationMs: Long,
    )

    private fun parseLine(json: String): ParsedLine? = try {
        val jo = JSONObject(json)
        val lineStart = jo.optLong("t", -1L)
        if (lineStart < 0) null
        else {
            val arr = jo.optJSONArray("c")
            val words = ArrayList<LyricWord>()
            var cursor = lineStart
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val w = arr.optJSONObject(i) ?: continue
                    val duration = w.optLong("t", 0L).coerceAtLeast(0L)
                    val text = w.optString("c")
                    if (text.isEmpty()) continue
                    words += LyricWord(startMs = cursor, durationMs = duration, text = text)
                    cursor += duration
                }
            }
            ParsedLine(
                timeMs = lineStart,
                text = words.joinToString("") { it.text },
                words = words,
                durationMs = cursor - lineStart,
            )
        }
    } catch (_: Throwable) {
        null
    }
}

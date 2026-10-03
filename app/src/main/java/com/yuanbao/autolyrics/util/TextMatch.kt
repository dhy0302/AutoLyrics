package com.yuanbao.autolyrics.util

/** 标题/艺人归一化 + 相似度打分，用于跨源匹配。 */
object TextMatch {

    /** 标题里常见的噪音后缀：remaster / live / official audio … */
    private val SUFFIX = Regex(
        """(?i)[\s\-_、,，\.]*(remaster(ed)?(\s*\d*)?|re-?master(ed)?|live(\s+(at|from|in)\b.*)?|""" +
                """official\s*(audio|video|music\s*video|mv|lyric\s*video)|explicit|mono|stereo|""" +
                """\d{4}\s*remaster|deluxe(\s*edition)?|version|ver\.?|radio\s*edit|single(\s*version)?|""" +
                """bonus\s*track|cover(\s*version)?|instrumental|acoustic(\s*version)?|remix|""" +
                """feat\.?.*|with\s+.*)\s*$"""
    )

    private val PUNCT = Regex("[\\p{Punct}\\p{Space}]+")

    /**
     * 归一化：小写、去括号补充、去 feat、去版本后缀、去标点、全角转半角。
     * [isArtist] 为真时不去版本后缀（歌手名里不该被误伤）。
     */
    fun normalize(s: String, isArtist: Boolean = false): String {
        var t = s.lowercase().trim()
        // 统一字形：繁体折叠成简体（Spotify 繁体元数据 vs 国内源的简体收录）
        t = ChineseConverter.toSimplified(t)
        // 去掉括号内的补充说明：(Live版) 【现场】 [feat. xxx] (From "xxx")
        t = t.replace(Regex("[（(\\[【][^）)\\]】]*[）)\\]】]"), " ")
        t = t.replace("（", " ").replace("）", " ")
        t = t.replace("feat.", " ").replace("featuring", " ")
        if (!isArtist) t = t.replace(SUFFIX, " ")
        t = foldToHalfWidth(t)
        return PUNCT.replace(t, " ").trim()
    }

    /** 中文场景下常见的全角字符转半角，避免「歌　名」与「歌 名」被判为不同。 */
    private fun foldToHalfWidth(s: String): String = s.map { c ->
        val code = c.code
        when {
            code in 0xFF01..0xFF5E -> (code - 0xFEE0).toChar()
            code == 0x3000.toInt() -> ' '
            else -> c
        }
    }.joinToString("")

    /**
     * 0~1 的相似度。中文按字比较同样有效（没有空格分词，退化为逐字）。
     * 取「编辑距离相似度」与「token 重合度」的较大值：
     * 前者抗语序变化，后者抗长标题稀释。
     */
    fun similarity(aRaw: String, bRaw: String): Double {
        val a = aRaw.trim()
        val b = bRaw.trim()
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) {
            val ratio = minOf(a.length, b.length).toDouble() / maxOf(a.length, b.length).toDouble()
            return 0.85 + 0.15 * ratio
        }

        val lev = 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length).toDouble()

        val ta = a.split(" ").filter { it.isNotBlank() }.toSet()
        val tb = b.split(" ").filter { it.isNotBlank() }.toSet()
        val inter = if (ta.isEmpty() || tb.isEmpty()) 0.0
        else ta.intersect(tb).size.toDouble() / minOf(ta.size, tb.size).toDouble()

        return maxOf(lev, inter * 0.9).coerceIn(0.0, 1.0)
    }

    private fun levenshtein(a: String, b: String): Int {
        // 超长串直接放弃精确计算，避免异常歌词把主线程拖死
        if (a.length > 200 || b.length > 200) return maxOf(a.length, b.length)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }

    /** 时长一致度。完全对不上是很强的排除信号（同名不同版本的歌）。 */
    fun durationScore(a: Long, b: Long): Double {
        if (a <= 0 || b <= 0) return 0.5
        val diff = kotlin.math.abs(a - b) / 1000.0
        return when {
            diff <= 2 -> 1.0
            diff <= 5 -> 0.85
            diff <= 10 -> 0.55
            else -> (1.0 - diff / 60.0).coerceAtLeast(0.0)
        }
    }
}

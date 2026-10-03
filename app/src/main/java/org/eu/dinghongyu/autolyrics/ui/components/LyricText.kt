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

package org.eu.dinghongyu.autolyrics.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity

/**
 * 一行歌词的渲染。
 *
 * ## 逐字渐进擦除（v1.8.3 重做）
 *
 * 用户要的规则是：**把每个字分配到的时长用来分摊擦除进度**。
 * 一个字 1 秒时 —— 0ms 完全不亮，100ms 亮 1/10，200ms 亮 2/10，……
 * 精度越细越好，但**不能定死成 10 份**，要按真实时间连续推进。
 *
 * ### v1.7.0 那版为什么不满足
 * 旧实现给**每个字各挂一条自己的横向渐变**
 * （`SpanStyle(brush = Brush.horizontalGradient(...))`），只在"正在唱的那一个字"内部推进。
 * 三个问题：
 *
 * 1. **时间分辨率不够（主因）**。进度源 `lyricPositionMs` 跟着
 *    `PrecisionMode.pollMs` 走（省电 200ms / 标准 100ms / 精准 50ms），
 *    一个 1 秒的字只有 5~10 级台阶。上一版不是算法不细，
 *    是**喂进来的时间戳本身只有 10 级分辨率**。这一版引入
 *    [rememberKaraokeClock] 就是为了解决它——把进度与刷新率解耦。
 * 2. **字与字之间没有连续过渡**。每字各一条渐变，视觉上是"一格亮、一格暗"，
 *    边界是硬的，只是格内平滑。
 * 3. **粗细会跳**。未唱字被强制 `FontWeight.Normal`、已唱字 `Bold`，
 *    唱到一半整行粗细会变一次，这也是"不顺"的观感来源。
 *
 * ### 现在的做法：双层叠加 + 整行裁剪
 * ```
 * ┌─────────────────────────────────┐
 * │ 底层 Text：dimColor（未唱，整行满） │
 * │ 上层 Text：highlightColor         │← clipRect(0, 0, boundary × 宽度)
 * └─────────────────────────────────┘
 * ```
 *
 * - **擦除边界 = Σ(前面各字的权重) + 当前字权重 × 字内进度**，按整行归一化。
 *   跨字时边界连续衔接（因为是累计量，不存在跳变），字内也连续推进。
 * - **粗细恒定**。两层用完全相同的 fontWeight，粗细问题消失。
 *
 * ## 性能
 * - 两个 `Text` 的 text 与 style 完全一致，Compose 的 `MultiParagraphCache`
 *   按 (text, style, constraints, density) 命中，**文本布局只算一次**。
 * - 每帧变的只有上层的 `clipRect` 宽度一个Float。
 * - 逐字动画只跑在当前行（见 [rememberKaraokeClock]），非当前行走纯色快路径。
 * - 两端（未唱 / 唱完）直接返回单层纯色，跳过全部计算——
 *   歌词在两端停留的时间远长于正在唱的那几秒，这是整页最热的路径。
 */

/**
 * 逐字擦除边界的羽化带宽度。
 *
 * 用固定 dp 而非行宽百分比：百分比会让长行粗、短行细（一个 10 字行与
 * 4 字行能差出两三倍），同一屏内粗细不一致，观感突兀。
 */
private val FEATHER_WIDTH = 1.dp

@Composable
fun LyricText(
    words: List<org.eu.dinghongyu.autolyrics.data.LyricWord>,
    plainText: String,
    /** 当前播放位置（毫秒）。逐字行必须传 [rememberKaraokeClock] 的输出。 */
    positionMs: Long,
    /** 是否启用逐字染色（关闭时退化为整行） */
    wordByWord: Boolean,
    highlightColor: Color,
    dimColor: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    lineHeight: TextUnit,
    textAlign: TextAlign = TextAlign.Center,
    modifier: Modifier = Modifier,
) {
    if (!wordByWord || words.isEmpty()) {
        PlainLine(plainText, highlightColor, fontSize, fontWeight, lineHeight, textAlign, modifier)
        return
    }

    val first = words.first()
    val last = words.last()

    // 两端快路径：纯色单层，跳过全部逐字计算
    if (positionMs >= last.startMs + last.durationMs) {
        PlainLine(plainText, highlightColor, fontSize, fontWeight, lineHeight, textAlign, modifier)
        return
    }
    if (positionMs < first.startMs) {
        PlainLine(plainText, dimColor, fontSize, fontWeight, lineHeight, textAlign, modifier)
        return
    }

    // ---- 定位当前字 ----
    //
    // 锚点只在「非空白字段」里找。酷狗 KRC 的英文歌词会把空格也当成独立
    // 逐字段（`<176,176,0> <352,176,0>In`），若直接 indexOfLast，
    // 播放位置落进空格时整行会突然"没人擦亮"，表现为字母全亮、后面一片暗。
    val visible = remember(words) { words.indices.filter { words[it].text.isNotBlank() } }
    if (visible.isEmpty()) {
        PlainLine(plainText, dimColor, fontSize, fontWeight, lineHeight, textAlign, modifier)
        return
    }
    val curIndex = visible[visible.indexOfLast { positionMs >= words[it].startMs }.coerceAtLeast(0)]
    val cur = words[curIndex]
    val curProgress = ((positionMs - cur.startMs).toFloat() / cur.durationMs.coerceAtLeast(1L))
        .coerceIn(0f, 1f)

    // ---- 边界：按字宽累计 ----
    //
    // 用「字符数」做权重近似，而不是逐帧 `measure` 真实像素宽度。
    // 这是**性能上的关键取舍**：真实像素宽度要每帧跑一次文本测量
    // （measure + LayoutResult +逐字offset 查询），那才是真正的开销。
    //
    // 对中文歌词（主场景）**这是精确的**——中文等宽，一字一权。
    // 对英文有误差（i 比 w 窄），但误差只是让推进速度在词间略快略慢，
    // 肉眼不可察，换来的是每帧零测量成本。
    //
    // 空白字段权重为0：否则英文词间空格会让边界"打嗝"。
    val weights = remember(words) {
        val w = FloatArray(words.size)
        var total = 0f
        words.forEachIndexed { i, word ->
            val weight = if (word.text.isBlank()) 0f else word.text.length.toFloat()
            w[i] = weight
            total += weight
        }
        if (total <= 0f) FloatArray(words.size) { 1f } else w
    }

    var sungWeight = 0f
    for (i in 0 until curIndex) sungWeight += weights[i]
    sungWeight += weights[curIndex] * curProgress

    // text 只构建一次：两层共用，同参。
    val text = remember(words, plainText) {
        androidx.compose.ui.text.buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = fontWeight)) { append(plainText) }
        }
    }

    val base = fontSize

    // ---- 逐字擦除的羽化带宽度（固定 1dp）----
    //
    // 这里是「已唱 / 未唱」那道软边的宽度。早期实现按行宽百分比算（2%），
    // 在长歌词行上会宽达两三个字，看起来像一根粗柱子；而短行又明显更细，
    // 同一屏内粗细不一致。改成固定 dp 后每行都是同一道细边。
    //
    // 歌词页与桌面悬浮窗共用本组件（HomeScreen / OverlayContent），
    // 因此两处的羽化宽度会同步生效。
    val featherPx = with(LocalDensity.current) { FEATHER_WIDTH.toPx() }

    // ---- 布局测量：只为知道「每个字落在哪一行的哪个 x」 ----
    //
    // ## 为什么必须按行分段，而不能整块一刀切
    //
    // v1.8.3 的裁剪是 `clipRect(0, 0, size.width × boundary, size.height)`，
    // 也就是**整个文本块**横向切一刀。单行时这恰好等于逐行推进，
    // 所以一直没问题；一旦排版折成两行，它就变成「两行同时从左往右亮」——
    // 因为两行共享同一个横向进度，各自从自己行的左端开始扫。
    // 连带的后果是：第一行最右端的字在句中（boundary≈0.5）就点亮了，
    // 而不是等整句唱完。
    //
    // 正确语义：**行是排版的结果，不是进度的边界**。
    // 高亮从第一行左端走到右端，越过行尾后接到第二行左端继续。
    //
    // ## 成本
    //
    // `onTextLayout` 只在**文本或约束变化**时回调（换行、字号、宽度变），
    // 不是每帧。行信息缓存进 `layoutState`，逐帧只做「查表 + 按行裁剪」，
    // 最多两三次 clipRect，比原来的一次还便宜。
    //
    // 单行（绝大多数歌词）走 `singleLine` 快路径，与 v1.8.3 完全一致。

    val layoutState = remember { mutableStateOf<TextLayoutResult?>(null) }
    val lineCount = layoutState.value?.lineCount ?: 0

    Box(modifier.fillMaxWidth()) {
        // 底层：未唱色，铺满整块
        Text(
            text = text,
            fontSize = base,
            lineHeight = lineHeight,
            textAlign = textAlign,
            color = dimColor,
            modifier = Modifier.fillMaxWidth(),
            onTextLayout = { layoutState.value = it },
        )

        if (lineCount > 1) {
            // ---- 多行：按行分段裁剪 ----
            //
            // 逐行计算该行内的进度，再按该行的实际 x 范围裁剪。
            // 唱完的行整行亮，未到的行整行暗，只有当前行在推进。
            val lr = layoutState.value
            if (lr != null) {
                val rows = remember(lr, words, weights, curIndex, curProgress) {
                    computeRowProgress(lr, words, weights, plainText, curIndex, curProgress)
                }
                // 有字要亮才画。boundary>0 保证至少当前行有进度。
                if (rows.any { it.fraction > 0f }) {
                    Text(
                        text = text,
                        fontSize = base,
                        lineHeight = lineHeight,
                        textAlign = textAlign,
                        color = highlightColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawRowsSoftEdge(rows, featherPx),
                    )
                }
            }
        } else {
            // ---- 单行快路径：与 v1.8.3 完全一致 ----
            val total = weights.sum().coerceAtLeast(1f)
            val boundary = (sungWeight / total).coerceIn(0f, 1f)
            if (boundary > 0f) {
                Text(
                    text = text,
                    fontSize = base,
                    lineHeight = lineHeight,
                    textAlign = textAlign,
                    color = highlightColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        // 裁到 boundary，再由 drawBehindSoftEdge 在边界处
                        // 叠一条 1dp 的羽化带做软边。
                        .drawBehindSoftEdge(clipFraction = boundary, featherPx = featherPx),
                )
            }
        }
    }
}

/**
 * 一行歌词的高亮进度：[line] 是行号，[fraction] 是该行内已唱的比例。
 */
private class RowProgress(
    val line: Int,
    val fraction: Float,
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
)

/**
 * 把整句的逐字进度分配到各个视觉行上。
 *
 * ## 核心：把「字的权重」映射到「行的区间」
 *
 * 输入是整句的逐字数据，输出是每一行各自的完成比例。
 * 做法是用 [TextLayoutResult] 反查每个字落在哪一行：
 *
 * ```
 * 字 0..4 → 第 0 行    字 5..9 → 第 1 行
 * 第 0 行 fraction = (0..4 的权重和 + 当前字内进度) / 第 0 行总权重
 * 第 1 行 fraction = 同理，但当前字不在这一行时就是 0
 * ```
 *
 * 于是：
 *  - 当前字在第 1 行 → 第 0 行 fraction = 1（整行已亮），第 1 行从 0 推进
 *  - 当前字在第 0 行 → 第 0 行推进，第 1 行 fraction = 0（还没唱到）
 *
 * 视觉上就是「第一行亮完 → 接第二行继续」，跨行处没有断层。
 *
 * ## v1.11.3：为什么不再逐字用 `getLineForOffset`
 *
 * 旧实现给每个字算一个「字符 offset 前缀和」，再用
 * `lr.getLineForOffset(offset)` 反查行号。**这条路在数据有偏差时全盘崩塌**：
 *
 * `KrcParser.parseLine` 结尾是
 * ```
 * ParsedLine(words.joinToString("") { it.text }.trim(), words, ...)
 * ```
 * ——`text` 被 trim 了，`words` 却保持原样（这是有意为之：英文歌词的
 * 空格段必须留着，否则单词会粘连）。于是 KRC 行首/行尾有多余空格时，
 * **`words` 拼接出来的字符序列与排版用的 `plainText` 差着若干个字符**。
 *
 * 错位之后，字 offset 与排版的行边界属于**两个不同的坐标空间**：
 * 字被判进错误的行，`rowTotal[0]` 变成 0 → 第 0 行 fraction 恒为 0 → 整行不亮；
 * 而尾部偏移较小，第 1 行看起来是好的。这正是用户报告的
 * 「当前字在第一行时不亮，播到第二行才有动画」。
 *
 * 改法：**不再假设 offset 空间对齐**，而是先把 `words` 整体对齐到
 * `plainText`（见 [alignWordsToText]），对齐失败时退化为
 * 「按权重比例平均切分到各行」的兜底，而不是让某一整行彻底不亮。
 */
private fun computeRowProgress(
    lr: TextLayoutResult,
    words: List<org.eu.dinghongyu.autolyrics.data.LyricWord>,
    weights: FloatArray,
    plainText: String,
    curIndex: Int,
    curProgress: Float,
): List<RowProgress> {
    val lineCount = lr.lineCount
    if (lineCount <= 0) return emptyList()

    val aligned = alignWordsToText(words, plainText)

    // 每个字归属的行：对齐失败时用 -1 标记，交由兜底逻辑处理
    val wordLine = IntArray(words.size) { i ->
        val off = aligned[i]
        if (off < 0) -1 else lr.getLineForOffset(off)
    }

    // 每行的总权重与已唱权重
    val rowTotal = FloatArray(lineCount)
    val rowDone = FloatArray(lineCount)

    // 逐字可定位时：直接按它所在的行累加。
    // 判据用「成功定位到的权重」而不是「字数」——空白字段权重为 0，
    // 用个数判断会出现"只定位到几个空格、于是走进了正常分支"的假阳性，
    // 结果仍然是某些行 rowTotal=0 → 整行不亮。
    var locatableWeight = 0f
    for (i in words.indices) {
        val l = wordLine[i]
        if (l !in 0 until lineCount) continue
        locatableWeight += weights[i]
    }

    val totalWeight = weights.sum()

    if (locatableWeight > 0f && locatableWeight >= totalWeight * 0.5f) {
        for (i in words.indices) {
            val l = wordLine[i]
            if (l !in 0 until lineCount) continue
            rowTotal[l] += weights[i]
        }
        for (i in words.indices) {
            val l = wordLine[i]
            if (l !in 0 until lineCount) continue
            if (i < curIndex) rowDone[l] += weights[i]
            else if (i == curIndex) rowDone[l] += weights[i] * curProgress
        }
        // 定位失败的那部分字（通常是纯空白，权重 0）不影响分母；
        // 但若真有带权重的字没对上，补到「按比例」模型上，避免整行空。
        if (locatableWeight < totalWeight) {
            fillMissingRows(rowTotal, weights, wordLine, lineCount)
        }
    } else {
        // 兜底：一个字都定位不了，说明 offset 空间完全对不上。
        // 此时按【权重比例】把整句均分给各行，宁可精度差一点，
        // 也不能让某一行 fraction 恒为 0（那就是"整行不亮"）。
        distributeEvenly(rowTotal, weights, lineCount)
        for (i in words.indices) {
            val l = rowOfByRatio(weights, i, lineCount)
            if (i < curIndex) rowDone[l] += weights[i]
            else if (i == curIndex) rowDone[l] += weights[i] * curProgress
        }
    }

    // 最后的保险：任何一行权重为 0 都会让它 fraction=0 → 整行不亮。
    // 用全局权重按比例补齐，保证每一行都有非零分母。
    for (l in 0 until lineCount) {
        if (rowTotal[l] > 0f) continue
        rowTotal[l] = (totalWeight / lineCount).coerceAtLeast(0.0001f)
    }

    return (0 until lineCount).map { l ->
        // 行边界必须用 getLineLeft / getLineRight，**不能**用
        // getHorizontalPosition(getLineEnd(l, true))。
        //
        // 原因：软换行（非末行）的 getLineEnd(l, true) 返回的是
        // 【下一行的起始 offset】。该 offset 已经归属下一行了，
        // 再对它调 getHorizontalPosition 拿到的是下一行内部的 x 坐标
        //（TextAlign.Start 时 ≈ 0）。
        // 于是 right - left == 0，drawRowsSoftEdge 里
        // `if (rowWidth <= 0f) continue` 会把这一整行静默跳过。
        // 末行因为 getLineEnd 返回 text.length 所以正常 ——
        // 这正是「折行歌词第一行永远不亮、第二行正常」的确切成因。
        RowProgress(
            line = l,
            fraction = if (rowTotal[l] > 0f) (rowDone[l] / rowTotal[l]).coerceIn(0f, 1f) else 0f,
            left = lr.getLineLeft(l),
            right = lr.getLineRight(l),
            top = lr.getLineTop(l),
            bottom = lr.getLineBottom(l),
        )
    }
}

/**
 * 把每个字在 `plainText` 里的起始 offset 求出来。
 *
 * 返回数组长度与 [words] 相同；对不齐时该位置填 -1。
 *
 * ## 为什么不直接用前缀和
 *
 * 逐字字段拼起来可能与 `plainText` 有偏差（KRC 的 trim、个别源多出的空格）。
 * 这里做一次**真实的字符序列对齐**：先假设只有一个偏移量
 * （行首被 trim 的情况），能对上就整体平移；对不上就逐字在
 * `plainText` 里查找它的真实位置。
 *
 * 空白字段（空格）在 `plainText` 里通常被 trim 掉了，找不到时返回 -1，
 * 但它的权重本来就是 0，不影响分母——这正是 [weights] 里空白为 0 的用意。
 */
private fun alignWordsToText(
    words: List<org.eu.dinghongyu.autolyrics.data.LyricWord>,
    plainText: String,
): IntArray {
    val n = words.size
    val result = IntArray(n) { -1 }
    if (n == 0) return result
    if (plainText.isEmpty()) return result

    // 逐字字段拼接，与 plainText 逐字符比较
    val joined = buildString {
        words.forEach { append(it.text) }
    }
    if (joined == plainText) {
        var acc = 0
        for (i in 0 until n) {
            result[i] = acc
            acc += words[i].text.length
        }
        return result
    }

    // 情况二：整体平移（行首/行尾被 trim）。找出能对上的那个偏移量。
    val maxShift = (joined.length - plainText.length).coerceAtLeast(0)
    for (shift in 0..maxShift) {
        if (matchesAt(words, plainText, shift, n, result)) return result
    }
    for (shift in 0..maxShift) {
        if (matchesAt(words, plainText, -shift, n, result)) return result
    }

    // 情况三：逐字在 plainText 里顺序查找（能对上多少算多少）
    var cursor = 0
    for (i in 0 until n) {
        val t = words[i].text
        if (t.isEmpty()) continue
        val at = plainText.indexOf(t, cursor)
        if (at >= 0) {
            result[i] = at
            cursor = at + t.length
        }
    }
    return result
}

/** 假设所有字整体偏移 [shift] 时能否对上；对上了就把 offset 写进 [out]。 */
private fun matchesAt(
    words: List<org.eu.dinghongyu.autolyrics.data.LyricWord>,
    plainText: String,
    shift: Int,
    n: Int,
    out: IntArray,
): Boolean {
    var acc = shift
    var hit = 0
    for (i in 0 until n) {
        val t = words[i].text
        if (t.isEmpty()) continue
        if (acc < 0 || acc + t.length > plainText.length) return false
        if (!plainText.regionMatches(acc, t, 0, t.length)) return false
        out[i] = acc
        acc += t.length
        hit++
    }
    // 至少要真的对上几个字，否则不算这次平移成功
    return hit > 0
}

/**
 * 把「定位失败」的那部分带权重的字，按比例模型补进各行的分母。
 *
 * 只在**部分**字定位成功时调用（完全定位不成功走 [distributeEvenly]）。
 * 目的是让每行分母都非零——分母为 0 会让 fraction 恒为 0，
 * 也就是那一句「歌词不会亮起」。
 */
private fun fillMissingRows(
    rowTotal: FloatArray,
    weights: FloatArray,
    wordLine: IntArray,
    lineCount: Int,
) {
    var missingWeight = 0f
    val missingRows = HashSet<Int>()
    for (i in weights.indices) {
        val l = wordLine[i]
        if (l in 0 until lineCount) continue
        missingWeight += weights[i]
        missingRows.add(rowOfByRatio(weights, i, lineCount))
    }
    if (missingWeight <= 0f || missingRows.isEmpty()) return
    val each = missingWeight / missingRows.size
    for (l in missingRows) {
        if (l in 0 until lineCount) rowTotal[l] += each
    }
}

/** 兜底：按权重比例把各字尽量均分到 [lineCount] 行。 */private fun distributeEvenly(
    rowTotal: FloatArray,
    weights: FloatArray,
    lineCount: Int,
) {
    var total = 0f
    for (w in weights) total += w
    if (total <= 0f) {
        for (l in 0 until lineCount) rowTotal[l] = 1f
        return
    }
    // 逐字累加到"累计权重越过第几个行界"对应的行
    var acc = 0f
    var row = 0
    for (i in weights.indices) {
        val boundary = total * (row + 1) / lineCount
        while (row < lineCount - 1 && acc + weights[i] > boundary) {
            row++
        }
        rowTotal[row] += weights[i]
        acc += weights[i]
    }
}

/** 兜底配套：算出第 [i] 个字在均分模型下属于哪一行。 */
private fun rowOfByRatio(weights: FloatArray, i: Int, lineCount: Int): Int {
    var total = 0f
    for (w in weights) total += w
    if (total <= 0f || lineCount <= 1) return 0
    var acc = 0f
    var row = 0
    for (k in 0 until i.coerceAtMost(weights.size - 1)) {
        val boundary = total * (row + 1) / lineCount
        while (row < lineCount - 1 && acc + weights[k] > boundary) row++
        acc += weights[k]
    }
    return row.coerceIn(0, lineCount - 1)
}

/**
 * 逐行裁剪 + 逐行羽化。
 *
 * 与 [drawBehindSoftEdge] 的区别只在于：那个是**整块**一刀切，
 * 这个是**每个视觉行**各切一刀，且裁剪的 x 范围是「行的左端 → 行内进度位置」。
 *
 * 羽化宽度按**行宽**算而不是整块宽，否则窄行会得到一条极宽的糊边。
 */
private fun Modifier.drawRowsSoftEdge(rows: List<RowProgress>, featherPx: Float): Modifier =
    this.drawWithContent {
        val canvas = drawContext.canvas
        // 整块宽度：只有在行宽算不出来时才用作退化基准。
        val blockWidth = size.width
        for (r in rows) {
            if (r.fraction <= 0f) continue
            // 行宽退化：宁可拿整块宽度当基准，也不能 `continue` 把整行丢掉。
            // 之前这里 `if (rowWidth <= 0f) continue` 会让任何边界算不出
            // 的行彻底不亮，而这种静默失败没有任何日志，很难定位。
            val rowWidth = (r.right - r.left).takeIf { it > 0f } ?: blockWidth
            if (rowWidth <= 0f) continue
            val rowLeft = if (r.right > r.left) r.left else 0f
            val edgeX = rowLeft + rowWidth * r.fraction
            // 羽化带用**固定像素**而非行宽百分比：按百分比算会让短行细、长行粗，
            // 视觉上忽粗忽细（一个 4字行与一个 10 字行能差出两三倍）。
            // 固定值保证每行、每个字号下都是同一道细边。
            val band = featherPx.coerceAtLeast(1f)

            canvas.save()
            // 只放开「已唱部分」：整块宽的左端 → edgeX，纵向只限本行。
            // 左侧从 0 起而不是 r.left 起，是因为 textAlign=Start 时
            // 行首通常贴着 0，但居中/右对齐时行首会右移，
            // 从 0 起才能把行首之前那段也一起画上。
            canvas.clipRect(0f, r.top, edgeX + band, r.bottom)
            drawContent()
            // 行内羽化：从 (edge − band) 到 edge 渐隐
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black,
                    1f to Color.Black.copy(alpha = 0f),
                    startX = (edgeX - band).coerceAtLeast(0f),
                    endX = edgeX,
                ),
                blendMode = BlendMode.DstIn,
            )
            canvas.restore()
        }
    }

/** 纯色单行（逐字关闭、或处于未唱/唱完两端时的快路径）。 */
@Composable
private fun PlainLine(
    text: String,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    lineHeight: TextUnit,
    textAlign: TextAlign,
    modifier: Modifier,
) {
    Text(
        text = text,
        fontSize = fontSize,
        fontWeight = fontWeight,
        color = color,
        lineHeight = lineHeight,
        textAlign = textAlign,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 把本层内容按"横向比例"裁掉右侧，并给边界一段羽化。
 *
 * ## 为什么需要羽化
 * `clipRect` 是硬裁剪，直接用会得到一条锐利的分界线。
 * 这里在裁剪区内叠一条反向渐变，用 [androidx.compose.ui.graphics.BlendMode.DstIn]
 * 削掉靠近边界那段的 alpha，做出"越靠近边界越淡"的过渡。
 *
 * ## 成本
 * 一次 `clipRect` + 一次 `drawRect`，都在 GPU 上；不涉及文本重排。
 */
private fun Modifier.drawBehindSoftEdge(clipFraction: Float, featherPx: Float): Modifier =
    this.drawWithContent {
        val edgeX = size.width * clipFraction
        // 羽化带：紧贴边界的一小段，**固定像素**宽度。
        // 早期实现按整宽百分比算（2%），在长歌词行上会宽到两三个字，
        // 看起来像一根粗柱子；而短行又明显更细，视觉上不稳定。
        val band = featherPx.coerceAtLeast(1f)

        // 用 canvas 的原生 clipRect 而不是 DrawScope 的扩展：
        // 后者不在基础 API jar 里，靠传递依赖提供，不同 Compose 版本
        // 里包路径变过（androidx.compose.ui.graphics.drawscope.clipRect
        // ↔ androidx.compose.ui.draw.clipRect），直接用原生 API 最稳。
        val canvas = drawContext.canvas
        canvas.save()
        canvas.clipRect(0f, 0f, edgeX, size.height)
        drawContent()
        // 从 (edge − band) 到 edge 画一条 alpha 由 1 降到 0 的遮罩。
        // DstIn 让它覆盖范围内的内容按该 alpha 保留，
        // 于是最靠近边界的一小段被"化开" → 软边。
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Black,
                1f to Color.Black.copy(alpha = 0f),
                startX = (edgeX - band).coerceAtLeast(0f),
                endX = edgeX,
            ),
            blendMode = BlendMode.DstIn,
        )
        canvas.restore()
    }

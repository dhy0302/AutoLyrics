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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 经典取色面板（参考系统 ColorPicker）：
 *  - 顶栏：取消 / 确定（拖动过程中不落盘，点「确定」才应用，「取消」还原并收起）
 *  - 预览：色块 + **RGB 读数** + 三个手动输入框（v1.12.10）
 *  - HSV 圆盘：角度 = 色相，半径 = 饱和度（圆心白 → 边缘纯色）
 *  - 明度条：黑 → 当前色相纯色，三角指示
 *  - 透明度条：棋盘格 → 当前色，三角指示（输出带 alpha 的 ARGB）
 *
 * 不依赖 Compose 的 [Color.toHsv]（部分 BOM 未提供），ARGB↔HSV 自行换算。
 *
 * ## v1.12.10：为什么把「0xAARRGGBB」换成 RGB 读数
 *
 * 十六进制是给机器看的：普通用户看到 `0xDC8253C3` 无法判断"这是不是我要的颜色"，
 * 更没法据此微调。改成 `RGB 130, 83, 195` 这种人人能对上号的形式，
 * 并配三个输入框，支持直接键入数值。
 *
 * 透明度的数值不再单独显示 —— 下方那根棋盘格条已经把它表达出来了，
 * 而 ARGB 里的 alpha 位只会让读数更难读。
 */
@Composable
fun ColorWheel(
    initial: Int,
    onConfirm: (Int) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    /**
     * 是否显示 RGB 手动输入框。
     *
     * 悬浮窗场景要传 false：那个窗口带 `FLAG_NOT_FOCUSABLE`
     * （不抢焦点、不拦截背后 App 的操作），收不到键盘输入，
     * 摆了输入框也点不出键盘，只留读数即可。
     */
    allowRgbInput: Boolean = true,
) {
    // [h∈0..360, s∈0..1, v∈0..1]
    var hsv by remember { mutableStateOf(argbToHsv(initial)) }
    var alpha by remember { mutableStateOf(((initial ushr 24) and 0xFF) / 255f) }
    val sizePx = with(LocalDensity.current) { size.toPx() }

    fun currentArgb(): Int = hsvToArgb(hsv[0], hsv[1], hsv[2], alpha)
    fun currentColor(): Color = Color(currentArgb())

    // ---------------- RGB 读数与手动输入 ----------------
    //
    // ## 为什么用「文本」而不是 Int 来存这三个分量
    //
    // 用户清空输入框准备重打时，中间态是**空串**——用 Int 存就没法表达，
    // 只能强行显示 0，用户会看到自己刚删掉的位置蹦出个 0，没法正常编辑。
    // 所以三个输入框各自持有一份字符串，只有能解析出合法数字时才写回颜色。
    //
    // 方向是单向的：**色轮 → 文本**由 [syncRgbText] 主动刷新，
    // **文本 → 色轮**由 [applyRgbInput] 处理。
    // 不能让文本随颜色自动重组，否则用户打到一半（如刚输入"1"、想接着打"25"）
    // 会被反向刷新覆盖掉。
    var rText by remember { mutableStateOf(((initial shr 16) and 0xFF).toString()) }
    var gText by remember { mutableStateOf(((initial shr 8) and 0xFF).toString()) }
    var bText by remember { mutableStateOf((initial and 0xFF).toString()) }

    /**
     * v1.13.5：「取消」要回到的那个基线颜色。
     *
     * 初值 -1，真正取值在 [LaunchedEffect] 里 —— 那里会重置面板并顺手记录。
     *
     * ## 为什么基线记的是「面板自己渲染出来的值」而不是 `initial`
     *
     * `argb → hsv → argb` 走的是浮点往返，可能有 ±1 的误差。
     * 若拿 `initial` 当基线，面板**刚打开、用户什么都没动**时
     * `currentArgb() != initial`，于是第一次点「取消」就会被判成"改过了"，
     * 把颜色动一下 —— 正好违反「没调色就点取消不反应」这条要求。
     * 记面板自己的渲染值则天然自洽。
     */
    var baseArgb by remember { mutableIntStateOf(-1) }

    /** 色轮被拖动后，把当前颜色的 RGB 分量刷回输入框。 */
    fun syncRgbText() {
        val argb = currentArgb()
        rText = ((argb shr 16) and 0xFF).toString()
        gText = ((argb shr 8) and 0xFF).toString()
        bText = (argb and 0xFF).toString()
    }

    /**
     * 本次打开后用户是否动过。
     *
     * 同时比对颜色与三个输入框：用户可能敲了「0130」这种
     * 解析后与原值同色、但文本不同的输入，那也算"动过"，
     * 点「取消」应当把文本一并还原。
     */
    fun isDirty(): Boolean {
        if (currentArgb() != baseArgb) return true
        val br = ((baseArgb shr 16) and 0xFF).toString()
        val bg = ((baseArgb shr 8) and 0xFF).toString()
        val bb = (baseArgb and 0xFF).toString()
        return rText != br || gText != bg || bText != bb
    }

    /**
     * 回到基线。
     *
     * 刻意**不**更新 [baseArgb]：还原之后再点「取消」，
     * [isDirty] 已是 false，于是不会有任何反应 —— 不会一路往回退。
     */
    fun revert() {
        hsv = argbToHsv(baseArgb)
        alpha = ((baseArgb ushr 24) and 0xFF) / 255f
        syncRgbText()
    }

    /**
     * 三个分量都合法时写回颜色；任一项缺失/越界就**保持原色不动**。
     *
     * 不动而不是取默认值很重要：用户改 R 时 G/B 可能是清空后刚打一半的状态，
     * 这时若把 G 当 0 处理，颜色会突然跳成完全不同的值。
     */
    fun applyRgbInput() {
        val r = rText.toIntOrNull() ?: return
        val g = gText.toIntOrNull() ?: return
        val b = bText.toIntOrNull() ?: return
        if (r !in 0..255 || g !in 0..255 || b !in 0..255) return
        hsv = argbToHsv((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
        // alpha 不参与：它由下方那根棋盘格条独立控制，输入 RGB 不该把它重置掉
    }

    /** 输入框的过滤：只留数字、最多三位、超过 255 直接压到 255。 */
    fun sanitize(raw: String): String {
        val digits = raw.filter { it.isDigit() }.take(3)
        val n = digits.toIntOrNull() ?: return digits
        return if (n > 255) "255" else digits
    }

    fun discHandle(offset: Offset) {
        val c = Offset(sizePx / 2f, sizePx / 2f)
        val dx = offset.x - c.x
        val dy = offset.y - c.y
        var deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        if (deg < 0f) deg += 360f
        val s = (hypot(dx, dy) / (sizePx / 2f)).coerceIn(0f, 1f)
        hsv = floatArrayOf(deg, s, hsv[2])
        syncRgbText()
    }

    fun setV(f: Float) {
        hsv = floatArrayOf(hsv[0], hsv[1], f.coerceIn(0f, 1f))
        syncRgbText()
    }

    fun setAlpha(f: Float) {
        // alpha 只影响透明度，不改 RGB，因此无需刷新输入框
        alpha = f.coerceIn(0f, 1f)
    }

    /**
     * v1.12.10：外部改了 [initial] 时，把面板内部状态整体重置。
     *
     * ## 为什么必须加这一段
     *
     * 上面的 `hsv` / `alpha` / 三个输入框都是 `remember { ... initial }`，
     * **没有 key**，所以只在首次组合时取一次 initial。之后 initial 再变，
     * 面板内部完全不知道。
     *
     * 真实后果（设置页就能复现）：
     * 1. 色轮旁边有一排预设色块（QuickColorRow），点其中一块 → 设置立刻变了；
     * 2. 但色轮和 RGB 读数还停在旧颜色 —— 预览色块与读数自相矛盾；
     * 3. 这时再点「确定」，会把颜色**改回**旧值。
     *
     * 这段 effect 让「数据源 → 面板」也变成单向可同步的，
     * 三个使用点（歌词页设置 / 悬浮窗设置 / 悬浮窗弹出的色轮）
     * 共用同一个 `overlayTextColor` 之类的设置项，因此三处天然一致。
     *
     * 不会和用户手输打架：手输只改内部 hsv、不改 initial，effect 不会重新触发。
     */
    LaunchedEffect(initial) {
        hsv = argbToHsv(initial)
        alpha = ((initial ushr 24) and 0xFF) / 255f
        syncRgbText()
        // 每次重置都把基线挪到当前位置：
        // 首次组合时是"打开时的颜色"，外部改了颜色时是"改完之后的颜色"。
        // 两种情况下「取消」都会回到用户看到的那一版。
        baseArgb = currentArgb()
    }

    Column(modifier.width(size)) {
        // 文字颜色改成跟随主题。
        //
        // v1.12.10：原来是写死的浅灰（0xFFDDDDDD）。在深底上没问题，
        // 但这份面板在设置页里是放在浅色卡片上的 —— 浅灰压浅底几乎看不见，
        // 「取消」基本等于隐形。改成 onSurface / onSurfaceVariant 后
        // 白天夜间都清楚。
        val textMain = MaterialTheme.colorScheme.onSurface
        val textDim = MaterialTheme.colorScheme.onSurfaceVariant

        // 顶栏：取消 | 确定
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // v1.13.5：取消 =「撤销本次改动」。
            // 用户没动过就什么都不做；动过就回到打开时的颜色与 RGB 数值。
            // 还原之后基线不变，所以连点取消不会继续往回退。
            //
            // onCancel 仍照常调用：设置页传的是空函数（面板常驻，不该关），
            // 悬浮窗传的是关闭动作（那里「取消」还兼作收起面板）。
            Text(
                "取消",
                fontSize = 14.sp,
                color = textDim,
                modifier = Modifier.clickable {
                    if (isDirty()) revert()
                    onCancel()
                },
            )
            Spacer(Modifier.weight(1f))
            Text(
                "确定",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onConfirm(currentArgb()) },
            )
        }

        // 预览：色块 + RGB 读数
        //
        // 读数取**当前颜色**而不是输入框里的字符串：输入框可能正处于
        // 「刚清空、还没打全」的中间态，那时显示空值会让人以为颜色丢了。
        // 颜色本身在三个分量补齐前不会变，所以读数始终是有效值。
        val previewArgb = currentArgb()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .background(currentColor(), RoundedCornerShape(4.dp))
                    .border(1.dp, textDim.copy(alpha = 0.4f), RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "RGB %d, %d, %d".format(
                    (previewArgb shr 16) and 0xFF,
                    (previewArgb shr 8) and 0xFF,
                    previewArgb and 0xFF,
                ),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = textMain,
            )
        }

        // 手动输入 RGB。三个框等宽排布，各自带 R/G/B 前缀。
        if (allowRgbInput) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            ) {
                RgbInputField("R", rText, textMain, textDim, Modifier.weight(1f)) { raw ->
                    rText = sanitize(raw); applyRgbInput()
                }
                RgbInputField("G", gText, textMain, textDim, Modifier.weight(1f)) { raw ->
                    gText = sanitize(raw); applyRgbInput()
                }
                RgbInputField("B", bText, textMain, textDim, Modifier.weight(1f)) { raw ->
                    bText = sanitize(raw); applyRgbInput()
                }
            }
        }

        // HSV 圆盘：色相（角度）+ 饱和度（半径）
        Canvas(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(size)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { discHandle(it) },
                        onDrag = { change, _ -> discHandle(change.position) },
                    )
                },
        ) {
            val R = minOf(this.size.width, this.size.height) / 2f
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            // 底盘：色相 sweep 渐变
            drawCircle(
                brush = Brush.sweepGradient(
                    0f to Color.Red,
                    1f / 6f to Color.Yellow,
                    2f / 6f to Color.Green,
                    3f / 6f to Color.Cyan,
                    4f / 6f to Color.Blue,
                    5f / 6f to Color.Magenta,
                    1f to Color.Red,
                ),
                radius = R,
                center = c,
            )
            // 白心叠加：圆心白 → 边缘透明，形成「中心白、边缘纯色相」的经典观感
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    center = c,
                    radius = R,
                ),
                radius = R,
                center = c,
            )
            // 指示点（空心圆 + 白描边）
            val rad = Math.toRadians(hsv[0].toDouble())
            val pr = R * hsv[1]
            val p = Offset(c.x + (pr * cos(rad)).toFloat(), c.y + (pr * sin(rad)).toFloat())
            drawCircle(Color.White, radius = 9f, center = p, style = Stroke(width = 3f))
        }

        // 明度条：黑 → 当前色相纯色
        Canvas(
            modifier = Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(Unit) { detectTapGestures { setV(it.x / this.size.width) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        setV(change.position.x / this.size.width)
                    }
                },
        ) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black,
                    1f to hsvToColor(hsv[0], 1f, 1f),
                ),
                cornerRadius = CornerRadius(6f, 6f),
            )
            drawPath(trianglePath(this.size.width * hsv[2], this.size.width), Color.Black)
        }

        // 透明度条：棋盘格 → 当前色（带 alpha）
        Canvas(
            modifier = Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(Unit) { detectTapGestures { setAlpha(it.x / this.size.width) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        setAlpha(change.position.x / this.size.width)
                    }
                },
        ) {
            // 棋盘格底
            val cell = 12f
            var y = 0f
            while (y < this.size.height) {
                var x = 0f
                while (x < this.size.width) {
                    val w = minOf(cell, this.size.width - x)
                    val h = minOf(cell, this.size.height - y)
                    drawRect(
                        color = if (((x / cell).toInt() + (y / cell).toInt()) % 2 == 0)
                            Color(0xFFCCCCCC) else Color(0xFFF1F1F1),
                        topLeft = Offset(x, y),
                        size = Size(w, h),
                    )
                    x += w
                }
                y += 12f
            }
            // 透明 → 当前色渐变叠加
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to currentColor().copy(alpha = 0f),
                    1f to currentColor(),
                ),
            )
            drawPath(trianglePath(this.size.width * alpha, this.size.width), Color.Black)
        }
    }
}

/**
 * 单个 RGB 分量的输入框：左侧一个 R/G/B 前缀字母，右侧是数字。
 *
 * 面板总宽只有 150~180dp，三个框要平分，所以做得很紧凑：
 * 前缀 9sp、数字 11sp、整体高 28dp。用 `BasicTextField` 而不是
 * `OutlinedTextField` —— 后者自带 56dp 高度和一整套内边距，
 * 在这种窄面板里会撑不开。
 */
@Composable
private fun RgbInputField(
    label: String,
    value: String,
    textColor: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(labelColor.copy(alpha = 0.10f))
            .border(1.dp, labelColor.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp),
    ) {
        Text(label, fontSize = 9.sp, color = labelColor)
        Spacer(Modifier.width(3.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = textColor, fontSize = 11.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            // 光标默认是黑色，深色面板上会看不见，跟着文字色走
            cursorBrush = SolidColor(textColor),
            modifier = Modifier.weight(1f),
        )
    }
}

/** 三角指示器：位于条上方，指向 [fracPx] 对应的横坐标。 */
private fun trianglePath(fracPx: Float, barWidth: Float): Path = Path().apply {
    val x = fracPx.coerceIn(12f, barWidth - 12f)
    moveTo(x - 11f, 0f)
    lineTo(x + 11f, 0f)
    lineTo(x, 12f)
    close()
}

/** ARGB 整型 → HSV（h∈[0,360), s∈[0,1], v∈[0,1]）。 */
private fun argbToHsv(argb: Int): FloatArray {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = if (r >= g && r >= b) r else if (g >= b) g else b
    val min = if (r <= g && r <= b) r else if (g <= b) g else b
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == r -> ((g - b) / d) % 6f
        max == g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    } * 60f
    val hh = if (h < 0f) h + 360f else h
    val s = if (max == 0f) 0f else d / max
    return floatArrayOf(hh, s, max)
}

/** HSV + alpha → ARGB 整型。 */
private fun hsvToArgb(h: Float, s: Float, v: Float, alpha: Float = 1f): Int {
    val c = v * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val red = ((r + m) * 255f).toInt().coerceIn(0, 255)
    val green = ((g + m) * 255f).toInt().coerceIn(0, 255)
    val blue = ((b + m) * 255f).toInt().coerceIn(0, 255)
    val a = ((alpha * 255f).toInt().coerceIn(0, 255))
    return (a shl 24) or (red shl 16) or (green shl 8) or blue
}

private fun hsvToColor(h: Float, s: Float, v: Float): Color = Color(hsvToArgb(h, s, v))

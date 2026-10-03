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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 经典取色面板（参考系统 ColorPicker）：
 *  - 顶栏：取消 / 预览色块+0xAARRGGBB / 确定（拖动过程中不落盘，点「确定」才应用，「取消」还原并收起）
 *  - HSV 圆盘：角度 = 色相，半径 = 饱和度（圆心白 → 边缘纯色）
 *  - 明度条：黑 → 当前色相纯色，三角指示
 *  - 透明度条：棋盘格 → 当前色，三角指示（输出带 alpha 的 ARGB）
 *
 * 不依赖 Compose 的 [Color.toHsv]（部分 BOM 未提供），ARGB↔HSV 自行换算。
 */
@Composable
fun ColorWheel(
    initial: Int,
    onConfirm: (Int) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    // [h∈0..360, s∈0..1, v∈0..1]
    var hsv by remember { mutableStateOf(argbToHsv(initial)) }
    var alpha by remember { mutableStateOf(((initial ushr 24) and 0xFF) / 255f) }
    val sizePx = with(LocalDensity.current) { size.toPx() }

    fun currentArgb(): Int = hsvToArgb(hsv[0], hsv[1], hsv[2], alpha)
    fun currentColor(): Color = Color(currentArgb())

    fun discHandle(offset: Offset) {
        val c = Offset(sizePx / 2f, sizePx / 2f)
        val dx = offset.x - c.x
        val dy = offset.y - c.y
        var deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        if (deg < 0f) deg += 360f
        val s = (hypot(dx, dy) / (sizePx / 2f)).coerceIn(0f, 1f)
        hsv = floatArrayOf(deg, s, hsv[2])
    }

    fun setV(f: Float) {
        hsv = floatArrayOf(hsv[0], hsv[1], f.coerceIn(0f, 1f))
    }

    fun setAlpha(f: Float) {
        alpha = f.coerceIn(0f, 1f)
    }

    Column(modifier.width(size)) {
        // 顶栏：取消 | 确定
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "取消",
                fontSize = 14.sp,
                color = Color(0xFFDDDDDD),
                modifier = Modifier.clickable { onCancel() },
            )
            Spacer(Modifier.weight(1f))
            Text(
                "确定",
                fontSize = 14.sp,
                color = Color(0xFF7DD3FC),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onConfirm(currentArgb()) },
            )
        }

        // 预览：色块 + 十六进制值
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .background(currentColor(), RoundedCornerShape(4.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Text(String.format("0x%08X", currentArgb()), fontSize = 11.sp, color = Color(0xFFBBBBBB))
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

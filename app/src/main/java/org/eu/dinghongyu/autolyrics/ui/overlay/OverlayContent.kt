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

package org.eu.dinghongyu.autolyrics.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.eu.dinghongyu.autolyrics.data.LyricLine
import org.eu.dinghongyu.autolyrics.lyric.LyricEngine
import org.eu.dinghongyu.autolyrics.lyric.LyricRepository
import org.eu.dinghongyu.autolyrics.media.PlaybackMonitor
import org.eu.dinghongyu.autolyrics.ui.components.ColorWheel
import org.eu.dinghongyu.autolyrics.ui.components.LyricText
import org.eu.dinghongyu.autolyrics.util.SettingsStore

/**
 * 悬浮窗内容：显示「上一行 / 当前行 / 下一行」三行。
 *
 * 行为：
 *  - 透明背景（[SettingsStore.Settings.overlayTransparentBg]）下，隐藏标题行与来源标签，只显示歌词；
 *  - 未锁定（[SettingsStore.Settings.overlayLocked] 为 false）时，底部显示控制条：锁定 / 字号 / 颜色，
 *    并可在悬浮窗内直接调字体颜色、字号；锁定后控制条与标题一并隐去，窗口转为点击穿透。
 *  - 拖动由 [onDrag] 交给 [OverlayWindow] 处理窗口参数。
 */
@Composable
fun OverlayContent(onDrag: (Float, Float) -> Unit) {
    val state by LyricEngine.state.collectAsState()
    val index by LyricEngine.index.collectAsState()
    // v1.12.1：歌词位置**不再 collectAsState**。
    //
    // 它每秒变 10~20 次。悬浮窗里真正需要这个值的只有 OverlayLine 内部的逐字染色，
    // 而悬浮窗外层（背景、边框、来源标签、点击区）不需要 ——
    // 订阅它会让整个悬浮窗每秒重组 10~20 次。
    //
    // 改成 lambda：LyricText 在**绘制阶段**读取，不进重组树。
    // 逐字动画本身不受影响（LyricText 本就是 draw 阶段读 curProgress）。
    //
    // remember 固定住这个 lambda 引用 —— 它不捕获任何变化的值，
    // 但每次重组新建一个 lambda 会让下游所有参数变化、重组照样传下去。
    val positionMs = remember { { LyricEngine.lyricPositionSample() } }
    val settings by SettingsStore.settings.collectAsState()
    var showWheel by remember { mutableStateOf(false) }

    // 真正的「暂停时隐藏」由 [OverlayController] 负责（它监听 PlaybackMonitor 并 hide 窗口）。
    // 这里只处理「从未播放过」的空状态：此时不该显示一个空窗口。
    // 注意不要写成 `!playing && state.track == null` —— 暂停时 track 依然存在，
    // 加上它会让暂停状态永远走不到隐藏（这正是 v1.6.0 的 bug）。
    if (state.track == null) return

    val lines = state.lyric?.lines.orEmpty()
    val currentIndex = index.coerceAtLeast(0)
    val fontSize = settings.fontSizeSp.sp
    val transparent = settings.overlayTransparentBg
    val locked = settings.overlayLocked

    val textColor = Color(settings.overlayTextColor)
    val titleColor = textColor.copy(alpha = 0.6f)
    val sourceColor = textColor.copy(alpha = 0.4f)
    val dimColor = textColor.copy(alpha = 0.5f)
    val transColor = textColor.copy(alpha = 0.7f)
    val bg = if (transparent) Color.Transparent else Color(0xBF0D0E10)

    // v1.8.0：跟随新主题的象牙白。原来这里是硬编码的浅蓝 #7DD3FC，
    // 与 App 内页面的墨黑+象牙白是另一套配色，悬浮窗浮在桌面上会显得来自别的 App。
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFE8E4DC))) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg, RoundedCornerShape(18.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { _, drag -> onDrag(drag.x, drag.y) }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 标题行（歌名 + 锁定开关 + 关闭）：仅非透明背景显示
                if (!transparent) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "≡ ${state.track?.title.orEmpty()}",
                            fontSize = 10.sp,
                            color = titleColor,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (locked) "已锁定" else "可拖动",
                            fontSize = 10.sp,
                            color = titleColor,
                            modifier = Modifier.clickable {
                                SettingsStore.update { s -> s.copy(overlayLocked = !s.overlayLocked) }
                            },
                        )
                        if (!locked) CloseButton(titleColor)
                    }
                }

                when {
                    state.status == LyricEngine.Status.LOADING -> Tip("正在获取歌词…", fontSize, textColor)

                    lines.isEmpty() -> {
                        val lyric = state.lyric
                        when {
                            lyric?.plainText != null -> Tip(lyric.plainText ?: "", fontSize, textColor)
                            lyric?.instrumental == true -> Tip("纯音乐，请欣赏", fontSize, textColor)
                            state.track != null -> Tip("未找到歌词", fontSize, textColor)
                            else -> Tip("等待播放…", fontSize, textColor)
                        }
                    }

                    else -> {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            OverlayLine(
                                line = lines.getOrNull(currentIndex),
                                isCurrent = true,
                                positionMs = positionMs,
                                wordByWord = settings.overlayWordByWord,
                                fontSize = fontSize,
                                highlightColor = textColor,
                                dimColor = dimColor,
                                showTranslation = settings.overlayTranslation,
                                transColor = transColor,
                            )
                            if (settings.overlayLineMode == "current_next") {
                                OverlayLine(
                                    line = lines.getOrNull(currentIndex + 1),
                                    isCurrent = false,
                                    positionMs = positionMs,
                                    wordByWord = false,
                                    fontSize = fontSize,
                                    highlightColor = dimColor,
                                    dimColor = dimColor,
                                )
                            }
                        }
                        // 来源标签：仅非透明背景显示
                        if (!transparent) {
                            state.fromSourceId?.let { id ->
                                val kind = if (state.lyric?.wordLevel == true) "逐字" else "整行"
                                Text(
                                    text = "$kind · ${LyricRepository.sourceName(id)}",
                                    fontSize = 9.sp,
                                    color = sourceColor,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }

                // 控制条：未锁定时显示（透明背景也显示，但无背景色块，方便在锁定前调样式）
                if (!locked) {
                    OverlayToolbar(
                        fontSizeSp = settings.fontSizeSp,
                        transparent = transparent,
                        wordByWord = settings.overlayWordByWord,
                        onToggleWordByWord = {
                            SettingsStore.update { s -> s.copy(overlayWordByWord = !s.overlayWordByWord) }
                        },
                        // 锁定只做点击穿透，与「透明背景」开关互不影响。
                        // 两者各自独立，想单独调整透明可以去设置页。
                        onLock = {
                            SettingsStore.update { s -> s.copy(overlayLocked = true) }
                        },
                        onFontDelta = { d ->
                            SettingsStore.update { s ->
                                s.copy(fontSizeSp = (s.fontSizeSp + d).coerceIn(12f, 30f))
                            }
                        },
                        onToggleWheel = { showWheel = !showWheel },
                        onColor = { SettingsStore.updateNow { s -> s.copy(overlayTextColor = it) } },
                        onCloseWheel = { showWheel = false },
                        showWheel = showWheel,
                        textColor = textColor,
                    )
                }
            }

            // 透明背景没有标题行：关闭叉绝对定位在右上角（仅未锁定时）
            if (transparent && !locked) {
                Text(
                    text = "✕",
                    fontSize = 13.sp,
                    color = textColor.copy(alpha = 0.75f),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clickable {
                            SettingsStore.update { s -> s.copy(overlayEnabled = false) }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun OverlayToolbar(
    fontSizeSp: Float,
    transparent: Boolean,
    wordByWord: Boolean,
    onToggleWordByWord: () -> Unit,
    onLock: () -> Unit,
    onFontDelta: (Float) -> Unit,
    onToggleWheel: () -> Unit,
    onColor: (Int) -> Unit,
    onCloseWheel: () -> Unit,
    showWheel: Boolean,
    textColor: Color,
) {
    val barBg = if (transparent) Color.Transparent else Color(0x33000000)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(top = 8.dp)
            .background(barBg, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ToolButton("A-", textColor) { onFontDelta(-2f) }
            Text("${fontSizeSp.toInt()}sp", fontSize = 11.sp, color = textColor)
            ToolButton("A+", textColor) { onFontDelta(2f) }
            // 逐字/整行就地切换（高亮表示当前为逐字）
            Text(
                text = "逐字",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (wordByWord) Color(0xFFE8E4DC) else textColor.copy(alpha = 0.45f),
                modifier = Modifier
                    .widthIn(min = 28.dp)
                    .clickable { onToggleWordByWord() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
            ToolButton("颜色", textColor) { onToggleWheel() }
            ToolButton("锁定", textColor) { onLock() }
        }
        if (showWheel) {
            ColorWheel(
                initial = (textColor.value.toLong() and 0xFFFFFFFF).toInt(),
                onConfirm = { argb ->
                    onColor(argb)
                    onCloseWheel()
                },
                onCancel = onCloseWheel,
                size = 180.dp,
                modifier = Modifier.padding(top = 8.dp),
                // 悬浮窗带 FLAG_NOT_FOCUSABLE（不抢焦点、不拦截背后 App 的操作），
                // 拿不到键盘输入，摆了输入框也点不出键盘 —— 这里只留 RGB 读数。
                // 想手输数值请到「设置 → 悬浮窗 → 字体颜色」，那边是普通窗口。
                allowRgbInput = false,
            )
        }
    }
}

@Composable
private fun ToolButton(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 12.sp,
        color = color,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .widthIn(min = 28.dp)
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** 标题行最右侧的关闭叉：点击关闭悬浮窗（写入 overlayEnabled=false，控制器会隐藏窗口）。 */
@Composable
private fun CloseButton(color: Color) {
    Text(
        text = "✕",
        fontSize = 12.sp,
        color = color,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(start = 10.dp)
            .clickable { SettingsStore.update { s -> s.copy(overlayEnabled = false) } },
    )
}

@Composable
private fun OverlayLine(
    line: LyricLine?,
    isCurrent: Boolean,
    /** v1.12.1：按需读取器，见 LyricText 同名参数的说明。 */
    positionMs: () -> Long,
    wordByWord: Boolean,
    fontSize: androidx.compose.ui.unit.TextUnit,
    highlightColor: Color,
    dimColor: Color,
    showTranslation: Boolean = false,
    transColor: Color = dimColor,
) {
    if (line == null) return
    val scaled = if (isCurrent) fontSize else (fontSize.value * 0.8f).sp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LyricText(
            words = line.words,
            plainText = line.text,
            positionMs = positionMs,
            wordByWord = wordByWord && isCurrent,
            highlightColor = if (isCurrent) highlightColor else dimColor,
            dimColor = if (isCurrent) dimColor else dimColor,
            fontSize = scaled,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            lineHeight = (scaled.value * 1.35f).sp,
        )
        if (isCurrent && showTranslation && !line.translation.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = line.translation!!,
                fontSize = (fontSize.value * 0.78f).sp,
                lineHeight = (fontSize.value * 1.0f).sp,
                color = transColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Tip(text: String, fontSize: androidx.compose.ui.unit.TextUnit, color: Color) {
    Text(
        text = text,
        fontSize = fontSize,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    )
}

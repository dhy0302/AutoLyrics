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
import org.eu.dinghongyu.autolyrics.ui.components.rememberKaraokeClock
import org.eu.dinghongyu.autolyrics.util.SettingsStore

/**
 * 悬浮窗内容：显示「上一行 / 当前行 / 下一行」三行。
 *
 * 行为：
 *  - 透明背景（[SettingsStore.Settings.overlayTransparentBg]）下，隐藏标题行与来源标签，只显示歌词；
 *  - 未锁定（[SettingsStore.Settings.overlayLocked] 为 false）时，底部显示控制条：字号 / 颜色 / 逐字，
 *    并可在悬浮窗内直接调字体颜色、字号；锁定后控制条与标题一并隐去，窗口转为点击穿透。
 *  - 拖动由 [onDrag] 交给 [OverlayWindow] 处理窗口参数。
 *
 * ## v1.18.7：「透明」与「锁定」两个开关搬进悬浮窗
 *
 * 它们原先只在通知栏里。用户要求把它们移进来、文案压缩，
 * 这样通知栏就只剩「开启/关闭桌面歌词」一个按钮。
 *
 * 两个开关读写的仍是 [SettingsStore] 里的**同一份数据**，
 * 与设置页共享—— 所以两边状态永远一致，不存在「两套开关打架」。
 *
 * 布局上放在**标题行**（歌词内容之上），不与歌词重叠；
 * 且只在非透明背景时显示 —— 透明模式下本就没有标题行。
 * 这意味着「从透明切回不透明」这个动作本身需要先退出透明模式，
 * 用户可以到设置页改，或重新开一次桌面歌词。
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
    // 改成 lambda：这个读取器交给 rememberKaraokeClock 做**周期性校准**，
    // 逐帧推进由 withFrameNanos 负责，与这个低频值解耦。
    //
    // remember 固定住这个 lambda 引用 —— 它不捕获任何变化的值，
    // 但每次重组新建一个 lambda 会让时钟每次重组都重新包一层。
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

    // v1.14.1：接上与App 内页同一个逐字时钟，修复悬浮窗逐字动画「卡卡的」。
    //
    //## 病因
    //
    // 原先这里直接把 `positionMs` 读取器透传给 LyricText，而那个读取器读的是
    // `LyricEngine.lyricPositionSample()` —— 它的刷新频率受精度档位限制
    // （省电 200ms / 标准 100ms / 精准 50ms，见 PlaybackMonitor.pollMs）。
    // 于是一个 1 秒的字在标准档下只前进 **10 级台阶**，省电档只有 5 级，
    // 而且台阶**不等距**（轮询周期与字的时间轴不相位）——
    // 表现为「某个字亮到 30% 忽然停住 100ms」，也就是用户说的卡。
    //
    // App 内页没这个问题，因为它接了[rememberKaraokeClock]：
    // 用 `withFrameNanos` 逐帧推进（60~120fps），低频位置值只用来周期性校准。
    // 悬浮窗当初为了省掉 `collectAsState` 而跳过时钟，结果把逐帧推进也一起跳过了。
    //
    // ## 为什么订阅 playing 是安全的
    //
    // 这里确实新增了一个 `collectAsState`，但播放状态**只在播放/暂停切换时变**，
    // 不是高频值。真正的逐帧推进在时钟内部，不经过这里。
    val playing by PlaybackMonitor.isPlaying.collectAsState()
    val currentLine = lines.getOrNull(currentIndex)
    val karaoke = settings.overlayWordByWord &&
        currentLine != null &&
        currentLine.words.isNotEmpty()

    // 时钟只在「当前行 + 逐字开启 + 该行有逐字数据」时启动，
    // 其余情况返回常量 0 且不产生任何帧回调（见 rememberKaraokeClock 的说明）。
    //
    // resetKey 用行的 timeMs —— 与 App 内页同策略：
    // 不能用 positionMs（它每秒变10~20 次，进 key 会让时钟每秒重启那么多次，
    // 逐字动画会直接卡死），但换行确实需要把进度基准归零。
    val smoothPosition by rememberKaraokeClock(
        active = karaoke,
        positionMs = positionMs,
        playing = playing,
        resetKey = currentLine?.timeMs,
    )

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
                        // v1.18.7：透明开关。
                        //
                        // 文案压缩成「透明」/「不透明」两字 —— 原来的
                        // 「歌词背景透明」「歌词背景不透明」是6 个字，
                        // 在标题行里会把歌名挤到没地方放。
                        // 且那个文案是**双向**的：写「点一下会发生什么」。
                        Text(
                            text = if (transparent) "不透明" else "透明",
                            fontSize = 10.sp,
                            color = titleColor,
                            modifier = Modifier
                                .clickable {
                                    SettingsStore.update { s ->
                                        s.copy(overlayTransparentBg = !s.overlayTransparentBg)
                                    }
                                }
                                .padding(horizontal = 5.dp),
                        )
                        // 锁定开关，文案同样压缩为两字。
                        //
                        // 锁定后主窗会 FLAG_NOT_TOUCHABLE 整窗点击穿透，
                        // 这里点不动 —— 解锁靠锁定时另开的那个小窗
                        // （见 OverlayWindow 的 unlockBar），不再是通知栏。
                        Text(
                            text = if (locked) "已锁" else "锁定",
                            fontSize = 10.sp,
                            color = titleColor,
                            modifier = Modifier
                                .clickable {
                                    SettingsStore.update { s -> s.copy(overlayLocked = !s.overlayLocked) }
                                }
                                .padding(horizontal = 5.dp),
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
                                line = currentLine,
                                isCurrent = true,
                                // v1.14.1：传时钟的平滑值（Long），不再传原始读取器。
                                // 命中 LyricText 的 Long 重载，由它包成 lambda。
                                positionMs = smoothPosition,
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
                                    // 非当前行永远是纯色（wordByWord=false），
                                    // LyricText 会直接走PlainLine 快路径，
                                    // 这个值不会被读。传 0L 而不是 smoothPosition
                                    // 是为了表达「这里没有进度概念」。
                                    positionMs = 0L,
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
                    // v1.18.7：工具栏不再有「锁定」按钮，它移到标题行去了。
                    // 这里只留样式类调节（字号 / 逐字 / 颜色），定位不变。
                    OverlayToolbar(
                        fontSizeSp = settings.fontSizeSp,
                        transparent = transparent,
                        wordByWord = settings.overlayWordByWord,
                        onToggleWordByWord = {
                            SettingsStore.update { s -> s.copy(overlayWordByWord = !s.overlayWordByWord) }
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
            // v1.18.7：「锁定」原先在这里，现在已移到标题行。
            // 工具栏只留样式类调节，交互类的开关不混进来。
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
    /**
     * v1.14.1：逐字进度（毫秒），由 [rememberKaraokeClock] 逐帧推进。
     *
     * 原先是 `() -> Long` 的按需读取器，直接读 `lyricPositionSample()`，
     * 而那个值只跟着轮询走（50~200ms 一跳），逐字动画只有 5~10 级台阶
     * 且不等距 —— 详见 [OverlayContent] 里接时钟那段注释。
     */
    positionMs: Long,
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

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

package org.eu.dinghongyu.autolyrics.ui.screen

import org.eu.dinghongyu.autolyrics.R
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.eu.dinghongyu.autolyrics.data.LyricLine
import org.eu.dinghongyu.autolyrics.lyric.LyricEngine
import org.eu.dinghongyu.autolyrics.lyric.LyricRepository
import org.eu.dinghongyu.autolyrics.media.MediaSessionWatcher
import org.eu.dinghongyu.autolyrics.media.PlaybackMonitor
import org.eu.dinghongyu.autolyrics.ui.components.AlbumArtCard
import org.eu.dinghongyu.autolyrics.ui.components.AlbumBackdrop
import org.eu.dinghongyu.autolyrics.ui.components.FluidBackdrop
import org.eu.dinghongyu.autolyrics.ui.components.LyricText
import org.eu.dinghongyu.autolyrics.ui.components.rememberKaraokeClock
import org.eu.dinghongyu.autolyrics.ui.components.PlayerBar
import org.eu.dinghongyu.autolyrics.ui.components.formatClock
import org.eu.dinghongyu.autolyrics.ui.components.rememberAlbumAccent
import org.eu.dinghongyu.autolyrics.ui.components.rememberAlbumColors
import org.eu.dinghongyu.autolyrics.ui.components.rememberAlbumCover
import org.eu.dinghongyu.autolyrics.util.Permissions
import org.eu.dinghongyu.autolyrics.util.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 手动滑动歌词后，暂停自动跟随的时长。 */
private const val FOLLOW_RESUME_DELAY_MS = 3_000L

/**
 * v1.10.0：松手后等多久再把当前行吸回中线。
 *
 * 必须留出这段时间——抬手瞬间 fling 惯性还在跑，
 * 这时候调 scrollToItem 会和惯性动画抢滚动控制权，
 * 结果是「刚松手就被弹一下」，比不回位还糟。
 * 350ms 是在 60Hz 触控设备上实测手感与稳定性的折中值。
 */
private const val SETTLE_AFTER_DRAG_MS = 350L

/** 当前行相对普通行的字号放大倍数，与 AppleLyricLine 里的 1.18f 必须一致。 */
private const val ACTIVE_FONT_SCALE = 1.18f

/** 行高 / 字号 的比值，与 AppleLyricLine 里 lineHeight 的算法必须一致。 */
private const val LINE_HEIGHT_RATIO = 1.30f

/** AppleLyricLine 里 Column 的上下 padding 合计（top 10dp + bottom 10dp）。 */
private const val LINE_PADDING_TOTAL_DP = 20f

/**
 * v1.12.9：当前高亮行中心在**整个窗口**（手机屏幕）里的垂直位置，按比例给。
 *
 * `7f / 16f = 0.4375`，即屏幕从上往下约 43.75% 处。
 *
 * ## 为什么从 1/2 改成 7/16
 *
 * 原来对齐的是屏幕正中（1/2）。实测偏下：屏幕上方要放状态栏、
 * 歌曲信息、以及"上一句"的回顾区，下方只有"下一句"，
 * 视觉重量天然偏上，正中反而显得高亮行被压在下半屏。
 *
 * 上移到 7/16 后，上方留 7 份、下方留 9 份 —— 上方的歌曲信息占掉一块，
 * 两边看起来才是均衡的。两个模式（普通 / 精简）共用这一处常量。
 */
private const val ANCHOR_FRACTION = 7f / 16f

/**
 * 歌词页（Apple Music 风格）：
 *
 * 布局自上而下：居中小字「正在播放」→ 居中封面大卡 → 居中歌名/歌手 →
 * 歌词列表（占据剩余空间）→ 播放控制条。背景为流体渐变（或静态磨砂），
 * 铺满整个屏幕含状态栏/导航栏（沉浸式由 MainActivity 的 enableEdgeToEdge 提供）。
 */
@Composable
fun HomeScreen(
    permTick: Int,
    /** v1.8.2：是否处于前台（无覆盖页）。隐藏时可暂停背景动画与歌词滚动动画。 */
    visible: Boolean = true,
    onGrantNotifications: () -> Unit,
    onOpenListenerSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    /** v1.8.2：右下角「歌词源」图标 */
    onOpenSources: () -> Unit,
    /** v1.8.2：右下角「设置」图标 */
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    // permTick 变化即重新判定：用户从系统设置返回时要立刻刷新
    val listenerOk = remember(permTick) { Permissions.notificationListenerGranted(context) }
    val overlayOk = remember(permTick) { Permissions.overlayGranted(context) }
    val notifyOk = remember(permTick) { Permissions.notificationPostGranted(context) }
    // 抓取链路是否就绪：以 MediaSession 实际能否取到会话为准，
    // 而不是 NotificationListenerService 有没有回调 onListenerConnected
    // （系统强杀/回收后可能永远不再回调，但权限在，检测照样可用）
    var linked by remember { mutableStateOf(false) }
    LaunchedEffect(permTick, listenerOk) {
        if (listenerOk) {
            MediaSessionWatcher.ensureStarted(context)
            linked = MediaSessionWatcher.isLinked
        } else {
            linked = false
        }
    }

    val state by LyricEngine.state.collectAsState()
    val index by LyricEngine.index.collectAsState()
    // v1.12.1：歌词位置**不再 collectAsState**。
    //
    // 它每秒变 10~20 次，而唯一消费者 rememberKaraokeClock 只把它当**基准值用一次**
    // （逐帧推进由 withFrameNanos 负责）。订阅它等于让整个 HomeScreen
    // 每秒重组 10~20 次 —— 而 HomeScreen 里含流体渐变背景、专辑封面、播放条。
    //
    // 改为按需读取。remember 固定住引用 —— 否则每次重组新建 lambda，
    // 下游 AppleLyricList / AppleLyricLine 的参数照样全变，优化等于白做。
    val lyricPosition = remember { { LyricEngine.lyricPositionSample() } }

    val playing by PlaybackMonitor.isPlaying.collectAsState()
    val position by PlaybackMonitor.positionMs.collectAsState()
    val duration by PlaybackMonitor.durationMs.collectAsState()
    val capabilities by PlaybackMonitor.capabilities.collectAsState()
    val artBitmap by PlaybackMonitor.albumArt.collectAsState()
    val artUri by PlaybackMonitor.albumArtUri.collectAsState()
    val settings by SettingsStore.settings.collectAsState()

    val cover = rememberAlbumCover(artBitmap, artUri)
    val accent = rememberAlbumAccent(cover, MaterialTheme.colorScheme.primary)
    val albumColors = rememberAlbumColors(cover, MaterialTheme.colorScheme.primary)

    // 点击歌词行要反算回播放器的时间轴：加上全局偏移与歌词自带偏移
    val timeOffset = settings.globalOffsetMs + (state.lyric?.offsetMs ?: 0L)

    // 歌词页高亮色：用户在设置里自选，默认纯白。
    // 未唱色由它按 0.45 透明度自动派生，保证配色协调。
    val lyricColor = Color(settings.inAppTextColor)
    val lyricDim = lyricColor.copy(alpha = 0.45f)
    val lyricFontSize = settings.inAppFontSizeSp.sp

    // 精简模式：点封面也能切进来（与设置项互为入口）。
    // v1.8.2 提到 Box 之前——权限卡片要读它来决定是否渲染。
    var minimal by remember(settings.inAppMinimal) { mutableStateOf(settings.inAppMinimal) }
    val minimalRaw = minimal

Box(Modifier.fillMaxSize()) {
    if (settings.fluidBackground) {
        // v1.8.2：三种情况都把背景动画降速/暂停
        //  1. 覆盖页盖住歌词页 → 看不见就别跑 GPU
        //  2. 播放暂停且用户开了省电 → 冻住
        //  3. 正常播放 → 全速
        val animScale = when {
            !visible -> 0f
            settings.freezeBackdropOnPause && !playing -> 0f
            else -> 1f
        }
        FluidBackdrop(
            cover = cover,
            colors = albumColors,
            animationScale = animScale,
        )
    } else {
        AlbumBackdrop(cover = cover)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        // v1.13.7：顶部留一口呼吸。
        //
        // v1.12.9 把 38dp 顶栏整条撤掉时，歌曲信息块跟着上移到了约 39dp，
        // 而它上方只剩 statusBarsPadding() —— 也就是**紧贴状态栏下沿**，
        // 截图上看着像被状态栏压住。补 12dp 让两者脱开。
        //
        // 为什么加在 Column 开头而不是歌曲信息块自身：
        // 权限卡片 / 重取提示出现时排在更上面，加在 Column 开头才能一并让位；
        // 只给歌曲信息块加的话，那两种情况下又会顶回状态栏。
        //
        // 为什么是 12dp：旧顶栏 38dp，撤掉后一版走得太急；
        // 12dp 约等于状态栏高度的 1/3，够脱开又不至于把歌词区压得太多。
        //
        // 不动 ANCHOR_FRACTION：高亮行的绝对位置 = viewportTop + centerTopPadding，
        // 而 centerTopPadding = windowH * ANCHOR_FRACTION - viewportTop，
        // 两项相消 —— 视口整体下移多少，高亮行在屏幕上的位置就正好上移多少，
        // **高亮行原地不动**，只是歌词区上边界往下挪了。
        Spacer(Modifier.height(12.dp))

        // v1.8.2：精简模式下**只留右上角那个展开按钮**，
        // 权限卡片与重试提示全部隐藏——它们也是"按钮"，会破坏极简。
        if (!minimalRaw && (!listenerOk || !overlayOk || !notifyOk)) {
            PermissionCard(
                listenerOk, overlayOk, notifyOk, lyricColor,
                onGrantNotifications, onOpenListenerSettings, onOpenOverlaySettings,
            )
            Spacer(Modifier.height(8.dp))
        }

        // 权限已授予但抓取链路仍未建立：给出可操作的重试入口
        if (!minimalRaw && listenerOk && !linked) {
            RebindHint(
                lyricColor = lyricColor,
                onRetry = {
                    MediaSessionWatcher.ensureStarted(context)
                    linked = MediaSessionWatcher.isLinked
                },
                onOpenListenerSettings = onOpenListenerSettings,
            )
            Spacer(Modifier.height(8.dp))
        }

        // v1.12.9：原来的 38dp 顶栏（居中「正在播放」小字 + 右侧「重取」与滑块设置入口）
        // 已整条移除。两条理由：
        //
        //  1. 「正在播放」是纯装饰文案，占着一整行高度却不提供任何操作；
        //  2. 右上角那个滑块入口与右下角 CornerActions 里的「设置」按钮功能重叠。
        //
        // 「重取」没有删，而是**留在右上角原处**（它本就在顶栏右端，
        // 顶栏一撤，这一行顶上来，它的屏幕位置几乎没变 —— 约 43dp → 40dp）。
        // 真正上移的是歌曲信息块：标题从约 99dp 提到约 51dp，
        // 于是歌名与「重取」落在同一水平线上。
        //
        // （v1.13.7 起是 51dp 而非 39dp：顶部补了 12dp 呼吸间距，
        //   免得整块贴着状态栏下沿。详见上面那个 Spacer 的注释。）
        //
        // 省下的 38+8dp 高度归歌词区。
        if (!minimal) {
            // 顶部横向条：小封面 + 歌名/歌手，参考图那种「专辑图只占一小部分」的布局
            //
            // v1.12.9：改 **Top 对齐**（原本是 CenterVertically）。
            //
            // 为什么：右侧竖列（重取 + 精简切换，共 64dp）比左列高，
            // 在居中对齐下左列会被垂直居中 —— 一旦「歌词源」那行不显示
            // （还没取到词时正好没有），左列变矮、居中后歌名就往下掉约 6dp，
            // 与「重取」错开。而没取到词恰恰是最需要点「重取」的时候。
            //
            // 顶对齐后，左列首行（歌名）永远贴着行顶，
            // 与「重取」的顶部对齐关系不再受左列行数影响。
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val smallCover = 56.dp
                AlbumArtCard(
                    cover = cover,
                    modifier = Modifier
                        .size(smallCover)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { minimal = true },
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.track?.title ?: "未在播放",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = lyricColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    val subtitle = buildString {
                        append(state.track?.artist.orEmpty().ifBlank { "—" })
                        val pkg = state.track?.pkg
                        if (!pkg.isNullOrBlank()) append("  ·  ").append(appLabel(pkg))
                    }
                    Text(
                        text = subtitle,
                        fontSize = 13.sp,
                        color = lyricColor.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sourceCaption = state.fromSourceId?.let {
                        LyricRepository.sourceName(it) +
                            if (state.lyric?.wordLevel == true) " · 逐字" else " · 整行"
                    }
                    if (sourceCaption != null) {
                        Text(
                            text = sourceCaption,
                            fontSize = 10.sp,
                            color = lyricColor.copy(alpha = 0.4f),
                            maxLines = 1,
                        )
                    }
                }
                // v1.12.9：右侧原来是「⋮ 精简切换」一个按钮，
                // 现在改成上下两枚：上面是「重取」（接替原设置入口的右上角位置），
                // 下面仍是精简切换。
                //
                // 为什么竖排而不是横排：原布局里设置入口与 ⋮ 就是**同一列**的上下两枚
                // （截图里 x 一致、y 不同）。横排会让两个按钮挤在一起，
                // 而且「重取」就没法落在歌名那一行的右端了。
                //
                // 这一列比左边的歌曲信息略高，Row 因此取它的高度。
                // 它自己贴着行顶，左列也贴着行顶（Row 用 Top 对齐），
                // 于是首枚「重取」与左列首行「歌名」稳定落在同一水平线上。
                Column(horizontalAlignment = Alignment.End) {
                    RightTopAction(
                        listenerOk = listenerOk,
                        hasTrack = state.track != null,
                        color = lyricColor,
                        onRefresh = { LyricEngine.refresh(force = true) },
                        onOpenListenerSettings = onOpenListenerSettings,
                    )
                    // 与精简模式里的「显示全部」互为镜像的入口
                    MinimalToggle(expand = false, color = lyricColor) { minimal = true }
                }
            }

            Spacer(Modifier.height(6.dp))
        } else {
            // 精简模式：右上角给个「显示全部」出口
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                MinimalToggle(expand = true, color = lyricColor) { minimal = false }
            }
        }

        Spacer(Modifier.height(10.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val lines = state.lyric?.lines.orEmpty()
            if (lines.isNotEmpty()) {
                AppleLyricList(
                    lines = lines,
                    index = index,
                    // v1.12.1：传 lambda 而非值 —— 见上方 :169 的说明。
                    positionMs = lyricPosition,
                    playing = playing,
                    wordByWord = settings.wordByWordEnabled,
                    showTranslation = settings.showTranslation,
                    highlight = lyricColor,
                    dim = lyricDim,
                    fontSize = lyricFontSize,
                    onSeekTo = { timeMs -> PlaybackMonitor.seek(timeMs + timeOffset) },
                    // v1.8.2：底栏已删，只需给右下角那两枚小图标留出高度，
                    // 否则最后一句歌词会被压在图标下面。
                    bottomContentPadding = if (minimal) 24.dp else 72.dp,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                EmptyHint(
                    listenerOk = listenerOk,
                    status = state.status,
                    plainText = state.lyric?.plainText,
                    instrumental = state.lyric?.instrumental == true,
                    color = lyricColor,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (!minimal) {
            /**
             * v1.9.1：**这里原来是各自贴屏幕底边**，于是右下角那两枚圆形图标
             * 压到了 PlayerBar 的进度条上——用户圈出来的就是这个问题。
             *
             * 根因不是"图标放错了地方"，是**两个独立定位的兄弟**：
             * PlayerBar 用 `align(BottomStart)` 类的贴底 + navigationBarsPadding，
             * CornerActions 用 `align(Alignment.BottomEnd)` + 自己的 bottom padding，
             * 谁也不知道对方有多高。竖排两枚 44dp 图标（44+10+44 = 98dp）
             * 从 bottom=14dp 往上占 98~112dp，而 PlayerBar 的进度条行
             * 恰好就在这个高度带里——于是「3:32」被圆形按钮压掉了半边。
             *
             * 修法：**让它们变成同一个 Column 里的上下两行**。
             * Column 会按子项实际高度依次排布，两者物理上不可能重叠，
             * 也不用再靠手算 padding 去猜对方的高度（换个字号/加个按钮就又错位了）。
             */
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
            ) {
                // 上层：两枚入口圆钮，右对齐
                CornerActions(
                    lyricColor = lyricColor,
                    onOpenSources = onOpenSources,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.padding(end = 14.dp, bottom = 10.dp),
                )
                // 下层：播放条。只让开系统导航栏
                PlayerBar(
                    positionMs = position,
                    durationMs = duration,
                    isPlaying = playing,
                    capabilities = capabilities,
                    accent = accent,
                    onSeek = { PlaybackMonitor.seek(it) },
                    onPrevious = { PlaybackMonitor.skipToPrevious() },
                    onNext = { PlaybackMonitor.skipToNext() },
                    onPlayPause = { PlaybackMonitor.playOrPause() },
                    // v1.8.2：底栏已删，播放条回到屏幕底部，只让开系统导航栏。
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 4.dp),
                )
            }
        }
        // v1.8.1：精简模式下**什么都不留**——不要迷你播放条。
        // 之前那个「⏮ ▶ ⏭ + 时间」的存在理由是「否则暂停了没法恢复」，
        // 但用户明确要求「只保留歌词」。播放控制在底部 TabBar 上方的
        // 完整 PlayerBar、以及通知栏里都有，够用。
    }

    /**
     * v1.8.2：右下角两枚半透明小图标 —— 歌词源 / 设置，竖向排列。
     *
     * v1.9.1 已挪进上面的 Column（与 PlayerBar 同列、排在它上方），
     * 解决这两个图标压住进度条的问题。这里原来还有一份 `align(BottomEnd)`
     * 的独立定位，是重叠的直接来源，已删除。
     *
     * 保留这条注释是因为它记录了「为什么它们在歌词页内部而不是全局浮层」：
     * 精简模式下连它们一起隐藏，这样"极简"才是真的极简。
     */
}
}

/**
 * 右下角的两枚半透明圆形图标（竖排）。
 *
 * ## 为什么是半透明而不是实心
 * 它们压在流动背景上，实心块会把背景切断，像贴了两张纸。
 * 用 22% 的黑底 + 高亮色图标，能读清又不抢戏。
 *
 * ## 为什么不用 Material3 的 IconButton
 * IconButton 自带 48dp 最小触摸区与水波纹，但它的容器在深色背景下
 * 是一块明显的方形区域；这里只需要一个"轻触点"，用 Box + clip 更干净。
 * 触摸区保留 44dp，正好是Material 无障碍下限。
 */
@Composable
private fun CornerActions(
lyricColor: Color,
onOpenSources: () -> Unit,
onOpenSettings: () -> Unit,
modifier: Modifier = Modifier,
) {
Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
    modifier = modifier,
) {
    CornerIcon(
        icon = R.drawable.ic_corner_sources,
        description = "歌词源",
        tint = lyricColor,
        onClick = onOpenSources,
    )
    CornerIcon(
        icon = R.drawable.ic_corner_settings,
        description = "设置",
        tint = lyricColor,
        onClick = onOpenSettings,
    )
}
}

@Composable
private fun CornerIcon(
icon: Int,
description: String,
tint: Color,
onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            // 半透明黑底：任何背景下都能撑住图标，同时不切断背景
            .background(Color.Black.copy(alpha = 0.22f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = tint.copy(alpha = 0.82f),
            modifier = Modifier.size(19.dp),
        )
    }
}

/* ------------------------------ 歌词列表 ------------------------------ */

/**
 * Apple Music 风格歌词列表：左对齐大字号，当前行用 spring 弹簧放大 + alpha 渐亮。
 *
 * 淡出边缘用离屏合成 + [BlendMode.DstIn] 实现（真 alpha 淡出，露出流体背景），
 * 而不是叠一层黑色渐变——黑渐变在彩色背景上会呈现为一个突兀的深色矩形。
 *
 * 跟随策略：默认平滑跟随、手动拖动即停、松手后 [FOLLOW_RESUME_DELAY_MS] 内不跟随。
 */
@Composable
private fun AppleLyricList(
    lines: List<LyricLine>,
    index: Int,
    /**
     * v1.12.1：歌词位置的**按需读取器**，不是值。
     *
     * 以前传 `Long`，顶层用 `collectAsState()` 订阅，每秒 10~20 次
     * 触发整个 HomeScreen 重组；而这个值只被 [rememberKaraokeClock]
     * 当基准值用一次，逐帧推进走 `withFrameNanos`，根本不依赖它。
     *
     * 改成 lambda 后值不进重组树 —— 只有真正需要时才读。
     */
    positionMs: () -> Long,
    playing: Boolean,
wordByWord: Boolean,
showTranslation: Boolean,
/** 已唱/当前行的高亮色（用户可自定义） */
highlight: Color,
/** 未唱部分的颜色，由高亮色派生 */
dim: Color,
fontSize: androidx.compose.ui.unit.TextUnit,
onSeekTo: (Long) -> Unit,
    /** 列表底部内边距。精简模式下底部只剩 TabBar，需要多留一些。 */
    bottomContentPadding: androidx.compose.ui.unit.Dp = 56.dp,
    modifier: Modifier = Modifier,
) {
val listState = rememberLazyListState()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var suppressUntil by remember { mutableLongStateOf(0L) }
    var wasDragging by remember { mutableStateOf(false) }

    /**
     * v1.12.1：当前行实测高度（像素），随 item 真实布局更新。
     *
     * 为什么不能继续用公式估算：
     * `activeHalfLinePx` 按「20dp padding + 行高」算，但 item 的真实高度
     * 会因为以下任一项变大：
     *   · 当前行带译文（showTranslation 开启时多一行 13sp + 4dp 间隔）
     *   · 长句折行（lineHeight × 行数）
     * 估算偏小 → scrollToItem 的 offset 偏小 → **当前行落在中线以下**。
     *
     * 之前一直"看着差不多"是因为大多数歌词既无译文也不折行，
     * 公式恰好成立；一旦翻译歌词出现就暴露。
     *
     * 初值 -1 表示"还没量到"，此时退回公式估算（见下方 activeHalfLinePx），
     * 避免首帧跳动。
     */
    var activeLineHeightPx by remember { mutableIntStateOf(-1) }

    /**
     * v1.12.1：当前行中心在**视口内**应该落到的 y（像素）。
     *
     * 由 onGloballyPositioned 实测算出，公式见下面 centerTopPadding 的推导。
     * 初值 -1 = 还没量到，此时退回原来的「视口中线」行为，不跳。
     *
     * 实测而不是让父级把inset 传进来：上方有多少留白（状态栏 / 展开按钮 /
     * 权限卡）、下方有没有 TabBar，会随权限状态、折叠状态、字号而变，
     * 让调用方手算这些数字迟早会错，而错的表现恰好是「看起来偏上/偏下几像素」
     * 这种很难自查的问题。
     */
    var targetTopPaddingPx by remember { mutableIntStateOf(-1) }

    /**
     * v1.12.1：视口自身高度（像素），供渐变遮罩换算当前行所在的相对位置。
     * 与 [targetTopPaddingPx] 同在 onGloballyPositioned 里量，两者一起更新。
     */
    var viewportHeightPx by remember { mutableIntStateOf(0) }

    /**
     * v1.12.1：窗口（Activity 根View）的高度，像素。
     *
     * 为什么不用 BoxWithConstraints 的 maxHeight：那是**视口**高。
     * 视口上下留白不对称（上方有状态栏与展开按钮，下方贴屏幕底），
     * 视口中线 ≠ 屏幕中线，这正是本次要修的 bug。
     *
     * 取 `LocalView.current.rootView.height` 而不是记成 state：
     * rootView 尺寸变化时 rootViewHeight 会同步更新，
     * 而 onGloballyPositioned 每次布局都会重跑并重算目标值，无需再存一份。
     */
    val rootViewHeight = LocalView.current.rootView.height

    // v1.10.0：松手信号。松手时 +1，作为「归位 effect」的 key。
//
// 为什么不用 dragging 本身当 key：dragging 在**整个拖动过程中**都是 true，
// 用它当 key 时 effect 只在拖动开始/结束各跑一次，中间无法区分；
// 而"拖动中"恰恰是我们最不该抢滚动的时候。
// 递增的计数器把"拖动中"整段跳过，只在松手那一帧产生一次变化。
//
// 为什么不用 wasDragging 直接当条件：那样正确性就依赖
// 「谁先执行」——两个 effect 同时以 dragging 为 key 时，
// 谁先写 wasDragging 会决定另一个读到 true 还是 false。
// 拆成信号量后两个 effect 的 key 完全解耦，不存在这种时序耦合。
var settleSignal by remember { mutableIntStateOf(0) }

LaunchedEffect(dragging) {
    if (wasDragging && !dragging) {
        suppressUntil = SystemClock.elapsedRealtime() + FOLLOW_RESUME_DELAY_MS
        settleSignal++
    }
    wasDragging = dragging
}

// v1.10.0：当前行滚到**屏幕垂直居中**。
//
// ## v1.9.0 的公式为什么把歌词顶到了屏幕顶端
//
// LazyColumn 里 item 的实际落点只有一个方程：
//
//     item 顶部 y = contentPadding.top - scrollOffset
//     item 中心 y = contentPadding.top - scrollOffset + itemHeight/2
//
// 注意 `contentPadding.top` 和 `scrollOffset` 是**同一个方程里的两项**。
// 而 v1.9.0 写成 `scrollOffset = 视口高/2 - halfLine`，
// 等于把 `contentPadding.top` 已经抵掉的那半个视口**又减了一遍**：
//
//     center = (视口高/2) - (视口高/2 - halfLine) + halfLine = itemHeight
//
// 也就是当前行中心只落在视口高 1/2 处再往下 halfLine 的位置 ≈ 顶部 12%。
// 用户录屏实测约 12%，与公式预测的 12.7% 吻合，确认就是这个错。
//
// ## v1.10.0 的做法
//
// 目标 center = 视口高/2，代入同一方程解得：
//
//     scrollOffset = contentPadding.top + halfLine - 视口高/2
//                  = (视口高/2) + halfLine - (视口高/2)
//                  = halfLine
//
// 也就是说**当 contentPadding.top 取半个视口时，scrollOffset 就等于半个行高**，
// 两个量各管一件事、不再互相抵消。视口高度变化时这个关系自动成立。
//
// 代价是列表首尾多出一段空白 —— 但这正是经典歌词布局该有的样子：
// 上一句在上半屏、下一句在下半屏。
BoxWithConstraints(
    modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        // v1.12.1：实测「基准线」（窗口高的 ANCHOR_FRACTION 处）在视口内的 y 坐标。
        //
        // 为什么不能直接用 BoxWithConstraints 的 maxHeight：
        // 它给的是**视口**高，而对齐目标是**窗口**中线 ——
        // 视口上下留白不对称，两者不是一回事（详见 centerTopPadding 的注释）。
        //
        // 推导：视口顶在窗口里的 y = coords.positionInWindow().y，
        // 窗口高取 LocalView.current.rootView.height（真正的窗口高度），
        // 于是**对齐基准线**距视口顶 = windowH * ANCHOR_FRACTION - viewportTop
        //（v1.12.9 起基准线是窗口高度的 7/16，不再是 1/2，见 ANCHOR_FRACTION）。
        //
        // 注意 onGloballyPositioned 的 lambda 收到的是 **LayoutCoordinates**，
        // 它没有 `root` 属性（那是 Density 的），窗口高必须另外取。
        // `positionInWindow()` / `size` 都是 LayoutCoordinates 的成员/扩展。
        //
        // 只在值真正变化时写 state，不产生每帧重组。
        .onGloballyPositioned { coords ->
            val windowH = rootViewHeight
            val viewH = coords.size.height
            val want = if (windowH > 0) {
                (windowH * ANCHOR_FRACTION - coords.positionInWindow().y).roundToInt()
            } else {
                -1
            }
            if (want != targetTopPaddingPx) targetTopPaddingPx = want
            if (viewH != viewportHeightPx) viewportHeightPx = viewH
        }
        .drawWithContent {
            drawContent()
            // DstIn：目标 alpha × 源 alpha → 两端渐隐到透明。
            //上下各留了半屏空白，渐隐区间必须跟着外推，
            // 否则渐隐带正好压在当前行上，把居中那行给淡化了。
            //
            // v1.12.1：当前行不再落在视口中线，而是**窗口上的固定比例线**
            //（v1.12.9 起为 7/16），
            // 所以「不透明区间」的中心要跟着挪，不再是 0.5。
            // centerFrac 由下面算出（当前行中心 / 视口高），
            // 半带宽0.20 保持不变 —— 只挪中心，不改宽度。
            val centerFrac = if (viewportHeightPx > 0) {
                (targetTopPaddingPx.toFloat() / viewportHeightPx).coerceIn(0.15f, 0.85f)
            } else {
                ANCHOR_FRACTION
            }
            val fade = 0.20f
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    (centerFrac - fade).coerceAtLeast(0f) to Color.Black,
                    (centerFrac + fade).coerceAtMost(1f) to Color.Black,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
) {
    val halfViewport = maxHeight / 2

    /**
     * v1.12.1：当前行中心的目标位置 —— **屏幕上的固定比例线**（[ANCHOR_FRACTION]），
     * 而不是视口中心。
     *
     * ## 为什么原来的 `halfViewport` 是错的
     *
     * `contentPadding.top = halfViewport` + `offset = 半行高` 这套推导本身没错，
     * 它确实能让当前行落在**视口**中线。但视口 ≠ 屏幕：
     *
     * ```
     * ┌─────────────────────────┐ ← 屏幕顶
     * │ 状态栏                   │
     * │ ┌─ 展开按钮 28dp ─┐│
     * │ └──────┐           │
     * │        ↓ 视口顶                │
     * │ （歌词列表视口）               │
     * │        ↓ 视口底 = 屏幕底        │ ← Column 没加 navigationBarsPadding
     * └─────────────────────────┘ ← 屏幕底
     * ```
     *
     * 视口顶比屏幕顶低「状态栏 + 28dp + 10dp」，视口底却等于屏幕底，
     * 于是**视口中心比屏幕中心低了 (状态栏 + 38dp) / 2**，用户看到的就是「当前行偏下」。
     *
     * 实测（1440×3136 截图，density 3）：当前行中心在屏幕中心下方 158px，
     * 与上面算出的偏移量吻合。
     *
     * ## 修法
     *
     * 目标 top padding =「基准线到视口顶的距离」= `windowH * ANCHOR_FRACTION - viewportTop`，
     * 由 [targetTopPaddingPx] 实测得到（见 BoxWithConstraints 上的
     * onGloballyPositioned）。不去猜「上方到底有多少留白」——
     * 那个值会随权限卡是否显示、字号、折叠状态而变，手算必错。
     *
     * 首帧（还没量到）退回 halfViewport，与原行为一致，不跳。
     */
    val density = LocalDensity.current
    val centerTopPadding = with(density) {
        if (targetTopPaddingPx >= 0) {
            targetTopPaddingPx.toDp().coerceAtLeast(0.dp)
        } else {
            // 首帧还没量到：按视口高的同一比例近似（视口顶只比窗口顶低一点点），
            // 与原行为一致——只是一帧的过渡值，量到后立刻被真实值取代。
            maxHeight * ANCHOR_FRACTION
        }
    }

    // 当前行 item 高度之半。
    //
    // 优先用**实测值**：item 里多一行译文、或长句折行时真实高度会大于公式估算，
    // 估算偏小会让当前行落在中线以下（v1.12.1 修的第二个问题）。
    // 还没量到时（首帧）退回公式估算，避免跳动。
    val activeHalfLinePx = if (activeLineHeightPx > 0) {
        activeLineHeightPx / 2f
    } else {
        // 公式估算：(上下 padding 合计 20dp + 行高) / 2
        //
        // 必须做 sp → dp → px 两级换算：行高按 sp 定义（随系统字号缩放），
        // 而 scrollToItem 的 offset 要物理像素。直接把 sp 当 dp 用，
        // 在 3x 屏上会差出好几倍。toDp/toPx 是 Density 的接口成员，
        // 只能在 Density 作用域内调用（没有对应的顶层扩展可 import）。
        with(density) {
            val padPx = LINE_PADDING_TOTAL_DP.dp.toPx()
            val linePx = (fontSize.value * ACTIVE_FONT_SCALE * LINE_HEIGHT_RATIO).sp.toPx()
            (padPx + linePx) / 2f
        }
    }

    // 见上方推导：contentPadding.top = 目标位置时，offset 就等于半行高。
    val alignOffsetPx = activeHalfLinePx.roundToInt()

    /**
     * v1.10.0 重写跟随逻辑。
     *
     * ## v1.9.0 的第二个 bug：拖动后永不归位
     *
     * 旧版：
     * ```kotlin
     * LaunchedEffect(index, ..., dragging, ...) {
     *     if (dragging) return@LaunchedEffect   // ← dragging 一变 true 就整个跳过
     * }
     * ```
     * `dragging` 是 key 之一，拖动开始时 effect 重启并立刻 return，
     * 于是**松手那一刻没有任何东西去重新对齐**——歌词就永远停在用户拖到的位置。
     * 表现是"手动翻几句之后就再也回不到当前行了"。
     *
     * ## 现在的做法
     *
     * 分成两个职责清晰的 effect：
     * 1. **松手归位**（`settleBack`）：只在 `dragging: true→false` 的瞬间跑一次，
     *    等 [FOLLOW_RESUME_DELAY_MS] 让手势惯性停下，然后**无动画**吸附到中线。
     *    用 scrollToItem 而非 animateScrollToItem —— 用户已经松手了，
     *    再放一段动画会显得"还在动"。
     * 2. **随歌跟随**（`index`/`fontSize` 变化时）：当前行变了才平滑滚过去。
     *    拖动期间不跑（由 suppressUntil 兜底），否则会和手势打架。
     */
    // 1) 松手归位
    //
    // key 是 settleSignal 而非 index：只由松手驱动，不会被随歌跟随重启。
    // index 用 rememberUpdatedState 读最新值而不进 key ——
    // 进了 key 就会每行歌词推进一次都重启本 effect、重新 delay 350ms，
    // 既拖慢归位又会跟下面的随歌跟随抢滚动。
    val latestIndex by rememberUpdatedState(index)
    val latestDragging by rememberUpdatedState(dragging)
    LaunchedEffect(settleSignal) {
        if (settleSignal == 0) return@LaunchedEffect
        // 抬手后等 fling 惯性彻底停下再吸附，否则会和惯性动画抢滚动。
        delay(SETTLE_AFTER_DRAG_MS)
        // 这 350ms 里用户可能又开始拖了，那就别抢。
        if (latestDragging) return@LaunchedEffect
        runCatching {
            listState.scrollToItem(latestIndex.coerceAtLeast(0), alignOffsetPx)
        }
    }

    // 2) 随歌跟随
    //
    // 依赖 `suppressUntil` 这个 key 是关键：它是**毫秒时间戳**，
    // 每次松手都会变，于是 effect 必然重启、重新走一遍 delay，
    // 天然实现了"松手后 3 秒内不打扰用户"。
    // 若只把 index 当 key，松手瞬间不会有任何 effect 跑，
    // 随歌跟随就会立刻把用户刚拖走的位置拽回去。
    // centerTopPadding 进key 的原因（v1.12.1）：它会因为
    // 权限卡显隐 / 精简模式切换 / 窗口尺寸变化而改变，
    // 而这些变化**不改变 index**，若不在 key 里，
    // contentPadding 已经偏移了、滚动位置却还停在旧目标上。
    LaunchedEffect(index, lines.size, suppressUntil, alignOffsetPx, centerTopPadding) {
        if (lines.isEmpty() || index < 0) return@LaunchedEffect
        // 拖动中直接让位。判据用 latestDragging 而不是启动瞬间的 dragging，
        // 因为 effect 启动到真正执行 scroll 之间可能隔着好几百毫秒。
        if (latestDragging) return@LaunchedEffect
        val wait = suppressUntil - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        // delay 完再查一次：等待期间用户可能已经上手拖了。
        if (latestDragging) return@LaunchedEffect
        runCatching {
            // 用框架自带的 fling+snap 动画（Compose 1.7.5 的
            // animateScrollToItem 内部就是它），切歌跨多行时初速度连续，
            // 观感上像"被磁铁吸过去"。
            listState.animateScrollToItem(
                index = index.coerceAtLeast(0),
                scrollOffset = alignOffsetPx,
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            // v1.12.1：用centerTopPadding 而非 halfViewport ——
            // 目标从「视口中线」改成「屏幕上的固定比例线」（v1.12.9 起为 7/16），
            // 推导见上面 centerTopPadding 的注释。
            top = centerTopPadding,
            // 底部照旧留半屏，保证「下一句在下半屏」的传统布局。
            bottom = halfViewport + bottomContentPadding,
        ),
    ) {
        itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
            val active = i == index
            AppleLyricLine(
                isActive = active,
                line = line,
                // v1.8.2 性能：只有当前行才需要知道播放位置。
                //
                // 旧版把 positionMs 无条件传给每一行。positionMs 每 100ms 变一次，
                // 意味着**整屏所有可见行**的参数都变 → 全部重组 → 逐行重跑
                // buildAnnotatedString + 文本测量。而其中只有 1 行真有逐字动画，
                // 其余行的渲染结果本来就完全相同。
                //
                // v1.12.1：positionMs 变成 lambda 后，这里的 `if (active)` 已无必要 ——
                // lambda 本身是**稳定引用**（不捕获变化的值），
                // 所有行的参数都不再随时间变化，Compose 全部跳过重组。
                // 「非当前行不读位置」这个语义下沉到 AppleLyricLine 内部：
                // 那里 karaoke=false，rememberKaraokeClock 直接返回常量 0，
                // 根本不会调用这个 lambda。
                positionMs = positionMs,
                playing = playing,
                wordByWord = wordByWord,
                showTranslation = showTranslation,
                highlightColor = highlight,
                dimColor = dim,
                fontSize = fontSize,
                onSeek = { onSeekTo(line.timeMs) },
                // v1.12.1：把当前行的真实高度报上去，供滚动对齐用。
                // 只有当前行需要测量——非当前行永远不是对齐目标。
                onHeightChange = if (active) {
                    { h -> if (h != activeLineHeightPx) activeLineHeightPx = h }
                } else {
                    null
                },
            )
        }
    }
}
}

/**
 * 单行歌词。
 *
 * ## v1.8.3 的两处改动
 *
 * **1. 放大当前行：从 `graphicsLayer.scale` 改成真实字号。**
 * 旧版用 scale(1.0 → 1.1) + transformOrigin(0, 0.5) 做那套"文字从左基线长出来"的
 * Apple Music 手感。但**缩放不改变布局尺寸**——放大后的行仍按原高度占位，
 * 相邻行的间距不会跟着变，视觉上像"字变大了但行距没变"，比例失衡。
 * 改字号后 LazyColumn 会真实地重新测量这一项，间距自然跟上。
 * spring 弹跳与 transformOrigin 一并去掉：缩放的抖动在"当前行恒定居中"的
 * 布局里反而显得晃。
 *
 * **2. 逐字动画改用独立时钟**（[rememberKaraokeClock]）。
 * 进度源不再是 100~200ms 一跳的 `lyricPositionMs`，
 * 而是由 FrameClock 逐帧推进的连续值——这才是"1秒的字分 10 份亮"
 * 能成立的前提（详见 [rememberKaraokeClock] 的说明）。
 *
 * 其余：alpha 仍按当前/非当前在 1.0 / 0.45 之间过渡。
 */
@Composable
private fun AppleLyricLine(
    isActive: Boolean,
    line: LyricLine,
    /**
     * v1.12.1：歌词位置的**按需读取器**。
     *
     * 非当前行不会被调用 —— `karaoke=false` 时 [rememberKaraokeClock]
     * 直接返回常量 0，压根不读。所以这里传同一个 lambda 给所有行是安全的，
     * 且所有行的参数都保持稳定引用，Compose 全部跳过重组。
     */
    positionMs: () -> Long,
    playing: Boolean,
    wordByWord: Boolean,
showTranslation: Boolean,
highlightColor: Color,
dimColor: Color,
fontSize: TextUnit,
    onSeek: () -> Unit,
    /**
     * v1.12.1：把本行的真实高度（像素）报给父级。
     * 仅当前行会传非null —— 对齐目标永远是当前行，量其他行没有意义。
     */
    onHeightChange: ((Int) -> Unit)? = null,
) {
// v1.8.3：当前行字号更大。
//
// 原来用的是 graphicsLayer 的 scale 缩放（1.0 → 1.1）。那有个隐患：
// **缩放不改变布局尺寸**，所以放大后的行仍然按原高度占位，
// 相邻行的间距不会跟着变，看起来像"字变大了但行距没变"，比例失衡。
// 改字号则会让 LazyColumn 真实地重新测量这一行，间距自然跟着走。
//
// 1.18倍是个取舍：再大（1.25+）长句容易折行，反而破坏歌词的整行感。
val isLarge = isActive
val lineFontSize = if (isLarge) fontSize * 1.18f else fontSize

var alpha by remember { mutableFloatStateOf(if (isActive) 1f else 0.45f) }

LaunchedEffect(isActive) {
    animate(
        initialValue = alpha,
        targetValue = if (isActive) 1f else 0.45f,
        animationSpec = tween(durationMillis = 500),
    ) { v, _ -> alpha = v }
}

val baseColor = if (isActive) highlightColor else dimColor
val lineHeight = (lineFontSize.value * 1.30f).sp
val karaoke = wordByWord && isActive && line.words.isNotEmpty()

// v1.8.3：逐字动画的独立时钟。
//
// 只有当前行会启动它（active 条件），暂停时协程整体挂起、不空转帧回调。
// 逐帧的推进值只被 [LyricText] 在绘制阶段读取。
//
// v1.12.1：这里传的是**读取器本身**（下面形参 positionMs 是 () -> Long），
// 不是调用结果 —— 时钟要能在协程里现读真实位置做周期性校准，
// 而且它绝不能进 produceState 的 key（每秒变 10~20 次，进 key 就等于
// 每秒重启时钟 10~20 次，逐字动画会直接卡住）。
val smoothPosition by rememberKaraokeClock(
    active = karaoke,
    positionMs = positionMs,
    playing = playing,
    // v1.12.1：换行/换歌时重置进度基准。
    //
    // 为什么必须显式给：时钟的 key 里刻意不含 positionMs（它每秒变 10~20 次，
    // 放进去会让时钟每秒重启 10~20 次，逐字动画直接卡住）。
    // 但换歌确实要把进度归零 —— 用 timeMs 当"这首歌的这一行"的标识即可，
    // 它只在真的换到另一行时才变。
    resetKey = line.timeMs,
)

Column(
    modifier = Modifier
        .fillMaxWidth()
        .clickable { onSeek() }
        .graphicsLayer { this.alpha = alpha }
        .padding(start = 4.dp, top = 10.dp, end = 20.dp, bottom = 10.dp)
        // v1.12.1：测量真实高度。
        //
        // 必须放在 padding **之后**才能量到含上下 padding 的整行高度 ——
        // padding 之前的 Modifier 链量到的是内容高度，会少掉 20dp。
        // onSizeChanged 只在高度**变化**时回调，不会每帧触发。
        .then(
            if (onHeightChange != null) {
                Modifier.onSizeChanged { onHeightChange(it.height) }
            } else {
                Modifier
            }
        ),
) {
    if (karaoke) {
        LyricText(
            words = line.words,
            plainText = line.text,
            // v1.12.1：这里传的是**平滑后的时钟值**（State<Long>），不是那个
            // 每秒变 10~20 次的原始位置流。传值即命中 LyricText 的 `Long` 重载，
            // 它会包成 `{ smoothPosition }` 交给 lambda 版。
            //
            // 顺带说明：这一行的重组是**有意保留**的 —— 逐字染色本来就是
            // 「当前行每帧重算一次」，而且只有当前行会走到这里，
            // 其余行走下面的 Text 分支，参数恒定、Compose 直接跳过。
            positionMs = smoothPosition,
            wordByWord = true,
            highlightColor = highlightColor,
            dimColor = highlightColor.copy(alpha = 0.45f),
            fontSize = lineFontSize,
            fontWeight = FontWeight.Bold,
            lineHeight = lineHeight,
            textAlign = TextAlign.Start,
        )
    } else {
        Text(
            text = line.text,
            fontSize = lineFontSize,
            fontWeight = FontWeight.Bold,
            color = baseColor.copy(alpha = if (isActive) 1f else 0.45f),
            lineHeight = lineHeight,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (isActive && showTranslation && !line.translation.isNullOrBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = line.translation!!,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            color = baseColor.copy(alpha = 0.72f),
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
}

@Composable
private fun EmptyHint(
listenerOk: Boolean,
status: LyricEngine.Status,
plainText: String?,
instrumental: Boolean,
color: Color,
modifier: Modifier = Modifier,
) {
val text = when {
    !listenerOk -> "先授予「通知读取」权限，才能抓取播放信息"
    status == LyricEngine.Status.LOADING -> "正在获取歌词…"
    status == LyricEngine.Status.NOT_FOUND -> "没找到歌词，去「歌词源」页可手动排查"
    status == LyricEngine.Status.ERROR -> "取词出错，去「歌词源」页看回退日志"
    instrumental -> "纯音乐，请欣赏"
    !plainText.isNullOrBlank() -> plainText
    else -> "打开播放器放一首歌试试"
}
Box(modifier, contentAlignment = Alignment.Center) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        color = color.copy(alpha = 0.65f),
        fontSize = 15.sp,
        lineHeight = 24.sp,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}
}

/* ------------------------------ 顶部信息 ------------------------------ */

/**
 * v1.12.9：歌曲信息行右上角的操作位 —— 「重取」或「开权限」。
 *
 * 这两个动作原本在 38dp 顶栏里（顶栏还有居中的「正在播放」小字和滑块设置入口）。
 * 顶栏移除后搬到这里，位置就是原设置入口所在的右上角。
 *
 * ## 为什么不用 TextButton
 *
 * 要让它与左侧歌名**同线**（它排在右侧竖列的第一位，列高由它和精简按钮撑起，
 * 左列随之垂直居中，于是歌名自然与它对齐），
 * 而 TextButton 在 Material3 下有 40dp 的默认最小高度，
 * 文字中心落在 20dp 处，比歌名行中心（约 12dp）低 8dp，肉眼能看出错位。
 *
 * 压到 32dp 后居中点在 16dp，与歌名只差 4dp。宽度交给内容决定
 * （`padding(horizontal = 8.dp)`），不会把「开权限」三个字挤断。
 */
@Composable
private fun RightTopAction(
    listenerOk: Boolean,
    hasTrack: Boolean,
    color: Color,
    onRefresh: () -> Unit,
    onOpenListenerSettings: () -> Unit,
) {
    val label: String
    val action: () -> Unit
    when {
        // 权限没给时优先引导开权限——这时"重取"没有意义（根本没有播放信息可查）
        !listenerOk -> {
            label = "开权限"
            action = onOpenListenerSettings
        }
        hasTrack -> {
            label = "重取"
            action = onRefresh
        }
        // 没权限问题也没在播：这个位置留空，不要摆一个点了没反应的按钮
        else -> return
    }
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = action)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            // 开权限是"需要用户动手"的引导，给足不透明度；重取是次要动作，压暗
            color = color.copy(alpha = if (listenerOk) 0.55f else 1f),
        )
    }
}

/**
 * 精简模式切换按钮。
 *
 * v1.8.0：以前这里是一行「轻触封面 → 只看歌词」的小字提示 + 一个
 * 「显示全部 ✕」文字按钮。两者不对称，而且指令式文案很生硬——
 * 用户看到封面就该知道它可点，不需要被告知。
 * 现在做成一个 32dp 的方块按钮（`expand=false` 为折叠成方块，`true` 为展开成横条），
 * 两种模式下的位置、尺寸、颜色完全对称。
 */
@Composable
private fun MinimalToggle(
expand: Boolean,
color: Color,
onClick: () -> Unit,
) {
Box(
    modifier = Modifier
        .then(if (expand) Modifier.width(64.dp).height(28.dp) else Modifier.size(32.dp))
        .clip(RoundedCornerShape(10.dp))
        .background(Color.Black.copy(alpha = 0.22f))
        .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) {
    Icon(
        painter = painterResource(if (expand) R.drawable.ic_expand else R.drawable.ic_collapse),
        contentDescription = if (expand) "显示全部" else "只看歌词",
        tint = color.copy(alpha = 0.62f),
        modifier = Modifier.size(16.dp),
    )
}
}

/**
 * 权限提示卡。
 *
 * v1.8.0：原先用 `Color.White.copy(alpha=0.1f)` 当底 +纯白文字，
 * 在流体背景上会糊成一块脏灰。这里改成「黑色半透明 + 细描边」的
 * 玻璃片，所有文字走歌词主题色（[lyricColor]），与整页保持同一套配色。
 */
@Composable
private fun PermissionCard(
listenerOk: Boolean,
overlayOk: Boolean,
notifyOk: Boolean,
lyricColor: Color,
onGrantNotifications: () -> Unit,
onOpenListenerSettings: () -> Unit,
onOpenOverlaySettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Color.Black.copy(alpha = 0.34f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "还需要以下权限",
            color = lyricColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(2.dp))
        if (!listenerOk) PermissionRow("通知读取（抓取播放信息必需）", lyricColor, onOpenListenerSettings)
        if (!overlayOk) PermissionRow("悬浮窗（桌面歌词）", lyricColor, onOpenOverlaySettings)
        if (!notifyOk) PermissionRow("发送通知（通知栏歌词）", lyricColor, onGrantNotifications)
    }
}

@Composable
private fun PermissionRow(text: String, lyricColor: Color, onClick: () -> Unit) {
Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(9.dp))
        .clickable(onClick = onClick)
        .padding(vertical = 6.dp),
) {
    Text(
        text,
        Modifier.weight(1f),
        fontSize = 12.sp,
        color = lyricColor.copy(alpha = 0.82f),
    )
    Text(
        "去开启 ›",
        fontSize = 11.sp,
        color = lyricColor.copy(alpha = 0.62f),
    )
}
}

/**
 * 已授予通知读取权限，但MediaSession 抓取链路仍未建立。
 * 「重试」先就地重建链路（多数情况这一步就能恢复，无需跳系统设置）；
 * 1.5s 后仍未成功，才提示去系统设置重新授权。
 */
@Composable
private fun RebindHint(
lyricColor: Color,
onRetry: () -> Unit,
onOpenListenerSettings: () -> Unit,
) {
var waited by remember { mutableStateOf(false) }
LaunchedEffect(Unit) { delay(1500); waited = true }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Color.Black.copy(alpha = 0.34f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (waited) "播放检测未就绪：先点「重试」，无效再去系统设置重新开启「通知读取」"
            else "正在恢复播放检测…",
            color = lyricColor,
            fontSize = 13.sp,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (waited) {
                Text(
                    "去设置 ›",
                    fontSize = 11.sp,
                    color = lyricColor.copy(alpha = 0.62f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(onClick = onOpenListenerSettings)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            Text(
                "重试",
                fontSize = 11.sp,
                color = lyricColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

private fun appLabel(pkg: String): String = when (pkg) {
"com.spotify.music" -> "Spotify"
"com.apple.android.music" -> "Apple Music"
"com.google.android.apps.youtube.music" -> "YouTube Music"
"com.netease.cloudmusic" -> "网易云"
"com.tencent.qqmusic" -> "QQ音乐"
"com.kugou.android", "com.kugou.android.lite" -> "酷狗"
"cn.kuwo.player" -> "酷我"
else -> pkg.substringAfterLast('.')
}

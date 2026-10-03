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
    onOpenLyricPageSettings: () -> Unit,
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
    val lyricPosition by LyricEngine.lyricPositionMs.collectAsState()
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

        // v1.8.3：精简模式下**整条顶部栏都不渲染**。
        //
        // 用户圈出的就是「正在播放 / 重取 / 滑块设置」这三个东西。
        // 之前只藏了权限卡片、保留了这条栏，是当时漏了——
        // 极简模式的判断标准应该是"屏幕上还剩什么"，
        // 留一条带三个可点元素的顶栏在"只留歌词"里显然不成立。
        if (!minimalRaw) {
            NowPlayingTopBar(
                title = state.track?.title,
                listenerOk = listenerOk,
                lyricColor = lyricColor,
                onRefresh = { LyricEngine.refresh(force = true) },
                onOpenListenerSettings = onOpenListenerSettings,
                onOpenSettings = onOpenLyricPageSettings,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (!minimal) {
            // 顶部横向条：小封面 + 歌名/歌手，参考图那种「专辑图只占一小部分」的布局
            Row(
                verticalAlignment = Alignment.CenterVertically,
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
                // 与精简模式里的「显示全部」互为镜像的入口
                MinimalToggle(expand = false, color = lyricColor) { minimal = true }
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
positionMs: Long,
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
        .drawWithContent {
            drawContent()
            // DstIn：目标 alpha × 源 alpha → 两端渐隐到透明。
            // 上下各留了半屏空白，渐隐区间必须跟着外推，
            // 否则渐隐带正好压在当前行上，把居中那行给淡化了。
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.30f to Color.Black,
                    0.70f to Color.Black,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
) {
    val halfViewport = maxHeight / 2

    // 当前行 item 高度之半 = (上下 padding 合计 20dp + 行高) / 2
    //
    // 必须做 sp → dp → px 两级换算：行高按 sp 定义（随系统字号缩放），
    // 而 scrollToItem 的 offset 要物理像素。直接把 sp 当 dp 用，
    // 在 3x 屏上会差出好几倍。toDp/toPx 是 Density 的接口成员，
    // 只能在 Density 作用域内调用（没有对应的顶层扩展可 import）。
    //
    // 上下那 20dp 见 LINE_PADDING_TOTAL_DP，是 AppleLyricLine 里 Column 的 padding(top/bottom 各 10dp)，
    // **必须算进来** —— 漏掉它会让当前行偏低 10dp。
    val density = LocalDensity.current
    val activeHalfLinePx = with(density) {
        val padPx = LINE_PADDING_TOTAL_DP.dp.toPx()
        val linePx = (fontSize.value * ACTIVE_FONT_SCALE * LINE_HEIGHT_RATIO).sp.toPx()
        (padPx + linePx) / 2f
    }

    // 见上方推导：contentPadding.top = halfViewport 时，offset 就等于半行高。
    // 唯一的量，contentPadding 和 offset 各管一件事。
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
    LaunchedEffect(index, lines.size, suppressUntil, alignOffsetPx) {
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
            top = halfViewport,
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
                // 传常量后，非当前行所有参数稳定，Compose 直接跳过重组，
                // 每帧的重组范围从「整屏行数」降到「1 行」。
                positionMs = if (active) positionMs else 0L,
                playing = playing,
                wordByWord = wordByWord,
                showTranslation = showTranslation,
                highlightColor = highlight,
                dimColor = dim,
                fontSize = fontSize,
                onSeek = { onSeekTo(line.timeMs) },
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
positionMs: Long,
playing: Boolean,
wordByWord: Boolean,
showTranslation: Boolean,
highlightColor: Color,
dimColor: Color,
fontSize: TextUnit,
onSeek: () -> Unit,
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
// 只有当前行会启动它（active 条件），且播放暂停时直接返回常量 0，
// 不产生任何帧回调。逐帧的推进值只被 [LyricText] 在绘制阶段读取。
val smoothPosition by rememberKaraokeClock(
    active = karaoke,
    positionMs = positionMs,
    playing = playing,
)

Column(
    modifier = Modifier
        .fillMaxWidth()
        .clickable { onSeek() }
        .graphicsLayer { this.alpha = alpha }
        .padding(start = 4.dp, top = 10.dp, end = 20.dp, bottom = 10.dp),
) {
    if (karaoke) {
        LyricText(
            words = line.words,
            plainText = line.text,
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
 * 顶部超薄操作栏：居中的「正在播放」小字 caption（Apple Music 招牌元素），
 * 右侧收纳「重取」与设置入口。
 *
 * v1.8.0：右侧加了 [R.drawable.ic_tune] 滑块按钮——歌词页的颜色/字号/逐字/背景
 * 全在设置页的二级页里，那是唯一能调它们的地方，所以在歌词页角落给一个直达入口，
 * 省得用户为了改个字号还要先去底栏「设置」再找「歌词页」。
 *
 * 所有文字/图标颜色都从 [lyricColor] 派生（用户在设置里自选），
 * 不再硬编码 Color.White——否则用户把歌词调成暖色时顶栏还是白的，看着像两套主题。
 */
@Composable
private fun NowPlayingTopBar(
title: String?,
listenerOk: Boolean,
lyricColor: Color,
onRefresh: () -> Unit,
onOpenListenerSettings: () -> Unit,
onOpenSettings: () -> Unit,
) {
Box(Modifier.fillMaxWidth().height(38.dp)) {
    Text(
        text = "正在播放",
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 3.sp,
        color = lyricColor.copy(alpha = 0.5f),
        modifier = Modifier.align(Alignment.Center),
    )
    Row(
        modifier = Modifier.align(Alignment.CenterEnd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (!listenerOk) {
            TextButton(onClick = onOpenListenerSettings, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("开权限", fontSize = 11.sp, color = lyricColor)
            }
        } else if (title != null) {
            TextButton(onClick = onRefresh, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("重取", fontSize = 11.sp, color = lyricColor.copy(alpha = 0.55f))
            }
        }
        // 歌词页设置直达入口
        //
        // 只做小按钮，不玻璃化整条顶栏——「正在播放」那行字是浮在
        // 流体背景上的通透文字，给它加壳会在顶部压出一条 38dp 高的
        // 横带，反而挡住背景。
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_tune),
                contentDescription = "歌词页设置",
                tint = lyricColor.copy(alpha = 0.62f),
                modifier = Modifier.size(17.dp),
            )
        }
    }
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

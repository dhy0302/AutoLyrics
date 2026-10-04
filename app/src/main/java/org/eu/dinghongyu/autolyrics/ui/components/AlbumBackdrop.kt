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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import org.eu.dinghongyu.autolyrics.R
import org.eu.dinghongyu.autolyrics.util.BitmapBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/** 专辑封面相关的取图、取色与背景。 */
object AlbumArt {

    /**
     * 从 URI 加载封面。
     * 部分播放器（含某些版本的Spotify）只在 MediaMetadata 里给 `ART_URI` 而不给 Bitmap，
     * 这时需要用 Coil 拉一次。`allowHardware(false)` 是必须的：
     * 硬件位图不能被 Palette 读取，也不能做像素级模糊。
     *
     * v1.8.2 加了 `maxSize`：直接取全尺寸图是内存浪费——
     * 最大的用处（背景模糊）只用到 64px，小卡片也只有 56dp。
     * 限到 320px 后，1000×1000 的封面从 4MB 降到 410KB。
     *
     * ## v1.18.1 加`fresh` 参数 —— 观察占位图时必须置 true
     *
     * 音乐 App 在专辑图就绪前会先返回一张「唱片占位图」（用户实测截图确认）。
     * 那是一次**成功**的加载，Coil 会把它正常缓存 —— 于是后面即使
     * 音乐 App 换成真图，同一个 URI 也会直接命中缓存里的占位图。
     *
     * v1.18.0 的注释写「Coil 失败不缓存，所以不需要动缓存策略」，
     * 那只对 null 成立，**对占位图不成立**。
     *
     * `fresh = true` 时连内存缓存一起跳过，逼 Coil 真的重新去问一次
     * ContentProvider。只有观察阶段用；正常首次加载仍走缓存，
     * 免得同一首歌反复切进切出时重复读盘。
     */
    suspend fun fromUri(context: Context, uri: String, fresh: Boolean = false): Bitmap? =
        withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(uri)
                .allowHardware(false)
                .size(COVER_MAX_PX)
                .apply {
                    if (fresh) memoryCachePolicy(CachePolicy.DISABLED)
                }
                .build()
            // 复用 Coil 的全局单例，不要每次新建 ImageLoader
            val drawable = context.imageLoader.execute(request).drawable
            (drawable as? BitmapDrawable)?.bitmap
        }.getOrNull()
    }

    /**
     * 把 MediaSession 直接给的封面缩到合理尺寸。
     *
     * v1.8.2 新增。MediaSession 的 `METADATA_KEY_ALBUM_ART` 给的是**原始全尺寸**
     * 位图（Spotify 常见 1000×1000 甚至更高），一次 ARGB_8888 就是 4MB+，
     * 而且这份位图会被 StateFlow 长期持有 —— 切歌不释放就一路涨。
     * 但 App 实际最大用途只有 56dp 的封面卡与背景，320px 绰绰有余。
     *
     * 这里**不 recycle 原图**：调用方（MediaSession）可能仍持有它，
     * 且降采样已经把单张从 4MB 压到约 410KB，交给 GC 即可。
     */
    fun downsample(src: Bitmap, maxSize: Int = COVER_MAX_PX): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSize) return src
        val ratio = maxSize.toFloat() / longest
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return runCatching {
            Bitmap.createScaledBitmap(src, w, h, true)
        }.getOrDefault(src)
    }

    private const val COVER_MAX_PX = 320
}

/**
 * 统一封面来源：优先 MediaSession直接给的 Bitmap，没有再用 URI 加载。
 *
 * ## v1.18.1：占位图会被后续上报的真图顶掉
 *
 * ### v1.18.0 修错了什么
 *
 * v1.18.0 以为是「取图失败 → null」，于是加了退避重试。**方向就错了**：
 * 用户截到的图证明我们**成功读到了一张 Bitmap**，只是那张图是音乐 App
 * 自己画的「唱片占位图」。于是
 *
 * ```
 * if (bmp != null) { loaded = bmp; return@LaunchedEffect }
 * ```
 *
 * 第一次就判定成功、协程立刻结束，**后面三次重试根本没机会跑**。
 * 这就是「加了重试却完全没用」的原因。
 *
 * 顺带两个 v1.18.0 自身的 bug：
 * 1. `COVER_RETRY_DELAYS_MS = [500, 2000, 5000]` 配 `if (i == lastIndex) break`，
 *    实际尝试时刻是 0s / 0.5s / 2.5s —— **最后一次等待被 break 掉了**，
 *    总覆盖只有 2.5 秒，与注释里写的「约 7.5 秒」不符。
 * 2. 占位图是一次**成功**的加载，Coil 会正常缓存它。
 *    v1.18.0 注释里「Coil 失败不缓存」只对 null 成立，对占位图不成立。
 *
 * ### 这版怎么修：不猜图像，观察「封面后来变了」
 *
 * 播放器补上真实专辑图时，`albumArt` 会从占位图变成**另一张不同的图**。
 * 所以只要取到图之后**继续观察一段时间**，发现同一首歌的封面变了就换过去。
 *
 * **为什么不用图像特征判别占位图**（走过弯路，别再走）：
 * 量过那张占位图 —— 平均饱和度 0.000、100% 纯灰阶，看着很好判别。
 * 但本项目自己的 logo 平均饱和度只有 0.048、93.8% 像素低饱和，
 * **会被误判成占位图**；而占位图的边缘能量反而比真实封面更高，
 * 与直觉相反。黑胶类真实封面会被这套判据杀掉。
 *
 * 观察法不依赖图像内容，只依赖「同一首歌封面变了」这个事实，
 * 因此对任何封面（含纯色、单色、小尺寸）都安全。
 *
 * ### 代价
 *
 * 取到图之后还留一个观察协程，20 秒内每 2 秒比一次。
 * 只在歌词页可见期间存在，切歌/ 离页立即取消，代价可忽略。
 */
@Composable
fun rememberAlbumCover(bitmap: Bitmap?, uri: String?): Bitmap? {
    val context = LocalContext.current
    var loaded: Bitmap? by remember(uri) { mutableStateOf(null) }

    // 观察窗口的起点 uri。`uri` 变了（切歌）时 remember 会重置它，
    // 于是观察协程自然作废，不会拿上一首的图去比下一首。
    LaunchedEffect(uri) {
        loaded = null
        if (uri.isNullOrBlank()) return@LaunchedEffect

        // ---- 阶段一：取到第一张图为止 ----
        //
        // 注意这里**不以「取到图」为终点**，那正是 v1.18.0 的错误。
        // 取到第一张就跳出重试循环，进入阶段二继续观察。
        var first: Bitmap? = null
        for (delayMs in COVER_RETRY_DELAYS_MS) {
            val bmp = AlbumArt.fromUri(context, uri)
            if (bmp != null) {
                first = bmp
                break
            }
            delay(delayMs)
        }
        if (first == null) return@LaunchedEffect
        loaded = first

        // ---- 阶段二：观察「封面后来变了」----
        //
        // 比对不能直接用 `bmp == current`：Bitmap 的 equals() 在
        // Android 上是**逐像素**比较，320px 图每次比要走 10 万像素，
        // 20 秒内 10 次就是 100 万次像素读 —— 太浪费。
        //
        // 这里比的是**内容指纹**（宽高 + 采样点的 RGB），
        // 同样的图必然算出同样的指纹，不同的图几乎不可能撞上。
        // 采样而不是全图哈希，是为了避开逐像素开销。
        var seen = contentKey(first)
        var waited = 0L
        while (waited < COVER_WATCH_WINDOW_MS) {
            delay(COVER_WATCH_INTERVAL_MS)
            waited += COVER_WATCH_INTERVAL_MS
            // 切歌后 uri 会变，这个协程随之被取消；这里再确认一次
            // 是为了防住 uri 恰好又变回同一个值的情况。
            //
            // fresh = true：占位图被 Coil 缓存过，不跳过缓存就永远
            // 读到同一张占位图，观察就白做了。
            val again = AlbumArt.fromUri(context, uri, fresh = true) ?: continue
            val key = contentKey(again)
            if (key != seen) {
                // 真的换成别的图了 —— 大概率是占位图被真图顶掉
                loaded = again
                seen = key
            }
        }
    }

    return bitmap ?: loaded
}

/**
 * v1.18.1：封面的内容指纹，用于判断「是不是同一张图」。
 *
 * 为什么不直接比Bitmap 的 `equals()`：Android 上 `Bitmap.equals()`
 * 是逐像素比较，320×320 的图一次要走 102400 个像素。
 * 观察窗口里要比十几次，负担不必要地大。
 *
 * 取「宽高 + 9 个采样点的 RGB」：同样的图必然得到同样的值，
 * 不同的图要撞上需要九个点同时巧合，概率极低。
 * 采样点取等距分布（中心 + 四边中点 + 四角附近），
 * 对「占位图 → 真图」这种整体替换足够敏感。
 */
private fun contentKey(bmp: Bitmap): Long {
    val w = bmp.width
    val h = bmp.height
    // FNV-1a，溢出是有意为之（Long 环绕运算）
    var acc = 1469598103934665603L          // FNV offset basis
    fun mix(v: Long) {
        acc = acc xor v
        acc *= 1099511628211L
    }
    mix(w.toLong())
    mix(h.toLong())

    // 5×5 均匀采样。取 25 点而不是 9 点：万一碰到大面积纯色的封面，
    // 采样点太少可能全落在同一处颜色上，漏掉「换图」这个事实。
    // 25 次 getPixel 依然很便宜（对比逐像素的 102400 次）。
    for (i in 0 until 5) {
        val yy = ((h - 1) * i / 4).coerceIn(0, h - 1)
        for (j in 0 until 5) {
            val xx = ((w - 1) * j / 4).coerceIn(0, w - 1)
            // ARGB 打包进Long。**每一步都显式加括号** ——
            // Kotlin 里 shl/or 同为中缀函数且同级，靠优先级推断很容易被后人改错。
            val c = bmp.getPixel(xx, yy)
            val alpha = ((c shr 24) and 0xFF).toLong()
            val red = ((c shr 16) and 0xFF).toLong()
            val green = ((c shr 8) and 0xFF).toLong()
            val blue = (c and 0xFF).toLong()
            mix(alpha)
            mix(red)
            mix(green)
            mix(blue)
        }
    }
    return acc
}

/**
 * v1.18.1：取图阶段的退避序列（毫秒）。
 *
 * **改法说明**：v1.18.0 写的是 `if (i == lastIndex) break`，
 * 导致最后一个等待根本没用上，实际只覆盖 2.5 秒。
 * 现在**直接遍历数组本身**（每个值都是「失败后等多久」），
 * 语义就是「失败就等这些时间」，不存在「最后一个被跳过」的问题。
 * 改数组长度就改了重试次数，不要另设次数常量。
 */
private val COVER_RETRY_DELAYS_MS = longArrayOf(300, 800, 1_500, 3_000, 5_000)

/**
 * v1.18.1：取到第一张图之后的观察窗口。
 *
 * 取到图不代表取对了 —— 可能只是占位图。播放器补上真图时
 * `albumArt` 会变成另一张图，在窗口内持续比对即可发现。
 *
 * 20 秒的依据：实测音乐 App 在切歌后一两秒就把专辑图写好了，
 * 20 秒是很宽裕的余量。到点就停，不长期占后台。
 */
private const val COVER_WATCH_WINDOW_MS = 20_000L

/** v1.18.1：观察窗口内的比对间隔。 */
private const val COVER_WATCH_INTERVAL_MS = 2_000L

/**
 * 封面主色（单个代表色），供歌词高亮与进度条使用。
 *
 * 与 [rememberAlbumColors] 共用同一份 Palette 结果 —— 见 [rememberAlbumPalette]。
 */
@Composable
fun rememberAlbumAccent(cover: Bitmap?, fallback: Color): Color {
    val palette = rememberAlbumPalette(cover, fallback)
    return palette.accent
}

/** 从专辑封面提取的流体渐变背景用色（双主色 + 衍生色）。 */
data class AlbumColors(
    val primary: Color,
    val secondary: Color,
    /** 主色相偏移 40° 得到的第三色，供着色器做第三色域 */
    val tertiary: Color,
)

/**
 * **v1.8.3：把取到的三色钉进受控区间，再交给着色器。**
 *
 * ## 为什么必须多这一步
 * 着色器里已经做了亮度收敛（压到 [0.13, 0.32]）和"不出现纯色块"的混色设计，
 * 但那是在**三色已经混完之后**才起作用的。如果三色本身天差地别
 * —— 比如 `lightMutedSwatch` 是个接近白的亮灰蓝 ——
 * 那么它即便被压暗，也仍然是一块"发脏的浅色"，和旁边的深蓝一对比
 * 依旧刺眼。v1.8.2 的白块问题正是这么来的：
 * 用户录屏里能看到深蓝底上糊着一片 (127,146,154) 的灰蓝亮斑。
 *
 * 所以约束要**提前到取色那一刻**：
 * 1. 饱和度封顶 —— 超过 [maxSat] 就往灰色混，
 *    避免暖色封面被推成纯橙红直接抢歌词的注意力；
 * 2. 亮度归一 —— 三色按**彼此的相对亮度**线性映射到 [lo, hi] 这条窄带，
 *    谁都不许独占高亮或独占暗部。
 *
 * 参数是离线用 numpy 复刻同一套着色器算法实测出来的
 * （见 `tools/shader_lab.py`），不是拍脑袋定的。
 */
fun AlbumColors.forShader(): AlbumColors = AlbumColors(
    primary = normalizeForShader(primary),
    secondary = normalizeForShader(secondary),
    tertiary = normalizeForShader(tertiary),
)

/** 亮度相对提升到这个区间（对比度靠"深底"而不是"亮块"）。 */
private const val SHADER_LUMA_LO = 0.24f
private const val SHADER_LUMA_HI = 0.46f
/** 饱和度上限。超过就往灰色混。 */
private const val SHADER_MAX_SAT = 0.30f

private fun normalizeForShader(color: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgbInt(), hsv)
    hsv[1] = hsv[1].coerceIn(0f, SHADER_MAX_SAT)
    hsv[2] = hsv[2].coerceIn(SHADER_LUMA_LO, SHADER_LUMA_HI)
    return Color(android.graphics.Color.HSVToColor(hsv))
}


/** 一次性从封面取到的全部颜色信息。 */
private data class AlbumPalette(
    val accent: Color,
    val colors: AlbumColors,
)

/**
 * 从封面提取颜色，**整页只跑一次 Palette**。
 *
 * ## v1.8.2 的性能修正
 * 旧版有 `rememberAlbumAccent` 与 `rememberAlbumColors` 两个独立的 Composable，
 * 各自 `Palette.from(cover).generate()` 跑一遍。Palette 要遍历所有像素做量化，
 * 对 1000×1000 的封面单次就要几十毫秒 —— **同一张封面的同一份结果被算了两次**，
 * 纯浪费。现在合并为 [rememberAlbumPalette]，一次生成、三处复用
 * （accent / primary / secondary / tertiary）。
 *
 * 防闪烁要点（沿用旧版实测结论）：颜色状态**不能**以 cover 为 remember 的 key。
 * 封面实例一变（会话切换、元数据重推都会发生），旧写法会先把颜色重置成fallback
 * 渲染一帧，下一帧 Palette 算完才恢复，肉眼看就是整页"一闪一闪"。
 */
@Composable
private fun rememberAlbumPalette(cover: Bitmap?, fallback: Color): AlbumPalette {
    var result by remember { mutableStateOf(AlbumPalette(fallback, AlbumColors(fallback, fallback, fallback))) }

    LaunchedEffect(cover) {
        if (cover == null) {
            result = AlbumPalette(fallback, AlbumColors(fallback, fallback, fallback))
            return@LaunchedEffect
        }
        val computed = withContext(Dispatchers.IO) {
            runCatching {
                val palette = androidx.palette.graphics.Palette.from(cover).generate()
                val primarySwatch = palette.vibrantSwatch
                    ?: palette.lightVibrantSwatch
                    ?: palette.darkVibrantSwatch
                    ?: palette.dominantSwatch
                val secondarySwatch = palette.lightMutedSwatch
                    ?: palette.mutedSwatch
                    ?: palette.darkMutedSwatch
                    ?: primarySwatch
                val accentSwatch = primarySwatch ?: palette.dominantSwatch

                val p = primarySwatch?.rgb?.let { saturate(Color(it)) } ?: fallback
                val s = secondarySwatch?.rgb?.let { saturate(Color(it), boost = 1.1f) } ?: p
                AlbumPalette(
                    accent = accentSwatch?.rgb?.let { Color(it) } ?: fallback,
                    colors = AlbumColors(p, s, shiftHue(p, 40f)),
                )
            }.getOrNull()
        }
        // computed 为 null（取色失败）时保留旧色，避免闪回兜底色
        if (computed != null) result = computed
    }

    // 切歌时颜色平滑过渡而不是硬切
    val accent by animateColorAsState(result.accent, tween(350), label = "albumAccent")
    val primary by animateColorAsState(result.colors.primary, tween(600), label = "fluidPrimary")
    val secondary by animateColorAsState(result.colors.secondary, tween(600), label = "fluidSecondary")
    val tertiary by animateColorAsState(result.colors.tertiary, tween(600), label = "fluidTertiary")
    return AlbumPalette(accent, AlbumColors(primary, secondary, tertiary))
}

/**
 * 取封面的双主色，专供流动背景。
 *
 * 保留旧函数名与签名以免调用方改动，但它现在只是 [rememberAlbumPalette] 的薄包装。
 */
@Composable
fun rememberAlbumColors(cover: Bitmap?, fallback: Color): AlbumColors =
    rememberAlbumPalette(cover, fallback).colors

/** 提饱和、压明度：背景色块要鲜艳但不能晃眼。 */
private fun saturate(color: Color, boost: Float = 1.3f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgbInt(), hsv)
    hsv[1] = (hsv[1] * boost).coerceIn(0f, 1f)
    hsv[2] = hsv[2].coerceIn(0.35f, 0.85f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** 色相偏移（度），给同一封面衍生出第二、第三种颜色。 */
private fun shiftHue(color: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgbInt(), hsv)
    hsv[0] = (hsv[0] + degrees + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)

/* ==================================================================== *
 *  流动背景
 * ==================================================================== */

/**
 * **真正在流动**的专辑背景。
 *
 * ## 两条实现路径
 * |系统版本 | 实现 | 观感 |
 * |---------|------|------|
 * | Android 13+ (API 33) | AGSL 域扭曲噪声着色器（`fluid_bg.agsl`） | 大面积色域持续翻涌|
 * | Android 8~12 | Canvas 径向渐变色块 | 4~6 个色块缓慢漂移 |
 *
 * ## 为什么要分两套
 * AGSL 依赖 `RuntimeShader`（API 33 引入），而本项目 minSdk 26。
 * 与其为了低版本放弃整个效果，不如给低版本一个「差一些但有」的降级版本。
 *
 * ## 性能取舍（v1.8.2 的重点）
 * 旧版是纯 Canvas：3 个 `animateFloat` 状态 + 每帧4 个大半径径向渐变。
 * **径向渐变是 CPU 逐像素算的**，1080×2400 的屏上每帧要算 260 万个像素 × 4 层。
 * AGSL 版把这部分全部交给 GPU，CPU 每帧只有一次 `setFloatUniform`，
 * 且不触发任何 Compose 重组。
 *
 * @param animationScale 动画时间缩放。暂停时传 0 可以完全静止（省电），
 *   过渡处用 [androidx.compose.animation.core.animate] 平滑降到 0，避免突然定格。
 */
@Composable
fun FluidBackdrop(
    cover: Bitmap?,
    colors: AlbumColors,
    modifier: Modifier = Modifier,
    animationScale: Float = 1f,
) {
    // v1.8.3：三色先归一化再进背景。着色器内部还有一层亮度收敛，
    // 但那是混色之后的事 —— 混色之前必须先把三色钉进同一区间。
    val shaderColors = colors.forShader()

    Box(modifier.fillMaxSize()) {
        // AGSL 路径：完全由着色器生成，不需要任何位图
        if (Build.VERSION.SDK_INT >= 33) {
            FluidShaderBackdrop(colors = shaderColors, animationScale = animationScale)
        } else {
            // 降级路径要靠模糊封面兜底，所以只在真的要走 Canvas 时才算 ——
            // 旧版两条路径都算，切歌时白做一次降采样 + 两趟模糊。
            val blurred = remember(cover) {
                cover?.let { BitmapBlur.blur(it, downSize = 64, radius = 4) }
            }
            FluidCanvasBackdrop(colors = shaderColors, blurred = blurred, animationScale = animationScale)
        }
    }
}

/**
 * 模糊封面底图。**仅 Canvas 降级路径使用** ——
 * AGSL 路径是全屏不透明的，铺在它下面等于白画（见 [FluidShaderBackdrop] 的说明）。
 */
@Composable
private fun BlurredCoverBase(blurred: Bitmap?, darken: Float, modifier: Modifier = Modifier) {
    if (blurred != null) {
        Image(
            bitmap = blurred.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize(),
        )
    } else {
        Box(
            modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF171B29), Color(0xFF0B0E18))))
        )
    }
    if (darken > 0f) {
        Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = darken)))
    }
}

/* ---------------------------- AGSL 路径 ---------------------------- */

/**
 * AGSL 梯度噪声背景（Android 13+）。
 *
 * 用 [androidx.compose.ui.graphics.ShaderBrush] 包装 `RuntimeShader`，
 * 交给 Compose 自己做缓存，动画推进只改 uniform 里的时间。
 *
 * ## v1.8.3：这里不再画模糊封面底图
 * v1.8.2 在着色器下面垫了一层模糊封面，注释说是"增加实物质感"。
 * 但着色器返回的是**不透明**颜色（alpha 恒为 1.0），后画的全屏 Canvas
 * 会把前一层的模糊底图一像素不剩地盖掉 —— 那层图层从来没被看见过，
 * 只是一次白做的降采样 + 两趟模糊（切歌时多花几毫秒）。
 * 要让底图透出来就得给着色器加 alpha，但半透明背景会露出后面的窗口内容，
 * 在这个全屏承载歌词的场景里不值得。直接删掉。
 */
@Composable
private fun FluidShaderBackdrop(
    colors: AlbumColors,
    animationScale: Float,
) {
    val context = LocalContext.current
    val shader = remember(context) {
        runCatching {
            android.graphics.RuntimeShader(
                context.resources.openRawResource(R.raw.fluid_bg).bufferedReader().use { it.readText() }
            )
        }.getOrNull()
    }

    // shader 创建失败（AGSL 语法在某些厂商 ROM 上解析不过）时退回 Canvas
    if (shader == null) {
        FluidCanvasBackdrop(colors = colors, blurred = null, animationScale = animationScale)
        return
    }

    // v1.17.0：**自己累加**的时间轴，不再直接读 infiniteTransition 的值。
    //
    // ## 为什么原来会「暂停后背景突然变掉」
    //
    // 原来写的是 `iTime = timeSec * animationScale`，而 `timeSec` 由
    // `rememberInfiniteTransition` 驱动 —— 它**完全不受 animationScale 影响**，
    // 暂停期间照样累加。于是：
    //
    //   播放 3 秒后暂停：iTime 从 3.0 变成 3.0 * 0 = 0，画面**倒回起点**
    //   暂停 30 秒     ：timeSec 从 3.0 跑到 33.0，画面仍显示 t=0
    //   恢复播放       ：animationScale 回到 1，iTime 突然 = 33.0
    //                   ⇒ 整个背景瞬移到 33 秒时的样子
    //
    // 用户说的「暂停后背景突然变掉」就是恢复播放那一刻的瞬移
    // （实测跳变 = 暂停时长，可达几十分钟）。
    //
    // 顺带一提：即使不瞬移，`iTime = 0` 本身也不对 ——
    // 暂停应该保留暂停前的画面，而不是倒回动画起点。
    //
    // ## 现在的做法
    //
    // 一条**只由自己推进**的时间轴：跑的时候按帧累加，冻结时原地不动。
    // 画面位置只由它决定，与全局时钟彻底解耦，
    // 所以恢复后从停住的地方接着走，跳变只剩一帧（实测 0.1s）。
    //
    // 为什么不用 `rememberInfiniteTransition`：它的动画由框架驱动，
    // 我们只能「读」它的值，没法让它在暂停时真的停住。
    // `InfiniteTransition.animateFloat` 也没有「暂停」这个概念。
    val running = animationScale > 0.01f
    val clock = rememberFluidClock(running)

    val brush = remember(shader, colors) {
        ShaderBrush(shader)
    }

    Canvas(Modifier.fillMaxSize()) {
        // 色彩 uniform：只在颜色变化时写
        shader.setFloatUniform("iColorA", colors.primary.red, colors.primary.green, colors.primary.blue)
        shader.setFloatUniform("iColorB", colors.secondary.red, colors.secondary.green, colors.secondary.blue)
        shader.setFloatUniform("iColorC", colors.tertiary.red, colors.tertiary.green, colors.tertiary.blue)
        // 分辨率：每帧都要写（旋转屏幕/尺寸变化）
        shader.setFloatUniform("iResolution", size.width, size.height)
        // 时间：唯一每帧变化的量。
        // v1.17.0：不再乘 animationScale，也不再读 transition ——
        // 冻结由 rememberFluidClock 自己停住（详见上方注释）。
        //
        // 注意 `clock.floatValue` 在**这里**（draw 块内）读，而不是组合期。
        // 组合期读会订阅这个 State，而它每帧都在变 ⇒ 每帧重组本函数，
        // 正好把v1.8.2 省下来的「CPU 每帧只写一次 uniform、不重组」又还回去。
        // draw 块只读不订阅，所以时间推进不触发任何重组。
        shader.setFloatUniform("iTime", clock.floatValue)
        // v1.13.10：动画开关。**这才是真正省电的那一行。**
        //
        // 以前只把 iTime 乘 0，画面静止了但GPU 仍在满速跑
        // 4 阶 fbm —— GPU 不会因为「输出恒定」就偷懒。
        // 现在把开关交给着色器，由它在 main() 开头短路，
        // 省掉整屏每帧的全部噪声指令。
        shader.setFloatUniform("uAnimating", if (running) 1f else 0f)

        drawRect(brush = brush, size = size)
    }
}

/**
 * v1.17.0：一条**只由自己推进**的动画时间轴，`running` 为 false 时原地冻结。
 *
 * ## 为什么不用 `rememberInfiniteTransition`
 *
 * 它的动画由框架按帧驱动，我们只能「读」当前值，**没法让它真的停下来**。
 * 而本项目的需求恰恰是「暂停时冻结」—— 之前用 `timeSec * animationScale`
 * 变通，结果冻结期timeSec 照跑，恢复时瞬移（见调用处注释）。
 *
 * ## 实现要点
 *
 * 用 `withFrameNanos` 而不是 `animateFloatAsState`：前者只在**组合期**
 * 读一次、且能自己判断要不要推进；后者即使目标值不变，
 * 动画驱动器仍在持续产帧。
 *
 * 冻结时**不进入** `withFrameNanos` 循环，于是连帧回调都不注册了
 * —— 这是省电的完整闭环：着色器短路（GPU） + 时间轴停摆（CPU 回调）。
 *
 * 累加而非直接取 `frameTime / 1e9`：后者的绝对值会随 App 启动时长增长，
 * 60 秒一个循环的着色器会在长时间运行后精度变差（float尾数不够）。
 * 从 0 开始自己累加、并在 [FLUID_PERIOD] 处回绕，与原来的
 * `infiniteRepeatable(Restart)` 行为一致。
 *
 * @return 当前时间（秒）。**必须返回 [MutableFloatState] 而不是 [State]**——
 *   调用方在 draw 块里用 `.floatValue` 读，那是个扩展属性，
 *   声明成 `State<Float>` 就编译不过（CI build56 实测）。
 */
@Composable
private fun rememberFluidClock(running: Boolean): MutableFloatState {
    val clock = remember { mutableFloatStateOf(0f) }
    // key 里带上 running：false→true 时协程重启，接着冻结前的值继续累加。
    // 不需要「记下暂停瞬间的值」—— 冻结期它本来就没动过。
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        // last 在协程的 while 里跨帧保持，只用于算相邻两帧的时间差。
        // 协程被取消（running 变 false）时整个作用域结束，不存在残留。
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val delta = (now - last) / 1_000_000_000f
                last = now
                val next = clock.floatValue + delta
                clock.floatValue = if (next >= FLUID_PERIOD) next - FLUID_PERIOD else next
            }
        }
    }
    return clock
}

/** 时间轴回绕周期（秒），与旧版 `infiniteRepeatable` 的 60 秒一致。 */
private const val FLUID_PERIOD = 60f

/**
 * 由「已流逝秒数」与周期算出该色块的相位（弧度）。
 *
 * 抽成函数是为了让三个色块共用同一条时间轴（见 [rememberFluidClock]），
 * 且周期一眼可辨。互质周期（9/12/16）保证整组画面约 144 秒才重复一次。
 */
private fun phase(elapsedSec: Float, periodSec: Float): Float =
    (2.0 * Math.PI).toFloat() * ((elapsedSec % periodSec) / periodSec)

/* --------------------------- Canvas 路径 --------------------------- */

/**
 * 低版本降级：用 6 个径向渐变色块沿各自轨迹漂移。
 *
 * 相比旧版的 4 个色块，这一版做了一处关键调整：
 * **周期从 26/34/41 秒压到 9/12/16 秒**。旧版的慢周期加上低透明度，
 * 在 3~5 秒的注视间隔内几乎观察不到变化，观感就是"静态渐变"——
 * 这也是用户反馈"背景是静态的"的直接原因。
 * 快一点 + 幅度大一点，流动感才立得住。
 */
@Composable
private fun FluidCanvasBackdrop(
    colors: AlbumColors,
    blurred: Bitmap?,
    animationScale: Float,
) {
    BlurredCoverBase(blurred = blurred, darken = 0.42f)

    // v1.17.0：与 shader 路径共用同一条自累加时钟（原因见那里的注释）。
    //
    // 三个相位由同一个「已流逝秒数」按各自周期取模得出：
    //   p1 = 2π * (elapsed % 9) / 9，依此类推。
    // 这样冻结时 elapsed 不动 ⇒ 三个色块全部停在原地，
    // 且恢复后接着走，不会像原来那样（transition 照跑）瞬移。
    //
    // 原来这里是三个 `transition.animateFloat`（9/12/16 秒互质周期），
    // 已删除 —— 它们由 infiniteTransition 驱动，冻结期照跑，正是瞬移的来源。
    //
    // `elapsed` 在下面的 draw 块里读，**不在这里读**：
    // 组合期读会订阅这个每帧变化的 State ⇒ 每帧重组本函数。
    val running = animationScale > 0.01f
    val clock = rememberFluidClock(running)

    Canvas(Modifier.fillMaxSize()) {
        // v1.13.10：冻结时直接不画，**连色块都不画**。
        //
        // 原来这里是 `val a = animationScale` 然后把 t1*t2*t3 乘 0 ——
        // 色块确实不动了，但每帧仍然要新建 6 个 Brush.radialGradient
        // （每个都是一次 shader 对象分配），而且底下的模糊封面也在重复绘制。
        //
        // 现在直接 return：DrawScope 什么都不画，Compose 会跳过这一帧的绘制，
        // 屏幕上保留的是最后一帧的画面 —— 视觉上就是"完全静止"，
        // 而 GPU 与 CPU 的开销归零。
        //
        // v1.17.0：这个 return 只是**省开销**，不是冻结的实现 ——
        // 真正的冻结是 rememberFluidClock 停摆（否则恢复播放会瞬移）。
        if (!running) return@Canvas

        // 在 draw 块内读，且此时 running 为真，直接用当前值算相位。
        val e = clock.floatValue
        val p1 = phase(e, 9f)
        val p2 = phase(e, 12f)
        val p3 = phase(e, 16f)

        fun blob(
            t: Float, cx: Float, cy: Float,
            ax: Float, ay: Float, r: Float,
            color: Color, alpha: Float,
        ) {
            val c = Offset(
                size.width * (cx + ax * cos(t)),
                size.height * (cy + ay * sin(t)),
            )
            val rad = size.minDimension * r
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
                    center = c,
                    radius = rad,
                ),
                radius = rad,
                center = c,
            )
        }

        blob(p1, 0.28f, 0.26f, 0.20f, 0.14f, 0.60f, colors.primary, 0.55f)
        blob(p2, 0.74f, 0.34f, 0.16f, 0.18f, 0.52f, colors.secondary, 0.48f)
        blob(p3, 0.56f, 0.78f, 0.20f, 0.12f, 0.58f, colors.tertiary, 0.42f)
        blob((p1 + 2.4f), 0.18f, 0.82f, 0.14f, 0.10f, 0.46f, colors.secondary, 0.36f)
        // 新增两块：让中央区域也有颜色在动，填上旧版中间偏空的观感
        blob((p2 + 1.1f), 0.50f, 0.50f, 0.24f, 0.16f, 0.52f, colors.primary, 0.30f)
        blob((p3 + 3.0f), 0.85f, 0.72f, 0.12f, 0.14f, 0.44f, colors.tertiary, 0.28f)
    }
}

/**
 * Apple Music 风格背景：静态磨砂专辑封面 + 暗色压暗 + 上下渐变。
 *
 * 保留它作为「流体渐变背景」开关关闭时的静态选项。
 */
@Composable
fun AlbumBackdrop(
    cover: Bitmap?,
    modifier: Modifier = Modifier,
) {
    // 64px 小图做两趟盒式模糊，再靠 Compose 放大插值得到柔和的大模糊
    val blurred = remember(cover) { cover?.let { BitmapBlur.blur(it, downSize = 64, radius = 4) } }

    Box(modifier.fillMaxSize()) {
        if (blurred != null) {
            Image(
                bitmap = blurred.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF16213E), Color(0xFF0F3460), Color(0xFF0B1020))
                        )
                    )
            )
        }

        // 整体压暗，保证亮色封面也不会晃眼、歌词可读
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        // 上下渐变：顶部接状态栏、底部接播放条，过渡更自然
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.55f),
                        Color.Transparent,
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.82f),
                    )
                )
            )
        )
    }
}

/* ==================================================================== *
 *  封面卡片
 * ==================================================================== */

/**
 * Apple Music 风格封面卡：清晰、圆角、带投影，**不做任何旋转/缩放动画**。
 *
 * v1.8.2：新增 [thumb] 参数。列表与调试页用小尺寸封面时传小图，
 * 避免为了画一个 40dp 的缩略图而持有整张 320px 位图。
 */
@Composable
fun AlbumArtCard(
    cover: Bitmap?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    thumbnailPx: Int = 0,
) {
    val bitmap = remember(cover, thumbnailPx) {
        if (cover == null || thumbnailPx <= 0) cover
        else AlbumArt.downsample(cover, thumbnailPx)
    }

    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        shadowElevation = 18.dp,
        color = Color(0xFF101010),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(cornerRadius))
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color(0xFF22304A), Color(0xFF101826)))
                    )
                )
            }
        }
    }
}

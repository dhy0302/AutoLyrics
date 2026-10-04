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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import coil.request.ImageRequest
import org.eu.dinghongyu.autolyrics.R
import org.eu.dinghongyu.autolyrics.util.BitmapBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/** 专辑封面相关的取图、取色与背景。 */
object AlbumArt {

    /**
     * 从 URI 加载封面。
     * 部分播放器（含某些版本的 Spotify）只在 MediaMetadata 里给 `ART_URI` 而不给 Bitmap，
     * 这时需要用 Coil 拉一次。`allowHardware(false)` 是必须的：
     * 硬件位图不能被 Palette 读取，也不能做像素级模糊。
     *
     * v1.8.2 加了 `maxSize`：直接取全尺寸图是内存浪费——
     * 最大的用处（背景模糊）只用到 64px，小卡片也只有 56dp。
     * 限到 320px 后，1000×1000 的封面从 4MB 降到 410KB。
     */
    suspend fun fromUri(context: Context, uri: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(uri)
                .allowHardware(false)
                .size(COVER_MAX_PX)
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
 * 统一封面来源：优先 MediaSession 直接给的 Bitmap，没有再用 URI 加载。
 */
@Composable
fun rememberAlbumCover(bitmap: Bitmap?, uri: String?): Bitmap? {
    val context = LocalContext.current
    var loaded: Bitmap? by remember(uri) { mutableStateOf(null) }

    LaunchedEffect(uri) {
        loaded = if (uri.isNullOrBlank()) null else AlbumArt.fromUri(context, uri)
    }

    return bitmap ?: loaded
}

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

    // 时间源：只在 draw阶段读取，不产生重组
    val transition = rememberInfiniteTransition(label = "fluidShader")
    val timeSec by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1_000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "fluidTime",
    )

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
        // 时间：唯一每帧变化的量
        shader.setFloatUniform("iTime", timeSec * animationScale)
        // v1.13.10：动画开关。**这才是真正省电的那一行。**
        //
        // 以前只把 iTime 乘 0，画面静止了但GPU 仍在满速跑
        // 4 阶 fbm —— GPU 不会因为「输出恒定」就偷懒。
        // 现在把开关交给着色器，由它在 main() 开头短路，
        // 省掉整屏每帧的全部噪声指令。
        shader.setFloatUniform("uAnimating", if (animationScale > 0.01f) 1f else 0f)

        drawRect(brush = brush, size = size)
    }
}

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

    val transition = rememberInfiniteTransition(label = "fluidCanvas")
    // 三个互质周期，避免构图重复
    val t1 by transition.animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "t1"
    )
    val t2 by transition.animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(12_000, easing = LinearEasing)), label = "t2"
    )
    val t3 by transition.animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "t3"
    )

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
        if (animationScale <= 0.01f) return@Canvas

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

        blob(t1, 0.28f, 0.26f, 0.20f, 0.14f, 0.60f, colors.primary, 0.55f)
        blob(t2, 0.74f, 0.34f, 0.16f, 0.18f, 0.52f, colors.secondary, 0.48f)
        blob(t3, 0.56f, 0.78f, 0.20f, 0.12f, 0.58f, colors.tertiary, 0.42f)
        blob((t1 + 2.4f), 0.18f, 0.82f, 0.14f, 0.10f, 0.46f, colors.secondary, 0.36f)
        // 新增两块：让中央区域也有颜色在动，填上旧版中间偏空的观感
        blob((t2 + 1.1f), 0.50f, 0.50f, 0.24f, 0.16f, 0.52f, colors.primary, 0.30f)
        blob((t3 + 3.0f), 0.85f, 0.72f, 0.12f, 0.14f, 0.44f, colors.tertiary, 0.28f)
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

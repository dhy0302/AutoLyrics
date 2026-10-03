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

package org.eu.dinghongyu.autolyrics.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.darkSaltColors
import com.moriafly.salt.ui.lightSaltColors
import com.moriafly.salt.ui.saltConfigs
import com.moriafly.salt.ui.saltDimens
import com.moriafly.salt.ui.saltTextStyles

/* ------------------------------------------------------------------ *
 *  v1.9.0 主题重做：接入 SaltUI（椒盐音乐的组件库）
 *
 *  v1.8.x 的配色方向是对的（UI 层少用彩色，把颜色预算留给歌词页的封面
 *  流体背景），但**形状语言是错的**——这也是"一眼 AI 生成"的主要来源。
 *  三处症结：
 *
 *  1) **圆角过大**。v1.8.0 铺的是 8/12/16/22/28dp，还专门注释说"圆角统一
 *     偏大，与流体背景的柔和曲线呼应"。但 M3 这套 16/22/28 的大圆角本身
 *     就是 AI 生成 UI 的典型指纹——Material You 胶囊化、Web 化的产物。
 *     SaltUI 官方规范（从 `saltDimens` 的字节码默认值反查得到）：
 *        corner                 = 12dp  ← 卡片/按钮
 *        dialogCorner           = 20dp  ← 弹窗
 *        outerHorizontalPadding = 16dp
 *        innerHorizontalPadding =  8dp
 *     口径是「12dp 为主、20dp 只给弹窗」，比 v1.8.0 小一圈。
 *
 *  2) **层级靠明度堆叠**。v1.8.0 连铺 Ink / InkElevated / InkHigher /
 *     surfaceContainerHigh 四档灰，靠"越来越亮"造深度。SaltUI 是
 *     background / subBackground / popup 三档 + 一条 stroke，
 *     层级差更小、靠描边界定——这是"克制"，不是"浮雕"。
 *
 *  3) **强调色被去成了无彩色**。v1.8.0 主色设象牙白（刻意去色），
 *     结果除歌词页外整个界面一点颜色都没有，寡淡得像没做完。
 *     SaltUI 官方深色 highlight = #1478C8（克制的钢蓝），
 *     浅色 = #0470E6。这点蓝只用在选中态/开关/进度条，
 *     面积小，但足以让界面"活"过来。
 *
 *  接入方式：外层 [SaltTheme] 提供官方配色与间距 token，
 *  内层 [MaterialTheme] 保留（大量组件依赖 M3 的 token），
 *  并把 M3 的色/形/字向 SaltUI 官方值对齐。
 * ------------------------------------------------------------------ */

/* ------------------------- SaltUI 官方 token ------------------------- */

/**
 * 官方深色配色。
 * 这些常量不是从文档抄的，是从 `darkSaltColors` 的字节码里反查出来的默认值：
 * highlight=#1478C8 / text=#EBEEF1 / subText=#E1E6EB /
 * background=#0C0C0C / popup=#191919，stroke = subText.copy(alpha=0.10f)。
 */
private val SaltNightColors = darkSaltColors()

/** 官方浅色配色：highlight=#0470E6 / text=#1E1715 / subText=#8C8C8C / background=#F7F9FA。 */
private val SaltDayColors = lightSaltColors()

/* ----------------------------- 夜间（默认） ----------------------------- */

/**
 * 底色取 SaltUI 的 background（#0C0C0C）。它比 v1.8.0 的 #0D0E10 略暖一点，
 * 但差别不大——真正的改善来自下面这组色的**间距**被拉近了。
 */
private val Ink = Color(0xFF0C0C0C)

/** 分组底 = SaltUI 的 subBackground，深度用 6% 白叠在 background 上。 */
private val InkElevated = Color(0xFF191919)

/** 需要"浮起来"的元素（选中项、滑块轨道）用 popup 再亮一点。 */
private val InkHigher = Color(0xFF242424)

/**
 * 文字主色：SaltUI 官方 text #EBEEF1。
 * 换掉象牙白 #E8E4DC —— 象牙白偏暖，和钢蓝强调色放一起会显脏；
 * SaltUI 这套是纯中性灰白，和它的蓝更配。
 */
private val SaltText = Color(0xFFEBEEF1)

/**
 * 强调色：SaltUI 官方钢蓝 #1478C8。
 * v1.8.0 这里是象牙白，等于整个界面没有强调色。
 * 这点蓝只出现在开关、选中项、滑块已填充段、进度条——面积小但有指向性。
 */
private val SaltHighlight = Color(0xFF1478C8)

private val NightColors = darkColorScheme(
    primary = SaltHighlight,
    onPrimary = Color.White,
    primaryContainer = InkHigher,
    onPrimaryContainer = SaltText,

    secondary = SaltText,
    onSecondary = Ink,
    secondaryContainer = InkHigher,
    onSecondaryContainer = SaltText,

    tertiary = Color(0xFF3E7CC4),
    onTertiary = Color.White,
    tertiaryContainer = InkHigher,
    onTertiaryContainer = SaltText,

    background = Ink,
    onBackground = SaltText,

    surface = Ink,
    onSurface = SaltText,
    surfaceVariant = InkElevated,
    onSurfaceVariant = Color(0xFF9AA0A6),

    // SaltUI 口径：三档（background/subBackground/popup）+ 一条 stroke，不铺五档灰。
    // 这里保留 M3 的 container 槽位，但相邻两档的明度差刻意做到很小。
    surfaceContainerLowest = Color(0xFF070707),
    surfaceContainerLow = Ink,
    surfaceContainer = InkElevated,
    surfaceContainerHigh = Color(0xFF1F1F1F),
    surfaceContainerHighest = InkHigher,

    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF2A2A2A),

    error = Color(0xFFD9695F),
    onError = Color(0xFF2A0F0D),
    errorContainer = Color(0xFF33191A),
    onErrorContainer = Color(0xFFF0B4B0),

    inverseSurface = SaltText,
    inverseOnSurface = Ink,
    scrim = Color(0xFF000000),
)

/* ------------------------------- 白天 ------------------------------- */

/** 纸色：SaltUI 官方浅色 background #F7F9FA（不是纯白，纯白在屏幕上刺眼且廉价）。 */
private val Paper = Color(0xFFF7F9FA)

/** 分组底 = SaltUI 的 subBackground。 */
private val PaperElevated = Color(0xFFEFF2F5)

private val PaperHigher = Color(0xFFE7EBEF)

private val DayColors = lightColorScheme(
    primary = Color(0xFF0470E6),
    onPrimary = Color.White,
    primaryContainer = PaperHigher,
    onPrimaryContainer = Color(0xFF1E1715),

    secondary = Color(0xFF1E1715),
    onSecondary = Color.White,
    secondaryContainer = PaperHigher,
    onSecondaryContainer = Color(0xFF1E1715),

    tertiary = Color(0xFF0F6CBD),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCEAF8),
    onTertiaryContainer = Color(0xFF0B3E70),

    background = Paper,
    onBackground = Color(0xFF1E1715),

    surface = Paper,
    onSurface = Color(0xFF1E1715),
    surfaceVariant = PaperElevated,
    onSurfaceVariant = Color(0xFF7A8087),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Paper,
    surfaceContainer = PaperElevated,
    surfaceContainerHigh = Color(0xFFEAEEF2),
    surfaceContainerHighest = PaperHigher,

    outline = Color(0xFFD3D8DE),
    outlineVariant = Color(0xFFE3E7EC),

    error = Color(0xFFB3463C),
    onError = Color.White,
    errorContainer = Color(0xFFF7DEDB),
    onErrorContainer = Color(0xFF6B2620),

    inverseSurface = Color(0xFF1E1715),
    inverseOnSurface = Color(0xFFF7F9FA),
    scrim = Color(0xFF000000),
)

/**
 * 中文字重习惯：正文用 Regular 偏细，标题一律 SemiBold/Bold，
 * 不用 Light——思源黑体的 Light 在深底上笔画会发虚。
 */
private val AutoLyricsTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * v1.9.0：**向 SaltUI 官方规范收敛**。
 *
 * 对照表（左=旧 v1.8.0 / 右=SaltUI 官方口径）：
 *   extraSmall   8dp  →  4dp    标签、色块这类"贴边"的小元素
 *   small       12dp  →  8dp    输入框、小按钮
 *   medium      16dp  → 12dp    卡片  ← SaltUI 的 corner 基准值
 *   large       22dp  → 16dp    弹窗内容块
 *   extraLarge  28dp  → 20dp    弹窗   ← SaltUI 的 dialogCorner
 *
 * 全线收 4dp 不是随手取的数：22→16、28→20 正好各自等于 SaltUI
 * 声明的 outerHorizontalPadding(16) 与 dialogCorner(20)。
 */
private val AutoLyricsShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/**
 * 应用主题。
 *
 * v1.8.1 起支持白天/夜间切换，[dark] 来自 [org.eu.dinghongyu.autolyrics.util.Settings.darkMode]
 * （设置页右上角那个太阳/月亮按钮切换的就是它）。
 *
 * v1.9.0 外层套上 SaltUI 的 [SaltTheme]：
 *  - `configs = saltConfigs(isDark = dark)`：告诉 SaltUI 当前明暗，
 *    它内部的组件（Button/Switch/Dialog 等）会据此选自己的配色；
 *  - `colors` 传官方色板，保持和上游一致；
 *  - `dimens` 用官方间距（corner=12dp / dialogCorner=20dp / 内外边距 16/8dp）；
 *  - `textStyles` 用官方 main/sub/paragraph 三档。
 *
 * 留 [MaterialTheme] 在内层是因为本项目大量组件（Switch / Slider /
 * AlertDialog / Card）直接吃 M3 的 token，砍掉会全线崩。
 * 两层共存不冲突——M3 的 ColorScheme/ Shapes 已经向 SaltUI 对齐过了。
 *
 * 歌词页是个例外：它的背景是**封面驱动的彩色流体渐变**，由 [Settings.inAppTextColor]
 * 决定文字色。在两种模式下背景都不变（都由封面决定），所以歌词页不因切换主题而改色——
 * 变的只有它周围那圈状态栏/导航栏区域。
 */
@Composable
fun AutoLyricsTheme(
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    SaltTheme(
        // `saltConfigs(isDark = dark)` 的形参在 2.0.10 里不叫 isDark
        // （反编译只看到裸 boolean），所以走位置参数。
        configs = saltConfigs(dark),
        colors = if (dark) SaltNightColors else SaltDayColors,
        textStyles = saltTextStyles(),
        dimens = saltDimens(),
    ) {
        MaterialTheme(
            colorScheme = if (dark) NightColors else DayColors,
            typography = AutoLyricsTypography,
            shapes = AutoLyricsShapes,
            content = content,
        )
    }
}

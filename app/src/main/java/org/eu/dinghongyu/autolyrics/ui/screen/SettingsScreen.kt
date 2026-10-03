package org.eu.dinghongyu.autolyrics.ui.screen

import org.eu.dinghongyu.autolyrics.R
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.eu.dinghongyu.autolyrics.util.SettingsStore
import android.content.Context

/**
 * 设置二级页的分类。每个分类对应一个独立页面。
 *
 * v1.8.0 起设置页是两级：一级只列分类（[SettingsScreen]），二级放具体项
 * （[SettingsSubPageContent]）。这样一级页永远是 6 行左右，不会因为
 * 设置项继续增加而变得冗长。
 */
enum class SettingsPage(val title: String, val summary: String, @DrawableRes val icon: Int) {
    DISPLAY(
        "显示方式",
        "悬浮窗 / 通知栏 / 歌词页",
        R.drawable.ic_cat_display,
    ),
    LYRIC_PAGE(
        "歌词页",
        "颜色、字号、逐字、背景",
        R.drawable.ic_cat_lyric,
    ),
    OVERLAY(
        "桌面悬浮窗",
        "字号、行数、颜色、行为",
        R.drawable.ic_cat_overlay,
    ),
    SOURCES(
        "歌词源",
        "启用顺序与回退",
        R.drawable.ic_cat_source,
    ),
    APPS(
        "监听范围",
        "不监听的应用",
        R.drawable.ic_cat_apps,
    ),
    ENGINE(
        "取词引擎",
        "精度、偏移、缓存",
        R.drawable.ic_cat_engine,
    ),
    ABOUT(
        "关于",
        "版本、仓库、许可",
        R.drawable.ic_cat_about,
    ),
}

/**
 * 设置一级页：只列分类目录。
 *
 * 每行右侧的副标题是该分类的**当前状态摘要**（例如「悬浮窗 开·通知栏 开」），
 * 用户不点进去也能看出个大概——这是把 30 多个开关收进 6 个格子后必须补的信息量。
 */
@Composable
fun SettingsScreen(
    permTick: Int,
    onOpenPage: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by SettingsStore.settings.collectAsState()
    val ctx = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, bottom = 32.dp,
        ),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
            ) {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                // 权重占满剩余空间，把切换按钮顶到最右，与「设置」标题同一行
                Box(Modifier.weight(1f))
                ThemeToggleButton(
                    dark = settings.darkMode,
                    onToggle = {
                        SettingsStore.update { s -> s.copy(darkMode = !s.darkMode) }
                    },
                )
            }
        }

        item {
            SettingCard {
                SettingsPage.entries.forEachIndexed { index, page ->
                    if (index > 0) SettingDivider()
                    NavigationRow(
                        title = page.title,
                        subtitle = summaryOf(page, settings, ctx),
                        icon = page.icon,
                        onClick = { onOpenPage(page) },
                    )
                }
            }
        }
    }
}

/**
 * 日/夜模式切换按钮（v1.8.1）。
 *
 * ## 图标语义按用户描述定：**显示的是"点下去会变成什么"**
 *  - 白天模式 → 显示**太阳**（点一下变夜间）
 *  - 夜间模式 → 显示**月亮**（点一下变白天）
 *
 * 这个方向和 Android 系统设置里那类开关的惯例一致：图标是"目标态"而非"当前态"，
 * 所以用户永远不需要在脑子里做一次取反。
 *
 * ## 为什么不在按钮上再加个开关轨道 / 胶囊底
 * 试想过做成 Material3 Switch 那种带轨道的，但两侧的滑动块会把"太阳↔月亮"
 * 两个完全不同的图形在水平方向上挤压变形，缩到 36dp 宽时月牙的弧度会失真。
 * 纯图标 + 轻微底色块最稳：底色块用 `surfaceContainerHigh` 跟随主题，
 * 白天是米白、夜间是深灰，都不是彩色，不会引入新的"AI 味"。
 */
@Composable
private fun ThemeToggleButton(
    dark: Boolean,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (dark) R.drawable.ic_moon else R.drawable.ic_sun),
            contentDescription = if (dark) "切换到白天模式" else "切换到夜间模式",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(19.dp),
        )
    }
}

/** 一级目录右侧的状态摘要。 */
private fun summaryOf(
    page: SettingsPage,
    s: org.eu.dinghongyu.autolyrics.util.Settings,
    ctx: Context,
): String = when (page) {
    SettingsPage.DISPLAY -> buildList {
        add(if (s.overlayEnabled) "悬浮窗 开" else "悬浮窗 关")
        add(if (s.notificationEnabled) "通知栏 开" else "通知栏 关")
        add(if (s.inAppEnabled) "歌词页 开" else "歌词页 关")
    }.joinToString(" · ")

    SettingsPage.LYRIC_PAGE -> buildList {
        add("${s.inAppFontSizeSp.toInt()}sp")
        if (s.inAppMinimal) add("精简模式")
        if (s.wordByWordEnabled) add("逐字")
        if (s.showTranslation) add("译文")
    }.joinToString(" · ")

    SettingsPage.OVERLAY -> buildList {
        add("${s.fontSizeSp.toInt()}sp")
        add(if (s.overlayLineMode == "current_next") "含下一句" else "仅当前句")
        if (s.overlayLocked) add("已锁定")
    }.joinToString(" · ")

    SettingsPage.SOURCES -> {
        val names = s.sourceOrder
            .filter { it in s.enabledSources }
            .map { org.eu.dinghongyu.autolyrics.lyric.LyricRepository.sourceName(it) }
        if (names.isEmpty()) "全部已禁用" else names.joinToString(" → ")
    }

    SettingsPage.APPS -> if (s.blockedPackages.isEmpty()) "全部监听" else "${s.blockedPackages.size} 个已屏蔽"

    SettingsPage.ENGINE -> buildList {
        add(s.precisionMode.label)
        if (s.globalOffsetMs != 0L) add("偏移 ${s.globalOffsetMs}ms")
    }.joinToString(" · ")

    // 副标题直接给版本号：这一页最想被用户确认的就是「我装的是哪一版」，
    // 挂在目录页上比点进去再看更省事（v1.11.0 移除液态玻璃时就是这么用的）。
    SettingsPage.ABOUT -> resolveAppVersionName(ctx)
}

/**
 * 二级页的内容。由 [org.eu.dinghongyu.autolyrics.ui.MainActivity] 在带返回栏的
 * Scaffold 里调用，所以这里不需要自己处理顶部inset。
 */
@Composable
fun SettingsSubPageContent(
    page: SettingsPage,
    permTick: Int,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, bottom = 40.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item { PageBody(page, permTick) }
    }
}

/** 各二级页的正文。由 [SettingsSubPageContent] 包在 LazyColumn 里。 */
@Composable
private fun PageBody(page: SettingsPage, permTick: Int) {
    val settings by SettingsStore.settings.collectAsState()
    Column {
        when (page) {
            SettingsPage.DISPLAY -> DisplayPage()
            SettingsPage.LYRIC_PAGE -> LyricPageSettings()
            SettingsPage.OVERLAY -> OverlaySettings()
            SettingsPage.SOURCES -> SourceSettings()
            SettingsPage.APPS -> BlockedAppsPage(permTick)
            SettingsPage.ENGINE -> EngineSettings()
            SettingsPage.ABOUT -> AboutPage()
        }
    }
}
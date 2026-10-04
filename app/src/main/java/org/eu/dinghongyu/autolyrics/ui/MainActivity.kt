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

import org.eu.dinghongyu.autolyrics.R
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.eu.dinghongyu.autolyrics.lyric.LyricEngine
import org.eu.dinghongyu.autolyrics.media.MediaNotificationListener
import org.eu.dinghongyu.autolyrics.media.MediaSessionWatcher
import org.eu.dinghongyu.autolyrics.media.PlaybackMonitor
import org.eu.dinghongyu.autolyrics.ui.notify.NotifyLyrics
import org.eu.dinghongyu.autolyrics.ui.overlay.OverlayController
import org.eu.dinghongyu.autolyrics.ui.screen.DebugScreen
import org.eu.dinghongyu.autolyrics.ui.screen.HomeScreen
import org.eu.dinghongyu.autolyrics.ui.screen.SettingsPage
import org.eu.dinghongyu.autolyrics.ui.screen.SettingsScreen
import org.eu.dinghongyu.autolyrics.ui.screen.SettingsSubPageContent
import org.eu.dinghongyu.autolyrics.util.Permissions
import org.eu.dinghongyu.autolyrics.util.SettingsStore

class MainActivity : ComponentActivity() {

    /** 每次 onResume 自增，用来强制 UI 重新判定权限状态。 */
    private var permTick by mutableIntStateOf(0)

    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式：状态栏/导航栏全透明，内容绘制到系统栏后面。
        setContent {
            // v1.8.1：白天/夜间切换。整个 App 的配色由这一处决定，
            // 所有页面都只读 MaterialTheme.colorScheme，所以自动全覆盖。
            val settings by SettingsStore.settings.collectAsState()

            // 系统栏图标要随主题反色：夜间是浅图标压在深底上，白天是深图标压在米白底上。
            // enableEdgeToEdge 可以重复调用，用 LaunchedEffect 跟随设置变化即可。
            LaunchedEffect(settings.darkMode) {
                val style = if (settings.darkMode) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            AutoLyricsTheme(dark = settings.darkMode) {
                MainScaffold(
                    permTick = permTick,
                    onGrantNotifications = {
                        requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onOpenListenerSettings = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onOpenOverlaySettings = {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permTick++
        // 权限一旦授予就主动建立/刷新抓取链路，不依赖通知服务是否回调过 onListenerConnected
        if (Permissions.notificationListenerGranted(this)) {
            MediaSessionWatcher.ensureStarted(this)
        }
        // 用户可能刚在系统设置里打开权限，这里重新评估悬浮窗与通知栏歌词
        OverlayController.attach(this)
        NotifyLyrics.attach(this)
        // 若服务确实还没连上（仅影响通知兜底），再请求一次重绑
        if (Permissions.notificationListenerGranted(this) && !PlaybackMonitor.listenerConnected) {
            MediaNotificationListener.requestRebind(this)
        }
        // v1.12.6：熄屏期间切歌时，App 在后台取词常被系统网络限制打断，
        // 那一次会留下 NOT_FOUND/ERROR 状态。回到前台时补一次重试，
        // 否则用户会一直看着「没找到歌词」，非得手动点重取才行。
        // force=false —— 此时网络已恢复，且真正「没歌词」的歌会命中负缓存。
        LyricEngine.retryIfUnresolved()
        PlaybackMonitor.update()
    }
}

/**
 * 覆盖式页面栈。
 *
 * ## v1.8.2 架构变更
 * v1.8.0~1.8.1 用的是底部三Tab（播放 / 歌词源 / 设置）+ 二级页。
 * 用户反馈"底部三栏太丑"，v1.8.2 把它**整个删掉**，改为：
 *
 *  - **歌词页是唯一常驻页**，铺满全屏（含状态栏与导航栏区域）
 *  - 「歌词源」和「设置」降级成歌词页右下角两个半透明小图标
 *  - 点图标 → 对应页面**从右侧滑入覆盖**在歌词页之上，底部不再有任何栏
 *
 * 覆盖而非并排的理由：歌词页的滑动歌词 + 流动背景是这套App 的主体验，
 * 平分屏幕会把它压到只剩一半；而且播放状态是全局的，
 * 任何时候都能用返回键立刻回到歌词页。
 */
@Stable
private class NavState {
    /** 当前覆盖在最上层的页面；null = 只显示歌词页。 */
    var overlay by mutableStateOf<Overlay?>(null)
    /** 设置二级页；非空时覆盖在设置一级页之上。 */
    var subPage by mutableStateOf<SettingsPage?>(null)

    /** 是否有任何覆盖层（决定返回键是否拦截）。 */
    val hasOverlay: Boolean get() = subPage != null || overlay != null

    fun openSettingsDirectory() {
        overlay = Overlay.SETTINGS
        subPage = null
    }

    /**
     * 进入某个设置的二级页。
     *
     * v1.12.9 之前，歌词页右上角还有一个直达「歌词页」设置的角标也走这里；
     * 那个角标已移除（与右下角「设置」入口功能重复），现在只剩设置一级页在用。
     */
    fun openSettings(page: SettingsPage) {
        overlay = Overlay.SETTINGS
        subPage = page
    }

    fun openSources() {
        overlay = Overlay.SOURCES
        subPage = null
    }

    /** 层级返回：设置二级页 → 设置一级页 → 歌词页 → 交给系统退出。 */
    fun back(): Boolean {
        if (subPage != null) {
            subPage = null
            return true
        }
        if (overlay != null) {
            overlay = null
            return true
        }
        return false
    }
}

/** 可从歌词页进入的覆盖页。 */
private enum class Overlay { SOURCES, SETTINGS }

/**
 * 应用主体。
 *
 * 结构上是「歌词页（全屏底板）」+ 可选的「覆盖页」，
 * 底部不再有任何导航栏。
 */
@Composable
private fun MainScaffold(
    permTick: Int,
    onGrantNotifications: () -> Unit,
    onOpenListenerSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
) {
    val nav = remember { NavState() }

    /**
     * 系统返回键：先退二级页 → 再退覆盖页 → 最后才交给系统（退出 App）。
     * 没有这一层的话，用户在设置二级页按返回会被直接踢出应用。
     */
    BackHandler(enabled = nav.hasOverlay) { nav.back() }

    // ---- v1.8.3：把「歌词页是否在前台」同步给悬浮窗控制器 ----
    //
    // 判定要同时满足两件事，缺一不可：
    //  1) Activity 处于前台（不是压在别的 App 下面）；
    //  2) 当前没有覆盖页（用户正看到的是歌词页本身，不是设置页）。
    //
    // 两者分别用不同机制驱动：Activity 前后台靠生命周期回调，
    // 覆盖页切换靠 Compose 重组 —— 合成一个布尔值后用 SideEffect 写出去。
    // 用 SideEffect 而不是 LaunchedEffect(key)：后者在 key 不变时**不会重跑**，
    // 而 nav.overlay 变化本身就会引起重组，用 SideEffect 每次都同步最直接。
    val lifecycleOwner = LocalLifecycleOwner.current
    var activityVisible by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            activityVisible = when (event) {
                Lifecycle.Event.ON_START -> true
                Lifecycle.Event.ON_STOP -> false
                else -> activityVisible
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Activity 真正销毁时必须复位，否则下次 attach 读到残留的 true，
            // 悬浮窗刚创建就会被立刻收掉，且再没人写回 false。
            OverlayController.setLyricsPageForeground(false)
        }
    }
    val lyricsPageForeground = activityVisible && nav.overlay == null
    SideEffect {
        OverlayController.setLyricsPageForeground(lyricsPageForeground)
    }

    Box(Modifier.fillMaxSize()) {
        // 底板：歌词页。任何时候都在，只是被覆盖时不可见。
        // 保持它常驻（而不是条件渲染）是为了切回时背景动画不重新开始、
        // 歌词滚动位置不丢。
        HomeScreen(
            permTick = permTick,
            // v1.13.10：补上 `activityVisible`。
            //
            // 原来只判 `nav.overlay`，于是 App整体退到后台时
            // （activityVisible == false）HomeScreen 仍认为「可见」，
            // 流体渐变背景照旧满速跑 GPU。
            // 上面第 264 行已经把两者合成过（`lyricsPageForeground`），
            // 这里只是漏用了 —— 两者语义本来就该一致：
            // 「歌词页当前是否真的在前台可见」。
            visible = lyricsPageForeground,
            onGrantNotifications = onGrantNotifications,
            onOpenListenerSettings = onOpenListenerSettings,
            onOpenOverlaySettings = onOpenOverlaySettings,
            // v1.12.9：歌词页右上角的设置直达入口已移除（与右下角「设置」重复），
            // HomeScreen 不再需要这个回调。歌词页设置仍可从
            // 右下角「设置」→「歌词页」进入。
            onOpenSources = { nav.openSources() },
            onOpenSettings = { nav.openSettingsDirectory() },
        )

        val sub = nav.subPage
        val overlay = nav.overlay

        if (sub != null) {
            // 设置二级页：顶部带返回栏
            SettingsShell(
                title = sub.title,
                onBack = { nav.back() },
            ) { pad ->
                SettingsSubPageContent(page = sub, permTick = permTick, modifier = pad)
            }
        } else if (overlay == Overlay.SETTINGS) {
            SettingsShell(
                title = null,
                onBack = { nav.back() },
            ) { pad ->
                SettingsScreen(permTick = permTick, onOpenPage = { nav.openSettings(it) }, modifier = pad)
            }
        } else if (overlay == Overlay.SOURCES) {
            SourcesShell(onBack = { nav.back() })
        }
    }
}

/**
 * 覆盖页的通用外壳：半透明底+ 左侧滑入动画 + 顶部返回栏。
 *
 * 底色用 `surface` 的 97% 不透明而不是纯色：底下流动背景隐约透出来一点，
 * 视觉上还是同一个世界，而不是硬切到另一个页面。
 *
 * ## v1.8.3：状态栏安全区
 * `enableEdgeToEdge` 让内容绘制到系统栏后面（沉浸式），但**内容自己必须让位** ——
 * 否则设置页顶部的「设置」标题会直接顶进状态栏图标里。
 * 这里在 Column 上加 `statusBarsPadding()`：它排在 `.background()` 之后，
 * 所以**背景依然铺满状态栏区域**（沉浸感不变），只是内容整体下移让开。
 *
 * 不用 `WindowInsets.statusBars` 去手算高度再减 padding：那样每台设备
 * （刘海、挖孔、状态栏多行）都要自己算对，而 `statusBarsPadding` 直接读系统给的
 * inset，永远不会算错。
 */
@Composable
private fun SettingsShell(
    title: String?,
    onBack: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            // 顶部一段极淡的竖向渐变，暗示"从下面升起来"，纯装饰
            .background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.surface,
                    1f to Color.Transparent,
                )
            )
            // 让开状态栏。注意放在 background 之后：背景照旧铺到屏幕最上沿
            .statusBarsPadding()
    ) {
        if (title != null) SettingsSubBar(title = title, onBack = onBack)
        content(Modifier.weight(1f))
    }
}

/** 歌词源页（旧的 DebugScreen）的外壳，同样带返回栏与状态栏让位。 */
@Composable
private fun SourcesShell(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            .statusBarsPadding()
    ) {
        SettingsSubBar(title = "歌词源", onBack = onBack)
        DebugScreen(Modifier.weight(1f))
    }
}

/**
 * 二级页/覆盖页顶部栏：返回箭头 + 标题。
 *
 * 用 Material3 的 TopAppBar 会带一整条带色调的分隔线与标题栏背景，
 * 在这套克制的深色主题里显得过重，这里手写一个 52dp 的轻量版本。
 */
@Composable
private fun SettingsSubBar(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(52.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_back),
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

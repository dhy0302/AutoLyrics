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

import android.content.Context
import org.eu.dinghongyu.autolyrics.media.PlaybackMonitor
import org.eu.dinghongyu.autolyrics.util.AppScope
import org.eu.dinghongyu.autolyrics.util.Permissions
import org.eu.dinghongyu.autolyrics.util.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 悬浮窗的开关与状态同步。
 *
 * 显示条件 = 用户开启 && 已授予 SYSTEM_ALERT_WINDOW && 不在歌词页。
 * 设置变化（开关、锁定、位置）都会实时反映到窗口上；
 * 关闭时把当前 Y 坐标记进设置，下次打开回到原位。
 *
 * v1.6.1 修复「暂停时悬浮窗不消失」：
 * 此前只collect 了 [SettingsStore.settings]，**完全没监听播放状态**，
 * 播放暂停后窗口一直留着。而 `OverlayContent` 里那句
 * `if (autoHideOnPause && !playing && track == null) return` 只是让内容区返回，
 * Window 本身还在屏幕上（只是变成透明/空白），所以用户看到「悬浮窗不消失」。
 * 现在把 [PlaybackMonitor.isPlaying] 也纳入 combine，暂停时真正 hide() 窗口。
 *
 * v1.8.3 新增「歌词页前台时自动隐藏」：
 * 见 [lyricsPageForeground]。这里刻意**不改写** `overlayEnabled` ——
 * 那是用户的持久意图，只在本次前台期间临时收掉窗口。
 *
 * ## v1.18.9：判定协程从主线程搬到IO（修「屏蔽源打断后悬浮窗不回来」）
 *
 * 这里是全项目**最后一个**挂在 `AppScope.main` 上的后台流水线。
 * 它订阅的 `PlaybackMonitor.isPlaying` 与播放轮询、取词、下标是同一组 StateFlow，
 * 而 v1.18.5 已把其余三处都搬去了 IO（见 [App.onCreate] 的说明）——
 * 唯独漏了这个「负责显示 / 隐藏窗口」的判定。
 *
 * 完整病因、为何只在「屏蔽源打断」时出现、以及「切下一首也没用」
 * 这条决定性证据，见 [attach] 里的 KDoc。改动只有那一处。
 */
object OverlayController {

    private var window: OverlayWindow? = null
    private var job: Job? = null

    /**
     * 歌词页是否正在前台可见。
     *
     * 由 [MainActivity] 的生命周期写入。它只表示"歌词页当前是用户在看的那一屏"，
     * 不含普通/精简模式的区别 —— 用户要求两种模式都隐藏。
     *
     * 之所以用 StateFlow 而不是直接 attach/detach 窗口：
     * 悬浮窗的显示条件已经是「开关 × 权限 × 播放状态」的一个组合，
     * 再叠一个「前台状态」维度，用 Flow 汇总是最不容易出错的做法
     * —— 无论哪一路条件变化，都会走到同一个判定点。
     */
    private val lyricsPageForeground = MutableStateFlow(false)

    /** 由 MainActivity 在歌词页可见/不可见时调用。 */
    fun setLyricsPageForeground(foreground: Boolean) {
        lyricsPageForeground.value = foreground
    }

    fun attach(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        // v1.18.9：这个 collect 从 `AppScope.main` 搬到 `AppScope.io`。
        //
        // ## 病因
        //
        // 它订阅的 `PlaybackMonitor.isPlaying` 与播放轮询、取词、下标
        // 是同一组 StateFlow，但**只有它留在主线程**（v1.18.5 搬走了其余三处）。
        //而它恰恰是全项目唯一一个「窗口会被它彻底摘掉」的执行者：
        // `autoHideOnPause`（默认开）一旦为真且 `playing == false`，
        // 就走 `hide()` 分支。
        //
        // 窗口一被摘掉，进程就**没有任何可见窗口**了 ——
        // 而 Android 在无可见窗口时会限制主线程消息队列的处理时机。
        // 于是「恢复播放」时 `isPlaying` 已在 IO 线程翻回 true，
        // 却没有任何东西去重新 `show()`：
        // **负责显示它的协程自己正等着被调度**。
        //
        // ## 为什么只在「被屏蔽的源打断」时出现
        //
        // 这是本bug 最关键的一环，也是它看起来像「屏蔽功能坏了」的原因：
        //
        // | 打断源 | `best()` 选谁 | `isPlaying` | 窗口 |
        // | --- | --- | --- | --- |
        // | 未屏蔽 | 切到新源（有播放态） | 始终 true | 从不 hide |
        // | 已屏蔽 | 过滤掉，**仍是原会话**（已暂停） | **翻 false** | **hide** |
        //
        // 未屏蔽那条路 `isPlaying` 从不翻 false ⇒ 窗口从未消失
        // ⇒ 进程始终有可见窗口 ⇒ 主线程始终被调度 ⇒ 一切正常。
        // 屏蔽源被 `best()` 过滤掉，才让 `isPlaying` 有机会翻 false，
        // 才让窗口消失、才让主线程被节流。
        // **屏蔽功能本身是好的，它是这条路径的触发条件而非原因。**
        //
        // ## 为什么「切下一首也没用」
        //
        // 因为歌词数据侧（取词 / 下标 / positionMs）全都在 IO 上好好跑着，
        // 缺的只是一次 `show()`。切歌只改数据、不碰窗口，所以毫无帮助 ——
        // 这条现象本身就是「问题在窗口层、不在数据层」的决定性证据。
        //
        // ## 为什么窗口操作仍然要切回主线程
        //
        // 判定与IO 解耦，但 `addView` / `removeView` / `updateViewLayout`
        // 碰的是 WindowManager，**必须在主线程**（项目既有约束，勿改）。
        // 所以只把 `window` 这几个字段的读写收进 [applyOnMain]，
        // 它内部用 `withContext(Dispatchers.Main)`。
        job = AppScope.io.launch {
            combine(
                SettingsStore.settings,
                PlaybackMonitor.isPlaying,
                // lyricsPageForeground 本身就是 StateFlow，直接参与combine 即可
                lyricsPageForeground,
            ) { settings, playing, lyricsFg ->
                Triple(settings, playing, lyricsFg)
            }.collect { (settings, playing, lyricsFg) ->
                // 播放中 = settings 开关打开 + 有悬浮窗权限；
                // 暂停时若开启了「暂停时自动隐藏」，则连窗口一起收掉；
                // 歌词页在前台时按设置额外收掉（同一份歌词出现两次没有意义）。
                val permitted = settings.overlayEnabled && Permissions.overlayGranted(app)
                val hiddenByLyricsPage = lyricsFg && settings.hideOverlayInLyricsPage
                val shouldShow = permitted && !hiddenByLyricsPage && (!settings.autoHideOnPause || playing)

                applyOnMain {
                    if (shouldShow && window == null) {
                        window = OverlayWindow(app).also {
                            it.show(settings.overlayY)
                            it.setLocked(settings.overlayLocked)
                        }
                    } else if (!shouldShow && window != null) {
                        val y = window?.currentY() ?: settings.overlayY
                        window?.hide()
                        window = null
                        // 只在「用户主动关闭」时记录位置；暂停或歌词页导致的临时隐藏
                        // 不该覆盖用户上次摆放的位置。
                        if (permitted && !hiddenByLyricsPage) {
                            SettingsStore.update { it.copy(overlayY = y) }
                        }
                    } else {
                        window?.setLocked(settings.overlayLocked)
                    }
                }
            }
        }
    }

    /**
     * v1.18.9：把对 [window] 的读改写收进主线程。
     *
     * 这几行原本直接跑在 collect 的接收者里。collect 现在挂在 IO 上，
     * 而 `OverlayWindow` 的三个方法都会碰 WindowManager，
     * **必须在主线程调用**，否则抛
     * `CalledFromWrongThreadException`（Android 10+ 直接崩）。
     *
     * 临界区里只有内存读写与一次 IPC 的add/remove，
     * 与 [MediaSessionWatcher] 「Binder 不进锁」的约定不冲突：
     * 那里防的是**持锁等Binder 把主线程占住**，
     * 而这里正是需要主动切到主线程去做窗口操作，且不持有任何自己的锁。
     */
    private suspend fun applyOnMain(block: () -> Unit) {
        // `Dispatchers.Main.immediate` 本身就带「已经位于主线程则直接执行、
        // 不必post」的优化（immediate 的定义就是 isDispatchNeeded 为 false 时
        // 不走dispatch），所以这里**不需要**再手工判断一次。
        //
        // ⚠️ v1.18.9 首次提交写成
        //    `if (Dispatchers.Main.immediate.isDispatchNeeded(false))`
        // CI 编译失败：
        //    「Argument type mismatch: actual type is 'kotlin.Boolean',
        //      but 'kotlin.coroutines.CoroutineContext' was expected.」
        // —— `isDispatchNeeded` 的参数是 CoroutineContext而非 Boolean。
        //
        // 教训：**能在withContext 里表达的语义就不要手写判断**。
        // 多写一行就多一处可能写错签名的地方，而 CI 是唯一能发现它的时机。
        withContext(Dispatchers.Main.immediate) { block() }
    }

    fun detach() {
        job?.cancel()
        job = null
        window?.hide()
        window = null
        // 关键：detach 通常意味着 Activity 正在销毁（App 退到后台 / finish），
        // 此时必须把前台标记一并清掉。否则下次 attach 时会读到残留的 true，
        // 悬浮窗刚创建就被立刻收掉 —— 而且因为这个值没人再写回 false，
        // 会一直卡在隐藏状态。
        lyricsPageForeground.value = false
    }

    fun isShowing(): Boolean = window != null
}

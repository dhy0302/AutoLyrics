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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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
        job = AppScope.main.launch {
            combine(
                SettingsStore.settings,
                PlaybackMonitor.isPlaying,
                // lyricsPageForeground 本身就是 StateFlow，直接参与 combine 即可
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
                    if (permitted && !hiddenByLyricsPage) SettingsStore.update { it.copy(overlayY = y) }
                } else {
                    window?.setLocked(settings.overlayLocked)
                }
            }
        }
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

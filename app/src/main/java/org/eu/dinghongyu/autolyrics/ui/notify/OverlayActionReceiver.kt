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

package org.eu.dinghongyu.autolyrics.ui.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import org.eu.dinghongyu.autolyrics.util.SettingsStore

/**
 * 通知栏三个悬浮窗控制动作的接收器。
 *
 * ## 按钮都是**双向**的：文案写「点一下会发生什么」
 *
 *  - 悬浮窗开着 →「关闭桌面歌词」；关着 →「开启桌面歌词」
 *  - 当前不透明 →「歌词背景透明」；当前透明 →「歌词背景不透明」
 *  - 当前未锁 →「锁定桌面歌词」；当前已锁 →「解锁桌面歌词」
 *
 * 这样用户不用先判断「现在是什么状态」，看文案就知道点了会怎样。
 *
 * ## 为什么走广播
 *
 * 通知栏 Action 不依赖悬浮窗可点击 ——
 * 即使窗口处于锁定（点击穿透）状态也能响应。
 * 等于把通知栏当成悬浮窗的遥控器：悬浮窗本身点不动时，这里照样能调。
 *
 * 这也是「锁定」这个按钮**必须**放在通知栏的原因：
 * 锁定后悬浮窗自己点不动了（FLAG_NOT_TOUCHABLE），
 * 唯一能解锁的入口就是通知栏这个按钮或设置页。
 *
 * ## 打开时为什么强制解锁
 *
 * 用户从通知栏点「打开」，期望的是「马上能用」。
 * 如果恢复成锁定态（点不动、拖不了），他会以为按钮坏了。
 * 想锁定可以在悬浮窗上操作，或用通知栏的锁定按钮，或去设置页开。
 */
class OverlayActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context ?: return
        when (intent?.action) {
            ACTION_TOGGLE_OVERLAY -> toggleOverlay(app)
            ACTION_TOGGLE_TRANSPARENT_BG -> toggleTransparentBg(app)
            ACTION_TOGGLE_LOCK -> toggleLock(app)
        }
    }

    private fun toggleOverlay(app: Context) {
        val wasOn = SettingsStore.current().overlayEnabled
        if (wasOn) {
            SettingsStore.update { it.copy(overlayEnabled = false) }
            Toast.makeText(app, "桌面歌词已关闭", Toast.LENGTH_SHORT).show()
        } else {
            SettingsStore.update {
                it.copy(overlayEnabled = true, overlayLocked = false)
            }
            Toast.makeText(app, "桌面歌词已打开", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 切换悬浮窗的透明背景。
     *
     * 与设置页的「透明背景」是同一个开关（[SettingsStore.Settings.overlayTransparentBg]），
     * 两边状态同步 —— 在设置页改了，通知栏按钮文案也会跟着变。
     *
     * 这里**不动** `overlayEnabled`：透明与否是样式，
     * 不该顺带把悬浮窗打开或关掉，否则点了会莫名其妙多出一个窗口。
     */
    private fun toggleTransparentBg(app: Context) {
        val nowTransparent = SettingsStore.current().overlayTransparentBg
        SettingsStore.update { it.copy(overlayTransparentBg = !nowTransparent) }
        Toast.makeText(
            app,
            if (nowTransparent) "歌词背景已设为不透明" else "歌词背景已设为透明",
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * v1.18.3：切换悬浮窗的锁定状态。
     *
     * 与设置页的「锁定」是同一个开关（[SettingsStore.Settings.overlayLocked]），
     * 两边状态同步。
     *
     * 锁定时 [org.eu.dinghongyu.autolyrics.ui.overlay.OverlayWindow] 会加
     * `FLAG_NOT_TOUCHABLE`，点击直接穿透到下层 App —— 悬浮窗自己就点不动了，
     * 所以这个「解锁」入口只能放在通知栏（走广播，不依赖悬浮窗可点）。
     *
     * 这里**不动** `overlayEnabled`：锁不锁是交互状态，
     * 不该顺带把悬浮窗打开或关掉。
     */
    private fun toggleLock(app: Context) {
        val nowLocked = SettingsStore.current().overlayLocked
        SettingsStore.update { it.copy(overlayLocked = !nowLocked) }
        Toast.makeText(
            app,
            if (nowLocked) "桌面歌词已解锁" else "桌面歌词已锁定",
            Toast.LENGTH_SHORT,
        ).show()
    }

    companion object {
        /** 广播 action。用自定义字符串，避免与其他 Receiver 的隐式 intent 冲突。 */
        const val ACTION_TOGGLE_OVERLAY = "org.eu.dinghongyu.autolyrics.action.TOGGLE_OVERLAY"

        /** 切换悬浮窗透明背景。 */
        const val ACTION_TOGGLE_TRANSPARENT_BG =
            "org.eu.dinghongyu.autolyrics.action.TOGGLE_TRANSPARENT_BG"

        /** v1.18.3：切换悬浮窗锁定（锁定后点击穿透，解锁入口只能在这里）。 */
        const val ACTION_TOGGLE_LOCK = "org.eu.dinghongyu.autolyrics.action.TOGGLE_LOCK"
    }
}
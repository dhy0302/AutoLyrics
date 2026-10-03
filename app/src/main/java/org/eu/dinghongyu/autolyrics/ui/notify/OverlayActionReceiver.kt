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
 * 通知栏「桌面歌词悬浮窗」开关动作的接收器。
 *
 * v1.8.1 起是**双向**的：悬浮窗开着时按钮显示「关闭桌面歌词悬浮窗」，
 * 关着时显示「打开桌面歌词悬浮窗」。
 *
 * 走广播而不是直接操作悬浮窗，是因为通知栏 Action 不依赖悬浮窗可点击——
 * 即使窗口处于锁定（点击穿透）状态也能响应。
 *
 * 打开时强制把 `overlayLocked` 置false：用户从通知栏点「打开」，
 * 期望的是「马上能用」，如果恢复成锁定态（点不动、拖不了）会以为按钮坏了。
 * 想锁定可以在悬浮窗上操作，或去设置页开。
 */
class OverlayActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context ?: return
        if (intent?.action != ACTION_TOGGLE_OVERLAY) return

        val wasOn = SettingsStore.current().overlayEnabled
        if (wasOn) {
            SettingsStore.update { it.copy(overlayEnabled = false) }
            Toast.makeText(app, "桌面歌词悬浮窗已关闭", Toast.LENGTH_SHORT).show()
        } else {
            SettingsStore.update {
                it.copy(overlayEnabled = true, overlayLocked = false)
            }
            Toast.makeText(app, "桌面歌词悬浮窗已打开", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /** 广播 action。用自定义字符串，避免与其他 Receiver 的隐式 intent 冲突。 */
        const val ACTION_TOGGLE_OVERLAY = "org.eu.dinghongyu.autolyrics.action.TOGGLE_OVERLAY"
    }
}
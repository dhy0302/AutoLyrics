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
 * 通知栏「开启/关闭桌面歌词」动作的接收器。
 *
 * ## v1.18.7：三个按钮只剩一个
 *
 * 原先有开启/关闭、透明、锁定三个。现在透明与锁定两个
 * 已迁进悬浮窗内部（见 [org.eu.dinghongyu.autolyrics.ui.overlay.OverlayContent]），
 * 通知栏只保留这个主开关。
 *
 * ## 打开时为什么强制解锁
 *
 * 用户从通知栏点「开启」，期望的是「马上能用」。
 * 如果恢复成锁定态（点不动、拖不了），他会以为按钮坏了。
 * 所以开启时顺手把 `overlayLocked` 与 `overlayTransparentBg` 归零——
 * 每次从通知栏开启都是干净的可拖动、不透明背景状态，
 * 不沿用上一次的样式。
 */
class OverlayActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context ?: return
        when (intent?.action) {
            ACTION_TOGGLE_OVERLAY -> toggleOverlay(app)
            // v1.18.7：透明与锁定两个 action 已删除。
            // 广播 action 常量也一并删除，避免留下无人处理的死常量。
        }
    }

    private fun toggleOverlay(app: Context) {
        val wasOn = SettingsStore.current().overlayEnabled
        if (wasOn) {
            SettingsStore.update { it.copy(overlayEnabled = false) }
            Toast.makeText(app, "桌面歌词已关闭", Toast.LENGTH_SHORT).show()
        } else {
            // v1.18.7：开启时把样式也归零——「锁定」与「透明背景」都恢复默认。
            //
            // 用户的要求是「每次开启都是解锁 + 不透明」。
            // 透明尤其要归零：透明模式下窗口没有底框，若上次残留为透明，
            // 这回点「开启」出来的却是个看不见背景的窗口，
            // 用户会以为没生效。
            SettingsStore.update {
                it.copy(
                    overlayEnabled = true,
                    overlayLocked = false,
                    overlayTransparentBg = false,
                )
            }
            Toast.makeText(app, "桌面歌词已打开", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /**
         * 广播 action。用自定义字符串，避免与其他 Receiver 的隐式 intent 冲突。
         *
         * v1.18.7：透明（`TOGGLE_TRANSPARENT_BG`）与锁定（`TOGGLE_LOCK`）
         * 两个 action 已随它们的按钮一起迁进悬浮窗，常量不再需要。
         */
        const val ACTION_TOGGLE_OVERLAY = "org.eu.dinghongyu.autolyrics.action.TOGGLE_OVERLAY"
    }
}
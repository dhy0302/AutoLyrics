package com.yuanbao.autolyrics.ui.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.yuanbao.autolyrics.util.SettingsStore

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
        const val ACTION_TOGGLE_OVERLAY = "com.yuanbao.autolyrics.action.TOGGLE_OVERLAY"
    }
}
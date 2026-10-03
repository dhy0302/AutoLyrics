package com.yuanbao.autolyrics.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import androidx.core.app.NotificationManagerCompat
import com.yuanbao.autolyrics.media.MediaNotificationListener

object Permissions {

    /** 悬浮窗（SYSTEM_ALERT_WINDOW）。 */
    fun overlayGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 通知监听权限。Android 强制要求：没有它，MediaSessionManager 直接抛 SecurityException。
     */
    fun notificationListenerGranted(context: Context): Boolean {
        val flat = ComponentName(context, MediaNotificationListener::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        )
        if (enabled.isNullOrBlank()) return false
        return enabled.split(":").any { TextUtils.equals(flat, it) }
    }

    /** Android 13+ 的发通知权限。 */
    fun notificationPostGranted(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()
}

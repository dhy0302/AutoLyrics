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

package org.eu.dinghongyu.autolyrics.media

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.eu.dinghongyu.autolyrics.data.TrackInfo
import org.eu.dinghongyu.autolyrics.ui.notify.NotifyLyrics
import org.eu.dinghongyu.autolyrics.ui.overlay.OverlayController

/**
 * 通知监听服务。它有三个职责：
 *
 *  1. **权限载体**：Android 强制要求——没有通知监听权限，
 *     `MediaSessionManager.getActiveSessions()` 会直接抛 SecurityException。
 *  2. **兜底解析**：个别 App 不暴露 MediaSession，从媒体通知里取歌名/歌手。
 *  3. **拉起 UI**：权限就绪后启动悬浮窗与通知栏歌词。
 */
class MediaNotificationListener : NotificationListenerService() {

    /** 已知的媒体类应用；只在这些包的通知里找兜底信息，避免误判。 */
    private val MEDIA_PACKAGES = setOf(
        "com.spotify.music",
        "com.apple.android.music",
        "com.google.android.apps.youtube.music",
        "com.netease.cloudmusic",
        "com.tencent.qqmusic",
        "com.kugou.android",
        "com.kugou.android.lite",
        "cn.kuwo.player",
        "com.ximalaya.ting.android",
        "tv.danmaku.bili",
        "com.soundcloud.android",
        "deezer.android.app",
        "com.aspiro.tidal",
        "com.android.music",
        "com.miui.player",
        "com.samsung.android.music",
        "com.huawei.music",
        "com.oppo.music",
        "com.vivo.music",
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        PlaybackMonitor.listenerConnected = true
        // ensureStarted 幂等：若App 侧已建立链路则只做一次 refresh，不会重复注册监听
        MediaSessionWatcher.ensureStarted(this)
        PlaybackMonitor.update()
        OverlayController.attach(this)
        NotifyLyrics.attach(this)
    }

    override fun onListenerDisconnected() {
        PlaybackMonitor.listenerConnected = false
        // 注意：这里**不能**停掉 MediaSessionWatcher。
        // 系统回收服务 / 临时断连时权限依然有效，此时 MediaSession 抓取仍然可用；
        // 旧实现在这里 stop()，导致断连一次就永久失灵，只能清数据或手动开关权限才能恢复。
        // 通知兜底数据同样保留，等重新连上时自然刷新。
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName !in MEDIA_PACKAGES) return

        val notification = sbn.notification ?: return
        val isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        val isTransport = notification.category == Notification.CATEGORY_TRANSPORT
        if (!isOngoing && !isTransport) return

        // 该 App 已经能用 MediaSession 读到，就不需要兜底（后者进度不准）
        //
        // v1.12.1：用 hasSessionFor 而不是 snapshots().any { it.pkg == ... }。
        // 后者会遍历所有 controller 各读 2 次跨进程 Binder，
        // 而通知回调默认在主线程 + 媒体 App 每秒重发通知 ⇒ 每秒主线程一次全量 IPC。
        // 这个判定只需要「有没有会话」，看 controllers 的 key 就够（零 Binder）。
        if (MediaSessionWatcher.hasSessionFor(sbn.packageName)) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        if (title.isBlank()) return
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()

        PlaybackMonitor.setFallback(
            TrackInfo(
                title = title,
                artist = artist,
                durationMs = 0L,
                pkg = sbn.packageName,
                origin = TrackInfo.ORIGIN_NOTIFICATION,
            )
        )
        PlaybackMonitor.update()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName in MEDIA_PACKAGES) {
            PlaybackMonitor.setFallback(null)
            PlaybackMonitor.update()
        }
    }

    companion object {
        fun component(context: Context) =
            ComponentName(context, MediaNotificationListener::class.java)

        /**
         * 强制系统（重新）绑定本通知监听服务。
         *
         * 关键修复：应用被「划掉」强杀后，系统不会自动重绑 NotificationListenerService，
         * 导致 MediaSession 抓取链路彻底失效、再也检测不到播放状态，只能清数据重授权。
         * 主动 requestRebind 即可在重新打开时恢复监听（系统会在绑定后回调 onListenerConnected）。
         * 该方法 API 24+ 才有，minSdk=26 安全。
         */
        fun requestRebind(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            try {
                NotificationListenerService.requestRebind(component(context))
            } catch (_: Throwable) {
                // 个别 ROM 实现异常，静默忽略；最坏只是本次启动检测不到，下次冷启会再试
            }
        }
    }
}

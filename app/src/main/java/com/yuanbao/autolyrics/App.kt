package com.yuanbao.autolyrics

import android.app.Application
import com.yuanbao.autolyrics.lyric.LyricEngine
import com.yuanbao.autolyrics.lyric.LyricRepository
import com.yuanbao.autolyrics.media.MediaNotificationListener
import com.yuanbao.autolyrics.media.MediaSessionWatcher
import com.yuanbao.autolyrics.media.PlaybackMonitor
import com.yuanbao.autolyrics.util.AppScope
import com.yuanbao.autolyrics.util.Permissions
import com.yuanbao.autolyrics.util.SettingsStore

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 顺序有讲究：设置 → 缓存目录 → 引擎（依赖前两者）
        SettingsStore.init(this)
        LyricRepository.init(this)
        LyricEngine.start(AppScope.main)
        PlaybackMonitor.startTicker(AppScope.main)
        // 关键修复：应用被强杀/被系统回收后，系统不一定会重新回调 onListenerConnected，
        // 但只要「通知读取」权限还在，MediaSession 抓取就依然可用。
        // 因此这里主动建立抓取链路（幂等），并顺带请求重绑通知服务以恢复通知兜底能力。
        if (Permissions.notificationListenerGranted(this)) {
            MediaSessionWatcher.ensureStarted(this)
            MediaNotificationListener.requestRebind(this)
        }
    }
}

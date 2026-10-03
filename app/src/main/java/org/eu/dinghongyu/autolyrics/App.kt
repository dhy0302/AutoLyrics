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

package org.eu.dinghongyu.autolyrics

import android.app.Application
import org.eu.dinghongyu.autolyrics.lyric.LyricEngine
import org.eu.dinghongyu.autolyrics.lyric.LyricRepository
import org.eu.dinghongyu.autolyrics.media.MediaNotificationListener
import org.eu.dinghongyu.autolyrics.media.MediaSessionWatcher
import org.eu.dinghongyu.autolyrics.media.PlaybackMonitor
import org.eu.dinghongyu.autolyrics.util.AppScope
import org.eu.dinghongyu.autolyrics.util.Permissions
import org.eu.dinghongyu.autolyrics.util.SettingsStore

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

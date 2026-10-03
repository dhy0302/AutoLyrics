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

import android.app.Activity
import android.app.Application
import android.os.Bundle
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

        // v1.12.1：监听「用户彻底离开 App」，那一刻把防抖攒着的设置写盘。
        watchAppBackground()
    }

    /**
     * v1.12.1：进程要退出去之前，把待落盘的设置立刻写下去。
     *
     * ## 为什么必须加这个
     * [SettingsStore] 的写盘现在是**防抖**的（连续修改只写最后一次），
     * 好处是拖动校准滑块时不再疯狂写磁盘，
     * 代价是「改完立刻划掉 App」时那 300ms 窗口内的修改可能丢。
     *
     * 对普通设置项（开关、主题）无所谓——最多丢一次偏好；
     * 但校准偏移丢一次就可能让用户觉得「我明明调过了怎么又偏了」，
     * 属于会直接影响信任的 bug，所以必须补上这个兜底。
     *
     * ## 为什么用 onTerminate 而不是别的
     * `onTerminate` 在真机上**不会被调用**（只有模拟器/单测才会），
     * 属于「写了但没用」的安慰剂，不能只靠它。
     * 真正有效的是下面注册的 Activity 生命周期回调：
     * 用户离开最后一个 Activity 时系统会回调 `onActivityStopped`，
     * 那一刻进程还活着，正是落盘的安全时机。
     */
    override fun onTerminate() {
        SettingsStore.flush()
        super.onTerminate()
    }

    /**
     * v1.12.1：在 [onCreate] 里注册，而不是 override `registerActivityLifecycleCallbacks`。
     *
     * 后者是个 public API，覆盖它会把外部调用者的回调也一起吞掉，
     * 而且「重写父类方法 + 往父类塞参数」这种写法极易出错，
     * 不如直接在 onCreate 里注册自己的监听来得直白。
     */
    private fun watchAppBackground() {
        var started = 0
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
            }

            override fun onActivityStopped(activity: Activity) {
                started--
                if (started <= 0) {
                    started = 0
                    // 用户彻底离开所有界面。防抖窗口里攒着的设置在这刻写盘，
                    // 保证「刚调完校准就划掉 App」不会丢。
                    SettingsStore.flush()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

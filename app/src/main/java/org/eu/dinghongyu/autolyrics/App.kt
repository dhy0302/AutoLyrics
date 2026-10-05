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
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import org.eu.dinghongyu.autolyrics.lyric.LyricEngine
import org.eu.dinghongyu.autolyrics.lyric.LyricRepository
import org.eu.dinghongyu.autolyrics.media.LyricsForegroundService
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
        // v1.18.5：这两处从 `AppScope.main` 改为 `AppScope.io`。
        //
        // 关键在于「后台无可见窗口时主线程消息队列会被限制处理时机」。
        // LyricEngine 的取词协程与下标协程、以及 PlaybackMonitor 的
        // 档位 collect 原本都挂在主线程上，退到后台后随时可能停摆——
        // 而这与「协程抛异常死亡」产生完全相同的症状，
        // 导致前几轮一直往错误方向排查（见LyricsForegroundService 的 KDoc）。
        //后台流水线一律放 IO，不依赖主线程调度。
        LyricEngine.start(AppScope.io)
        PlaybackMonitor.startTicker(AppScope.io)
        // 关键修复：应用被强杀/被系统回收后，系统不一定会重新回调 onListenerConnected，
        // 但只要「通知读取」权限还在，MediaSession 抓取就依然可用。
        // 因此这里主动建立抓取链路（幂等），并顺带请求重绑通知服务以恢复通知兜底能力。
        if (Permissions.notificationListenerGranted(this)) {
            MediaSessionWatcher.ensureStarted(this)
            MediaNotificationListener.requestRebind(this)
        }

        // v1.18.2：启动前台服务。
        //
        // **没有它，退到后台后进程会被系统冻结**，所有后台协程停止执行，
        // 症状是「通知栏歌词永久停在退出 App 时的那一句，切歌也不变，
        // 但打开 App 进歌词页就正常」。修复背景见 LyricsForegroundService 的 KDoc。
        //
        // 放在 onCreate 而非 MainActivity.onResume 是关键：
        // 后台被杀后重启时根本没有 Activity 回调，只有 onCreate 每次进程启动都跑。
        //
        // 前台服务必须挂通知，而 POST_NOTIFICATIONS 在 Android 13+ 需要用户授权；
        // 未授权时 startForeground 仍能跑（系统会降级为不可见的常驻通知），
        // 所以这里不做权限判断，避免把用户挡在门外。
        LyricsForegroundService.ensureStarted(this)

        // v1.12.1：监听「用户彻底离开 App」，那一刻把防抖攒着的设置写盘。
        watchAppBackground()

        // v1.13.10：内存吃紧时主动让出缓存。
        watchMemoryPressure()
    }

    /**
     * v1.13.10：监听系统的内存压力回调。
     *
     * ## 为什么之前没有
     *
     * 全项目原先没有任何 [ComponentCallbacks2] / [onTrimMemory] 注册。
     * 歌词 LRU、封面缓存、元数据缓存全都只靠自然 GC 释放 ——
     * 而 GC 只在系统觉得必要时才跑。于是在 2~3GB 的低端机上，
     * 进程会一路涨到被 LMK 杀掉，表现为「切后台一会儿回来，App 被重启了」。
     *
     * 主动让出永远比被杀掉好：缓存丢了最多是下次切歌重新联网一次。
     *
     * ## 各级别的处理
     *
     *  - `RUNNING_LOW`（轻度紧张）：只清**元数据缓存**。它最容易重建
     *    （下次 tick 重新解析一次），且可能钉着一份带全尺寸封面的
     *    MediaMetadata，性价比最高。
     *  - `RUNNING_CRITICAL` / `onLowMemory`：连歌词 LRU 一起清。
     *    歌词 LRU 约 3~8MB，是这时候最值得让出的一块。
     *
     *## 为什么不用 `ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN`
     *
     * 那个级别在 App 退到后台时就会触发，而后台恰恰是这个App 最需要缓存的时候
     *（悬浮窗正在显示歌词）。所以只处理「真的内存不够」的两个级别。
     */
    private fun watchMemoryPressure() {
        val cb = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                when {
                    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                        MediaSessionWatcher.trimMetaCache()
                        // 达到critical 就连歌词缓存一起清
                        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                            LyricRepository.trimMemoryCache()
                        }
                    }
                    // TRIM_MEMORY_UI_HIDDEN 等「不那么紧张」的级别刻意不处理，
                    // 理由见 KDoc。
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Android 已改为回调 onTrimMemory(level)", ReplaceWith("onTrimMemory(level)"))
            override fun onLowMemory() {
                // 老系统（API < 14）才走这里；本项目 minSdk 26 不会触发，
                // 但留着不删可以让这个类在新旧系统上都语义完整。
                MediaSessionWatcher.trimMetaCache()
                LyricRepository.trimMemoryCache()
            }
        }
        registerComponentCallbacks(cb)
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

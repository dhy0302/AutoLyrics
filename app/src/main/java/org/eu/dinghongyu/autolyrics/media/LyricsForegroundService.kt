/*
 * AutoLyrics — 安卓自动歌词
 * Copyright (C) 2026 丁宏宇
 *
 * 本程序遵循 GNU General Public License v3.0 或更高版本发布。
 * 详见仓库根目录的 LICENSE 文件。
 */

package org.eu.dinghongyu.autolyrics.media

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import org.eu.dinghongyu.autolyrics.ui.notify.NotifyLyrics
import org.eu.dinghongyu.autolyrics.ui.overlay.OverlayController

/**
 * v1.18.2：前台服务 —— 让通知栏歌词在**App 退到后台后还能持续更新**。
 *
 * ## 这个 bug 的完整因果（找了很久，值得写清楚）
 *
 * 用户报：「切到别的 App 后，通知栏歌词停在退出歌词页时的那一句，
 * 永久不变，连切歌也不变；但打开 App 进歌词页就正常，
 * 点通知栏的『开启桌面歌词』也会恢复正常。」
 *
 * 最后那条现象是**决定性证据**。因为
 * [org.eu.dinghongyu.autolyrics.ui.notify.OverlayActionReceiver] 全文只做
 * `SettingsStore.update { overlayEnabled = true }`，
 * **没有任何一行 `NotifyLyrics.attach()`**。
 *
 * 那它凭什么让通知恢复更新？只有一种解释：
 * **点通知按钮这个动作本身把进程唤醒了**（进程已死则拉起，
 * 被冻结则解冻）。恢复的是「进程能执行代码」这件事，
 * 而不是「重启了某个协程」。
 *
 * 于是根因浮出水面：**本 App 原本没有任何前台组件。**
 * `AndroidManifest.xml` 里只有 `MediaNotificationListener`，而它
 * **不是前台服务** —— 它只在系统连接时回调一次 `onListenerConnected`，
 * 之后不提供任何「持续运行」的保证。
 *
 * 没有前台服务 ⇒ 退到后台后进程随时被系统冻结/回收 ⇒
 * 所有后台协程（[PlaybackMonitor] 的 ticker、[org.eu.dinghongyu.autolyrics.lyric.LyricEngine]
 * 的 index 计算、[NotifyLyrics] 的 collect）**全部停止执行**。
 *
 * ## 为什么静态读代码永远找不到这个 bug
 *
 * 因为**没有任何一行代码是错的**。`combine`、去重、`indexAt` 全部正确，
 * 它们只是「根本没机会被调用」。
 * 我前几天在纯逻辑层反复排查（combine conflation、去重键、smooth 停滞…），
 * 全部方向性错误 —— 纯逻辑差分测试对「进程被冻结」这类问题天然无效。
 *
 * 教训：**「一边正常一边僵死」且「某个 UI 动作能恢复」，
 * 优先怀疑执行环境（进程生命周期/调度），而不是数据流。**
 *
 * ## 为什么必须用前台服务
 *
 * Android 后台持续运行的正规手段只有前台服务。
 * 常见误区是以为「NotificationListenerService 已经算前台了」—— 不是。
 * 它是为了拿权限（`getActiveSessions` 需要它），不是为了保活。
 * 系统对它没有持续运行的承诺。
 *
 * ## 为什么不另发一条通知（用户选定的方案）
 *
 * 前台服务必须挂一条通知。备选是额外发一条内容固定的「运行中」通知，
 * 用户选了**合并**：前台服务与歌词通知**共用同一个 ID**，
 * 于是占位通知会被歌词内容直接覆盖，通知栏只留一条、就是当前歌词。
 *
 * 代价：用户在系统设置里清掉这条通知 = 停掉前台服务。
 * 这是可接受的 —— 他确实不想要这个功能。
 */
class LyricsForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        // 服务一创建就把整条链路拉起来 —— 不依赖 MainActivity.onResume，
        // 因为后台被杀后重启时根本没有 Activity 回调。
        // 三者的 attach 都是幂等的（内部会先看协程是否还活着）。
        MediaSessionWatcher.ensureStarted(this)
        OverlayController.attach(this)
        NotifyLyrics.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground 必须在 startService 之后 5 秒内调用，
        // 否则系统抛 ANR / ForegroundServiceDidNotStartInTimeException。
        // 所以这里先用占位内容把它顶上去，真正的歌词随后由
        // NotifyLyrics 的 collect 更新（同 ID，覆盖掉这条占位）。
        startForegroundCompat()
        running = true
        NotifyLyrics.attach(this)
        // START_STICKY：进程被回收后系统会重新拉起并回调 onStartCommand，
        // 于是歌词链路能自愈。这是「后台被杀后自己恢复」的关键一环。
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        // 不主动 cancel 通知：它是用户可见的界面。
        // 服务被系统回收时让最后一条内容留着，比留一片空白更合理。
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val placeholder = Notification.Builder(this, NotifyLyrics.CHANNEL_ID)
            .setSmallIcon(NotifyLyrics.SMALL_ICON_RES)
            .setContentTitle("AutoLyrics")
            .setContentText("正在启动…")
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        // dataSync 是本 App 唯一符合的类型（后台同步歌词与进度）。
        // API 29 起foregroundServiceType 必须显式给出，否则抛异常。
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID_SERVICE, placeholder, type)
    }

    companion object {
        /** 与 [NotifyLyrics] 共用同一个通知 ID，前台服务的占位通知会被歌词覆盖。 */
        const val NOTIFICATION_ID_SERVICE = NotifyLyrics.NOTIFICATION_ID

        @Volatile
        private var running = false

        /**
         * 前台服务当前是否在运行。
         *
         * [NotifyLyrics] 用它决定「撤下歌词时是真的移除通知、
         * 还是改成占位内容」—— 前台服务在跑时必须走后者，
         * 否则服务失去前台身份、进程被回收，老问题立刻复发。
         *
         * 由 [onCreate] / [onStartCommand] 置 true、[onDestroy] 置 false，
         * 而不是只在 `ensureStarted` 里置 —— 后者只代表「发起过启动请求」，
         * 系统若拒绝启动（后台启动限制等），状态会与实际不符。
         */
        val isRunning: Boolean get() = running

        /**
         * 启动前台服务。幂等：已在前台运行时重复调用直接返回。
         *
         * 调用点放在 [org.eu.dinghongyu.autolyrics.App.onCreate] ——
         * 进程每次启动都跑一遍，保证「冷启动（完全没有 Activity）也有前台服务」。
         */
        fun ensureStarted(context: Context) {
            if (running) return
            val app = context.applicationContext
            val intent = Intent(app, LyricsForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            }
            // 刻意不在这里置 running = true：
            // 请求发出 ≠ 服务真的起来了。真实状态由 onCreate/onDestroy 维护，
            // 这样 ensureStarted 在服务已死时仍会重试，不会误判为「已在跑」。
        }
    }
}
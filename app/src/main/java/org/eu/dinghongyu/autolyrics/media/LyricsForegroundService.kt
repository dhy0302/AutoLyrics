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
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eu.dinghongyu.autolyrics.lyric.LyricEngine
import org.eu.dinghongyu.autolyrics.util.AppScope
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
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground 必须在 startService 之后 5 秒内调用，
        // 否则系统抛 ANR / ForegroundServiceDidNotStartInTimeException。
        // 所以这里先用占位内容把它顶上去，真正的歌词随后由
        // NotifyLyrics 的 collect 更新（同 ID，覆盖掉这条占位）。
        startForegroundCompat()
        running = true
        NotifyLyrics.attach(this)
        startWatchdog()
        // START_STICKY：进程被回收后系统会重新拉起并回调 onStartCommand，
        // 于是歌词链路能自愈。这是「后台被杀后自己恢复」的关键一环。
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        watchdogJob?.cancel()
        watchdogJob = null
        // 不主动 cancel 通知：它是用户可见的界面。
        // 服务被系统回收时让最后一条内容留着，比留一片空白更合理。
        super.onDestroy()
    }

    /**
     * v1.18.4：**看门狗** —— 定期检查播放轮询链路是否还活着，死了就重启它。
     *
     * ## 为什么前台服务自己不够
     *
     * v1.18.2 只保证了「进程不被冻结」，但进程活着 ≠ 协程活着。
     * [PlaybackMonitor] 的 ticker 里跑着跨进程 Binder 调用，播放器进程
     * 被系统回收时会抛 `DeadObjectException`。旧版循环体**没有任何 try**，
     * 异常直接终止协程 —— 而且是**静默**终止：不崩溃、不打日志。
     *
     * 于是症状与「进程被冻结」**完全一样**：歌词停在最后一句、切歌也不变、
     * 打开 App 就恢复。因为打开 App 时 [PlaybackMonitor.update] 会被
     * 手动调一次（[org.eu.dinghongyu.autolyrics.ui.MainActivity.onResume]），
     * 状态瞬间"活"过来 —— 这个假象把人一次次引向「进程被冻结」的错误方向。
     *
     * 真正的判据是心跳：[PlaybackMonitor.lastHeartbeatAt] 超过阈值不动
     * 就说明轮询停了，此时重启整条链路。
     *
     * ## 为什么阈值取 5秒
     *
     * 空闲档的轮询间隔是 [PlaybackMonitor] 的 IDLE_POLL_MS = 1000ms，
     * 播放档最快 50ms。取 5 秒意味着**连续 5 轮空闲轮询都没跑**
     * 才判定为死，足以排除偶发的系统调度延迟，又不会让用户等太久
     * （最坏情况下 5 秒后自动恢复）。
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        // v1.18.5：看门狗搬离 `Dispatchers.Main` —— 这是它此前完全失效的原因。
        //
        // v1.18.4 的看门狗跑在 `AppScope.main` 上，而它要检测的
        // [LyricEngine.startIndexLoop] 当时也在主线程。
        // **自愈机制与被自愈对象在同一根线程上**：
        // 主线程被系统限制时，两者一起停摆，看门狗压根没被执行过。
        //
        // 这就是「加了看门狗却一点用没有」的直接解释 ——
        // 不是判定逻辑写错了，是它一次都没跑起来。
        //
        // 放在 IO 上还有一个附带好处：[ensurePlaybackTickerAlive] 里会调
        // [MediaSessionWatcher.ensureStarted]，那是纯 Binder 链路操作，
        // 本就不该占主线程。
        watchdogJob = AppScope.io.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!running) return@launch
                runCatching { ensurePlaybackTickerAlive() }
            }
        }
    }

    /**
     * 心跳检查 + 必要时重启。
     *
     * 拆出来而不是内联在协程里，是为了让 [ensureStarted] 与看门狗
     * 能复用同一份判定逻辑。
     */
    private fun ensurePlaybackTickerAlive() {
        val now = SystemClock.elapsedRealtime()

        // ---- 第二级：歌词行下标计算（v1.18.5 新增）----
        //
        // 放在前面检查，因为它更靠近真正的症状：
        // 这一级停了，通知栏就冻住，而第一级心跳一切正常。
        // v1.18.4 只查了第一级，所以漏掉了它。
        //
        // 注意判定用「心跳停滞」而不是「indexRunning == false」：
        // 后者在暂停/ 没歌时也会是假阳性之外的情况，
        // 而心跳停滞对「协程已死」和「线程被节流」两种成因都成立 ——
        // 对后者来说，协程确实没在跑，重启它是**唯一正确的处置**。
        val idxBeat = LyricEngine.indexHeartbeatAt
        if (idxBeat != 0L && now - idxBeat > LyricEngine.INDEX_STALE_MS) {
            LyricEngine.startIndexLoop(AppScope.io)
        }

        // ---- 第一级：播放进度轮询（v1.18.4）----
        val last = PlaybackMonitor.lastHeartbeatAt
        // 从未跑过（刚创建）：不干预，让它自己起来。
        if (last == 0L) return
        if (now - last <= STALE_HEARTBEAT_MS) return

        // 心跳停滞 —— 判定轮询已死，重启。
        MediaSessionWatcher.ensureStarted(this)
        PlaybackMonitor.restartTicker(AppScope.io)
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

        /** v1.18.4：看门狗的检查间隔（毫秒）。 */
        private const val WATCHDOG_INTERVAL_MS = 3_000L

        /**
         * v1.18.4：心跳停滞多久判定轮询已死（毫秒）。
         *
         * 空闲档轮询间隔是 1000ms，连续 5 次没动才判死，
         * 既排除偶发调度延迟，又让最坏情况的恢复时间控制在这之内。
         */
        private const val STALE_HEARTBEAT_MS = 5_000L

        @Volatile
        private var running = false

        /** v1.18.4：看门狗协程。 */
        private var watchdogJob: Job? = null

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
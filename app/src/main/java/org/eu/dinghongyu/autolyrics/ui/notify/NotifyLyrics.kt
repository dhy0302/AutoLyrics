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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.eu.dinghongyu.autolyrics.R
import org.eu.dinghongyu.autolyrics.lyric.LyricEngine
import org.eu.dinghongyu.autolyrics.media.LyricsForegroundService
import org.eu.dinghongyu.autolyrics.util.AppScope
import org.eu.dinghongyu.autolyrics.util.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 通知栏：**只显示正在播放的歌曲信息**，不再显示歌词。
 *
 * ## v1.18.7：删掉「通知栏歌词」这个功能
 *
 * 这个功能从 v1.8 一直存在，但它带来的问题比价值大：
 *
 *  - **长期存在一个修不好的 bug。** 「切到别的 App 后歌词停住」从 v1.14
 *    前后开始出现，为它连发 v1.18.2 / 1.18.4 / 1.18.5 三版修复，
 *    三次判断全错（进程冻结 → 协程静默死亡 → 主线程被节流），
 *    最后靠v1.18.6 埋了一整版探针仍未定位。
 *  - **高频刷通知。** 歌词每 50~100ms 就要 notify 一次，
 *    而系统对通知有频率限制，也确实吵。
 *
 * 用户最终选择：通知栏只要歌曲信息，歌词交给桌面悬浮窗。
 *
 * ⇒ 「通知栏歌词」开关（`Settings.notificationEnabled`）**一并删除**，
 *   字段与持久化项（`notify`）不再读写，参照v1.12.6 删 `inAppEnabled`
 *   的做法——那也是个「关掉也没用」的开关。
 *
 * ## 保留的是什么
 *
 * **歌曲信息本身照旧显示，通知也照旧存在**，因为它同时是
 * [org.eu.dinghongyu.autolyrics.media.LyricsForegroundService] 的前台身份载体。
 * 撤掉它等于让服务失去前台身份、进程被系统回收，
 * 于是连桌面歌词的取词也会停 —— 那是更大的损失。
 *
 * 桌面歌词的遥控器（开启/关闭）也留在这个通知上，
 * 它是通知存在的第二个理由。
 *
 * ## 顺带解决的旧债
 *
 * v1.18.3 曾引入三形态（歌词 / 非歌词 / 占位）与两个去重键
 * （`lastText` / `lastTrackOnlyText`），形态之间要互相清对方的键，
 * 极易漏发。现在**只剩一种形态**，两个去重键一并删除。
 */
object NotifyLyrics {

    /**
     * v1.18.2：通知 ID 与渠道对外暴露，供 [org.eu.dinghongyu.autolyrics.media.LyricsForegroundService]
     * 复用。
     *
     * ## 为什么前台服务要用同一个 ID
     *
     * 前台服务必须挂一条通知，而用户不希望通知栏出现两条。
     * 共用同一个 ID 之后，[android.app.NotificationManager.notify] 会**更新**既有通知
     * 而不是新增一条 —— 于是前台服务的「AutoLyrics 正在启动…」占位内容
     * 会被后续的歌曲信息直接覆盖，通知栏最终只剩一条。
     *
     * 反过来代价是：[cancel] 也不能真撤这条通知了 ——
     * 它已被前台服务征用为保活依托，撤掉等于让服务失去前台身份。
     * 所以 [cancel] 改成「更新为占位内容」，详见它的 KDoc。
     *
     * 由此得到一个可预期的行为：**用户在系统设置里手动清掉这条通知，
     * 等于停掉前台服务**（他确实不想要这个功能），此后桌面歌词不再更新。
     */
    const val NOTIFICATION_ID = 2001
    const val CHANNEL_ID = "lyric_channel"

    /** 小图标资源，供前台服务的占位通知复用，避免两处各写一个 drawable。 */
    val SMALL_ICON_RES: Int = R.drawable.ic_music_note

    private var appContext: Context? = null
    private var manager: NotificationManager? = null
    private var job: Job? = null

    /**
     * v1.18.7：上一次发出的通知标题，用于去重。
     *
     * 原先有两个去重键（`lastText` 记歌词、`lastTrackOnlyText` 记歌名），
     * 且两者互相清对方以免漏发形态切换。现在只有一种形态，一个键就够。
     */
    private var lastTitle = ""

    /** v1.18.7：上次发通知时的桌面歌词开关状态——按钮文案随它翻转，故须参与去重。 */
    private var lastOverlayOn = false

    /**
     * 通知的 collect 协程是否在跑（诊断面板的「通知渲染」一行）。
     *
     * v1.18.7 保留了它：即使通知不再显示歌词，
     * 但它仍承担**前台服务身份载体**的职责，
     * 这个协程死了通知就停止更新 ⇒ 服务失去前台身份 ⇒ 进程被回收。
     * 那时桌面歌词也会跟着停，所以这个观测量依然有价值。
     */
    val jobRunning: Boolean get() = job?.isActive == true

    fun attach(context: Context) {
        val app = context.applicationContext
        appContext = app
        manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        createChannel()

        job?.cancel()
        // v1.18.7：collect 不再监听 LyricEngine.index，也不再拼歌词文本。
        //
        // ## 为什么连state 都要再 map 一层
        //
        // 直接订阅 `state` 会让这个 collect 被**歌词变化**唤醒 ——
        // State 里带着 lyric / attempts / message，歌词每隔几百毫秒换一行，
        // 整个 State 就不同，combine 随之发射。也就是说我们会在
        // 「每秒十几次」的空转里做十几次同样的去重判断。
        //
        // 只 `map { it.track }` 之后，唤醒源只剩
        // **换歌** 与 **悬浮窗开关变化** —— 频率降到接近零。
        //
        // 歌词仍然照常取词、照常进桌面悬浮窗，只是不再经过通知栏。
        //
        // 线程用 `AppScope.io`：notify() 与 PendingIntent.getBroadcast
        // 都要过 Binder，属于阻塞调用，不能留在主线程。
        job = AppScope.io.launch {
            combine(
                LyricEngine.state.map { it.track },
                SettingsStore.settings.map { it.overlayEnabled },
            ) { track, overlayOn -> track to overlayOn }
                .collect { (track, overlayOn) -> postTrackOnly(track, overlayOn) }
        }
    }

    fun detach() {
        job?.cancel()
        job = null
        cancel()
    }

    /**
     * 撤下通知。
     *
     * **v1.18.3 起只在两种情况下调用**：[detach]（App 真的不要通知了），
     * 以及前台服务没在跑时的兜底。
     *
     * 日常的「歌词关闭」「暂停」都走 [postTrackOnly]——
     * 通知必须留着当桌面歌词的遥控器，不能撤。
     *
     * ## 为什么前台服务在跑时不能真正撤下
     *
     * [org.eu.dinghongyu.autolyrics.media.LyricsForegroundService] 靠这条通知
     * 才拿得到「前台」身份 —— 一旦 `manager.cancel()` 把它移除，
     * 系统会认为服务没有前台通知，轻则警告，重则直接回收进程，
     * 于是又回到「后台冻结、歌词僵死」的老问题。
     */
    private fun cancel() {
        val mgr = manager
        val ctx = appContext
        if (LyricsForegroundService.isRunning && ctx != null) {
            // 前台服务在跑时，把通知**改成占位内容**而不是移除 ——
            // 它是服务保持前台身份的唯一依托，移走等于自断保活。
            runCatching {
                mgr?.notify(
                    NOTIFICATION_ID,
                    baseBuilder(ctx, "AutoLyrics 运行中")
                        .setOngoing(true)
                        .setCategory(Notification.CATEGORY_SERVICE)
                        .build(),
                )
            }
        } else {
            runCatching { mgr?.cancel(NOTIFICATION_ID) }
        }
        resetDedup()
    }

    /** 清空去重键，强制下次 collect 一定重发通知。 */
    private fun resetDedup() {
        lastTitle = ""
        lastOverlayOn = false
    }

    /**
     * v1.18.7：唯一的通知形态 —— 标题「歌名 - 歌手」。
     *
     * v1.18.3 时它叫「不显示歌词形态」，是三种形态之一。
     * 现在歌词功能整个删掉，它就成了**唯一**形态，
     * 于是名字里的「TrackOnly」已无意义（不再有「另一种」形态）。
     *
     * 副标题改成专辑名（若有）—— 原来写的是「通知栏歌词已关闭」，
     * 那句话现在会让人困惑：明明歌词功能都没了，还提示「已关闭」。
     */
    private fun postTrackOnly(
        track: org.eu.dinghongyu.autolyrics.data.TrackInfo?,
        overlayOn: Boolean,
    ) {
        val context = appContext ?: return
        val title = track?.let { "${it.title} - ${it.artist}" } ?: "AutoLyrics 运行中"

        // v1.18.7：去重键只剩「标题 + 悬浮窗开关」。
        // 曾经还要比对透明与锁定两个状态，是因为通知栏有三个按钮、
        // 文案都随它们翻转；现在只剩一个按钮，歌词状态也不再影响文案。
        if (title == lastTitle && overlayOn == lastOverlayOn) return
        lastTitle = title
        lastOverlayOn = overlayOn

        val builder = baseBuilder(context, title)
            // 副标题用专辑名（若有）。原来这里写的是「通知栏歌词已关闭」，
            // 那句话现在会让人困惑：歌词功能都删了，还提示「已关闭」。
            .setContentText(track?.album?.takeIf { it.isNotBlank() })
            .setOngoing(false)
            .setCategory(Notification.CATEGORY_STATUS)
        addOverlayActions(builder, overlayOn)
        runCatching { manager?.notify(NOTIFICATION_ID, builder.build()) }
    }

    /**
     * 两种形态共用的通知骨架：小图标 + 标题 + **点击跳歌词页**。
     *
     * v1.18.3 新增 [setContentIntent] —— 之前通知没有点击行为，
     * 点了什么都不会发生。目标必须是「歌词页」而不是「打开 App」：
     * App 可能在后台某个二级页上，只启动 Activity 会停在那里。
     */
    private fun baseBuilder(context: Context, title: String): Notification.Builder =
        Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(SMALL_ICON_RES)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(lyricsPageIntent(context))

    /**
     * 点通知 → 跳到歌词页的 [PendingIntent]。
     *
     * ## FLAG_ACTIVITY_SINGLE_TOP 是必需的
     *
     * [org.eu.dinghongyu.autolyrics.ui.MainActivity] 声明为 `singleTop`，
     * 带这个 flag 才能在 App 已运行时走 `onNewIntent` 而不是重建 Activity。
     * 配合 MainActivity 里的 `handleGotoLyrics` 覆盖冷启动与复用两条路径。
     */
    private fun lyricsPageIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            Intent(context, org.eu.dinghongyu.autolyrics.ui.MainActivity::class.java)
                .setAction(org.eu.dinghongyu.autolyrics.ui.MainActivity.EXTRA_GOTO_LYRICS)
                .putExtra(
                    org.eu.dinghongyu.autolyrics.ui.MainActivity.EXTRA_GOTO_LYRICS,
                    true,
                )
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * 桌面歌词开关。**v1.18.7 起通知栏只剩这一个按钮。**
     *
     * 原先有三个：开启/关闭桌面歌词、歌词背景透明/不透明、锁定/解锁。
     * 后两个已迁进悬浮窗内部（见 [org.eu.dinghongyu.autolyrics.ui.overlay.OverlayContent]）。
     *
     * 锁定这个按钮曾**必须**放在通知栏，因为锁定后悬浮窗
     * `FLAG_NOT_TOUCHABLE` 点不动自己。现在解锁入口搬进了悬浮窗，
     * 锁定时另开一个独立小窗，所以通知栏不再需要它。
     *
     * 文案是**双向**的：开着给「关闭桌面歌词」，关着给「开启桌面歌词」——
     * 用户不用先判断当前状态。
     */
    private fun addOverlayActions(
        builder: Notification.Builder,
        overlayOn: Boolean,
    ) {
        val context = appContext ?: return
        builder.addAction(
            Notification.Action.Builder(
                null,
                if (overlayOn) "关闭桌面歌词" else "开启桌面歌词",
                broadcast(context, if (overlayOn) 1001 else 1002, OverlayActionReceiver.ACTION_TOGGLE_OVERLAY),
            ).build(),
        )
    }

    /**
     * 造一条指向 [OverlayActionReceiver] 的 [PendingIntent]。
     *
     * ## requestCode 必须逐按钮不同
     *
     * `PendingIntent` 靠 `(requestCode, action)` 判定是否同一个，
     * 而 `FLAG_UPDATE_CURRENT` 会**就地替换** Extras。
     * 若几个按钮复用同一组码，后发的会把先发的覆盖掉，
     * 结果就是所有按钮都触发同一个动作。
     * v1.18.7 起只剩开启/关闭桌面歌词一组（1001/1002）——
     * 透明与锁定两个按钮已迁进悬浮窗。
     */
    private fun broadcast(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, OverlayActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createChannel() {
        val nm = manager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "播放信息", NotificationManager.IMPORTANCE_LOW).apply {
            // v1.18.7：通知不再显示歌词，只报当前播的是什么歌。
            // 文案必须跟着改，否则系统设置里会看到
            // 一个叫「播放信息」却写着「实时显示当前播放的歌词」的渠道。
            description = "显示当前正在播放的歌曲，并提供桌面歌词开关"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        runCatching { nm.createNotificationChannel(channel) }
    }

    /**
     * v1.18.3：点击通知（[lyricsPageIntent]）用的 requestCode。
     *
     * 单独一个常量而不是复用 1001~1006：
     * 那是 [broadcast] 的区间，两边混用会让 Action 与点通知互相覆盖。
     * 2000 段留空，与 [NOTIFICATION_ID] 的 2001 也不冲突。
     */
    private const val REQUEST_CONTENT = 2000
}

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
import org.eu.dinghongyu.autolyrics.media.PlaybackMonitor
import org.eu.dinghongyu.autolyrics.util.AppScope
import org.eu.dinghongyu.autolyrics.util.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 通知栏歌词。
 *
 * 只在「歌词行真的变了」时才更新通知：系统对高频 notify 有限流，
 * 每 50~100ms 刷一次会被丢弃甚至被判定为异常行为。
 *
 * ## v1.18.2：同时也是前台服务的通知载体
 *
 * [org.eu.dinghongyu.autolyrics.media.LyricsForegroundService] 与本对象
 * **共用同一个通知 ID** —— 前台服务必须有通知才能保持前台身份，
 * 而额外再发一条会污染通知栏。于是这里既当歌词通知、
 * 也当服务保活的通知：撤下歌词时改成占位内容而**不移除**。
 *
 * ## v1.18.3：「通知栏歌词」只管歌词，**不管通知的存在**
 *
 * 这个开关关掉后，通知**仍然存在**，只是不显示歌词：
 *标题是「歌名 - 歌手」，三个桌面歌词控件照常可用。
 *
 * 之前不是这样—— 关闭时整条通知退化成一句「通知栏歌词已关闭」，
 * 歌名、歌手、三个按钮全都没有了。等于把「关掉歌词」做成了
 * 「关掉整个通知栏遥控器」，这不合理：
 *
 *  - 用户常常就是想留着遥控器，只是不想让歌词占通知栏；
 *  - 前台服务还指着这条通知，彻底清掉等于自断保活（见[cancel]）。
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
     * 会被后续的歌词内容直接覆盖，通知栏最终只剩一条、就是当前歌词。
     *
     * 反过来代价是：[cancel] 也不能真撤这条通知了 ——
     * 它已被前台服务征用为保活依托，撤掉等于让服务失去前台身份。
     * 所以 [cancel] 改成「更新为占位内容」，详见它的 KDoc。
     *
     * 由此得到一个可预期的行为：**用户在系统设置里手动清掉这条通知，
     * 等于停掉前台服务**（他确实不想要这个功能），此后台外歌词不再更新。
     */
    const val NOTIFICATION_ID = 2001
    const val CHANNEL_ID = "lyric_channel"

    /** 小图标资源，供前台服务的占位通知复用，避免两处各写一个 drawable。 */
    val SMALL_ICON_RES: Int = R.drawable.ic_music_note

    private var appContext: Context? = null
    private var manager: NotificationManager? = null
    private var job: Job? = null

    /**
     * v1.18.5：collect 协程是否在跑（诊断面板的第三级）。
     *
     * 前两版面板只观测「播放进度」与「歌词行下标」，
     * 而通知其实还有第三级 —— 组装并发出通知。
     * 前两级全绿但通知仍不动时，只有这一行能指出问题在渲染层。
     */
    val jobRunning: Boolean get() = job?.isActive == true

    /** 上一次发出的文本，用于去重。 */
    private var lastText = ""

    /** 上一次发通知时的悬浮窗开关状态，也参与去重（见 collect 里的注释）。 */
    private var lastOverlayOn = true

    /** 上一次发通知时的透明背景状态，同样参与去重——第二个按钮的文案随它翻转。 */
    private var lastTransparent = false

    /**
     * v1.18.3：上一次发通知时的锁定状态。
     *
     * 同样参与去重——第三个按钮的文案随它翻转
     * （「锁定桌面歌词」↔「解锁桌面歌词」）。
     */
    private var lastLocked = false

    /**
     * 三个悬浮窗开关是否都与上次发通知时一致。
     *
     * 抽出来是因为**两条发通知的路径**（[post] 与 [postTrackOnly]）
     * 都要做这个判断，写两遍迟早会漏掉其中一个 ——
     * 而漏掉的后果是「改了设置通知却不更新」，很难察觉。
     */
    private fun sameSwitches(s: org.eu.dinghongyu.autolyrics.util.Settings): Boolean =
        s.overlayEnabled == lastOverlayOn &&
            s.overlayTransparentBg == lastTransparent &&
            s.overlayLocked == lastLocked

    /** 记住当前的三个开关状态，供 [sameSwitches] 下次比较。 */
    private fun rememberSwitches(s: org.eu.dinghongyu.autolyrics.util.Settings) {
        lastOverlayOn = s.overlayEnabled
        lastTransparent = s.overlayTransparentBg
        lastLocked = s.overlayLocked
    }

    /**
     * v1.18.3：上次发「非歌词形态」通知时的标题，用于跨形态去重。
     *
     * 需要它的原因：歌词形态与非歌词形态**可能算出同一个标题**
     * （例如正在播放的歌词恰好就叫「等待播放…」这类极端情况，
     * 或者歌词未取到时两者都显示歌名）。若只看 [lastText]，
     * 从形态 A 切到形态 B 时可能被误判成「没变」而漏发通知。
     */
    private var lastTrackOnlyText = ""

    fun attach(context: Context) {
        val app = context.applicationContext
        appContext = app
        manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        createChannel()

        job?.cancel()
        // v1.18.5：这条 collect 搬离`Dispatchers.Main`。
        //
        // 它是通知栏歌词流水线的**最后一级** —— 上游（positionMs → index）
        // 就算全部正常，这一级若停在主线程上没被调度，通知就永远停在最后一句。
        // 而症状与「上游协程死了」一模一样：切歌不变、进 App 就好。
        //
        // 用 `Dispatchers.IO` 而非 `Main`：这里做的是 Notification 构造与
        // `notify()`，其中 `PendingIntent.getBroadcast` 与 `notify` 都要过
        // Binder，属于阻塞调用。
        //
        // **安全性**：`post` / `postTrackOnly` 内部只碰
        // NotificationManager 与几个局部去重字段，不触碰任何 UI 状态；
        // 去重字段（lastText 等）由这个唯一的 collect 独占写入，
        // 换线程不引入新的并发写。
        job = AppScope.io.launch {
            combine(
                LyricEngine.state,
                LyricEngine.index,
                PlaybackMonitor.isPlaying,
                SettingsStore.settings,
            ) { state, index, playing, settings -> Quad(state, index, playing, settings) }
                .collect { (state, index, playing, settings) ->
                    // v1.18.3：通知**恒在**，三种形态，区别只在标题内容。
                    //
                    //  1) 歌词开着 + 有播放 → 标题是当前歌词
                    //  2) 歌词关着→ 标题是「歌名 - 歌手」（控件照常可用）
                    //  3) 暂停/ 没歌      → 标题是「歌名 - 歌手」（或等待播放）
                    //
                    // 2 和 3 曾经走[cancel]()，把整条通知收成一句占位文案——
                    // 用户要的恰恰相反：遥控器要一直在。
                    val paused = !playing && settings.autoHideOnPause

                    if (!settings.notificationEnabled) {
                        postTrackOnly(state, settings)
                        return@collect
                    }
                    if (paused && state.track == null) {
                        postTrackOnly(state, settings)
                        return@collect
                    }

                    val line = state.lyric?.lines?.getOrNull(index)
                    val text = when {
                        !line?.text.isNullOrBlank() -> line!!.text
                        state.lyric?.instrumental == true -> "纯音乐，请欣赏"
                        state.status == LyricEngine.Status.LOADING -> "正在获取歌词…"
                        state.track != null -> "《${state.track.title}》未找到歌词"
                        else -> ""
                    }
                    if (text.isBlank()) {
                        postTrackOnly(state, settings)
                        return@collect
                    }
                    // v1.8.1 起：去重键必须包含所有会影响按钮文案的状态。
                    // 三个按钮的文案都随状态翻转（「关闭…」↔「打开…」、
                    // 「歌词背景透明」↔「歌词背景不透明」、锁定 ↔ 解锁），
                    // 若只按歌词文本去重，用户在**设置页**改了这些开关后
                    // 通知不会重发，按钮就一直停在旧文案上 ——
                    // 显示的和实际的状态对不上，点了会发生意料之外的事。
                    if (text == lastText && sameSwitches(settings)) {
                        return@collect
                    }
                    lastText = text
                    rememberSwitches(settings)

                    val translation = line?.translation?.takeIf { settings.showTranslation }
                    post(text, state, translation, settings)
                }
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

    /** 清空全部去重键，强制下次 collect 一定重发通知。 */
    private fun resetDedup() {
        lastText = ""
        lastTrackOnlyText = ""
        lastOverlayOn = true
        lastTransparent = false
        lastLocked = false
    }

    /**
     * v1.18.3：**不显示歌词**形态的通知 —— 标题是「歌名 - 歌手」，
     * 三个桌面歌词控件与点击跳转全部照常可用。
     *
     * 覆盖三种情况：
     *  - 用户把「通知栏歌词」关掉了；
     *  - 暂停且没有正在播放的曲目；
     *  - 歌词取不到（空歌词/ 纯音乐之外拿不到内容）。
     *
     * 与 [post] 的唯一区别就是标题；控件、点击行为、去重逻辑都一致。
     * 之所以复用同一套 [addOverlayActions]，是让「关掉歌词」不等于
     * 「失去遥控器」—— 用户常常就是想要遥控器而不想要歌词占屏。
     */
    private fun postTrackOnly(
        state: LyricEngine.State,
        settings: org.eu.dinghongyu.autolyrics.util.Settings,
    ) {
        val context = appContext ?: return
        val track = state.track
        val title = when {
            track != null -> "${track.title} - ${track.artist}"
            state.status == LyricEngine.Status.LOADING -> "正在获取歌曲信息…"
            else -> "AutoLyrics 运行中"
        }

        // 跨形态去重：标题与三个开关都没变就跳过。
        if (title == lastTrackOnlyText && sameSwitches(settings)) return
        lastTrackOnlyText = title
        // 与歌词形态互斥：写这边就要清那边，反之亦然，
        // 否则「歌词文本恰好等于歌名」时会漏发形态切换的那次通知。
        lastText = ""
        rememberSwitches(settings)

        val builder = baseBuilder(context, title)
            .setContentText(if (track != null) "通知栏歌词已关闭" else null)
            .setOngoing(false)
            .setCategory(Notification.CATEGORY_STATUS)
        addOverlayActions(builder, settings)
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
     * 三个悬浮窗控件。两种通知形态共用。
     *
     * 全部走 [OverlayActionReceiver] 广播而不走悬浮窗本身，
     * 因为锁定后悬浮窗是点击穿透的（FLAG_NOT_TOUCHABLE），
     * 只有广播能保证「锁了也能点」。
     *
     * 所有文案都是**双向**的：写「点一下会发生什么」，
     * 用户不用先判断当前状态。
     */
    private fun addOverlayActions(
        builder: Notification.Builder,
        settings: org.eu.dinghongyu.autolyrics.util.Settings,
    ) {
        val context = appContext ?: return

        // 按钮一：桌面悬浮窗开关（开着给「关闭」，关着给「打开」）。
        val overlayOn = settings.overlayEnabled
        builder.addAction(
            Notification.Action.Builder(
                null,
                if (overlayOn) "关闭桌面歌词" else "开启桌面歌词",
                broadcast(context, if (overlayOn) 1001 else 1002, OverlayActionReceiver.ACTION_TOGGLE_OVERLAY),
            ).build(),
        )

        // 按钮二：悬浮窗透明背景（当前不透明 →「歌词背景透明」，反之亦然）。
        // 与设置页的「透明背景」是同一个开关，两边状态同步。
        val transparent = settings.overlayTransparentBg
        builder.addAction(
            Notification.Action.Builder(
                null,
                if (transparent) "歌词背景不透明" else "歌词背景透明",
                broadcast(context, if (transparent) 1003 else 1004, OverlayActionReceiver.ACTION_TOGGLE_TRANSPARENT_BG),
            ).build(),
        )

        // 按钮三：锁定/解锁（v1.18.3 新增）。
        //
        // 锁定后悬浮窗自己点不动了，这个按钮是通知栏侧唯一的解锁入口 ——
        // 这也是它必须存在的原因，不是「顺手加的第三个」。
        val locked = settings.overlayLocked
        builder.addAction(
            Notification.Action.Builder(
                null,
                if (locked) "解锁桌面歌词" else "锁定桌面歌词",
                broadcast(context, if (locked) 1005 else 1006, OverlayActionReceiver.ACTION_TOGGLE_LOCK),
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
     * 现有四组已占1001~1004，锁定用 1005/1006。
     */
    private fun broadcast(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, OverlayActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * 「显示歌词」形态的通知 —— 标题是当前这句歌词，
     * 副标题带译文与「歌名 - 歌手」，控件与点击行为与 [postTrackOnly] 完全一致。
     */
    private fun post(
        text: String,
        state: LyricEngine.State,
        translation: String?,
        settings: org.eu.dinghongyu.autolyrics.util.Settings,
    ) {
        val context = appContext ?: return
        val track = state.track

        // 与 postTrackOnly 互斥：写这边就要清那边。
        lastTrackOnlyText = ""

        val builder = baseBuilder(context, text)
            .setContentText(
                buildString {
                    if (translation != null) append(translation).append(" · ")
                    if (track != null) append(track.title).append(" - ").append(track.artist)
                }.ifBlank { null },
            )
            // v1.8.1：不再显示歌词源名。原 setSubText 会显示「网易云音乐」这类标签，
            // 但对用户来说来源没有决策价值——他要的只是歌词本身，
            // 源的信息在「歌词源」排查页能看到，不该占通知栏的宝贵空间。
            .setOngoing(false)
            .setCategory(Notification.CATEGORY_STATUS)
        addOverlayActions(builder, settings)
        runCatching { manager?.notify(NOTIFICATION_ID, builder.build()) }
    }

    private fun createChannel() {
        val nm = manager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "歌词", NotificationManager.IMPORTANCE_LOW).apply {
            description = "实时显示当前播放的歌词"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        runCatching { nm.createNotificationChannel(channel) }
    }

    /** combine 的四元组载体：把四个流打包后统一处理。 */
    private data class Quad<A, B, C, D>(
        val state: A,
        val index: B,
        val playing: C,
        val settings: D,
    )

    /**
     * v1.18.3：点击通知（[lyricsPageIntent]）用的 requestCode。
     *
     * 单独一个常量而不是复用 1001~1006：
     * 那是 [broadcast] 的区间，两边混用会让 Action 与点通知互相覆盖。
     * 2000 段留空，与 [NOTIFICATION_ID] 的 2001 也不冲突。
     */
    private const val REQUEST_CONTENT = 2000
}

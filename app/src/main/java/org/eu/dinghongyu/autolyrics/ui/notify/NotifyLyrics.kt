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

    /** 上一次发出的文本，用于去重。 */
    private var lastText = ""

    /** 上一次发通知时的悬浮窗开关状态，也参与去重（见 collect 里的注释）。 */
    private var lastOverlayOn = true

    /** 上一次发通知时的透明背景状态，同样参与去重——第二个按钮的文案随它翻转。 */
    private var lastTransparent = false

    /**
     * v1.18.2：撤下歌词时给前台服务占位通知用的文案。
     *
     * 让占位通知带上有意义的文字（歌名 / 暂停 / 加载中），
     * 而不是干巴巴一句「运行中」—— 用户看到的仍是「这首歌的状态」，
     * 不会以为App 出问题了。
     */
    @Volatile
    private var foregroundNoticeText: String? = null

    fun attach(context: Context) {
        val app = context.applicationContext
        appContext = app
        manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        createChannel()

        job?.cancel()
        job = AppScope.main.launch {
            combine(
                LyricEngine.state,
                LyricEngine.index,
                PlaybackMonitor.isPlaying,
                SettingsStore.settings,
            ) { state, index, playing, settings -> Quad(state, index, playing, settings) }
                .collect { (state, index, playing, settings) ->
                    if (!settings.notificationEnabled) {
                        // 前台服务还指着这条通知，不能真撤 —— 撤了进程会被回收，
                        // 下次用户开通知栏歌词时又得重新拉起服务。
                        foregroundNoticeText = "通知栏歌词已关闭"
                        cancel()
                        return@collect
                    }
                    if (!playing && settings.autoHideOnPause && state.track == null) {
                        foregroundNoticeText = null
                        cancel()
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
                        cancel()
                        return@collect
                    }
                    // v1.8.1 起：去重键必须包含所有会影响按钮文案的状态。
                    // 两个按钮的文案都随状态翻转（「关闭…」↔「打开…」、
                    // 「歌词背景透明」↔「歌词背景不透明」），
                    // 若只按歌词文本去重，用户在**设置页**改了这些开关后
                    // 通知不会重发，按钮就一直停在旧文案上 ——
                    // 显示的和实际的状态对不上，点了会发生意料之外的事。
                    if (text == lastText &&
                        settings.overlayEnabled == lastOverlayOn &&
                        settings.overlayTransparentBg == lastTransparent
                    ) {
                        return@collect
                    }
                    lastText = text
                    lastOverlayOn = settings.overlayEnabled
                    lastTransparent = settings.overlayTransparentBg

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
     * v1.18.2：**前台服务在跑时不能真正撤下**。
     *
     * [org.eu.dinghongyu.autolyrics.media.LyricsForegroundService] 靠这条通知
     * 才拿得到「前台」身份 —— 一旦 `manager.cancel()` 把它移除，
     * 系统会认为服务没有前台通知，轻则警告，重则直接回收进程，
     * 于是又回到「后台冻结、歌词僵死」的老问题。
     *
     * 所以这里在取消前先把内容改成一条中性的占位
     * （标题保持可用、内容说明当前状态），**更新**而不是**移除**：
     * 通知还在前台服务手里，只是内容不再是歌词。
     *
     * 只重置去重键、不动通知本身是不够的 ——
     * 那样用户会一直看到停住的最后一句，正是本次要修的 bug。
     */
    private fun cancel() {
        // 前台服务在跑时，把通知**改成占位内容**而不是移除 ——
        // 它是服务保持前台身份的唯一依托，移走等于自断保活。
        val ctx = appContext
        val mgr = manager
        if (LyricsForegroundService.isRunning && ctx != null && mgr != null) {
            runCatching {
                mgr.notify(
                    NOTIFICATION_ID,
                    Notification.Builder(ctx, CHANNEL_ID)
                        .setSmallIcon(SMALL_ICON_RES)
                        .setContentTitle(contextTitleForIdle())
                        .setOngoing(true)
                        .setShowWhen(false)
                        .setCategory(Notification.CATEGORY_SERVICE)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .build(),
                )
            }
        } else {
            runCatching { mgr?.cancel(NOTIFICATION_ID) }
        }
        lastText = ""
        lastOverlayOn = true
        lastTransparent = false
    }

    /** 占位通知的标题：有歌名就带歌名，否则只说明 App 在运行。 */
    private fun contextTitleForIdle(): String =
        if (foregroundNoticeText.isNullOrBlank()) "AutoLyrics 运行中" else foregroundNoticeText!!

    private fun post(
        text: String,
        state: LyricEngine.State,
        translation: String?,
        settings: org.eu.dinghongyu.autolyrics.util.Settings,
    ) {
        val context = appContext ?: return
        val track = state.track

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(text)
            .setContentText(
                buildString {
                    if (translation != null) append(translation).append(" · ")
                    if (track != null) append(track.title).append(" - ").append(track.artist)
                }.ifBlank { null }
            )
            // v1.8.1：不再显示歌词源名。原setSubText 会显示「网易云音乐」这类标签，
            // 但对用户来说来源没有决策价值——他要的只是歌词本身，
            // 源的信息在「歌词源」排查页能看到，不该占通知栏的宝贵空间。
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        // 按钮一：桌面悬浮窗开关（双向。开着给「关闭」，关着给「打开」）。
        // 走广播而非悬浮窗本身，所以锁定与否都能点。
        val overlayOn = settings.overlayEnabled
        val togglePi = PendingIntent.getBroadcast(
            context,
            if (overlayOn) 1001 else 1002,
            Intent(context, OverlayActionReceiver::class.java)
                .setAction(OverlayActionReceiver.ACTION_TOGGLE_OVERLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(
            Notification.Action.Builder(
                null,
                if (overlayOn) "关闭桌面歌词" else "开启桌面歌词",
                togglePi,
            ).build(),
        )

        // 按钮二：悬浮窗透明背景（双向。
        // 当前不透明 →「歌词背景透明」；当前透明 →「歌词背景不透明」。
        // 与设置页的「透明背景」是同一个开关，两边状态同步。
        //
        // requestCode 用 1003/1004，与上面 1001/1002 区分开：
        // PendingIntent 靠 (requestCode + action) 判定是否同一个，
        // 若复用同一组码，FLAG_UPDATE_CURRENT 会让两个按钮互相覆盖。
        val transparent = settings.overlayTransparentBg
        val bgPi = PendingIntent.getBroadcast(
            context,
            if (transparent) 1003 else 1004,
            Intent(context, OverlayActionReceiver::class.java)
                .setAction(OverlayActionReceiver.ACTION_TOGGLE_TRANSPARENT_BG),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(
            Notification.Action.Builder(
                null,
                if (transparent) "歌词背景不透明" else "歌词背景透明",
                bgPi,
            ).build(),
        )

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
}

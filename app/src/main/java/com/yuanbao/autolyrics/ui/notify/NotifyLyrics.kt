package com.yuanbao.autolyrics.ui.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.yuanbao.autolyrics.R
import com.yuanbao.autolyrics.lyric.LyricEngine
import com.yuanbao.autolyrics.media.PlaybackMonitor
import com.yuanbao.autolyrics.util.AppScope
import com.yuanbao.autolyrics.util.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 通知栏歌词。
 *
 * 只在「歌词行真的变了」时才更新通知：系统对高频 notify 有限流，
 * 每 50~100ms 刷一次会被丢弃甚至被判定为异常行为。
 */
object NotifyLyrics {

    private const val NOTIFICATION_ID = 2001
    private const val CHANNEL_ID = "lyric_channel"

    private var appContext: Context? = null
    private var manager: NotificationManager? = null
    private var job: Job? = null

    /** 上一次发出的文本，用于去重。 */
    private var lastText = ""

    /** 上一次发通知时的悬浮窗开关状态，也参与去重（见 collect 里的注释）。 */
    private var lastOverlayOn = true

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
                        cancel()
                        return@collect
                    }
                    if (!playing && settings.autoHideOnPause && state.track == null) {
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
                    // v1.8.1：去重键必须包含 overlayEnabled。
                    // 按钮文案随它翻转（「关闭…」↔「打开…」），
                    // 如果只按歌词文本去重，用户在设置里改了悬浮窗开关后
                    // 通知不会重发，按钮就一直停在旧文案上。
                    if (text == lastText && settings.overlayEnabled == lastOverlayOn) {
                        return@collect
                    }
                    lastText = text
                    lastOverlayOn = settings.overlayEnabled

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

    private fun cancel() {
        runCatching { manager?.cancel(NOTIFICATION_ID) }
        lastText = ""
        lastOverlayOn = true
    }

    private fun post(
        text: String,
        state: LyricEngine.State,
        translation: String?,
        settings: com.yuanbao.autolyrics.util.Settings,
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

        // 桌面悬浮窗开关：双向。开启时给「关闭」，关闭时给「打开」。
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
                if (overlayOn) "关闭桌面歌词悬浮窗" else "打开桌面歌词悬浮窗",
                togglePi,
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

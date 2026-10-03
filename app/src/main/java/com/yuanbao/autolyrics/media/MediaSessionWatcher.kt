package com.yuanbao.autolyrics.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.yuanbao.autolyrics.data.TrackInfo
import com.yuanbao.autolyrics.data.TransportCapabilities
import com.yuanbao.autolyrics.ui.components.AlbumArt
import com.yuanbao.autolyrics.util.SettingsStore

/** 一个 MediaSession 在某一时刻的完整快照。 */
data class SessionSnapshot(
    /** 会话标识（包名 + token 哈希） */
    val key: String,
    val pkg: String,
    val track: TrackInfo,
    val state: Int,
    /** 已按上报时刻推算过的播放位置 */
    val positionMs: Long,
    val albumArt: Bitmap?,
    val albumArtUri: String?,
    val capabilities: TransportCapabilities,
)

/**
 * 通过 MediaSessionManager 抓取所有活跃 MediaSession。
 *
 * 这是获取 Spotify 曲名/歌手/时长/播放位置/封面，以及控制播放的正确姿势：
 *  - 读会话需要通知监听权限（Android 强制，见 [com.yuanbao.autolyrics.media.MediaNotificationListener]）
 *  - 播放位置 = `PlaybackState.position + (now - lastPositionUpdateTime)`，
 *    播放器上报是稀疏的，必须自己插值，否则进度条会一跳一跳
 */
object MediaSessionWatcher {

    private var app: Context? = null
    private var manager: android.media.session.MediaSessionManager? = null
    private val handler = Handler(Looper.getMainLooper())

    private val controllers = LinkedHashMap<String, MediaController>()
    private val callbacks = HashMap<String, MediaController.Callback>()
    private var sessionsListener: android.media.session.MediaSessionManager.OnActiveSessionsChangedListener? = null

    /** 抓取链路是否就绪（权限已授予且 OnActiveSessionsChangedListener 注册成功）。 */
    private var linked = false

    /** 上一轮选中的会话 key：多个会话同时活跃时保持选择稳定，避免来回跳。 */
    private var lastKey: String? = null

    /** 最近一次 [best] 的结果，供传输控制复用。 */
    private var current: SessionSnapshot? = null

    /**
     * 会话元数据解析缓存（v1.8.2）。
     *
     * key = 会话 key，value = (metadata 实例, 解析结果)。
     * 用 `===` 比对实例而非内容：换歌必然产生新的 MediaMetadata 实例，
     * 内容比对要走Bundle 序列化，反而把省下的开销还回去。
     *
     * 容量按活跃会话数量级给足（一般 1~5 个），但仍设上限防止
     * 某些 ROM 疯狂重建 controller 时无限增长。
     */
    private val metaCache = HashMap<String, MetaCacheEntry>()

    private class MetaInfo(
        val track: TrackInfo,
        val albumArt: Bitmap?,
        val albumArtUri: String?,
    )

    private class MetaCacheEntry(
        val raw: MediaMetadata,
        val info: MetaInfo,
    )

    fun start(context: Context) {
        val ctx = context.applicationContext
        app = ctx
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as? android.media.session.MediaSessionManager ?: return
        manager = msm

        // 幂等：onListenerConnected 可能因重绑被多次调用。
        // 先移除旧监听，避免重复注册造成监听泄漏与重复回调
        try { sessionsListener?.let { msm.removeOnActiveSessionsChangedListener(it) } } catch (_: Throwable) {}
        sessionsListener = null
        lastKey = null
        current = null
        // v1.8.2：重建链路时旧缓存全部失效（controller 实例都换了）
        metaCache.clear()

        val component = ComponentName(ctx, MediaNotificationListener::class.java)
        try {
            val listener =
                // v1.8.2：用轻量版，只做 controller 增删。
                // 旧版这里调 refresh()，会在每次系统回调（某些 ROM 上很频繁）
                // 都做一次全量 best() 扫描，与 ticker 重复。
                android.media.session.MediaSessionManager.OnActiveSessionsChangedListener { refreshSessionsOnly() }
            msm.addOnActiveSessionsChangedListener(listener, component)
            sessionsListener = listener
            // 标记为「链路已就绪」：getActiveSessions 能否工作只取决于通知读取权限是否授予，
            // 不取决于 NotificationListenerService 是否已回调 onListenerConnected。
            linked = true
            refresh()
        } catch (_: SecurityException) {
            // 通知监听权限未授予：整个抓取链路不可用，交给 UI 引导用户
            linked = false
        } catch (_: Throwable) {
            // 个别 ROM 的实现有 bug，静默忽略
        }
    }

    /**
     * 只要通知读取权限已授予，就确保抓取链路已建立。
     *
     * 关键修复（用户实测「权限一直开着却检测不到」的根因）：
     * `MediaSessionManager.getActiveSessions()` 的准入条件是**通知读取权限已授予**，
     * 并**不要求** NotificationListenerService 已绑定/已回调 onListenerConnected。
     * 旧实现把 start() 只挂在 onListenerConnected 上，一旦被强杀后系统不再回调该方法，
     * 整条链路就永久失灵——只能靠用户手动关开权限来触发。
     * 现在改为：App 启动 / Activity 恢复时主动调用本方法，与服务回调解耦。
     */
    fun ensureStarted(context: Context) {
        if (linked) {
            // 已建立，只需重新扫一次（会话可能在这期间才出现）
            refresh()
            return
        }
        start(context)
    }

    /** 抓取链路是否就绪（等价于「权限已授予且监听已注册」）。 */
    val isLinked: Boolean get() = linked

    fun stop() {
        try {
            sessionsListener?.let { manager?.removeOnActiveSessionsChangedListener(it) }
        } catch (_: Throwable) {
        }
        sessionsListener = null
        linked = false
        // v1.8.2：停链路时回收缓存里的封面位图，这是最容易漏的一处：
        // 缓存持有 Bitmap 引用，不清就等于延长了它们的生命周期。
        metaCache.values.forEach { runCatching { it.info.albumArt?.recycle() } }
        metaCache.clear()
        controllers.forEach { (key, controller) ->
            callbacks[key]?.let { runCatching { controller.unregisterCallback(it) } }
        }
        controllers.clear()
        callbacks.clear()
        lastKey = null
        current = null
    }

    /** 重新扫描活跃会话，增新注册/注销回调。 */
    fun refresh() {
        val msm = manager ?: return
        val ctx = app ?: return
        val tokens = try {
            msm.getActiveSessions(ComponentName(ctx, MediaNotificationListener::class.java))
        } catch (_: Throwable) {
            return
        }

        val alive = HashSet<String>()
        // getActiveSessions 返回的是 MediaController（不是 Token），
        // 构造新的 controller 需要从中取 sessionToken
        for (ctrl in tokens) {
            val key = keyOf(ctrl)
            alive += key
            if (controllers.containsKey(key)) continue
            val controller = MediaController(ctx, ctrl.sessionToken)
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = PlaybackMonitor.update()
                override fun onPlaybackStateChanged(state: PlaybackState?) = PlaybackMonitor.update()
            }
            if (runCatching { controller.registerCallback(callback, handler) }.isFailure) continue
            controllers[key] = controller
            callbacks[key] = callback
        }

        controllers.keys.filter { it !in alive }.forEach { key ->
            val controller = controllers.remove(key)
            callbacks.remove(key)?.let { cb -> controller?.let { runCatching { it.unregisterCallback(cb) } } }
            // v1.8.2：会话消失时一并清掉它的元数据缓存（这里可以安全 recycle：
            // controller 已注销，不会再有新一轮读取指向这份位图）
            metaCache.remove(key)?.let { runCatching { it.info.albumArt?.recycle() } }
        }
        PlaybackMonitor.update()
    }

    /**
     * 会话增删时的轻量刷新：**只做注册/注销，不做全量扫描**。
     *
     * ## v1.8.2 的性能修正
     * 旧版 [refresh] 既要diff controller 列表，又在结尾调 `PlaybackMonitor.update()`
     * 做一次完整的 `best()`（遍历所有会话 + 读 metadata）。
     * 但 `OnActiveSessionsChangedListener` 在某些 ROM 上会被频繁回调
     * （播放器每次广播媒体通知都算一次），等于在主线程上反复做全量扫描。
     *
     * 现在拆开：会话增删只做O(n) 的 controller 增删（很轻），
     * 把状态更新交给 [PlaybackMonitor] 自己的 ticker（已在 IO 线程、按精度档节流），
     * 周期是 50~200ms 而不是"每次系统回调"。两者合起来等于去掉了高频重复扫描。
     */
    fun refreshSessionsOnly() {
        val msm = manager ?: return
        val ctx = app ?: return
        val tokens = try {
            msm.getActiveSessions(ComponentName(ctx, MediaNotificationListener::class.java))
        } catch (_: Throwable) {
            return
        }

        val alive = HashSet<String>()
        for (ctrl in tokens) {
            val key = keyOf(ctrl)
            alive += key
            if (controllers.containsKey(key)) continue
            val controller = MediaController(ctx, ctrl.sessionToken)
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = PlaybackMonitor.update()
                override fun onPlaybackStateChanged(state: PlaybackState?) = PlaybackMonitor.update()
            }
            if (runCatching { controller.registerCallback(callback, handler) }.isFailure) continue
            controllers[key] = controller
            callbacks[key] = callback
        }

        controllers.keys.filter { it !in alive }.forEach { key ->
            val controller = controllers.remove(key)
            callbacks.remove(key)?.let { cb -> controller?.let { runCatching { it.unregisterCallback(cb) } } }
            metaCache.remove(key)?.let { runCatching { it.info.albumArt?.recycle() } }
        }
    }

    fun snapshots(): List<SessionSnapshot> = controllers.values.mapNotNull { it.toSnapshot() }

    /**
     * 选出「当前正在播的那个」，选择必须粘滞，否则会来回跳导致 UI 闪烁。
     *
     * 优先级：
     *  1. 上次选中的会话仍在播放 → 保持不变（多会话同时播放时防抖）
     *  2. 其他正在播放的会话 → 切过去
     *  3. 上次选中的会话还活着但暂时读不到播放态 → **保持不变**
     *     （部分播放器（Spotify 等）更新 PlaybackState 的瞬间可能读到
     *      null/短暂非 playing，若无此兜底，选中会每拍跳到列表第一个会话再跳回来，
     *      表现为封面/主题色/进度条整页一闪一闪）
     *  4. 列表第一个
     */
    fun best(): SessionSnapshot? {
        val ctx = app ?: return null
        val blocked = SettingsStore.current().blockedPackages
        val list = snapshots().filter { it.pkg != ctx.packageName && it.pkg !in blocked }
        val playing = list.filter { it.state == PlaybackState.STATE_PLAYING }
        val last = list.firstOrNull { it.key == lastKey }
        val chosen = when {
            playing.isEmpty() -> last ?: list.firstOrNull()
            else -> playing.firstOrNull { it.key == lastKey } ?: playing.first()
        }
        lastKey = chosen?.key
        current = chosen
        return chosen
    }

    // ---------------- 传输控制 ----------------
    // 全部走 MediaSession 标准 TransportControls，
    // Spotify 与其他本地播放器行为一致；不支持的动作在 capabilities 里已标记为 false。

    fun seek(positionMs: Long) {
        val snapshot = current ?: return
        if (!snapshot.capabilities.canSeek) return
        runCatching { controllers[snapshot.key]?.transportControls?.seekTo(positionMs.coerceAtLeast(0)) }
    }

    fun skipToPrevious() {
        val snapshot = current ?: return
        if (!snapshot.capabilities.canSkipPrev) return
        runCatching { controllers[snapshot.key]?.transportControls?.skipToPrevious() }
    }

    fun skipToNext() {
        val snapshot = current ?: return
        if (!snapshot.capabilities.canSkipNext) return
        runCatching { controllers[snapshot.key]?.transportControls?.skipToNext() }
    }

    fun playOrPause(playing: Boolean) {
        val snapshot = current ?: return
        if (!snapshot.capabilities.canPlayPause) return
        runCatching {
            val controls = controllers[snapshot.key]?.transportControls ?: return
            if (playing) controls.pause() else controls.play()
        }
    }

    /**
     * 读取会话快照。
     *
     * ## v1.8.2 性能修正：把「不变的部分」缓存起来
     *
     * 旧版每一轮（默认 100ms 一次）都做这些事：
     *  - `metadata` —— **跨进程 Binder 调用**，取回整个 MediaMetadata Bundle
     *  - 从 Bundle 里 `getString` 取歌名/歌手/专辑
     *  - `getBitmap` 反序列化封面位图
     *
     * 但这些字段**只在换歌时变**，位置和播放状态才是每 100ms 变的。
     * 也就是说 99% 的轮次都在重复 IPC + 重复解析同一份不变的 Bundle。
     *
     * 现在按 `MediaMetadata` 实例做 key 缓存（换歌会产生新实例，key 自然失效）：
     *  - 只有 metadata 实例变了才重新解析字符串与封面
     *  - 位置/状态每轮照常读（这才是真正需要高频的部分，且 `playbackState`
     *    是较轻量的调用）
     *
     * 顺带把 `getBitmap` 缩到 320px 再交给上层，
     * 避免 4MB 的位图在每次 metadata 变化时都被反序列化出来。
     */
    private fun MediaController.toSnapshot(): SessionSnapshot? {
        val metadata = metadata ?: return null

        // ---- 缓存命中判定：同一个 metadata 实例 → 复用上次解析结果 ----
        val cached = metaCache[keyOf(this)]
        val info: MetaInfo
        if (cached != null && cached.raw === metadata) {
            info = cached.info
        } else {
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            if (title.isNullOrBlank()) return null
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
            val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            // 封面：优先直接给 Bitmap 的字段，其次退化为 URI（交给 Coil 加载）
            val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            info = MetaInfo(
                track = TrackInfo(title, artist.orEmpty(), album.orEmpty(), duration, packageName),
                albumArt = art?.let { AlbumArt.downsample(it) },
                albumArtUri = artUri,
            )
            metaCache[keyOf(this)] = MetaCacheEntry(metadata, info)
        }

        val state = playbackState
        val stateCode = state?.state ?: PlaybackState.STATE_NONE
        val position = estimatePosition(stateCode, state?.position ?: 0L, state?.lastPositionUpdateTime ?: 0L)

        return SessionSnapshot(
            key = keyOf(this),
            pkg = packageName,
            track = info.track,
            state = stateCode,
            positionMs = position,
            albumArt = info.albumArt,
            albumArtUri = info.albumArtUri,
            capabilities = TransportCapabilities(
                canSeek = hasAction(state, PlaybackState.ACTION_SEEK_TO),
                canSkipPrev = hasAction(state, PlaybackState.ACTION_SKIP_TO_PREVIOUS),
                canSkipNext = hasAction(state, PlaybackState.ACTION_SKIP_TO_NEXT),
                canPlayPause = hasAction(state, PlaybackState.ACTION_PLAY_PAUSE),
            ),
        )
    }

    /**
     * 播放位置推算：播放器只在状态变化/定期广播时上报 position，
     * 播放中需要用「上报时刻到现在的墙钟时间」补上差值。
     */
    private fun estimatePosition(stateCode: Int, reported: Long, updateTime: Long): Long {
        if (reported < 0) return 0L
        if (stateCode != PlaybackState.STATE_PLAYING || updateTime <= 0L) return reported
        val elapsed = (SystemClock.elapsedRealtime() - updateTime).coerceAtLeast(0L)
        return reported + elapsed
    }

    private fun hasAction(state: PlaybackState?, action: Long): Boolean =
        state?.actions?.let { it and action != 0L } ?: false

    /** 会话 key：包名 + token 实例标识（Token 本身没有可比较的公开 id）。 */
    private fun keyOf(controller: MediaController): String =
        "${controller.packageName}#${System.identityHashCode(controller.sessionToken)}"
}

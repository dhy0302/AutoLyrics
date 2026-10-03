package com.yuanbao.autolyrics.media

import android.graphics.Bitmap
import android.media.session.PlaybackState
import android.os.SystemClock
import com.yuanbao.autolyrics.data.PrecisionMode
import com.yuanbao.autolyrics.data.TrackInfo
import com.yuanbao.autolyrics.data.TransportCapabilities
import com.yuanbao.autolyrics.ui.components.AlbumArt
import com.yuanbao.autolyrics.util.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 全局唯一的「正在播什么 + 播到哪了」状态源。
 *
 * UI 层（App 内歌词页、悬浮窗、通知栏）只依赖这里，不直接碰 MediaSession。
 *
 * 数据来源优先级：
 *  1. MediaSession（精确进度、封面、可控）
 *  2. 媒体通知兜底（只有歌名/歌手，进度靠墙钟估算）
 */
object PlaybackMonitor {

    private val _track = MutableStateFlow<TrackInfo?>(null)
    val track: StateFlow<TrackInfo?> = _track.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _albumArt = MutableStateFlow<Bitmap?>(null)
    val albumArt: StateFlow<Bitmap?> = _albumArt.asStateFlow()

    private val _albumArtUri = MutableStateFlow<String?>(null)
    val albumArtUri: StateFlow<String?> = _albumArtUri.asStateFlow()

    private val _capabilities = MutableStateFlow(TransportCapabilities())
    val capabilities: StateFlow<TransportCapabilities> = _capabilities.asStateFlow()

    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnectedFlow = _listenerConnected.asStateFlow()

    var listenerConnected: Boolean
        get() = _listenerConnected.value
        internal set(v) { _listenerConnected.value = v }

    /** 通知兜底的曲目与其起始时刻（用墙钟估算进度）。 */
    private var fallback: TrackInfo? = null
    private var fallbackSince = 0L

    /** 上一帧的平滑位置，仅 PRECISE 档位使用。 */
    private var smoothedPosition: Long? = null

    /**
     * 启动轮询；用户在设置里切换精度档位时会自动重建内部循环。
     *
     * ## v1.8.2：整个 ticker 搬到 IO 线程
     *
     * 旧版跑在 `Dispatchers.Main`上，而循环体里的 `MediaSessionWatcher.best()`
     * → `toSnapshot()` 会读 `MediaController.metadata`——
     * **那是跨进程 Binder 调用**，每次都要等播放器的进程回数据。
     * 默认 100ms 一轮（PRECISE 档 50ms），等于每秒 10~20 次同步 IPC 压主线程。
     *
     * 这解释了「打开 App 后滑动列表偶尔顿一下」：
     * 主线程本来在等 vsync 完成一帧绘制，却被 Binder 往返挡住了。
     * 而且多 App 同时有活跃会话时，`snapshots()` 要遍历每个 controller，
     * 开销成倍放大。
     *
     * 搬到 IO 线程后，Binder 等待发生在后台，
     * 主线程只收到 StateFlow 的赋值通知（本身无阻塞，且是线程安全的）。
     */
    fun startTicker(scope: CoroutineScope) {
        scope.launch {
            var tickJob: Job? = null
            SettingsStore.settings
                .map { it.precisionMode }
                .distinctUntilChanged()
                .collect { mode ->
                    tickJob?.cancel()
                    tickJob = launch(Dispatchers.IO) {
                        while (isActive) {
                            update(mode)
                            delay(mode.pollMs)
                        }
                    }
                }
        }
    }

    fun update(mode: PrecisionMode = SettingsStore.current().precisionMode) {
        val snapshot = MediaSessionWatcher.best()
        if (snapshot != null) {
            applySession(snapshot, mode)
            return
        }
        applyFallback()
    }

    private fun applySession(snapshot: SessionSnapshot, mode: PrecisionMode) {
        // 切歌时重置平滑基准，避免上一首的位置污染下一首
        if (snapshot.track.key() != _track.value?.key()) {
            smoothedPosition = null
            _track.value = snapshot.track
        }

        val duration = snapshot.track.durationMs
        var position = snapshot.positionMs
        if (duration > 0) position = position.coerceIn(0, duration)

        _durationMs.value = duration
        _positionMs.value = if (mode.smoothing) smooth(position) else position
        _isPlaying.value = snapshot.state == PlaybackState.STATE_PLAYING
        _capabilities.value = snapshot.capabilities

        // 封面防抖：曲目没变且已有可用封面/封面地址时，不因实例不同而重复发射，
        // 否则下游取色/模糊会跟着重组，UI 表现为整页闪烁
        val sameTrack = snapshot.track.key() == _track.value?.key()
        val hasUsableArt = _albumArt.value != null || !_albumArtUri.value.isNullOrBlank()
        if (snapshot.albumArt !== _albumArt.value &&
            !(sameTrack && hasUsableArt && snapshot.albumArt == null)
        ) {
            // v1.8.2 内存优化：MediaSession 给的是**原始全尺寸**封面
            // （Spotify 常见 1000×1000，ARGB_8888 就是 4MB），而这份位图
            // 会被 StateFlow 长期持有 —— 切歌不释放就一路涨。
            // App 实际最大用途只有 56dp 的封面卡与背景模糊，320px 足够。
            //
            // 这里**刻意不 recycle 旧位图**：UI 侧（HomeScreen 的 cover、
            // 模糊缓存）还持有它的引用，Compose 重组慢一帧就会撞上
            // "Canvas: trying to use a recycled bitmap" 崩溃。
            // 降采样本身已经把单张从 4MB 压到约 410KB（10 倍），
            // 余下的交给 GC 是安全且足够的选择。
            _albumArt.value = snapshot.albumArt?.let { AlbumArt.downsample(it) }
        }
        if (snapshot.albumArtUri != _albumArtUri.value &&
            !(sameTrack && hasUsableArt && snapshot.albumArtUri.isNullOrBlank())
        ) {
            _albumArtUri.value = snapshot.albumArtUri
        }
    }

    /**
     * 一阶低通滤波：抑制部分播放器上报位置的抖动，让逐字染色不会来回闪。
     *
     * 两处保护：
     *  - 跳变超过 1.5s（seek、切歌）直接采用新值，避免平滑拖尾
     *  - 未播放时不做平滑，避免暂停瞬间位置缓慢爬行
     */
    private fun smooth(raw: Long): Long {
        val prev = smoothedPosition
        val next = when {
            prev == null -> raw
            kotlin.math.abs(raw - prev) > SMOOTH_SNAP_THRESHOLD_MS -> raw
            else -> prev + ((raw - prev) * SMOOTH_FACTOR).toLong()
        }
        smoothedPosition = next
        return next
    }

    /** 通知兜底：没有 MediaSession 时用「通知发布时间 + 墙钟」估算进度。 */
    private fun applyFallback() {
        val info = fallback
        val since = fallbackSince
        if (info == null || SystemClock.elapsedRealtime() - since > FALLBACK_MAX_AGE_MS) {
            clear()
            return
        }
        if (info.key() != _track.value?.key()) {
            smoothedPosition = null
            _track.value = info
        }
        _isPlaying.value = true
        _durationMs.value = info.durationMs
        var position = SystemClock.elapsedRealtime() - since
        if (info.durationMs > 0 && position > info.durationMs) position = 0
        _positionMs.value = position
        // 兜底路径无法控制播放器
        _capabilities.value = TransportCapabilities()
        _albumArt.value = null
        _albumArtUri.value = null
    }

    private fun clear() {
        if (_track.value != null) _track.value = null
        _isPlaying.value = false
        _positionMs.value = 0L
        _durationMs.value = 0L
        // v1.8.2：这里曾尝试 recycle 封面释放内存，但 UI 侧可能仍持有引用，
        // 重组慢一帧就会崩在 "trying to use a recycled bitmap"。改为置空，
        // 让 GC 回收 —— 位图已降采样到 320px，单张约 410KB，不再是隐患。
        _albumArt.value = null
        _albumArtUri.value = null
        _capabilities.value = TransportCapabilities()
        smoothedPosition = null
    }

    fun setFallback(track: TrackInfo?) {
        fallback = track
        fallbackSince = SystemClock.elapsedRealtime()
    }

    // ---------------- 传输控制 ----------------

    fun seek(positionMs: Long) = MediaSessionWatcher.seek(positionMs)
    fun skipToPrevious() = MediaSessionWatcher.skipToPrevious()
    fun skipToNext() = MediaSessionWatcher.skipToNext()
    fun playOrPause() = MediaSessionWatcher.playOrPause(_isPlaying.value)

    private const val SMOOTH_FACTOR = 0.35f
    private const val SMOOTH_SNAP_THRESHOLD_MS = 1500L
    private const val FALLBACK_MAX_AGE_MS = 120_000L
}

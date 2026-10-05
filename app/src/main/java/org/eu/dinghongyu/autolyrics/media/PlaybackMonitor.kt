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

package org.eu.dinghongyu.autolyrics.media

import android.graphics.Bitmap
import android.media.session.PlaybackState
import android.os.SystemClock
import org.eu.dinghongyu.autolyrics.data.PrecisionMode
import org.eu.dinghongyu.autolyrics.data.TrackInfo
import org.eu.dinghongyu.autolyrics.data.TransportCapabilities
import org.eu.dinghongyu.autolyrics.ui.components.AlbumArt
import org.eu.dinghongyu.autolyrics.util.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
     *
     * ## v1.13.10：按「有没有在播放」分层唤醒
     *
     * 原来的 `while (isActive) { update(mode); delay(mode.pollMs) }`
     * **没有任何前置条件**：息屏、App 在后台、没有音乐播放、甚至用户
     * 根本没开悬浮窗 —— 全都照跑。每小时 1.8万~7.2 万次跨进程 Binder，
     * 而且因为被 [MediaNotificationListener] 绑定，进程难以进 idle，
     * 系统级的省电机制也帮不上忙。
     *
     * 现在按「当前有没有活跃播放」分两档：
     *  - **有播放**：全速 `mode.pollMs`（50/100/200ms）—— 这时进度条要动，
     *    省不得。
     *  - **无播放**：退到 [IDLE_POLL_MS]（1 秒）。这一档只是为了让
     *    「暂停后按播放键」能在 1 秒内跟上，而不是要刷进度条。
     *
     * ### 为什么无播放时不完全停掉
     *
     * 直接停会有两个真实问题：
     *  1. 某些播放器不发 [MediaController.Callback]（或回调被系统丢掉），
     *     只靠事件驱动会漏掉「开始播放了」；
     *  2. 通知兜底路径（[applyFallback]）没有回调，只能靠轮询发现新歌。
     *
     * 1 秒一次的开销（每小时 3600 次）相比原来的 3.6 万次是 90% 的降幅，
     * 而「按播放键到歌词刷新」的延迟最多 1 秒 —— 用户完全感知不到
     * （他在按之前就已经看到播放器的状态变了）。
     *
     * ### 事件驱动仍然负责「即时」
     *
     * [MediaSessionWatcher] 的两个 [MediaController.Callback] 里
     * 仍会调 [pokeByCallback]，所以真实的切歌/播放状态变化是**立即**处理的，
     * 轮询只做兜底。两者不冲突。
     */
    fun startTicker(scope: CoroutineScope) {
        // v1.18.4：每次调用换一个新令牌，旧的循环会在下一次醒来时自行退出。
        val myGate = Any()
        _tickerGate = myGate
        // v1.18.5：外层 collect 也搬到后台（调用方已改传 AppScope.io）。
        //
        // 旧版外层跑在主线程、内层硬编码 `launch(Dispatchers.IO)`，
        // 看似分层合理，实则留下一个漏洞：**档位变化的 collect 本身在主线程上**。
        // 主线程被限制时，用户改精度档位不会重建循环 ——
        // 而看门狗重启时会顺手发一次设置/状态更新，
        // 于是「看门狗重启了但档位没生效」这种半死不活的状态很容易出现。
        scope.launch {
            var tickJob: Job? = null
            SettingsStore.settings
                .map { it.precisionMode }
                .distinctUntilChanged()
                .collect { mode ->
                    tickJob?.cancel()
                    tickJob = launch {
                        tickerRunning = true
                        try {
                            while (isActive) {
                                // v1.18.4：换代检查。发现令牌已换就让位给新循环，
                                // 靠它实现「重启」而不必 cancel（cancel 会在挂起点抛异常）。
                                if (_tickerGate !== myGate) return@launch
                                // v1.18.4：**异常必须在这里被吃掉**。
                                //
                                // update() 内部是跨进程 Binder 调用，播放器进程被系统
                                // 回收时会抛 DeadObjectException/RuntimeException。
                                // 旧版这里**完全没有 try**，异常会穿透 while 直接终止
                                // 协程 —— 而协程死亡是静默的：不崩溃、不打日志，
                                // 只是 positionMs 与 track 从此永远不再变化。
                                // 症状正是「通知栏歌词永久停住，连切歌都不变」。
                                //
                                // 循环体还挂在 AppScope 上，子协程死了父协程不会重新拉起，
                                // 所以**一次异常 = 永久失效**，直到进程重启。
                                runCatching { update(mode) }
                                    .onFailure { lastError = "${it.javaClass.simpleName}: ${it.message}" }
                                    .onSuccess { lastError = null }
                                // v1.18.5：**心跳必须在 update 之后记，且要在 runCatching 之外**。
                                //
                                // 放外面是为了「这一轮跑完了」就算活着 ——
                                // 若 update 每轮都抛异常，链路的其余部分（曲名、封面）
                                // 仍在更新，不该被判死；真正要防的是「循环整体停摆」。
                                markHeartbeat()
                                delay(if (hasActivePlayback()) mode.pollMs else IDLE_POLL_MS)
                            }
                        } finally {
                            // 只有「自己仍是当前这一代」才置 false，
                            // 否则交接班时会误把新一代的状态抹掉。
                            if (_tickerGate === myGate) tickerRunning = false
                        }
                    }
                }
        }
    }

    /**
     * v1.13.10：当前是否有「值得高频轮询」的活跃播放。
     *
     * 条件是 [_isPlaying] 或 [_track] 任一非空 ——
     * 只看 [_isPlaying] 的话，「暂停但仍显示着歌名」这种状态
     * （很多播放器的暂停态就是这么显示的）会被误判成空闲。
     */
    private fun hasActivePlayback(): Boolean =
        _isPlaying.value || _track.value != null

    /**
     * v1.13.10：供 [MediaSessionWatcher] 的 [android.media.session.MediaController.Callback]
     * 调用，**立即**做一次更新。
     *
     * ## 为什么它现在就只是 [update] 的别名
     *
     * 审查报告里 P0-4 建议在这里加一层「元数据新鲜度窗口」，
     * 让回调成为一段时间内的唯一权威、ticker 直接跳过元数据解析。
     * **实现时评估后决定不做**，理由如下：
     *
     *  1. **省不到真正的开销。** Binder 调用发生在
     *     [MediaSessionWatcher.best] → `MediaController.getMetadata()` 内部，
     *     而窗口只能让 `applySession` 跳过封面判断，
     *     **省不掉那次 IPC 本身** —— 而 IPC 才是这里的大头。
     *  2. **元数据解析在 P0-1 之后已经很便宜了。** 指纹化之后，
     *     同一首歌的重复扫描只是几次字符串比较，
     *     不再反序列化 4MB 位图、不再新建 MetaInfo，
     *     从「每秒 15 次 4MB 分配」降到了「每秒 15 次内存比较」。
     *     这已经不是瓶颈，再加一层去重是**为一个已解决的问题增加状态同步复杂度**。
     *  3. **有引入 bug 的风险。** 写第一版时已经踩到：
     *     `sameTrack` 是在 `_track.value` 被赋新值*之后*计算的，
     *     所以切歌时它恒为 true ——
     *     一旦用 `sameTrack` 做跳过判断，切歌后的封面就再也不会更新。
     *
     * 保留这个函数而不是让回调直接调 [update]，是为了给「回调 vs 轮询
     * 的职责边界」留一个明确的落点：将来若真需要在这里做去重，
     * 改这一个函数即可，不必再去改两处 `registerCallback`。
     */
    fun pokeByCallback() {
        update()
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
        val trackChanged = snapshot.track.key() != _track.value?.key()
        if (trackChanged) {
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

        // v1.18.1 修正：这个防抖判据会**吞掉占位图之后补上的真图**，
        // 也会**吞掉切歌瞬间的空封面**。
        //
        // 原判据是`!(sameTrack && hasUsableArt && snapshot.albumArt == null)`，
        // 只想拦「同一首歌反复上报 metadata 导致封面闪烁」。但两个方向都出事：
        //
        // 1. **切歌那一瞬**：`_albumArt.value` 还持有上一首的封面，
        //    `hasUsableArt` 为 true，新歌首帧封面可能还没来（null）——
        //    新歌的空封面被当成「抖动」不发射，于是 UI 侧
        //    `rememberAlbumCover` 连 uri 都收不到，重试与观察无从启动。
        // 2. **占位图场景**：本函数上方的 KDoc（pokeByCallback）已经记过，
        //    `sameTrack` 是在 `_track.value` 被赋新值*之后*算的，
        //    切歌时恒为 true —— 同理会漏掉切歌后的封面更新。
        //
        // 修法：判据收紧为「**新值非 null** 才防抖」。
        // 空值一律放行，让切歌瞬间的空封面照常发出去（下游据此清空旧图），
        // 非空的新封面照常发射。只拦「非 null 且实例相同」的重复发射。
        // 代价是封面真变了但内容相同时会多发射一次 StateFlow——
        // 那本来就是该发的情况，且封面比对是引用相等判断，很便宜。
        val shouldEmitArt = snapshot.albumArt != null ||
            _albumArt.value == null ||
            snapshot.albumArt !== _albumArt.value
        if (shouldEmitArt && snapshot.albumArt !== _albumArt.value) {
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
        // uri 同理：切歌瞬间的新 uri 为空时必须放行，否则切歌后
        // 拿到的还是上一首的 uri，观察协程会一直比错对象。
        val shouldEmitUri = snapshot.albumArtUri != null ||
            _albumArtUri.value == null ||
            snapshot.albumArtUri != _albumArtUri.value
        if (shouldEmitUri && snapshot.albumArtUri != _albumArtUri.value) {
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

    /**
     * v1.13.10：没有活跃播放时的轮询间隔。
     *
     * 取 1000ms 而非更长：既能把空闲期的 Binder 调用压到原来的 1/10，
     * 又能保证「没有回调的播放器」在 1 秒内被跟上。
     * 再长（如 3~5 秒）会让这类播放器的播放键响应明显迟钝，不值得。
     */
    private const val IDLE_POLL_MS = 1000L

    // ---------------- v1.18.4：心跳与自愈 ----------------

    /**
     * v1.18.4：ticker 每成功跑完一轮就更新这个时间戳（`SystemClock.elapsedRealtime()`）。
     *
     * ## 为什么要它
     *
     * 这个 bug 之前无法定位，根因是**ticker 死掉时没有任何外部症状**：
     * 协程异常终止不会崩溃、不会打日志、不会通知任何人，
     * 只是从某一刻起 [positionMs] 与 [track] 永远不再变化。
     *
     * 有了心跳，[LyricsForegroundService] 的看门狗才能判断
     * 「链路是否还活着」，进而决定要不要重启它。
     */
    @Volatile
    var lastHeartbeatAt: Long = 0L
        private set

    /**
     * v1.18.4：ticker 累计成功轮数。
     *
     * 只用于诊断页显示「这条链路到底动过没有」，
     * 与心跳相比它不会因为时钟回拨而失真。
     */
    @Volatile
    var heartbeatCount: Long = 0L
        private set

    /**
     * v1.18.4：最近一次 [update] 抛出的异常摘要（无异常时为 null）。
     *
     * 这是**判断根因的直接证据**：ticker 若是被 [DeadObjectException] 打死的，
     * 这里就会留下内容；若是别的机制，它会是空。
     */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * v1.18.4：ticker 当前是否在跑。
     *
     * 由 [startTicker] 写入；看门狗重启时会先置 false 再置 true，
     * 便于诊断页区分「重启过」。
     */
    @Volatile
    var tickerRunning: Boolean = false
        private set

    /** v1.18.4：重启 ticker 的次数。反复增长说明看门狗在反复救火。 */
    @Volatile
    var restartCount: Int = 0
        private set

    /**
     * v1.18.4：**重启整个播放轮询链路**。由看门狗在判定 ticker 已死时调用。
     *
     * 做法是让 [startTicker] 换一个新的 [_tickerGate]，
     * 于是旧循环下一次醒来时发现 gate 已换、主动退出，
     * 新循环同时接手。比「记录 Job 然后 cancel」更稳，
     * 因为 cancel 会在任意挂起点抛异常，而这里只是下一次轮询时自然收敛。
     */
    fun restartTicker(scope: CoroutineScope) {
        restartCount++
        _tickerGate = Any()
        startTicker(scope)
    }

    /**
     * v1.18.4：[startTicker] 内部用的「换代令牌」。
     *
     * 每次 [startTicker] 换一个新的，旧循环通过比对引用发现失效并退出。
     * 用 `Any()` 而不是整数，是为了让每次的引用都必然不同。
     */
    @Volatile
    private var _tickerGate: Any = Any()

    /**
     * v1.18.4：记录一次成功心跳。
     *
     * 刻意放在 `update(mode)` **之后**而不是之前 ——
     * 只有真正跑完一轮才算活着。若 update 抛异常，这一轮就不该算数，
     * 否则看门狗会把「一直在抛异常」误判成「链路健康」。
     */
    private fun markHeartbeat() {
        lastHeartbeatAt = SystemClock.elapsedRealtime()
        heartbeatCount++
    }
}

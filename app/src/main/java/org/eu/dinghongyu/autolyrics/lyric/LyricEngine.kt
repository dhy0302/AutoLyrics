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

package org.eu.dinghongyu.autolyrics.lyric

import org.eu.dinghongyu.autolyrics.data.Lyric
import org.eu.dinghongyu.autolyrics.data.PrecisionMode
import org.eu.dinghongyu.autolyrics.data.TrackInfo
import org.eu.dinghongyu.autolyrics.lyric.parser.LyricParser
import org.eu.dinghongyu.autolyrics.media.PlaybackMonitor
import org.eu.dinghongyu.autolyrics.util.AppScope
import org.eu.dinghongyu.autolyrics.util.SettingsStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 把「当前播放」与「歌词」绑定起来：
 *  - 曲目变化 → 按设置里的源顺序取词
 *  - 播放位置变化 → 计算当前行下标 + 换算到歌词时间轴上的位置
 */
object LyricEngine {

    enum class Status { IDLE, NO_PERMISSION, LOADING, FOUND, NOT_FOUND, ERROR }

    data class State(
        val track: TrackInfo? = null,
        val status: Status = Status.IDLE,
        val lyric: Lyric? = null,
        val fromSourceId: String? = null,
        val attempts: List<SourceAttempt> = emptyList(),
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 当前歌词行下标，-1 表示还没到第一行。 */
    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()

    /**
     * v1.12.1：换算到歌词时间轴上的位置：`播放位置 - 全局偏移 - 歌词自带偏移`。
     *
     * **故意用 `mutableLongStateOf` 而非 StateFlow** ——
     * 它没有响应式消费者（见 [lyricPositionSample] 的说明），
     * 用 Flow 只会白付一次 emit 的开销。
     */
    private var lyricPosition by mutableLongStateOf(0L)

    /**
     * v1.12.1：**按需读取**当前歌词位置。
     *
     * ## 为什么不给 StateFlow
     *
     * 这个值跟随 PlaybackMonitor 的 ticker，**每秒变 10~20 次**。
     * 而它唯一的消费者是 [ui.components.rememberKaraokeClock]，
     * 且**只被当作基准值用一次**——逐帧推进由 `withFrameNanos` 负责，
     * 不依赖这个流。
     *
     * 以前 UI 侧 `collectAsState()` 订阅它，于是每秒 10~20 次
     * **整页重组**（歌词页含流体渐变背景、专辑封面、播放条），
     * 而对逐字动画毫无收益。
     *
     * 现在改为 lambda：调用方在**真正需要的那一刻**读取，
     * 值不进重组树，[AppleLyricLine] 的时钟启动时读一次即可。
     */
    fun lyricPositionSample(): Long = lyricPosition

    private var loadJob: Job? = null

    /**
     * v1.12.1：取词请求的递增序号，用于丢弃过期结果。
     * 每次 [fetch] 自增；协程写回状态前比对，发现自己不是最新一次就直接放弃。
     */
    @Volatile
    private var loadGeneration = 0

    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        val configFlow = SettingsStore.settings
            .map { Triple(it.enabledSources, it.sourceOrder, it.sourceOverride) }
            .distinctUntilChanged()

        // 曲目或源配置变化（含手动锁定来源）→ 重新取词
        scope.launch {
            combine(PlaybackMonitor.track, configFlow) { track, config -> track to config }
                .distinctUntilChanged { a, b ->
                    a.first?.key() == b.first?.key() && a.second == b.second
                }
                .collect { (track, config) -> load(track, config.first, config.second, config.third) }
        }

        // 位置变化 → 更新高亮行（与精度档位无关，跟随 PlaybackMonitor 的刷新频率）
        scope.launch {
            combine(PlaybackMonitor.positionMs, _state, SettingsStore.settings) { position, st, settings ->
                Triple(position, st, settings.globalOffsetMs)
            }.collect { (position, st, globalOffset) ->
                val adjusted = position - globalOffset - (st.lyric?.offsetMs ?: 0L)
                lyricPosition = adjusted
                _index.value = st.lyric?.lines?.let { LyricParser.indexAt(it, adjusted) } ?: -1
            }
        }
    }

    private fun load(
        track: TrackInfo?,
        enabled: Set<String>,
        order: List<String>,
        override: Map<String, String>,
    ) {
        loadJob?.cancel()
        if (track == null || track.isBlank()) {
            _state.value = State(
                status = if (PlaybackMonitor.listenerConnected) Status.IDLE else Status.NO_PERMISSION
            )
            _index.value = -1
            return
        }
        val effectiveOrder = order.filter { it in enabled }
        fetch(track, effectiveOrder, forceRefresh = false)
    }

    /** 手动重试。[force] 为真时忽略本地缓存。 */
    fun refresh(force: Boolean = true) {
        val track = _state.value.track ?: PlaybackMonitor.track.value ?: return
        val settings = SettingsStore.current()
        fetch(track, settings.sourceOrder.filter { it in settings.enabledSources }, forceRefresh = force)
    }

    private fun fetch(track: TrackInfo, order: List<String>, forceRefresh: Boolean) {
        // v1.12.1：请求序号。每发起一次取词 +1。
        //
        // 用它而不是「比较 loadJob === 我的 job」来判定自己是否过期：
        // 命中内存缓存时 load() 全程不挂起，协程可能在 `loadJob = launch{}`
        // 这条赋值真正生效前就跑完了，那时 loadJob 还是上一条或 null，
        // 用身份比较会把**正常结果**误判为过期而丢弃 —— 歌词就永远不出来了。
        // 序号在发起请求前自增，与协程何时被调度无关，判断可靠。
        val generation = ++loadGeneration
        loadJob?.cancel()
        _state.value = State(track = track, status = Status.LOADING)
        loadJob = AppScope.io.launch {
            val result = try {
                LyricRepository.load(track, order, forceRefresh)
            } catch (t: Throwable) {
                // v1.12.1：**取消必须放出去**，不能当失败处理。
                //
                // 切歌时上一条协程会被 cancel()，而 CancellationException 是 Throwable，
                // 旧版直接把它兜住了，于是：切歌瞬间 UI 闪一下「加载失败」，
                // 更糟的是这条**旧歌**的 ERROR 状态会把**新歌**刚设的 LOADING 覆盖掉。
                // 现在放行后，取消的协程就地结束，什么都不写。
                if (t is CancellationException) throw t
                _state.value = State(track, Status.ERROR, message = t.message)
                return@launch
            }
            // v1.12.1：写回之前再确认「我还是最新的一次请求」。
            // cancel() 到协程真正抛异常之间有窗口，若这期间用户已切到别的歌，
            // 这里就会把**旧歌的结果**写进**新歌**的状态里（表现为歌词闪一下错歌）。
            if (generation != loadGeneration) return@launch
            _state.value = State(
                track = track,
                status = if (result.lyric != null) Status.FOUND else Status.NOT_FOUND,
                lyric = result.lyric,
                fromSourceId = result.fromSourceId,
                attempts = result.attempts,
            )
        }
    }

    /** 当前生效的精度档位（供 UI 展示）。 */
    fun precision(): PrecisionMode = SettingsStore.current().precisionMode
}

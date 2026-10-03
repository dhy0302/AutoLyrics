package org.eu.dinghongyu.autolyrics.lyric

import org.eu.dinghongyu.autolyrics.data.Lyric
import org.eu.dinghongyu.autolyrics.data.PrecisionMode
import org.eu.dinghongyu.autolyrics.data.TrackInfo
import org.eu.dinghongyu.autolyrics.lyric.parser.LyricParser
import org.eu.dinghongyu.autolyrics.media.PlaybackMonitor
import org.eu.dinghongyu.autolyrics.util.AppScope
import org.eu.dinghongyu.autolyrics.util.SettingsStore
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
     * 换算到歌词时间轴上的位置：`播放位置 - 全局偏移 - 歌词自带偏移`。
     * 逐字染色与行高亮都用这个值，保证和用户的微调一致。
     */
    private val _lyricPositionMs = MutableStateFlow(0L)
    val lyricPositionMs: StateFlow<Long> = _lyricPositionMs.asStateFlow()

    private var loadJob: Job? = null

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
                _lyricPositionMs.value = adjusted
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
        loadJob?.cancel()
        _state.value = State(track = track, status = Status.LOADING)
        loadJob = AppScope.io.launch {
            val result = try {
                LyricRepository.load(track, order, forceRefresh)
            } catch (t: Throwable) {
                _state.value = State(track, Status.ERROR, message = t.message)
                return@launch
            }
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

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
import kotlinx.coroutines.delay
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
     * v1.12.7：自动退避重试的定时任务。
     *
     * 见 [scheduleRetry] 的说明。单例只需一个 —— 同一时刻只可能有一首歌
     * 「处于没解决状态」，切歌时旧的会被 [cancelRetry] 作废。
     */
    private var retryJob: Job? = null

    /**
     * v1.12.7：自动退避重试的退避间隔（毫秒）。
     *
     * 累计约 4 分钟，覆盖绝大多数「解锁后看一眼」的场景；
     * 数组长度就是重试次数上限（5 次）—— 改这个数组即改次数，
     * 不另设常量避免两处数字对不上。
     */
    private val RETRY_DELAYS_MS = longArrayOf(3_000L, 10_000L, 30_000L, 60_000L, 120_000L)

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
        // v1.12.7：切歌/改配置时作废上一首歌的退避重试。
        // 否则旧歌的重试醒来后会发现 track 不匹配而自行退出 ——
        // 虽然最终不会污染状态，但会白占一个协程；更重要的是
        // 若用户切回同一首歌，旧任务的 key 恰好相等，会造成重复重试。
        cancelRetry()
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

    /**
     * v1.12.6：回到前台时，若当前歌是「上次没查成」就自动再查一次。
     *
     * 已被下面的自动退避重试取代，保留给「手动点重取失败后再回前台」兜底。
     */
    fun retryIfUnresolved() {
        val st = _state.value
        if (st.status != Status.NOT_FOUND && st.status != Status.ERROR) return
        val track = st.track ?: PlaybackMonitor.track.value ?: return
        // 没有 track 说明是「连播放信息都没有」，那是权限问题，重取词也没用
        if (track.isBlank()) return
        refresh(force = false)
    }

    /**
     * v1.12.7：**自动退避重试**——让「熄屏切歌丢歌词」不需要用户做任何操作。
     *
     * ## 为什么 [retryIfUnresolved] 还不够
     *
     * 它只在 `MainActivity.onResume` 里被调用。而实际场景里，
     * 用户亮屏后**很可能根本没打开 App**，只是看到桌面上的悬浮窗。
     * 这时没有任何东西会触发重试，歌词就一直空着 ——
     * 而这恰恰是用户报障时描述的「悬浮窗也没歌词」。
     *
     * 所以这里不依赖 Activity 生命周期，也不依赖亮屏广播
     * （`ACTION_SCREEN_ON` 需要动态注册，且部分ROM 上不保证送达）。
     * 改为**由播放轮询驱动**：只要当前状态还是「没解决」，
     * 就每隔一段时间自己再试一次。
     *
     * ## 为什么用退避而不是固定间隔
     *
     * 播放轮询本身每 50~200ms 一趟，若在这里无脑重试就是灾难性的流量。
     * 退避序列 [3, 10, 30, 60, 120] 秒：前几次密集（网络刚恢复时尽快补上），
     * 之后拉长（确实没网就别白试了）。累计约 4 分钟，覆盖绝大多数「解锁后看一眼」的场景。
     *
     * 超过退避数组长度后停止 —— 真长时间没网就不惊动用户了，
     * 等他手动点「重取」或下次切歌。
     *
     * ## 为什么不会浪费请求
     *
     * 重试用 `force = false`：真正「确实没歌词」的歌会命中负缓存（TTL 3 天），
     * 直接返回、不发任何请求。所以退避只对「网络失败导致没查成」的场景有效，
     * 代价极低。
     */
    private fun scheduleRetry(trackKey: String) {
        if (retryJob?.isActive == true) return
        retryJob = AppScope.io.launch {
            for (step in RETRY_DELAYS_MS) {
                delay(step)
                val st = _state.value
                // 只为同一首歌重试；用户已切歌就没必要了
                if (st.track?.key() != trackKey) return@launch
                // 状态已经解决（拿到词、或又开始加载）→ 不再重试
                if (st.status != Status.NOT_FOUND && st.status != Status.ERROR) return@launch
                val t = st.track ?: return@launch
                fetch(t, currentOrder(), forceRefresh = false)
            }
        }
    }

    private fun currentOrder(): List<String> {
        val s = SettingsStore.current()
        return s.sourceOrder.filter { it in s.enabledSources }
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
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
            // v1.12.7：拿到词就停；没解决就排一次自动退避重试。
            //
            // 只在 `networkFailed` 时排：那种情况明确是「网络没通」，
            // 而非「确实没歌词」。真没歌词的走负缓存，重试也没意义，
            // 不该为它反复联网。
            if (result.lyric != null || !result.networkFailed) {
                cancelRetry()
            } else {
                scheduleRetry(track.key())
            }
        }
    }

    /** 当前生效的精度档位（供 UI 展示）。 */
    fun precision(): PrecisionMode = SettingsStore.current().precisionMode
}

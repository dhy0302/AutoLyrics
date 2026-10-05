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

import android.os.SystemClock
import org.eu.dinghongyu.autolyrics.data.Lyric
import org.eu.dinghongyu.autolyrics.data.PrecisionMode
import org.eu.dinghongyu.autolyrics.data.TrackInfo
import org.eu.dinghongyu.autolyrics.lyric.parser.LyricParser
import org.eu.dinghongyu.autolyrics.media.PlaybackMonitor
import org.eu.dinghongyu.autolyrics.util.AppScope
import org.eu.dinghongyu.autolyrics.util.SettingsStore
import org.eu.dinghongyu.autolyrics.util.Trace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
     * 换算到歌词时间轴上的位置：`播放位置 - 全局偏移 - 歌词自带偏移`。
     *
     * ## v1.18.5：从 `mutableLongStateOf` 改为 `@Volatile Long`
     *
     * 旧版是 Compose State，理由是「它会被 UI 读」—— 但这是个误判：
     * [lyricPositionSample] 的两个调用点（`OverlayContent.kt` 与
     * `HomeScreen.kt`）都是 `remember { { ... } }` 的**lambda 形式**，
     * **不订阅 State**，只在逐帧动画里按需读一次。
     *
     * 既然没有订阅者，Compose 快照就是纯开销：
     *  - 每秒 10~20 次写快照 + 全局快照通知，比一个 volatile 写贵得多；
     *  - 更要紧的是，写它的协程现在跑在后台线程
     *    （见 [startIndexLoop]），而**后台线程写 Compose 快照是不安全的**。
     *
     * `@Volatile` 恰好匹配真实语义：单写多读、只要求可见性、不要求重组通知。
     */
    @Volatile
    private var lyricPosition: Long = 0L

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

    /**
     * v1.15.0：取词配置的指纹。
     *
     * 单独抽成 data class 是为了让 [start] 里的 `combine` 能带四个字段 ——
     * 原本用 `Triple`，加 [artistAliases] 时塞不进去。
     * 用 data class 而非多个 Flow 是为了 `distinctUntilChanged` 只比较一次。
     */
    private data class Config(
        val enabled: Set<String>,
        val order: List<String>,
        val override: Map<String, String>,
        val artistAliases: Map<String, List<String>>,
    )

    fun start(scope: CoroutineScope) {
        startFetchPipeline(scope)
        startIndexLoop(scope)
    }

    /**
     * 曲目或源配置变化 → 重新取词。
     *
     * 拆成独立方法，是为了让 [restartIndexLoop] 只重启下标协程。
     * 早期版本让「重启」直接调 [start]，于是每次自愈都会**额外挂一条取词协程**
     * —— 而取词是要发网络请求的，等于看门狗每救一次火就多泄漏一个协程。
     */
    private fun startFetchPipeline(scope: CoroutineScope) {
        // v1.15.0：把 artistAliases 也纳入配置指纹 ——
        // 用户改完别名必须立刻重新取词，否则新别名要等到下次切歌才生效。
        // 放进 Triple 的第四位（原本是 sourceOverride）。
        val configFlow = SettingsStore.settings
            .map { Config(it.enabledSources, it.sourceOrder, it.sourceOverride, it.artistAliases) }
            .distinctUntilChanged()

        scope.launch {
            combine(PlaybackMonitor.track, configFlow) { track, config -> track to config }
                .distinctUntilChanged { a, b ->
                    a.first?.key() == b.first?.key() && a.second == b.second
                }
                .collect { (track, config) ->
                    load(track, config.enabled, config.order, config.override)
                }
        }
    }

    /**
     * 位置变化 → 更新高亮行（与精度档位无关，跟随 PlaybackMonitor 的刷新频率）。
     *
     * ## v1.18.5：这条协程必须离开 `Dispatchers.Main` —— 本次修复的核心
     *
     * 它原本跑在 `AppScope.main` 上，而 `combine(...).collect{}`
     * 每收到一帧都要在主线程排一次任务。App 退到后台、**没有可见窗口**时，
     * 主线程消息队列的处理时机会被系统限制，于是：
     *
     *  - 刚退出时积压的消息被处理掉一两条 → **表现为「先切一句再卡住」**；
     *  - 之后队列不再被处理 → 下标永远停在那一帧；
     *  - 任何 Activity 出现或悬浮窗弹出 → 进程可见性提升 → 队列恢复
     *    → **表现为「进 App 任意页面都好了」**。
     *
     * 这与「协程异常死亡」的假设**产生完全相同的症状**，
     * 但根因不同：代码一行没错，只是从没被调度过。
     * v1.18.4 加的看门狗同样跑在 `AppScope.main` 上，因此它自己也没被执行 ——
     * 这就是「加了自愈机制却毫无效果」的直接原因。
     *
     * ## 为什么用 `Dispatchers.Default` 而不是 IO
     *
     * 这里只做减法与下标二分，**没有任何阻塞调用**，属CPU 密集；
     * 用 IO 会占用 IO 线程池（上限 64），而那个池本该留给真正阻塞的
     * 取词请求（[fetch] 就在 `AppScope.io` 上）。
     */
    fun startIndexLoop(scope: CoroutineScope) {
        // v1.18.5：这个协程必须有自己的心跳，不能只观测 PlaybackMonitor。
        // 通知栏歌词是两级流水线（MediaSession → positionMs → index → 通知），
        // v1.18.4 只观测了第一级，结果它正常时面板一切正常，
        // 而真正卡住的是这一级 —— 面板看不出任何异常，白查一轮。
        val myGeneration = ++indexGeneration
        scope.launch(Dispatchers.Default) {
            indexRunning = true
            try {
                combine(PlaybackMonitor.positionMs, _state, SettingsStore.settings) { position, st, settings ->
                    Triple(position, st, settings.globalOffsetMs)
                }.collect { (position, st, globalOffset) ->
                    // 换代检查：被 startIndexLoop 换掉就让位给新一代。
                    //
                    // ⚠️ 这里**必须用 `return@collect`，不能用 `return@launch`**。
                    // `Flow.collect` 的接收者是 `crossinline` 的，
                    // 在它的 lambda 里写 `return@launch` 属于**非局部返回**，
                    // 编译期直接报「'return' is prohibited here」
                    // —— v1.18.5 首次构建就是这样挂的（CI 报错定位到 212:58）。
                    //
                    // 顺带说明为什么「跳过这一帧」就够了：
                    // 被换代的协程从此每帧都走这个分支、什么都不做，
                    // 它不会写 `_index`、不会心跳，实质上已经退役；
                    // 同时置 `indexRunning = false`，让看门狗与面板都看到真相。
                    if (indexGeneration != myGeneration) {
                        indexRunning = false
                        Trace.log("idx", "本协程已被换代，退出")
                        return@collect
                    }

                    val adjusted = position - globalOffset - (st.lyric?.offsetMs ?: 0L)
                    lyricPosition = adjusted

                    // v1.18.5：**单轮必须有异常防护**。
                    //
                    // 旧版整个 collect 没有任何 try，异常（indexAt 里的
                    // 边界、歌词行数被中途换掉等）会终止本协程，
                    // 于是 _index 永远停在最后一帧。
                    //
                    // ⇒ 教训：**一条数据流上每个协程都要单独观测**，
                    // 上层心跳正常不代表下层没死。
                    runCatching {
                        _index.value = st.lyric?.lines?.let { LyricParser.indexAt(it, adjusted) } ?: -1
                    }.onFailure {
                        lastIndexError = "${it.javaClass.simpleName}: ${it.message}"
                    }.onSuccess {
                        lastIndexError = null
                    }
                    indexHeartbeatAt = SystemClock.elapsedRealtime()
                    indexHeartbeatCount++
                    // v1.18.6 探针：确认「下标确实在推进」。
                    // 与 ticker 的间隔对比即可判断这一级是否停摆。
                    Trace.changed("idx", "adj=$adjusted line=${_index.value}")
                }
                // 正常结束只发生在被换代或 Flow 结束时，
                // 两者都意味着「本协程不该再被当作健康的」。
                indexRunning = false
            } catch (t: Throwable) {
                // combine/collect 层面的异常（上游 Flow 崩了）同样致命，
                // 也不能让它悄悄把协程带走。
                lastIndexError = "${t.javaClass.simpleName}: ${t.message}"
                indexRunning = false
                throw t
            }
        }
    }

    // ---------------- v1.18.5：下标计算协程的健康观测 ----------------

    /**
     * v1.18.5：本协程最近一次算出下标的时间（`SystemClock.elapsedRealtime()`）。
     *
     * ## 为什么必须与 [PlaybackMonitor] 的心跳分开
     *
     * 通知栏显示的歌词是**两级流水线**：
     * ```
     * MediaSession → PlaybackMonitor.positionMs（协程 A）
     *             → LyricEngine.index      （协程 B）→ 通知
     * ```
     * v1.18.4 只观测了 A，于是 A 正常时面板一切正常，
     * 而真正卡住的是 B —— 面板**看不出任何异常**，白查一轮。
     *
     * ⇒ 每个协程都要有自己的心跳，任一级停摆都能被看到。
     */
    @Volatile
    var indexHeartbeatAt: Long = 0L
        private set

    /** v1.18.5：下标计算累计成功次数（纯诊断用）。 */
    @Volatile
    var indexHeartbeatCount: Long = 0L
        private set

    /**
     * v1.18.5：本协程是否在跑。
     *
     * 死掉时不会自己改回 true —— 只能由 [restartIndexLoop] 或进程重启恢复。
     */
    @Volatile
    var indexRunning: Boolean = false
        private set

    /** v1.18.5：最近一次异常摘要。 */
    @Volatile
    var lastIndexError: String? = null
        private set

    /**
     * v1.18.5：换代序号。
     *
     * 每次 [startIndexLoop] 自增；协程内部记住自己诞生时的值，不一致就退出。
     */
    @Volatile
    private var indexGeneration: Int = 0

    /**
     * v1.18.5：看门狗判定「下标计算已停」的心跳阈值（毫秒）。
     *
     * 取 5 秒的理由与前台服务那套一致：
     * 播放档最快 50ms 一轮，慢档 200ms，连续 5 秒不动必然是死了。
     * 注意这比「应当更新一次」的间隔宽松得多，避免把
     * 「暂停/ 没歌时本就不更新」误判成死掉。
     */
    const val INDEX_STALE_MS = 5_000L

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

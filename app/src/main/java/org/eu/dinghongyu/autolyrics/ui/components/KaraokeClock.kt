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

package org.eu.dinghongyu.autolyrics.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlinx.coroutines.flow.first

/**
 * **逐字歌词的独立时钟。**
 *
 * ## 要解决的问题
 * 用户要的擦除规则是：字分配到 1 秒时，
 * 0ms 完全不亮、100ms 亮 1/10、200ms 亮 2/10……
 *
 * 旧实现拿 [org.eu.dinghongyu.autolyrics.lyric.LyricEngine.lyricPositionMs] 当进度源，
 * 而它的更新频率受 `PrecisionMode.pollMs` 限制
 * （省电 200ms / 标准 100ms / 精准 50ms）。于是：
 *
 *  - 一个 1 秒的字在「标准」档下**每帧前进 10%**，只能画出 10 级台阶；
 *  - 更糟的是这些台阶**不等距**——轮询与字的时间轴不相位，
 *    会出现"某个字 30% 时整整停留 100ms"的卡顿感；
 *  - 省电档（200ms）更是一个字只更新 5 次，逐字基本等于整行跳变。
 *
 * 换句话说：**不是渲染算法不够细，是喂进来的时间戳本身就只有 10 级分辨率。**
 * 把渐变做得再准，也只能在 10 个台阶之间跳。
 *
 * ## 这里的做法
 * 播放位置仍然从外部取（它是低频的、来自 Binder 的事实），
 * 但**只用来校准**，推进由 [withFrameNanos] 逐帧完成：
 *
 * ```
 * 起始值 initialValue（调用方给的一次性采样值）
 * 上次记账时刻 lastFrameAt（**每帧都更新**，无论正常还是异常）
 * 每帧：value += (现在 Nanos - lastFrameAt) / 1_000_000
 * 每 16 帧：与真实播放位置比对，偏差超过 80ms 就拉回去
 * ```
 *
 * 注意这是**增量式**：每帧只加「距上一帧的间隔」，而不是
 * 「起始值 + 总经过时间」。v1.12.0 之前用的是后者并带一个 1500ms 总量上限，
 * 但因为基准值在一行内不变，那个上限实际等价于「歌词行最长 1.5 秒」，
 * 导致长句的高亮走到 1.5s 就停住（build36 修的就是这个）。
 *
 * 增量式**必须每帧更新基准**，否则会退化成平方级增长
 * （第 n帧累计 ≈ 16.7 × n(n+1)/2 毫秒，0.17 秒就能冲过 1 秒的歌词）。
 * 这正是 build36 的错误，详见循环内的注释。
 *
 * 进度因此与显示刷新率一致（通常 60~120fps），
 * 1 秒的字会被切成 60~120 级台阶 —— 肉眼就是连续擦除。
 *
 * ## 性能：为什么这不会烧 CPU
 * 关键在于**只有当前行在跑这个时钟**，而且它只写一个 [Long]：
 *
 *  - 写入走 [androidx.compose.runtime.State]，读取方（绘制层）只触发
 *    **draw invalidation**，不触发重组（见 [rememberSmoothKaraokeProgress] 的用法）；
 *  - 逐字渐变的边界由像素宽度算出并缓存，只有边界那两个数会变，
 *    不需要每帧重跑文本测量；
 *  - 非当前行直接返回常量 0，不产生任何帧回调；
 *  - **暂停时协程整体挂起**（不是空转），见下方暂停分支的说明。
 *
 * 换来的代价是**每帧一次 Long 写入 + 一次渐变端点更新**，
 * 相比每帧重跑 buildAnnotatedString 是数量级的下降。
 */
@Composable
fun rememberKaraokeClock(
    /** 是否需要逐帧推进（当前行 + 逐字开启） */
    active: Boolean,
    /**
     * 播放位置（毫秒）的**按需读取器**。
     *
     * 收 lambda 而不是值，是为了让「校准」能在协程里现读，
     * 而不必把它塞进 [produceState] 的 key ——
     * 它每秒变 10~20 次，放进 key 会让时钟每秒重启 10~20 次，逐字动画直接卡住。
     *
     * 调用方应当传一个**身份稳定**的 lambda（例如
     * `remember { { LyricEngine.lyricPositionSample() } }`），
     * 否则每次重组传新实例会让这里每次重组都重新包一层。
     */
    positionMs: () -> Long,
    /** 暂停时冻结进度（保持已唱部分的高亮，不归零） */
    playing: Boolean,
    /**
     * 显式的重置键：**换歌时**传歌词行标识（或任何"这首歌变了"的标记）。
     *
     * 为什么要显式给：key 里不能放 positionMs（它每秒变 10~20 次，
     * 放进 key 等于每秒重启时钟 10~20 次，逐字动画会卡住不动）。
     * 但切歌又确实需要把进度重置到新歌的起点——
     * 这两件事用 positionMs 表达不了，所以拆成两个参数。
     *
     * 传 null（默认）表示"不主动重置"，仅靠 active 变化控制。
     */
    resetKey: Any? = null,
): State<Long> {
    // 非当前行：恒 0，不产生任何帧回调。
    //
    // 此时调用方走的是纯色 Text 分支，根本不会读这个值
    //（见 [HomeScreen] 里 `karaoke` 的判定），所以 0 是安全的。
    if (!active) return remember { mutableLongStateOf(0L) }

    // 这两个值会**在协程运行期间被重组改掉**，所以要包 rememberUpdatedState。
    // 用它而不是直接读参数，是为了让它们**不必进 produceState 的 key**：
    // playing 一旦进 key，暂停/恢复就会重启协程，
    // 而重启会把进度重置到 initialValue —— 那正是 build36 之前
    // 「一恢复播放整行全亮」的直接原因。
    // 刻意用显式的 .value 而不是 `by` 委托。
    //
    // 【踩坑记录】`by` 委托写法要求 import androidx.compose.runtime.getValue，
    // 而 State 接口本身并没有 getValue 方法（它在扩展函数里）。
    // 漏掉这个 import 时的报错非常有迷惑性：
    //   "Type 'State<Function0<Long>>' has no method 'getValue(Nothing?, KProperty0<*>)',
    //    so it cannot serve as a delegate."
    // 报错里既没有「缺少 import」也没有「getValue」这个关键词提示，
    // 光看这句话只会怀疑是不是 rememberUpdatedState 用错了。
    // 显式 .value 一眼就能看懂，也不必多一个 import。
    val currentPosition = rememberUpdatedState(positionMs)
    val currentPlaying = rememberUpdatedState(playing)

    // 起始值只在「这个时钟的生命周期开始时」采样一次。
    //
    // 用 remember 包一层，而不是直接把 currentPosition.value() 写在 initialValue 里：
    // 后者会在**每次重组**都读一次 State，而这里是组合期读取，
    // 会让调用方（AppleLyricLine）白白订阅一个它不关心的状态。
    // 键与 produceState 的键保持一致，两者的生命周期因此严格同步。
    val startPosition = remember(active, resetKey) { positionMs() }

    // key 里只有 active + resetKey，**刻意不含 positionMs 和 playing**。
    return produceState(initialValue = startPosition, active, resetKey) {
        // 上一次记账的帧时刻。
        // 用 nanoTime 而不是 SystemClock.elapsedRealtime：
        // 前者单调递增且不受用户改系统时间影响。
        var lastFrameAt = System.nanoTime()
        // 距离上次校准真实播放位置过去了多少帧
        var syncCounter = 0

        while (true) {
            // ---- 暂停：把整个协程挂起，而不是空转帧回调 ----
            //
            // v1.12.1 之前是 `if (!active || !playing) return 常量 0`，
            // 两个毛病：
            //  1. 归零会让 [LyricText] 收到 pos=0，命中
            //     `pos < first.startMs` 快路径 → 整行变暗，
            //     用户看到的就是「一暂停所有歌词都不高亮」；
            //  2. 恢复时 produceState 重建，initialValue 是一次全新的采样，
            //     于是画面从 0 瞬间跳到当前真实进度 → 「整行全部亮起来」。
            //
            // 挂起则两者都避开：value 原地冻结，
            // 而「继续播放」只是把这个协程唤醒，value 一路延续。
            //
            // 用 snapshotFlow 而不是 while(!playing) 空转：
            // 后者会让应用永远停在 60fps 的帧回调里，无法进入 idle。
            if (!currentPlaying.value) {
                snapshotFlow { currentPlaying.value }.first { it }
                // 恢复时重置基准：暂停期间可能过了很久，
                // 下一帧的 delta 会很大，正好被 MAX_FRAME_GAP_MS 挡掉。
                lastFrameAt = System.nanoTime()
                syncCounter = 0
                continue
            }

            // 睡到下一帧。FrameClock 会等 vsync，
            // 因此循环频率天然等于屏幕刷新率，不需要自己算 sleep 时长。
            withFrameNanos { nowNanos ->
                // v1.12.1 修正（第二次）。
                //
                // build36 的写法是：
                //     val deltaMs = (nowNanos - baseAt) / 1_000_000L
                //     if (deltaMs in 0..MAX) value += deltaMs else baseAt = nowNanos
                //
                // 那个写法有两个错：
                //
                // **错误一：基准只在 else 分支更新。**
                // 于是 deltaMs 算的是「距时钟启动」而不是「距上一帧」，
                // 每帧又把它整个累加进 value —— 这是**平方级增长**：
                //   第 n 帧后累计 ≈ 16.7 × n(n+1)/2 毫秒
                // 10 帧（约 0.17 秒）就冲过 1 秒的歌词，
                // 整行 4 秒的词大约 0.4 秒扫完——
                // 用户看到的正是「一行一下子从左到右亮完了，
                // 可歌还没唱到那个字」；冲过行尾后还会命中
                // `pos >= last.startMs + last.durationMs` 快路径变成整行纯亮。
                //
                // **错误二：只增不减，从不与真实播放位置校准。**
                // 纯累加一旦有偏差（音频时钟漂移、变速、seek）就永远偏着。
                val deltaMs = (nowNanos - lastFrameAt) / 1_000_000L

                // 关键：**每帧都更新基准**，无论正常还是异常。
                lastFrameAt = nowNanos

                if (deltaMs in 0..MAX_FRAME_GAP_MS) {
                    value += deltaMs
                }
                // 间隔异常大（息屏/切后台/严重卡顿）：本帧的增量直接丢弃。
                // 因为基准已经更新，下一帧会从现在重新算，
                // 于是进度「接着走」而不是「跳一大段」。
                // 正常播放时相邻帧间隔绝不超过 ~35ms，所以不会误伤。
                //
                // 但这也意味着长时间卡顿后 value 会**落后**于真实位置，
                // 那要靠下面的周期性校准补回来。

                // ---- 周期性校准：平滑靠累加，准确靠校准 ----
                //
                // 累加只保证「不跳变」，不保证「跟得上」。
                // 音频时钟漂移、播放器变速、用户 seek 都会让真实位置
                // 偏离累加出来的 value，而且偏差只增不减。
                //
                // 16 次/秒（60fps 下约每秒一次）足够及时，
                // 又不会频繁跨进程读位置造成抖动。
                syncCounter++
                if (syncCounter >= SYNC_EVERY_N_FRAMES) {
                    syncCounter = 0
                    val real = currentPosition.value()
                    // 双向比较并设阈值：小于 80ms 肉眼察觉不到，
                    // 大于它明显能看到高亮和歌声对不上。
                    // 必须双向——只判`real > value` 的话，往回 seek 之后
                    // value 会一直超前到冲出行尾，整行又变成全亮。
                    if (abs(real - value) > RESYNC_THRESHOLD_MS) {
                        value = real
                    }
                }
            }
        }
    }
}

/**
 * v1.12.1：单帧间隔上限（毫秒）—— 超过就认为发生了掉帧/息屏/后台，只重置基准。
 *
 * 取 400ms 的理由：60Hz 下一帧约 16.7ms，120Hz 约 8.3ms，
 * 即便是 30fps 的低端机也只有 33ms。400ms 相当于「连续丢了 20 帧以上」，
 * 这种情况基本只来自息屏/切后台/严重卡顿，此时**丢弃这一帧的增量**是对的
 * —— 否则进度会凭空跳一大段，整行瞬间高亮完。
 *
 * 反过来，正常播放时相邻帧间隔绝不超过 ~35ms，所以这个阈值
 * **不会误伤**正常的逐字推进（包括长句、慢歌）。
 */
private const val MAX_FRAME_GAP_MS = 400L

/**
 * v1.12.1：每多少帧校准一次真实播放位置。
 *
 * 60fps 下 16 帧≈ 1 秒，120fps 下≈ 0.13 秒 —— 两边都够及时。
 * 再密就要跨进程读位置（Binder），性价比不划算；
 * 再疏则 seek 之后高亮要"慢半拍"才跟上。
 */
private const val SYNC_EVERY_N_FRAMES = 16

/**
 * v1.12.1：校准阈值（毫秒）—— 偏差超过它才把进度拉回真实位置。
 *
 * 取 80ms：小于它肉眼基本察觉不到（唱歌本身就有音准容差），
 * 大于它就能明确看出「字亮着但歌还没唱到」。
 *
 * 之所以不设成 0：无条件对齐会让每帧的抖动直接写进画面，
 * 反而把累加带来的平滑优势全丢掉，还可能出现高频抖动。
 */
private const val RESYNC_THRESHOLD_MS = 80L

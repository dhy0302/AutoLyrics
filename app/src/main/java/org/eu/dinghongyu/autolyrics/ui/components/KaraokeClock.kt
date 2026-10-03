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
import androidx.compose.runtime.withFrameNanos

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
 *  - 一个 1 秒的字在「标准」档下**每帧前进 10%**，只能画出10 级台阶；
 *  - 更糟的是这些台阶**不等距**——轮询与字的时间轴不相位，
 *    会出现"某个字 30% 时整整停留 100ms"的卡顿感；
 *  - 省电档（200ms）更是一个字只更新 5 次，逐字基本等于整行跳变。
 *
 * 换句话说：**不是渲染算法不够细，是喂进来的时间戳本身就只有10 级分辨率。**
 * 把渐变做得再准，也只能在 10 个台阶之间跳。
 *
 * ## 这里的做法
 * 播放位置仍然从外部取（它是低频的、来自 Binder 的事实），
 * 但**只取一次**作为基准；之后用 [withFrameNanos] 逐帧自己推进：
 *
 * ```
 * 起始值initialValue（调用方给的一次性采样值）
 * 基准时刻 baseAt（每帧更新）
 * 每帧：value += (现在Nanos - baseAt) / 1_000_000
 * ```
 *
 * 注意这是**增量式**：每帧只加「距上一帧的间隔」，而不是
 * 「起始值 + 总经过时间」。v1.12.1 之前用的是后者并带一个 1500ms 总量上限，
 * 但因为基准值在一行内不变，那个上限实际等价于「歌词行最长 1.5 秒」，
 * 导致长句的高亮走到 1.5s 就停住。详见下方循环内的注释。
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
 *  - 非当前行的时钟直接返回常量 0，不产生任何帧回调；
 *  - 播放暂停 / 页面不可见时不启动（见 [running]），
 *    逐字动画本身就只在"正在唱"时才有意义。
 *
 * 换来的代价是**每帧一次 Long 写入 + 一次渐变端点更新**，
 * 相比每帧重跑 buildAnnotatedString 是数量级的下降。
 */
@Composable
fun rememberKaraokeClock(
    /** 是否需要逐帧推进（当前行 + 逐字开启 + 正在播放） */
    active: Boolean,
    /**
     * 播放位置（毫秒）。
     *
     * ## 只作为基准，不是进度源
     *
     * 本函数只在 [produceState] 的 key 里用它采样一次「起点」，
     * 之后每帧只加「距上一帧的间隔」，**与它无关**。
     * 所以调用方**不需要**（也不应该）把它接成每秒变 10~20 次的 State ——
     * 那会让这个 key 每秒变 10~20 次，从而**每帧重启整个 produceState**，
     * 时钟变成反复重置的 0，动画反而不动了。
     *
     * v1.12.1 起调用方传的是「现读」的值而非响应式订阅，
     * 见 [ui.screen.HomeScreen] 里AppleLyricLine 的 positionMs 参数说明。
     */
    positionMs: Long,
    /** 暂停时冻结 */
    playing: Boolean,
    /**
     * 显式的重置键：**换歌时**传歌词行标识（或任何"这首歌变了"的标记）。
     *
     * 为什么要显式给：key 里不能放 positionMs（它每秒变 10~20 次，
     * 放进 key 等于每秒重启时钟 10~20 次，逐字动画会卡住不动）。
     * 但切歌又确实需要把进度重置到新歌的起点 ——
     * 这两件事用 positionMs 表达不了，所以拆成两个参数。
     *
     * 传null（默认）表示"不主动重置"，仅靠 active 变化控制。
     */
    resetKey: Any? = null,
): State<Long> {
    // 播放暂停时直接给常量 0：不启动帧回调，一分 CPU 都不花。
    if (!active || !playing) return remember { mutableLongStateOf(0L) }

    // key 里是 active + resetKey，**刻意不含 positionMs** ——
    // 每帧推进由 withFrameNanos 完成，positionMs 变了也不该重启时钟。
    return produceState(initialValue = positionMs, active, resetKey) {
        // 基准时刻：上一次记账的帧时刻。
        // 用 nanoTime 而不是 SystemClock.elapsedRealtime：
        // 前者单调递增且不受用户改系统时间影响。
        //
        // 注意这里**不再需要 baseMs**（旧版用它做基准值 + 1500ms 上限），
        // 因为改成增量式推进后，每帧只加「距上一帧的间隔」，
        // 进度天然连续，不再依赖「起点 + 总经过时间」这套算法。
        var baseAt = System.nanoTime()
        while (true) {
            // 睡到下一帧。FrameClock 会等 vsync，
            // 因此循环频率天然等于屏幕刷新率，不需要自己算 sleep 时长。
            withFrameNanos { nowNanos ->
                // v1.12.1 修正：这里曾写成
                //     value = (baseMs + elapsedMs).coerceAtMost(baseMs + 1_500L)
                // 那个 1500ms 上限的**本意**是「播放状态丢了很久时别瞬移」，
                // 但它被写成了「相对基准值的总量上限」——
                // 而基准值在整行播放期间是**不变的**，
                // 于是任何超过 1.5 秒的歌词行，高亮走到 1.5s 就永远停住
                // （用户反馈的现象：逐字高亮卡住不动）。
                //
                // 为什么以前没暴露：`positionMs` 曾经在 produceState 的 key 里，
                // 它每 100ms 变一次 → 每 100ms 重启协程 → 基准值被不断刷新，
                // 所以那个上限永远碰不到（等于一行歌词的时长上限）。
                // v1.12.1 为了消除「每秒 10~20 次全页重组」把 positionMs
                // 从 key 里移除（那个优化本身是对的），
                // 于是基准值在一行内固定下来，这个上限才变成了硬伤。
                //
                // 现在改成**增量式**推进：每帧只加「距上一帧的间隔」，
                // 并对**单帧间隔**设上限，而不是对总量设上限。
                //   · 正常播放：每帧 +16ms，行内时间线性增长，任意行长度都能走完
                //   · 息屏/后台/长卡顿回来：那一帧间隔巨大 → 只重置基准，
                //     不会累积出一个荒谬的进度（原注释想防的正是这个）
                val deltaMs = (nowNanos - baseAt) / 1_000_000L
                if (deltaMs in 0..MAX_FRAME_GAP_MS) {
                    value += deltaMs
                } else {
                    // 间隔异常大（> MAX_FRAME_GAP_MS）：判定为「掉帧/息屏/后台」，
                    // 把基准挪到现在，进度接着走而不是跳一大段。
                    baseAt = nowNanos
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

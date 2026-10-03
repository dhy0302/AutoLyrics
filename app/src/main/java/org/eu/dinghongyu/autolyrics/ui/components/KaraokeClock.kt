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
 * 基准位置 baseMs（取自 PlaybackMonitor）
 * 基准时刻 baseAtNanos（取自 FrameClock）
 * 每帧：现在 = baseMs + (现在Nanos - baseAtNanos) / 1_000_000
 * ```
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
    /** 播放位置（毫秒）。基准值，只在它自身变化时被采样。 */
    positionMs: Long,
    /** 暂停时冻结 */
    playing: Boolean,
): State<Long> {
    // 播放暂停时直接给常量 0：不启动帧回调，一分 CPU 都不花。
    if (!active || !playing) return remember { mutableLongStateOf(0L) }

    return produceState(initialValue = positionMs, positionMs, active) {
        // 记录"位置事实"与"时刻事实"的对应关系。
        // 用 nanoTime 而不是 SystemClock.elapsedRealtime：
        // 前者单调递增且不受用户改系统时间影响。
        val baseAt = System.nanoTime()
        val baseMs = value
        while (true) {
            // 睡到下一帧。FrameClock 会等 vsync，
            // 因此循环频率天然等于屏幕刷新率，不需要自己算 sleep 时长。
            withFrameNanos { nowNanos ->
                val elapsedMs = (nowNanos - baseAt) / 1_000_000L
                // 上限 1500ms：超过说明播放状态丢了很久，
                // 继续推会算出荒谬的进度。不设限的话切歌/暂停恢复时会整行瞬移。
                value = (baseMs + elapsedMs).coerceAtMost(baseMs + 1_500L)
            }
        }
    }
}

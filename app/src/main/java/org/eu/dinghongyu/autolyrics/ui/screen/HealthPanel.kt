/*
 * AutoLyrics — 安卓自动歌词
 * Copyright (C) 2026 丁宏宇
 *
 * 本程序遵循 GNU General Public License v3.0 或更高版本发布。
 * 详见仓库根目录的 LICENSE 文件。
 */

package org.eu.dinghongyu.autolyrics.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.eu.dinghongyu.autolyrics.lyric.LyricEngine
import org.eu.dinghongyu.autolyrics.media.LyricsForegroundService
import org.eu.dinghongyu.autolyrics.media.MediaSessionWatcher
import org.eu.dinghongyu.autolyrics.media.PlaybackMonitor
import org.eu.dinghongyu.autolyrics.ui.notify.NotifyLyrics

/**
 * v1.18.4 起的后台链路健康面板。
 *
 * ## 为什么需要它
 *
 * 这个 bug 反复误判了两次，根源都是**失效时不崩溃、不打日志**，
 * 只能靠症状推理，而后台症状极易互相伪装。
 *
 * 面板的价值是**把「哪一级没在动」变成一眼可见的事实**，
 * 而不是让人再猜一轮。
 *
 * ## 为什么必须分两级看（v1.18.5 加的）
 *
 * 通知栏歌词是**两级流水线**：
 * ```
 * ① MediaSession → PlaybackMonitor.positionMs
 * ② positionMs → LyricEngine.index → 通知
 * ```
 *
 * v1.18.4 只观测了 ①，于是 ① 正常时面板一片正常，
 * 而真正卡住的是 ② —— 白查了一轮。
 *
 * ⇒ **一级心跳正常不代表链路健康**，每级都要单独看。
 *
 * ## 怎么用
 *
 * 复现问题后打开这一页：
 *  - **① 正常、② 停住** → 歌词下标计算停了
 *  - **① 停住** → 播放进度轮询停了
 *  - **①② 正常、③ 停住** → 通知刷新层停了。前台服务失去载体，
 *    被系统回收 ⇒ 连桌面歌词也会一起停
 *  - **「最近异常」有内容** → 直接写明被什么打断
 *
 * ## v1.18.5 的教训：面板必须覆盖**每一级**
 *
 * 前两版的面板只盯第一级，于是「第一级正常、第二级已停」这种情况
 * 显示为**一片正常** —— 比没有面板更误导，因为它给了虚假的安心感。
 *
 * 可靠的做法只有一条：**链路上每个协程都要有自己的心跳与展示位**。
 * 这个 bug 因此误判了两轮才找到真正的原因。
 */
@Composable
fun HealthPanel(modifier: Modifier = Modifier) {
    // 每秒刷新，让「N 秒前」持续走动。
    //
    // 为什么不用 collectAsState：这些是普通 `var`，不是 Flow，collect 不到。
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            tick++
            delay(1000)
        }
    }

    val now = android.os.SystemClock.elapsedRealtime()

    // 组合期读一次 tick，确立订阅关系。
    //
    // Compose 只在「组合期读到的 State 变化」时重组。
    // tick 由上面的协程每秒 +1，若这里不读它，那个 State 就没人订阅，
    // 重组不会发生，年龄会永远停在打开面板那一秒 ——
    // 而且看起来一切正常，只是数字不动，反而误导排查。
    @Suppress("UNUSED_EXPRESSION")
    tick

    val posAge = ageOf(PlaybackMonitor.lastHeartbeatAt, now)
    val idxAge = ageOf(LyricEngine.indexHeartbeatAt, now)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("后台链路健康", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))

            HealthRow(
                "① 播放进度",
                "${stageText(posAge, PlaybackMonitor.tickerRunning)} · ${PlaybackMonitor.heartbeatCount} 轮",
                stageColor(posAge, PlaybackMonitor.tickerRunning),
            )
            HealthRow(
                "② 歌词行下标",
                "${stageText(idxAge, LyricEngine.indexRunning)} · ${LyricEngine.indexHeartbeatCount} 轮",
                stageColor(idxAge, LyricEngine.indexRunning),
            )
            HealthRow(
                "③ 通知刷新",
                if (NotifyLyrics.jobRunning) "运行中" else "已停止",
                if (NotifyLyrics.jobRunning) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
            HealthRow(
                "前台服务",
                if (LyricsForegroundService.isRunning) "运行中" else "未运行",
                if (LyricsForegroundService.isRunning) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
            HealthRow(
                "会话抓取",
                if (MediaSessionWatcher.isLinked) "已链接" else "未链接",
                if (MediaSessionWatcher.isLinked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
            HealthRow("看门狗重启", "${PlaybackMonitor.restartCount} 次", MaterialTheme.colorScheme.secondary)

            PlaybackMonitor.lastError?.let {
                HealthRow("① 最近异常", it, MaterialTheme.colorScheme.error)
            }
            LyricEngine.lastIndexError?.let {
                HealthRow("② 最近异常", it, MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 心跳时间戳距今多少毫秒；从未跑过返回 -1。 */
private fun ageOf(beat: Long, now: Long): Long =
    if (beat == 0L) -1L else now - beat

/** 单级的状态文案。 */
@Composable
private fun stageText(ageMs: Long, running: Boolean): String = when {
    ageMs < 0 -> "尚未开始"
    !running -> "已停止"
    else -> "${ageMs / 1000} 秒前"
}

/** 单级的配色：灰=未开始，红=停了，橙=偏慢，主色=正常。 */
@Composable
private fun stageColor(ageMs: Long, running: Boolean) = when {
    ageMs < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
    !running || ageMs > STALE_MS -> MaterialTheme.colorScheme.error
    ageMs > STALE_MS / 2 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun HealthRow(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(value, fontSize = 12.sp, color = color)
    }
}

/** 与 [LyricsForegroundService] 的判定阈值一致。 */
private const val STALE_MS = 5_000L
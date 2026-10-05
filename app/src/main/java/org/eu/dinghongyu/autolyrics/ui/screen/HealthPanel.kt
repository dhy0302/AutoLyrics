/*
 * AutoLyrics — 安卓自动歌词
 * Copyright (C) 2026 丁宏宇
 *
 * 本程序遵循 GNU General Public License v3.0 或更高版本发布。
 * 详见仓库根目录的 LICENSE 文件。
 */

package org.eu.dinghongyu.autolyrics.ui.screen

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.eu.dinghongyu.autolyrics.media.LyricsForegroundService
import org.eu.dinghongyu.autolyrics.media.MediaSessionWatcher
import org.eu.dinghongyu.autolyrics.media.PlaybackMonitor

/**
 * v1.18.4：后台链路健康面板。
 *
 * ## 为什么需要它
 *
 * 这个 bug 之所以反复误判，是因为**协程静默死亡没有任何外部症状**：
 * 不崩溃、不打日志、通知照常显示 —— 只是内容永远停在那一句。
 * v1.18.2 因此把根因错判成「进程被冻结」，加了前台服务却没修好。
 *
 * 而「打开 App 就恢复」这个现象**两种根因都能解释**：
 *  - 进程被冻结 → 打开 App 解冻了进程；
 *  - 协程已死 → `MainActivity.onResume` 会手动调一次
 *    [PlaybackMonitor.update]，状态瞬间"活"过来。
 *
 * 也就是说这个现象**根本无法区分**两者。必须有可观测量才能定案。
 *
 * ## 怎么用
 *
 * 复现问题后打开这一页，看「播放轮询」那一行：
 *  - 「运行中 · 3秒前」→ 链路健康，问题在别处；
 *  - 「**已停止**」→ ticker 死了（看门狗会在 5 秒内自愈并计入重启次数）；
 *  - 「异常：DeadObjectException…」→ 播放器进程被回收导致 Binder 失败。
 *
 * 停留几秒观察心跳是否在走 —— 它每轮都会更新。
 */
@Composable
fun HealthPanel(modifier: Modifier = Modifier) {
    // 每秒刷新一次，让「N秒前」持续走动。
    //
    // 为什么不用 collectAsState：心跳是普通 `var`，不是 Flow，collect 不到。
    // 用一个 State 计数当「重组触发器」—— LaunchedEffect 每写它一次就触发一次重组，
    // 而读心跳的代码就在同一次重组里重新算出新的年龄。
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            tick++
            delay(1000)
        }
    }

    val now = android.os.SystemClock.elapsedRealtime()
    val beat = PlaybackMonitor.lastHeartbeatAt
    val ageMs = if (beat == 0L) -1L else now - beat
    val running = PlaybackMonitor.tickerRunning

    // 组合期读一次 tick，确立订阅关系。
    //
    // Compose 的重组规则是「组合期读到的 State 变化时重组」。
    // tick 由上面的协程每秒 +1，若这里不读它，那个 State 就没人订阅，
    // 重组不会发生，年龄会永远停在打开面板那一秒算出的值。
    // 所以它必须以「被读取」的形式出现在组合逻辑里。
    @Suppress("UNUSED_EXPRESSION")
    tick

    // 颜色即结论：红=链路死了，黄=有心跳但有异常记录，其余正常。
    val statusColor = when {
        !running -> MaterialTheme.colorScheme.error
        PlaybackMonitor.lastError != null -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val statusText = when {
        beat == 0L -> "尚未开始"
        !running -> "已停止"
        ageMs <= STALE_MS -> "${ageMs / 1000} 秒前"
        else -> "心跳滞后 ${ageMs / 1000} 秒"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("后台链路健康", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            HealthRow("播放轮询", "$statusText · ${PlaybackMonitor.heartbeatCount} 轮", statusColor)
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
                HealthRow("最近异常", it, MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun HealthRow(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            value,
            fontSize = 12.sp,
            color = color,
        )
    }
}

/** 与 [org.eu.dinghongyu.autolyrics.media.LyricsForegroundService] 的判定阈值一致。 */
private const val STALE_MS = 5_000L
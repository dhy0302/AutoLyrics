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

package org.eu.dinghongyu.autolyrics.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.eu.dinghongyu.autolyrics.data.TrackInfo
import org.eu.dinghongyu.autolyrics.lyric.Candidate
import org.eu.dinghongyu.autolyrics.lyric.LyricEngine
import org.eu.dinghongyu.autolyrics.lyric.LyricRepository
import org.eu.dinghongyu.autolyrics.media.PlaybackMonitor
import org.eu.dinghongyu.autolyrics.util.SettingsStore
import org.eu.dinghongyu.autolyrics.util.Trace
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * 「歌词源」页：手动搜索排查。
 *
 * 当自动取词失败时，用它逐源看两件事：
 *  1. 能不能搜到候选（国内接口是否有地域/风控问题）
 *  2. 候选得分够不够（低于 0.70 连候选池都进不去，会被直接跳过）
 *
 * v1.13.8：自动取词改为多轮递降阈值（见 LyricRepository.scoreLadder，
 * 当前 1.00 / 0.97 / 0.95 / 0.90 / 0.85 / 0.80 / 0.75 / 0.70），
 * 每轮都从第一个源重新扫一遍。所以「候选得分」这一列的意义变了：
 * 它决定了这个候选能在第几轮被试到，而不是像从前那样「够 0.55 就用」。
 */
@Composable
fun DebugScreen(modifier: Modifier = Modifier) {
    val state by LyricEngine.state.collectAsState()
    val track by PlaybackMonitor.track.collectAsState()
    val scope = rememberCoroutineScope()
    val override by SettingsStore.settings.collectAsState()

    var title by remember { mutableStateOf(track?.title ?: "") }
    var artist by remember { mutableStateOf(track?.artist ?: "") }
    var durationSec by remember { mutableStateOf(((track?.durationMs ?: 0L) / 1000).toString()) }
    var sourceId by remember { mutableStateOf(LyricRepository.allSources.first().id) }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Pair<Candidate, Double>>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    // 播放中的歌变化时，把查询框同步成当前曲目，省去手输
    var lastKey by remember { mutableStateOf("") }
    val key = track?.key().orEmpty()
    if (key.isNotEmpty() && key != lastKey) {
        lastKey = key
        title = track?.title.orEmpty()
        artist = track?.artist.orEmpty()
        durationSec = ((track?.durationMs ?: 0L) / 1000).toString()
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // v1.18.4：后台链路健康面板放在最上方。
        // 这个 bug 的根因曾是「协程静默死亡」，没有任何外部症状可观察；
        // 把心跳暴露出来，下次再出问题看一眼就能定案，不必再猜。
        HealthPanel()
        Spacer(Modifier.height(8.dp))
        TraceExportRow()
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("歌名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = artist,
            onValueChange = { artist = it },
            label = { Text("歌手") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = durationSec,
            onValueChange = { durationSec = it },
            label = { Text("时长（秒，用于匹配校验）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LyricRepository.allSources.forEach { source ->
                AssistChip(
                    onClick = { sourceId = source.id },
                    label = { Text(source.displayName, fontSize = 11.sp) },
                    colors = chipColors(selected = sourceId == source.id),
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // 当前曲目已锁定的歌词来源
        val trackKey = track?.key().orEmpty()
        val ovSource = override.sourceOverride[trackKey]
        if (trackKey.isNotBlank() && ovSource != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Text(
                    "已锁定来源：${LyricRepository.sourceName(ovSource)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                MiniAction("清除") {
                    SettingsStore.update { s ->
                        s.copy(
                            sourceOverride = s.sourceOverride - trackKey,
                            sourceOverrideCandidate = s.sourceOverrideCandidate - trackKey,
                        )
                    }
                    LyricEngine.refresh(force = true)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (title.isNotBlank() && !loading) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                .clickable(enabled = title.isNotBlank() && !loading) {
                    loading = true
                    error = null
                    scope.launch {
                        val query = TrackInfo(
                            title = title,
                            artist = artist,
                            durationMs = (durationSec.toLongOrNull() ?: 0L) * 1000,
                        )
                        results = try {
                            LyricRepository.debugSearch(query, sourceId)
                        } catch (t: Throwable) {
                            error = t.message
                            emptyList()
                        }
                        loading = false
                    }
                }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (loading) "搜索中…" else "用「${LyricRepository.sourceName(sourceId)}」搜索",
                style = MaterialTheme.typography.labelLarge,
                color = if (title.isNotBlank() && !loading) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            // 阈值从 LyricRepository 读，不在文案里再硬编码一份 ——
            // 否则将来调整档位，逻辑改了而这里还写着旧数字，会误导排查。
            text = "候选与匹配得分" +
                "（≥${LyricRepository.minAcceptScore} 进入候选池，" +
                "按 ${LyricRepository.scoreLadder.size} 轮阈值逐档放宽）",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )

        LazyColumn(Modifier.weight(1f).padding(top = 6.dp)) {
            itemsIndexed(results) { _, pair ->
                val (candidate, score) = pair
                CandidateCard(
                    candidate = candidate,
                    score = score,
                    onUseSource = { applyOverride(trackKey, candidate.sourceId, null) },
                    onUseCandidate = { applyOverride(trackKey, candidate.sourceId, candidate.id) },
                )
            }
            item {
                if (error != null) {
                    Text("失败：$error", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                if (results.isEmpty() && !loading) {
                    Text("暂无结果", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
        }

        Text("上次自动取词过程", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                .padding(8.dp)
        ) {
            if (state.attempts.isEmpty()) {
                Text("—", fontSize = 11.sp)
            } else {
                state.attempts.forEach { attempt ->
                    Text(
                        text = "${if (attempt.ok) "✓" else "✗"} ${attempt.displayName}：${attempt.note}",
                        fontSize = 11.sp,
                        color = if (attempt.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * v1.18.6：探针日志导出。
 *
 * 「通知栏歌词后台停住」这个 bug 从 v1.14 前后开始，前三轮修复（前台服务 /
 * 协程心跳看门狗 / 搬到后台线程）全部无效——因为它们都只能证明「代码没写错」，
 * 而用户反馈「以前版本没这个问题」说明代码本来就是对的，是别的东西变了。
 * 静态推理在这里已经用尽了，只能造可观测量。
 *
 * 所以需要用户把 filesDir/trace/trace.log 导出来：那里记着
 * positionMs / index / 通知提交 / 生命周期 / 看门狗五路事件的时间戳，
 * 以及每条「距上一条隔了多久」——停摆一眼就能看出来，不用再猜。
 *
 * 走系统分享而不是直接写 Downloads，是因为不需要存储权限，
 * 且用户能顺手贴到聊天里。
 */
@Composable
private fun TraceExportRow() {
    val context = LocalContext.current
    // 手动用 key 驱动重读：文件读盘不该在重组里反复发生（最多 4000 行），
    // 但清空/复现之后又必须能看到最新的行数，所以给一个显式的重读开关。
    var readKey by remember { mutableStateOf(0) }
    val text = remember(readKey) { Trace.readAll() }
    val lines = remember(text) { if (text.isBlank()) 0 else text.count { it == '\n' } + 1 }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (lines == 0) "探针日志：无记录" else "探针日志：$lines 行",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        MiniAction("刷新", subtle = true) { readKey++ }
        Spacer(Modifier.width(6.dp))
        MiniAction("清空", subtle = true) {
            Trace.clear()
            readKey++
        }
        Spacer(Modifier.width(6.dp))
        MiniAction(
            text = "导出",
            subtle = lines == 0,
        ) {
            val body = Trace.readAll().ifBlank { "(空，探针可能未初始化)" }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "AutoLyrics 探针日志")
                putExtra(Intent.EXTRA_TEXT, body)
            }
            context.startActivity(
                Intent.createChooser(intent, "导出探针日志").apply {
                    // 从悬浮窗/后台调起时没有 Activity 挂载，必须显式新建任务栈
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: Candidate,
    score: Double,
    onUseSource: () -> Unit,
    onUseCandidate: () -> Unit,
) {
    // v1.13.8：门槛从 0.55 改为读 LyricRepository.minAcceptScore（0.70）——
    // 它同时是「进候选池」的底线与八轮递降的最后一档，两者必须一致。
    val good = score >= LyricRepository.minAcceptScore
    // v1.8.0：得分高亮从 primaryContainer 改成「左侧一条竖线 + 底色微亮」，
    // 不再往卡片里塞一整块彩色（M3 默认的彩色容器是「AI 味」重灾区）。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (good) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceContainer,
            )
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (good) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(
                "${candidate.title} — ${candidate.artist}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (good) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            if (candidate.durationMs > 0) "时长 ${candidate.durationMs / 1000}s · id=${candidate.id}"
            else "时长未知 · id=${candidate.id}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "得分 %.3f".format(score),
                style = MaterialTheme.typography.bodySmall,
                color = if (good) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            MiniAction("用此源", onClick = onUseSource)
            Spacer(Modifier.width(6.dp))
            MiniAction("用此候选", subtle = true, onClick = onUseCandidate)
        }
    }
}

/**
 * 排查页的次要动作按钮：透明底 + 细描边，比 M3 默认 Button 轻。
 *
 * [subtle] 放在 [onClick] **之前**，这样 `MiniAction("清除") { ... }` 的尾随 lambda
 * 能正确绑到 onClick —— Kotlin 的尾随 lambda 只会绑定到最后一个形参。
 */
@Composable
private fun MiniAction(text: String, subtle: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (subtle) Color.Transparent
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (subtle) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
        )
    }
}

/** 锁定本曲的歌词来源（与候选），并强制重新取词。 */
private fun applyOverride(trackKey: String, sourceId: String, candidateId: String?) {
    if (trackKey.isBlank()) return
    SettingsStore.update { s ->
        s.copy(
            sourceOverride = s.sourceOverride + (trackKey to sourceId),
            sourceOverrideCandidate = if (candidateId != null)
                s.sourceOverrideCandidate + (trackKey to candidateId)
            else s.sourceOverrideCandidate - trackKey,
        )
    }
    LyricEngine.refresh(force = true)
}

@Composable
private fun chipColors(selected: Boolean) = AssistChipDefaults.assistChipColors(
    containerColor = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceContainer,
    labelColor = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant,
)

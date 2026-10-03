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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.eu.dinghongyu.autolyrics.data.TrackInfo
import org.eu.dinghongyu.autolyrics.lyric.Candidate
import org.eu.dinghongyu.autolyrics.lyric.LyricEngine
import org.eu.dinghongyu.autolyrics.lyric.LyricRepository
import org.eu.dinghongyu.autolyrics.media.PlaybackMonitor
import org.eu.dinghongyu.autolyrics.util.SettingsStore
import kotlinx.coroutines.launch

/**
 * 「歌词源」页：手动搜索排查。
 *
 * 当自动取词失败时，用它逐源看两件事：
 *  1. 能不能搜到候选（国内接口是否有地域/风控问题）
 *  2. 候选得分够不够（低于 0.55 会被判定为不可信并跳过）
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
            "候选与匹配得分（≥0.55 才会取词）",
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

@Composable
private fun CandidateCard(
    candidate: Candidate,
    score: Double,
    onUseSource: () -> Unit,
    onUseCandidate: () -> Unit,
) {
    val good = score >= 0.55
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

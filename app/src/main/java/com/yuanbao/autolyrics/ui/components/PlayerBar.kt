package com.yuanbao.autolyrics.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuanbao.autolyrics.R
import com.yuanbao.autolyrics.data.TransportCapabilities

/**
 * 歌词页底部的播放器控制条：进度条 + 上一首/播放暂停/下一首。
 *
 * 进度条有个关键细节：拖动期间必须屏蔽外部（200ms 轮询）推来的位置，
 * 否则手指会被"打回去"。所以拖动中用 [dragValue] 自持，松手才真正 seek。
 */
@Composable
fun PlayerBar(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    capabilities: TransportCapabilities,
    accent: Color,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val max = durationMs.coerceAtLeast(1L).toFloat()

    /**
     * v1.8.2 性能：**非拖动时把进度量化到整秒**。
     *
     * 原来直接用 `positionMs`（每 100ms 变一次），Slider 也就每 100ms 重建一次
     * —— 而进度条上显示的时间本来就是 `formatClock`（精度到秒），
     * 每秒移动 10 次里有 9 次渲染结果完全相同。
     *
     * 量化到 1s 后，Slider 稳定在每秒只重建一次，观感无差别（人类看不出
     * 0.1 秒的进度差异），但把这一处的重组频率降到了原来的 1/10。
     *
     * 拖动中不受影响：此时用 [dragValue]，本来就是交互驱动的。
     */
    // 拖动中的取值由下面的 dragging/dragValue 提供，这里只算"非拖动态"的量化进度。
    val shown = (positionMs / 1000L * 1000L).toFloat().coerceIn(0f, max)

    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    // 拖动中显示拖动值，否则显示量化到整秒的播放位置。
    val shownNow = if (dragging) dragValue else shown

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatClock(shownNow.toLong()),
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.width(44.dp),
            )
            Slider(
                value = shownNow,
                onValueChange = { v ->
                    dragging = true
                    dragValue = v
                },
                onValueChangeFinished = {
                    if (!dragging) return@Slider
                    dragging = false
                    onSeek(dragValue.toLong())
                },
                valueRange = 0f..max,
                enabled = capabilities.canSeek,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                ),
            )
            Text(
                text = formatClock((max).toLong()),
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.width(44.dp),
            )
        }

        if (!capabilities.canSeek) {
            Text(
                text = "该播放器不支持拖动进度",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlButton(
                icon = R.drawable.ic_skip_previous,
                desc = "上一首",
                enabled = capabilities.canSkipPrev,
                onClick = onPrevious,
            )
            ControlButton(
                icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                desc = if (isPlaying) "暂停" else "播放",
                enabled = capabilities.canPlayPause,
                onClick = onPlayPause,
                size = 40.dp,
                tint = accent,
            )
            ControlButton(
                icon = R.drawable.ic_skip_next,
                desc = "下一首",
                enabled = capabilities.canSkipNext,
                onClick = onNext,
            )
        }
    }
}

@Composable
private fun ControlButton(
    icon: Int,
    desc: String,
    enabled: Boolean,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 32.dp,
    tint: Color = Color.White,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            painter = painterResource(icon),
            contentDescription = desc,
            tint = if (enabled) tint else Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(size),
        )
    }
}

/** mm:ss */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

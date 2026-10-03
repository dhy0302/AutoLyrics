package org.eu.dinghongyu.autolyrics.data

/** 当前正在播放的曲目信息。 */
data class TrackInfo(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val pkg: String = "",
    val origin: String = ORIGIN_SESSION,
) {
    companion object {
        /** 来自 MediaSession（有精确进度） */
        const val ORIGIN_SESSION = "MediaSession"

        /** 来自媒体通知兜底（只能估算进度） */
        const val ORIGIN_NOTIFICATION = "通知兜底"
    }

    fun isBlank(): Boolean = title.isBlank()

    /** 缓存与去抖动用的稳定 key：切歌时才认为变化。 */
    fun key(): String = "${title.trim().lowercase()}|${artist.trim().lowercase()}"
}

/** 逐字歌词中的一个字（或一小段）。 */
data class LyricWord(
    /** 该字开始的绝对时间（毫秒，已加上行起始时间） */
    val startMs: Long,
    /** 该字持续的时长（毫秒） */
    val durationMs: Long,
    val text: String,
) {
    val endMs: Long get() = startMs + durationMs
}

data class LyricLine(
    /** 本行起始时间 */
    val timeMs: Long,
    val text: String,
    /** 译文（来自 tlyric / trans） */
    val translation: String? = null,
    /** 逐字信息；为空表示这是整行歌词 */
    val words: List<LyricWord> = emptyList(),
    /** 本行总时长（QRC 提供，整行歌词为 0） */
    val durationMs: Long = 0L,
) {
    val isWordLevel: Boolean get() = words.isNotEmpty()
}

data class Lyric(
    val lines: List<LyricLine> = emptyList(),
    val sourceId: String = "",
    /** 歌词自带的整体偏移（LRC 的 [offset:]），正=延后出现 */
    val offsetMs: Long = 0L,
    /** 是否包含译文 */
    val hasTranslation: Boolean = false,
    /** 是否逐字歌词 */
    val wordLevel: Boolean = false,
    /** 纯音乐（无歌词可显示） */
    val instrumental: Boolean = false,
    /** 无时间轴的纯文本歌词 */
    val plainText: String? = null,
) {
    fun hasTimings(): Boolean = lines.isNotEmpty()
}

/**
 * 进度刷新精度档位。
 *
 * 逐字歌词对时间精度敏感：档位越高，逐字染色越贴合，但轮询越密、耗电越多。
 * 三档都带「时间插值」（用 PlaybackState 的上报时刻推算真实位置），
 * 只有 PRECISE 额外做位置平滑滤波。
 */
enum class PrecisionMode(
    /** 轮询间隔 */
    val pollMs: Long,
    val label: String,
    val desc: String,
    /** 是否启用低通平滑滤波 */
    val smoothing: Boolean,
) {
    POWER_SAVING(
        pollMs = 200L,
        label = "流畅省电",
        desc = "200ms 刷新，够用且最省电，适合整行歌词",
        smoothing = false,
    ),
    STANDARD(
        pollMs = 100L,
        label = "标准",
        desc = "100ms 刷新 + 时间插值，逐字级准确",
        smoothing = false,
    ),
    PRECISE(
        pollMs = 50L,
        label = "极致精准",
        desc = "50ms 刷新 + 插值 + 平滑滤波，逐字最贴合，耗电较高",
        smoothing = true,
    ),
}

/** 播放器支持的传输控制能力（来自 PlaybackState.actions）。 */
data class TransportCapabilities(
    val canSeek: Boolean = false,
    val canSkipPrev: Boolean = false,
    val canSkipNext: Boolean = false,
    val canPlayPause: Boolean = false,
)

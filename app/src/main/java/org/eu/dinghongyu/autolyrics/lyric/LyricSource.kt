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

import org.eu.dinghongyu.autolyrics.data.TrackInfo

/** 搜索到的候选曲目。 */
data class Candidate(
    /** 归属的歌词源 id，便于日志与调试 */
    val sourceId: String,
    /** 该源内部的曲目 id */
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long = 0L,
    val album: String = "",
    /** 取词阶段需要的附加参数（如酷狗的 accesskey） */
    val extra: Map<String, String> = emptyMap(),
)

/** 歌词原始文本的格式，决定用哪个解析器。 */
enum class RawFormat { LRC, QRC, YRC, KRC }

data class RawLyric(
    val main: String,
    val translation: String? = null,
    val format: RawFormat = RawFormat.LRC,
    /** 从歌词文件本身解析出的总时长（毫秒）；取不到时为 0，由上层决定是否用于匹配。 */
    val durationMs: Long = 0L,
)

interface LyricSource {
    val id: String
    val displayName: String
    suspend fun search(track: TrackInfo): List<Candidate>
    suspend fun fetch(candidate: Candidate): RawLyric?
}

/** 一次取词尝试的结果，用于在「歌词源」页展示回退过程。 */
data class SourceAttempt(
    val sourceId: String,
    val displayName: String,
    val ok: Boolean,
    /** 命中的曲目与得分，或失败原因 */
    val note: String,
    /** 是否逐字歌词 */
    val wordLevel: Boolean = false,
)

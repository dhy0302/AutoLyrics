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

/**
 * v1.18.0：一次**取词**的完整结果，**区分「没有」与「没查成」**。
 *
 * ## 这是 [SearchOutcome] 在取词阶段的同款问题
 *
 * 旧接口 `fetch(): RawLyric?` 里 `null` 有两种含义：
 *  - 这首歌**确实没有**歌词（VIP/无版权/纯音乐）
 *  - 网络失败、风控拦截、响应结构异常 —— **根本没查成**
 *
 * 各源实现（尤其 [org.eu.dinghongyu.autolyrics.lyric.source.NeteaseSource]）
 * 把后者统一写成 `return null`，上层 [org.eu.dinghongyu.autolyrics.lyric.LyricRepository]
 * 看到 `null` 就记成「无歌词」→ **写进负缓存 3 天** → 点重取也命中缓存 →
 * 既不显示歌词也不显示「暂无歌词」，界面像是卡住了。
 *
 * 网易云最容易命中：它的三个 host 都需要 Cookie，风控返回的 JSON
 * 与正常结构不同，`code != 200` 判断失败后正好走到 `return null`。
 *
 * ⇒ [failed] 为真时，上层不写负缓存，并安排自动退避重试
 * （见 LyricRepository 的 RETRY_DELAYS_MS）。
 */
data class FetchOutcome(
    val lyric: RawLyric?,
    /** 本次取词是否因网络/接口异常而未能完成。 */
    val failed: Boolean = false,
) {
    companion object {
        /** 请求成功，这首歌确实没有歌词。 */
        fun none() = FetchOutcome(null, false)

        /** 请求成功，拿到了歌词。 */
        fun of(raw: RawLyric) = FetchOutcome(raw, false)

        /** 因异常未能完成 —— 不可据此判定「没有歌词」，且不可写负缓存。 */
        fun failed() = FetchOutcome(null, true)
    }
}

interface LyricSource {
    val id: String
    val displayName: String
    /**
     * 返回 [SearchOutcome] 而非裸 `List<Candidate>`：
     * 见 [SearchOutcome] 的说明 —— 必须能表达「没查成」，
     * 否则「网络失败」会被上层当成「确实没有歌词」写进负缓存。
     */
    suspend fun search(track: TrackInfo): SearchOutcome

    /**
     * v1.18.0：返回 [FetchOutcome] 而非裸 `RawLyric?`。
     *
     * 原因与 [SearchOutcome] 完全相同 —— `null` 无法表达
     * 「确实没有歌词」与「没查成」的区别，后者若被当成前者
     * 会写进负缓存，用户点重取也没用。
     */
    suspend fun fetch(candidate: Candidate): FetchOutcome
}

/**
 * v1.12.7：一次检索的完整结果，**区分「没有」与「没查成」**。
 *
 * ## 为什么必须显式带回失败标记
 *
 * 旧接口 `search(): List<Candidate>` 无法表达「空列表」的两种含义：
 *  - 真的没有这首歌的候选
 *  - 网络/接口异常，请求根本没完成
 *
 * 而各源实现普遍把异常 `catch (_: Throwable) { null }` 之后
 * `return emptyList()`——**异常被吞成了空结果**。
 * 于是上层看到「空列表」就判定「查过了，确实没有」，
 * 把这次结果写进负缓存（有效期 3 天）。
 *
 * 这就是「熄屏切歌后歌词丢失」的真正根因：
 * 熄屏时 Android 限制网络 → 源内部catch 掉异常 → 返回空列表 →
 * 上层以为是「没歌词」→ 写负缓存 → 亮屏后一直显示「没找到歌词」，
 * 而手动重取（走force=true 绕过缓存）又能拿到。
 *
 * ⇒ [failed] 为真时，上层不写负缓存，并在回到前台时自动重试。
 */
data class SearchOutcome(
    val candidates: List<Candidate>,
    /** 本次检索是否因网络/接口异常而未能完成。 */
    val failed: Boolean = false,
) {
    companion object {
        /** 正常完成，确实没有候选。 */
        fun empty() = SearchOutcome(emptyList(), false)

        /** 正常完成，有候选。 */
        fun of(list: List<Candidate>) = SearchOutcome(list, false)

        /** 因异常未能完成 —— 不可据此判定「这首歌没有歌词」。 */
        fun failed() = SearchOutcome(emptyList(), true)
    }
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

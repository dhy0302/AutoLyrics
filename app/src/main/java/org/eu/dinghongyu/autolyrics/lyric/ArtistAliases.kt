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
import org.eu.dinghongyu.autolyrics.util.SettingsStore
import org.eu.dinghongyu.autolyrics.util.TextMatch

/**
 * v1.15.0：歌手别名表。
 *
 * ## 要解决的问题
 *
 * 同一个歌手在不同平台的元数据里名字不同，用播报名去搜会扑空：
 * ```
 * 周兴哲  网易云=周兴哲    酷狗=Eric周兴哲     Lrclib=周兴哲
 * 福禄寿  网易云=福禄寿    酷狗=福禄寿FloruitShow
 * ```
 * 能不能搜到，取决于「哪一家的写法恰好和播报方一致」——纯属运气。
 *
 * ## 关键纪律：别名只用于「搜候选」，不用于「认定同一人」
 *
 * 这是整个机制最容易写错的地方。
 *
 * `[LyricRepository.score]` 永远拿**播报原名**与候选的歌手名比对，
 * 别名**不参与打分**。别名只是「让同一个歌手多几种写法去搜」，
 * 搜回来的东西仍由原有的相似度逻辑验证。
 *
 * 为什么不省这个事：别名可能填错。若让别名也参与「认定」，
 * 一次错误映射（如误把「周杰伦」映射到「周杰倫」以外的东西）
 * 就会让**别的歌手的候选**被判为同一个人。
 * **拿错歌词远比取不到歌词糟糕** —— 用户看到的是词跟唱的对不上，
 * 而「没找到歌词」至少是诚实的失败。
 *
 * ## 内置表为什么刻意保守
 *
 * 只有经过实测、确认「不加就真的搜不到」的条目才收录。
 * 错一条比空着更糟：用户看到内置值会默认它是对的，
 * 反而不会自己去填正确的那个。
 *
 * 用户表优先于内置表（见 [aliasesFor]），所以内置填错了用户能覆盖。
 */
object ArtistAliases {

    /**
     * 内置别名表（播报名 → 该歌手在其他平台的写法）。
     *
     * 键用**归一化后**的形式（见 [keyOf]），不是原样歌名 ——
     * 查表前必须先归一化，否则大小写/全角/繁简差异会让查找直接落空。
     */
    private val BUILT_IN: Map<String, List<String>> = mapOf(
        // 酷狗把周兴哲标成「Eric周兴哲」，实测：只搜「周兴哲」在酷狗扑空
        keyOf("周兴哲") to listOf("Eric周兴哲"),
        // 酷狗把福禄寿标成「福禄寿FloruitShow」，实测同上
        keyOf("福禄寿") to listOf("福禄寿FloruitShow"),
    )

    /**
     * 查表键：归一化后的播报歌手名。
     *
     * 必须与 [LyricRepository.searchVariants] 用同一个 [TextMatch.normalize]，
     * 否则「表里有、查不到」。
     */
    private fun keyOf(artist: String): String = TextMatch.normalize(artist, isArtist = true)

    /**
     * 取某个播报歌手的全部别名（用户 + 内置，取并集，用户在前）。
     *
     * ## 为什么不按「用户覆盖内置」处理
     *
     * 一开始想的是「两边都有同一个播报名时以用户为准」，
     * 实测下来那个语义有害：内置表里的「Eric周兴哲」是我们**确实观察到**
     * 存在的写法，用户未必知道它的存在。丢掉它等于让功能退化回今天的样子。
     *
     * 反过来，用户填的也绝不该丢——他可能知道我们没收录的第三个平台。
     * 所以取并集：用户填的排在前面（那是用户最确定的），
     * 内置的补在后面。重复的用 [LinkedHashSet] 去重。
     *
     * ## 去重
     *
     * 三层：
     *  - 剔除原名本身（它本来就是检索词之一，重复只会多发一次请求）
     *  - 剔除空白项（用户很容易在逗号前后留下空格）
     *  - 整体去重（用户可能把内置的别名又填了一遍）
     */
    fun aliasesFor(artist: String): List<String> {
        if (artist.isBlank()) return emptyList()
        val k = keyOf(artist)
        if (k.isBlank()) return emptyList()
        val builtIn = BUILT_IN[k].orEmpty()
        val user = SettingsStore.current().artistAliases[k].orEmpty()

        val merged = LinkedHashSet<String>()
        user.forEach { it.trim().takeIf { s -> s.isNotBlank() }?.let(merged::add) }
        builtIn.forEach { it.trim().takeIf { s -> s.isNotBlank() }?.let(merged::add) }
        merged.remove(artist)
        return merged.toList()
    }

    /**
     * 内置表的只读快照，供设置页展示。
     *
     * 返回归一化前的原始写法（便于用户看懂），
     * 所以这里反查回显示用的键。
     */
    fun builtInForDisplay(): Map<String, List<String>> {
        val raw = mapOf(
            "周兴哲" to listOf("Eric周兴哲"),
            "福禄寿" to listOf("福禄寿FloruitShow"),
        )
        return raw
    }

    /**
     * 某个歌手是否有别名（用于判断该不该生成检索变体）。
     *
     * 不命中时**不生成任何额外变体** —— 这是控制请求量的关键：
     * 没有别名的普通歌曲开销与改动前完全一致。
     */
    fun hasAlias(artist: String): Boolean = aliasesFor(artist).isNotEmpty()

    /**
     * 生成用于检索的 TrackInfo 变体：把 [track] 的歌手替换为 [alias]。
     *
     * 保留原歌名与时长 —— 别名只解决「歌手名写法不同」，
     * 歌名/时长对不上是另一个问题，不该在这里混进来。
     */
    fun variantOf(track: TrackInfo, alias: String): TrackInfo =
        track.copy(artist = alias)
}

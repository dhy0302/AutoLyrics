package com.yuanbao.autolyrics.util

import android.content.Context
import com.yuanbao.autolyrics.data.TrackInfo
import com.yuanbao.autolyrics.R

/**
 * 繁体 → 简体 转换（离线、零依赖）。
 *
 * 数据来源：OpenCC 的 tw2s 配置（台湾正体 → 大陆简体）。在构建期把「每一个 CJK 字符
 * 逐字过一遍转换、凡发生变化就记一对」生成词表，存于 [R.raw.t2s]（约 4200 对，~34KB），
 * 覆盖 U+3400~U+9FFF 与 U+F900~U+FAFF 内的全部差异字，足以处理几乎所有中文歌名 / 歌手名。
 *
 * 为什么不用运行时依赖（如 opencc4j）：其完整词库会让 APK 膨胀数 MB；而歌词匹配只需要
 * 「歌名 + 歌手」的逐字转换，字符级映射已足够，且本文件仅 ~34KB。
 *
 * 用途：Spotify 广播态里的中文歌常是繁体，直接用繁体去 QQ / 网易云 / 酷狗检索会扑空，
 * 所以检索前把歌名、歌手（及专辑）转简体再搜，同时保留原繁体一并检索以提高命中。
 */
object ChineseConverter {

    /** 加载后的 繁→简 映射；未初始化时转换为空操作（返回原串），避免空指针。 */
    private var map: Map<String, String>? = null

    /** 在 [LyricRepository.init] 里调用，从 raw 资源加载词表（只加载一次）。 */
    fun init(context: Context) {
        if (map != null) return
        val m = LinkedHashMap<String, String>()
        try {
            context.resources.openRawResource(R.raw.t2s)
                .bufferedReader(Charsets.UTF_8)
                .forEachLine { line ->
                    val i = line.indexOf('\t')
                    if (i <= 0) return@forEachLine
                    val t = line.substring(0, i)
                    val s = line.substring(i + 1)
                    if (t.isNotEmpty() && s.isNotEmpty()) m[t] = s
                }
        } catch (_: Throwable) {
            // 加载失败则退化成「不转换」，不影响其它源取词
        }
        map = m
    }

    /** 文本里是否含有可转写的繁体字。 */
    fun containsTraditional(text: String): Boolean {
        val m = map ?: return false
        for (c in text) if (m.containsKey(c.toString())) return true
        return false
    }

    /** 繁体 → 简体；无差异字符原样保留。空串直接返回。 */
    fun toSimplified(text: String): String {
        val m = map ?: return text
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(m[c.toString()] ?: c.toString())
        return sb.toString()
    }

    /**
     * 把曲目信息转简体。仅当歌名 / 歌手 / 专辑任一发生变化才返回新实例，
     * 否则返回原对象（便于上层判断「是否需要额外检索简体变体」）。
     */
    fun simplify(track: TrackInfo): TrackInfo {
        val t = toSimplified(track.title)
        val a = toSimplified(track.artist)
        val al = toSimplified(track.album)
        if (t == track.title && a == track.artist && al == track.album) return track
        return track.copy(title = t, artist = a, album = al)
    }
}

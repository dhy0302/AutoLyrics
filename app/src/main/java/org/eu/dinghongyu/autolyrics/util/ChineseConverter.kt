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

package org.eu.dinghongyu.autolyrics.util

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import org.eu.dinghongyu.autolyrics.data.TrackInfo
import org.eu.dinghongyu.autolyrics.R

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
    @Volatile
    private var map: Map<String, String>? = null

    /**
     * v1.12.1：词表加载完成的信号。
     *
     * 用 [CompletableDeferred] 而不是 [CountDownLatch]，因为等待方是协程，
     * 挂起比阻塞线程正确得多（不占用任何线程）。
     *
     * **为什么需要它**：词表加载已挪到后台线程（见 [initAsync]），
     * 但 [toSimplified] 在 map 为空时会**静默返回原串**。
     * 如果用户恰好在词表加载完成前就点了歌，
     * 繁体歌名不会被转换 → 检索扑空 → 「怎么突然搜不到这首歌了」。
     * 这个坑很难复现也很难报告，所以干脆让调用方等一下。
     */
    private val ready = CompletableDeferred<Unit>()

    /**
     * v1.12.1：把词表加载丢到后台线程。
     *
     * ## 线程安全
     * [map] 是 `volatile` 的，多线程可见性有保证；
     * 且重复调用是幂等的（第二次直接返回），不会出现两个线程同时建表。
     *
     * ## 失败也不卡人
     * 加载失败时 `map` 保持 null、转换退化成「不转换」，
     * 这与旧行为一致；而 [awaitReady] 仍然会完成，
     * 不会让取词流程永久挂住。
     */
    fun initAsync(context: Context) {
        if (map != null) {
            if (!ready.isCompleted) ready.complete(Unit)
            return
        }
        val app = context.applicationContext
        AppScope.io.launch {
            val m = LinkedHashMap<String, String>()
            try {
                app.resources.openRawResource(R.raw.t2s)
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
            // 无论成功失败都要放行，否则等待方会一直挂着
            ready.complete(Unit)
        }
    }

    /**
     * v1.12.1：等词表就绪。
     *
     * 取词入口在真正用到转换之前调它一下，
     * 避免「词表还在加载 → 繁体没转 → 检索扑空」这种极难复现的问题。
     * 加载失败时也立刻返回，行为与旧的「转换失败就退化成不转换」一致。
     */
    suspend fun awaitReady() {
        ready.await()
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

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

import android.content.Context
import org.eu.dinghongyu.autolyrics.data.Lyric
import org.eu.dinghongyu.autolyrics.data.TrackInfo
import org.eu.dinghongyu.autolyrics.lyric.parser.KrcParser
import org.eu.dinghongyu.autolyrics.lyric.parser.LyricParser
import org.eu.dinghongyu.autolyrics.lyric.parser.QrcParser
import org.eu.dinghongyu.autolyrics.lyric.parser.YrcParser
import org.eu.dinghongyu.autolyrics.lyric.source.KugouSource
import org.eu.dinghongyu.autolyrics.lyric.source.LrclibSource
import org.eu.dinghongyu.autolyrics.lyric.source.NeteaseSource
import org.eu.dinghongyu.autolyrics.util.AppScope
import org.eu.dinghongyu.autolyrics.util.ChineseConverter
import org.eu.dinghongyu.autolyrics.util.TextMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.eu.dinghongyu.autolyrics.util.SettingsStore

data class LyricResult(
    val lyric: Lyric?,
    val fromSourceId: String?,
    val attempts: List<SourceAttempt> = emptyList(),
)

/**
 * 多源聚合 + 自动回退 + 本地缓存。
 *
 * 单个源的流程：search → 打分选最佳候选 → fetch → 按 [RawFormat] 选解析器 → 校验。
 * 只有「有时间轴且 ≥2 行」才算命中，否则继续下一个源（在 [sourceOrder] 里往后走）。
 *
 * 缓存策略：
 *  - 命中：写本地 JSON，30 天有效
 *  - 未命中（负缓存）：只记时间戳，3 天有效，避免同一首歌反复打网络
 */
object LyricRepository {

    /** 顺序即默认回退顺序；实际顺序由用户在设置里调整。 */
    val allSources: List<LyricSource> = listOf(NeteaseSource, KugouSource, LrclibSource)

    /** 低于该得分认为匹配不可信，直接换下一个源。 */
    private const val MIN_SCORE = 0.55

    /** 少于该行数视为无效歌词（多为「纯音乐，请欣赏」占位）。 */
    private const val MIN_VALID_LINES = 2

    /** 单个源最多尝试的候选数（按匹配度降序），兼顾命中率与请求量。 */
    private const val MAX_CANDIDATES_PER_SOURCE = 3

    private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val NEGATIVE_TTL_MS = 3L * 24 * 60 * 60 * 1000
    private const val MEMORY_CACHE_SIZE = 64

    fun sourceName(id: String): String = allSources.firstOrNull { it.id == id }?.displayName ?: id

    private lateinit var app: Context

    /** 内存缓存：切歌来回切换时不重复读文件。 */
    private val memory = object : LinkedHashMap<String, LyricResult>(MEMORY_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricResult>?): Boolean =
            size > MEMORY_CACHE_SIZE
    }

    /**
     * v1.12.1：专用于保护 [memory] 的锁。
     *
     * ## 为什么它原先是安全的、现在不安全
     *
     * 旧版一把进程级大锁把`memory` 的读写全圈住了，所以它裸着也没事。
     * v1.12.1 把锁改成按歌曲 key 粒度后，`memory[key] = result`（锁内）
     * 与 `memory[key]?.let { return it }`（**锁外**）就可能并发了。
     *
     * 而这个 `LinkedHashMap` 是 **accessOrder = true** 的，
     * 每次 `get` 都会把命中的节点挪到链表末尾 —— 也就是**读操作会改结构**。
     * 并发 get + put 时可能把链指成环（死循环）或抛
     * ConcurrentModificationException，属于最难复现的那类故障。
     *
     * 刻意用**独立的第二把锁**而不是复用 [locks]：
     * 两把锁若混用会出现「先持 A 再等 B」的顺序问题，
     * 而这里只需要一把极短的锁（纯内存读写，微秒级）。
     */
    private val memoryLock = Any()

    /** 读内存缓存。[memoryLock] 只在纯内存操作期间持有，不含任何 IO。 */
    private fun memoryGet(key: String): LyricResult? =
        synchronized(memoryLock) { memory[key] }

    /** 写内存缓存。 */
    private fun memoryPut(key: String, value: LyricResult) {
        synchronized(memoryLock) { memory[key] = value }
    }

    /**
     * v1.12.1：防止同一首歌被并发重复取词，**按歌曲 key 加锁**。
     *
     * ## 为什么要改成按 key
     *
     * 旧版是一把**进程级** `Mutex()`，锁内是整个多源回退链
     * （最多 3 个源 × 2 个繁简变体 × 3 个候选 ≈ **18 次串行 HTTP**）加磁盘写。
     * 一次最坏情况能跑十几秒，而**这首歌的网络请求期间，这把锁就一直被占着**。
     *
     * 于是快速切歌时：新歌要等旧歌查完才能开始。
     * 旧歌那条链一旦进了慢源（网易云/酷狗接口偶尔要好几秒），
     * 新歌就干等到超时 —— 表现就是「连点切歌，歌词半天不出来」。
     *
     * 现在改成 `key → Mutex` 的映射，锁的粒度是「一首歌」：
     * 查 A 歌不会挡住查 B 歌，同一首歌的并发请求仍会被正确合并
     * （第二个进来时会命中 memory 缓存或等第一个的结果）。
     */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * 取这首歌对应的锁；用完**不删除**，避免「删了锁但别人正等着」导致并发保护失效。
     *
     * ## 为什么用 computeIfAbsent 而不是 getOrPut
     *
     * `getOrPut` 是「先 get，为 null 再 put」两步，**中间有竞态窗口**：
     * 两个线程同时请求同一首歌时可能各自new 出一个 Mutex，后写的覆盖先写的，
     * 于是两个协程各自持有**不同的锁** —— 恰好就是本次改动要防的那个场景失效了。
     * 而且这种 bug「平时不出事，出事就是并发保护完全没了」，最难查。
     *
     * `computeIfAbsent` 是 ConcurrentHashMap 的原子操作，直接返回唯一那把锁。
     */
    private fun lockFor(key: String): Mutex = locks.computeIfAbsent(key) { Mutex() }

    fun init(context: Context) {
        val appCtx = context.applicationContext
        app = appCtx
        // v1.12.1：建目录是磁盘 IO，从主线程挪走。
        //
        // 原来这里是冷启动主线程上的三件事：
        //   mkdirs()               磁盘 IO
        //   ChineseConverter.init  读 34KB raw 建 4200 项词表（30~80ms 的主要来源）
        //   —— 现在两件都丢给后台，冷启动不再为它们付出等待。
        //
        // 目录本身是「首次写缓存时才需要」，而首次写缓存必然发生在
        // 取词流程里（已在 IO 线程），所以不必在启动时急着建。
        AppScope.io.launch {
            runCatching { File(appCtx.cacheDir, "lyrics").mkdirs() }
            ChineseConverter.initAsync(appCtx)
        }
    }

    suspend fun load(track: TrackInfo, order: List<String>, forceRefresh: Boolean = false): LyricResult {
        if (track.isBlank()) return LyricResult(null, null)

        // v1.12.1：等繁简词表就绪再干活。
        //
        // 词表现在是后台加载的（见 ChineseConverter.initAsync），
        // 而 toSimplified 在 map 为空时会**静默返回原串**。
        // 不等的话，词表没加载完就取词 ⇒ 繁体歌名不转换 ⇒ 检索扑空。
        // 这个问题的表现是「偶尔搜不到某首歌」，极难复现，
        // 所以宁可在这里等一下（正常情况下只等几毫秒）。
        ChineseConverter.awaitReady()

        val key = track.key()

        // 手动锁定来源：跳过缓存与自动回退，直接按指定源/候选取词
        val ovSource = SettingsStore.current().sourceOverride[key]
        val ovCand = SettingsStore.current().sourceOverrideCandidate[key]
        if (ovSource != null && ovSource in SettingsStore.current().enabledSources) {
            val result = fetchForced(track, ovSource, ovCand)
            memoryPut(key, result)
            // v1.12.1：磁盘写挪到锁外。这条路径本来就没加 per-key 锁，
            // 但下面主路径原来在锁内写 —— 写文件是纯 IO，
            // 没有任何理由让同歌的第二个请求陪着一起等磁盘。
            writeCache(key, result)
            return result
        }

        if (!forceRefresh) {
            memoryGet(key)?.let { return it }
            readCache(key)?.let {
                memoryPut(key, it)
                return it
            }
        }

        // v1.12.1：锁只罩住「网络取词 + 填内存缓存」，
        // 且按歌曲 key 粒度 —— 查这首歌不会挡住查别的歌。
        val result = lockFor(key).withLock {
            memoryGet(key)?.takeIf { !forceRefresh }?.let { return@withLock it }
            val r = fetchFromNetwork(track, order)
            memoryPut(key, r)
            r
        }
        // 磁盘写必须在锁外：writeCache 是文件 IO，
        // 放在锁内会让「同歌并发」白等一次落盘。
        writeCache(key, result)
        return result
    }

    /**
     * 手动锁定来源时的取词：只用 [sourceId]，若给了 [candidateId] 则直接取该候选，
     * 否则在该源按匹配度选最佳候选。失败返回带原因的 LyricResult（不回退其它源）。
     */
    private suspend fun fetchForced(track: TrackInfo, sourceId: String, candidateId: String?): LyricResult {
        val source = allSources.firstOrNull { it.id == sourceId } ?: return LyricResult(null, null)
        val attempts = ArrayList<SourceAttempt>()
        val candidates = try {
            searchVariants(track).flatMap { source.search(it) }
                .distinctBy { it.sourceId + ":" + it.id }
        } catch (t: Throwable) {
            // v1.12.1：取消放行（见 fetchFromNetwork 里同一注释）
            if (t is CancellationException) throw t
            attempts += failed(source, "搜索失败：${t.message}")
            return LyricResult(null, null, attempts)
        }
        val cand = if (candidateId != null) candidates.firstOrNull { it.id == candidateId }
        else candidates.maxByOrNull { scoreMerged(track, it) }
        if (cand == null) {
            attempts += failed(source, "无搜索结果")
            return LyricResult(null, null, attempts)
        }
        val raw = try {
            source.fetch(cand)
        } catch (t: Throwable) {
            // v1.12.1：取消放行（见 fetchFromNetwork 里同一注释）
            if (t is CancellationException) throw t
            attempts += failed(source, "取词失败：${t.message}")
            return LyricResult(null, null, attempts)
        }
        if (raw == null) {
            attempts += failed(source, "《${cand.title}》无歌词")
            return LyricResult(null, null, attempts)
        }
        val lyric = when (raw.format) {
            RawFormat.QRC -> QrcParser.parse(raw.main, raw.translation, source.id)
            RawFormat.YRC -> YrcParser.parse(raw.main, raw.translation, source.id)
            RawFormat.KRC -> KrcParser.parse(raw.main, raw.translation, source.id)
            RawFormat.LRC -> LyricParser.parse(raw.main, raw.translation, source.id)
        }
        if (isPlaceholder(lyric)) {
            attempts += failed(source, "《${cand.title}》仅占位歌词")
            return LyricResult(null, null, attempts)
        }
        attempts += SourceAttempt(
            sourceId = source.id,
            displayName = source.displayName,
            ok = true,
            note = "锁定来源 · 《${cand.title}》",
            wordLevel = lyric.wordLevel,
        )
        return LyricResult(lyric, source.id, attempts)
    }

    /** 调试页用：列出某个源的候选与得分（同时用繁体原词与简体变体检索）。 */
    suspend fun debugSearch(track: TrackInfo, sourceId: String): List<Pair<Candidate, Double>> {
        val source = allSources.firstOrNull { it.id == sourceId } ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                searchVariants(track).flatMap { source.search(it) }
                    .distinctBy { it.sourceId + ":" + it.id }
                    .map { it to scoreMerged(track, it) }
                    .sortedByDescending { it.second }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    /** 综合匹配度：标题权重最高，歌手次之，时长做校验。 */
    fun score(track: TrackInfo, c: Candidate): Double {
        val title = TextMatch.similarity(TextMatch.normalize(track.title), TextMatch.normalize(c.title))
        val artist = TextMatch.similarity(
            TextMatch.normalize(track.artist, isArtist = true),
            TextMatch.normalize(c.artist, isArtist = true),
        )
        val duration = TextMatch.durationScore(track.durationMs, c.durationMs)
        return 0.55 * title + 0.25 * artist + 0.20 * duration
    }

    /**
     * 检索变体：歌名/歌手含繁体时，额外生成一份简体副本。
     * 两个变体都会拿去各源检索并合并候选，避免 Spotify 广播态里的繁体歌名
     * 在 QQ / 网易云 / 酷狗扑空（这三个源里的中文歌名基本都是简体）。
     */
    private fun searchVariants(track: TrackInfo): List<TrackInfo> {
        val simp = ChineseConverter.simplify(track)
        return if (simp === track) listOf(track) else listOf(track, simp)
    }

    /** 同时按繁体原词与简体变体打分取较优者，兼容「源返回简体候选 / 源返回繁体候选」。 */
    private fun scoreMerged(track: TrackInfo, c: Candidate): Double =
        maxOf(score(track, c), score(ChineseConverter.simplify(track), c))

    private suspend fun fetchFromNetwork(track: TrackInfo, order: List<String>): LyricResult {
        val attempts = ArrayList<SourceAttempt>()
        val sources = order.mapNotNull { id -> allSources.firstOrNull { it.id == id } }

        for (source in sources) {
            // 繁体歌名同时用「原词」与「简体变体」检索，合并去重后一起打分
            val variants = searchVariants(track)
            val candidates = try {
                variants.flatMap { source.search(it) }
                    .distinctBy { it.sourceId + ":" + it.id }
            } catch (t: Throwable) {
                // v1.12.1：取消必须放出去。
                // 下面所有 catch (t: Throwable) 原本会把 CancellationException
                // 当成「这个源搜索失败」然后 continue 到下一个源 ——
                // 用户切歌后，旧歌仍会把剩下几个源全试一遍，
                // 既浪费流量又占着 IO 线程不放，正好是本次要修的「切歌被堵」。
                if (t is CancellationException) throw t
                attempts += failed(source, "搜索失败：${t.message}")
                continue
            }
            if (candidates.isEmpty()) {
                attempts += failed(source, "无搜索结果")
                continue
            }

            // 按匹配度降序，逐个候选尝试，直到拿到有效（非占位）歌词
            val ranked = candidates.map { it to scoreMerged(track, it) }
                .sortedByDescending { it.second }
                .take(MAX_CANDIDATES_PER_SOURCE)
            if (ranked.first().second < MIN_SCORE) {
                attempts += failed(source, "最佳匹配 %.2f < %.2f".format(ranked.first().second, MIN_SCORE))
                continue
            }

            var tried = 0
            for ((cand, sc) in ranked) {
                tried++
                val raw = try {
                    source.fetch(cand)
                } catch (t: Throwable) {
                    // v1.12.1：取消放行，理由见上方 search 处同一注释
                    if (t is CancellationException) throw t
                    attempts += failed(source, "取词失败(${cand.title})：${t.message}")
                    continue
                }
                if (raw == null) {
                    attempts += failed(source, "《${cand.title}》无歌词")
                    continue
                }

                val lyric = when (raw.format) {
                    RawFormat.QRC -> QrcParser.parse(raw.main, raw.translation, source.id)
                    RawFormat.YRC -> YrcParser.parse(raw.main, raw.translation, source.id)
                    RawFormat.KRC -> KrcParser.parse(raw.main, raw.translation, source.id)
                    RawFormat.LRC -> LyricParser.parse(raw.main, raw.translation, source.id)
                }
                // 占位歌词（暂无歌词 / 纯音乐）不算命中，继续试下一个候选
                if (isPlaceholder(lyric)) {
                    attempts += failed(source, "《${cand.title}》仅占位歌词")
                    continue
                }

                val kind = if (lyric.wordLevel) "逐字" else "整行"
                attempts += SourceAttempt(
                    sourceId = source.id,
                    displayName = source.displayName,
                    ok = true,
                    note = "$kind · 命中《${cand.title}》得分 %.2f".format(sc),
                    wordLevel = lyric.wordLevel,
                )
                return LyricResult(lyric, source.id, attempts)
            }
            if (tried == 0) attempts += failed(source, "候选均无有效歌词")
        }

        return LyricResult(null, null, attempts)
    }

    private fun failed(source: LyricSource, reason: String) =
        SourceAttempt(source.id, source.displayName, false, reason)

    /**
     * 判断解析出的歌词是否只是占位（无实际内容）。
     * 典型情况：网易云 VIP/无版权曲返回 "[00:00.00]暂无歌词"、QQ 返回 "纯音乐，请欣赏"。
     * 这些不算命中，应继续尝试下一个候选或下一个源。
     */
    private fun isPlaceholder(lyric: Lyric): Boolean {
        if (!lyric.plainText.isNullOrBlank()) return false
        if (lyric.lines.isEmpty()) return true
        if (lyric.lines.size < MIN_VALID_LINES) {
            val txt = lyric.lines.firstOrNull()?.text.orEmpty()
            return txt.contains("暂无歌词") || txt.contains("纯音乐") || txt.contains("instrumental")
        }
        return false
    }

    // ---------------- 本地缓存 ----------------

    private fun fileFor(key: String) = File(app.cacheDir, "lyrics/${md5(key)}.json")

    /**
     * 缓存只保存「原文 + 译文」两串 LRC 文本，读出来重新解析。
     * 这样逐字歌词也能原样还原，且解析器的后续改进会自动应用到旧缓存上。
     */
    private fun readCache(key: String): LyricResult? {
        val file = fileFor(key)
        if (!file.exists()) return null
        // v1.12.1：**先用文件修改时间粗筛，过期直接返回，连读都不读。**
        //
        // 旧版先 file.readText() 把整个 JSON 读进内存、JSONObject 解析、
        // 才拿里面的 ts 去比 TTL —— 而缓存里存的恰恰是整首歌词（可达几十 KB）。
        // 于是一个 31 天前的过期缓存，每次切歌都要完整读一遍 + 解析一遍才被丢掉。
        // lastModified 是 stat 出来的，几乎零成本。
        //
        // 这里刻意用**较长的那个 TTL（30 天）**：负缓存（3 天）不受影响，
        // 它超期后仍会被下面 ts 那一层的判定拦住。
        // 反过来若用 3 天粗筛，就会把 3~30 天之间本来有效的正缓存**误杀**——
        // 那是更糟的错（每次切歌都重新联网，流量费又回来了）。
        //
        // 另注意 lastModified 只能当粗筛：改系统时间 / 某些 ROM 的写入时间戳异常
        // 都会让它失真，所以下面基于 ts 的判定必须保留。
        if (System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS) return null
        return try {
            val jo = JSONObject(file.readText())
            val age = System.currentTimeMillis() - jo.optLong("ts", 0L)
            val from = jo.optString("from").takeIf { it.isNotBlank() }
            if (from == null) {
                // 负缓存：短期内不再重复请求
                if (age > NEGATIVE_TTL_MS) null else LyricResult(null, null)
            } else {
                if (age > CACHE_TTL_MS) null
                else {
                    val lyric = when (jo.optString("fmt")) {
                        RawFormat.QRC.name -> QrcParser.parse(jo.optString("main"), jo.optString("trans"), from)
                        RawFormat.YRC.name -> YrcParser.parse(jo.optString("main"), jo.optString("trans"), from)
                        RawFormat.KRC.name -> KrcParser.parse(jo.optString("main"), jo.optString("trans"), from)
                        else -> LyricParser.parse(jo.optString("main"), jo.optString("trans"), from)
                    }
                    LyricResult(lyric, from)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeCache(key: String, result: LyricResult) {
        try {
            val jo = JSONObject()
            jo.put("ts", System.currentTimeMillis())
            jo.put("from", result.fromSourceId ?: "")
            val lyric = result.lyric
            if (lyric != null) {
                jo.put("fmt", formatNameOf(lyric))
                jo.put("main", serialize(lyric))
                jo.put("trans", serializeTranslation(lyric))
            }
            fileFor(key).writeText(jo.toString())
        } catch (_: Throwable) {
            // 缓存写失败不影响主流程
        }
    }

    private fun formatNameOf(lyric: Lyric): String = when {
        // 逐字歌词（QQ/网易/酷狗）统一按「[行起,行长]<起,长,0>字」文本保存，读回用 KrcParser 解析
        lyric.wordLevel -> RawFormat.KRC.name
        else -> RawFormat.LRC.name
    }

    /** 逐字歌词把每个字还原成 `<start,duration>字` 形式，保证读回来仍是逐字。 */
    private fun serialize(lyric: Lyric): String {
        if (!lyric.wordLevel) return LyricParser.serialize(lyric.lines)
        return lyric.lines.joinToString("\n") { line ->
            if (!line.isWordLevel) "[${line.timeMs},0]${line.text}"
            else {
                val head = "[${line.timeMs},${line.durationMs}]"
                val body = line.words.joinToString("") { "<${it.startMs - line.timeMs},${it.durationMs},0>${it.text}" }
                head + body
            }
        }
    }

    private fun serializeTranslation(lyric: Lyric): String =
        if (!lyric.hasTranslation) "" else lyric.lines.joinToString("\n") { line ->
            "[${LyricParser.formatTime(line.timeMs)}]${line.translation.orEmpty()}"
        }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

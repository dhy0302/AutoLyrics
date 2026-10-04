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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    /**
     * 本次取词是否**因网络/接口异常而未能完成**，而不是「确实没有这首歌的歌词」。
     *
     * ## 为什么必须区分这两者
     *
     * 熄屏或App 在后台时，Android 会限制网络访问，于是每个源都搜索失败。
     * 旧版把这两种情况一律当作「没找到」→ 结果被写进**负缓存**（TTL 3 天）。
     *
     * 用户看到的现象就是：锁屏期间切歌 → 亮屏后歌词页显示
     * 「没找到歌词，去歌词源页可手动排查」，悬浮窗也跟着空掉；
     * 而手动点「重取」立刻又能拿到——因为重取会绕过缓存重新联网。
     *
     * ⇒ 网络失败**不能写负缓存**，否则一次熄屏就把这首歌锁死 3 天。
     */
    val networkFailed: Boolean = false,
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

    /**
     * v1.13.8：八轮递降阈值，取代原先单一的 [MIN_ACCEPT_SCORE]。
     *
     * 每轮都按 `sourceOrder` 从头扫一遍全部启用的源，任一轮命中即返回。
     * 所以规则是「**全局最高分优先，同分时靠前的源优先**」，而不是
     * 原先那种「源优先级绝对」——原先酷狗有 0.60 的结果就直接用，
     * 网易云有 0.99 的结果根本不看。
     *
     * 1.00 / 0.97 这两档在真实场景里极少命中（时长项差5 秒就掉到 0.85，
     * 满分要求歌名、歌手、时长三项全对），但**留着它们是对的**：
     * 一旦日后调过[score] 的权重让满分变得可达，这里无需再改结构。
     * 而且因为候选只检索一次（见 [fetchFromNetwork]），
     * 空转一轮的代价只是几次内存比对，不产生任何网络请求。
     */
    private val SCORE_LADDER = listOf(1.00, 0.97, 0.95, 0.90, 0.85, 0.80, 0.75, 0.70)

    /**
     * 低于该得分认为匹配不可信。
     *
     * 注意它不是「八轮都试完仍无果」的兜底 —— 那个兜底由
     * [SCORE_LADDER] 最后一项 0.70 承担。此常量只用于**筛选候选池**：
     * 低于它的候选连进池都不进，省得在八轮里被反复比对。
     */
    private const val MIN_ACCEPT_SCORE = 0.70

    /**
     * 八轮阈值与候选池底线，供 UI（调试页）展示，避免文案里再硬编码一份数字。
     *
     * 将来调整 [SCORE_LADDER] 或 [MIN_ACCEPT_SCORE] 时，调试页的
     * 说明文案与候选高亮门槛会自动跟着变，不会出现「逻辑改了、
     * 文案还写着旧阈值」这种误导排查的情况。
     */
    val scoreLadder: List<Double> get() = SCORE_LADDER

    /** 候选池准入底线（= [SCORE_LADDER] 最后一项）。 */
    val minAcceptScore: Double get() = MIN_ACCEPT_SCORE

    /** 少于该行数视为无效歌词（多为「纯音乐，请欣赏」占位）。 */
    private const val MIN_VALID_LINES = 2

    /** 单个源最多尝试的候选数（按匹配度降序），兼顾命中率与请求量。 */
    private const val MAX_CANDIDATES_PER_SOURCE = 3

    private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val NEGATIVE_TTL_MS = 3L * 24 * 60 * 60 * 1000
    private const val MEMORY_CACHE_SIZE = 64

    /**
     * v1.13.10：磁盘缓存的容量上限。
     *
     * `cacheDir/lyrics` 原先**只写不删、无上限、无过期删除**。
     * 缓存文件里存的是整首歌词（可达几十 KB），1000 首就是 30~80MB，
     * 而且系统不会主动清理 app 的 cacheDir —— 于是长期使用会一直累积。
     *
     * 与 [CACHE_TTL_MS] 的分工：
     *  - TTL 管「单条缓存还有没有效」（30 天）
     *  - 本上限管「总共留多少」（数量 + 字节数）
     *
     * 只在这两者之一越界时才删，**按mtime 倒序删最旧的**，
     * 不动正在读的那些（读是按 key 直接 `fileFor` 取，不走目录列举）。
     */
    private const val DISK_CACHE_MAX_FILES = 500
    private const val DISK_CACHE_MAX_BYTES = 20L * 1024 * 1024

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
            // v1.13.10：启动时顺手做一次磁盘缓存瘦身（原先只写不删）。
            runCatching { pruneDiskCache() }
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
            // 网络失败**不进内存缓存**：否则这次「查不成」会在内存里钉死，
            // 下次切回这首歌时 memoryGet 直接命中，再也不会重试。
            if (!r.networkFailed) memoryPut(key, r)
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
        val outcome = try {
            source.searchMerged(track)
        } catch (t: Throwable) {
            // v1.12.1：取消放行（见 fetchFromNetwork 里同一注释）
            if (t is CancellationException) throw t
            attempts += failed(source, "搜索失败：${t.message}")
            // 同 fetchFromNetwork：异常属「没查成」，不能当「没歌词」写负缓存
            return LyricResult(null, null, attempts, networkFailed = true)
        }
        // 源自己报告「没查成」
        if (outcome.failed) {
            attempts += failed(source, "网络或接口异常")
            return LyricResult(null, null, attempts, networkFailed = true)
        }
        val candidates = outcome.candidates
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
            return LyricResult(null, null, attempts, networkFailed = true)
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
                source.searchMerged(track).candidates
                    .map { it to scoreMerged(track, it) }
                    .sortedByDescending { it.second }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    /**
     * 按检索变体逐个检索后**合并**成一个结果。
     *
     * v1.12.7：抽出来是因为三处调用（主取词 / 锁定来源 / 调试页）都要做同一件事，
     * 各写一份很容易漏掉 `source.search()` 那一步或漏合并 `failed`。
     *
     * 合并规则：候选累加去重；**任一变体失败即整体算失败** ——
     * 因为只要有一次请求没打通，就不能断定「这首歌没有歌词」。
     *
     * v1.13.9：变体之间改为**并发**。
     *
     * 原来串行是为了省流量，但代价是时间翻倍：只要歌名含一个繁体字
     * （绝大多数中文歌名都含），检索量与耗时都会 ×2。而三个源本身
     * 也是串行的，于是最坏情形是「繁简 × 三源」= 六段等待相加。
     *
     * 改成并发后总耗时取「最长的一段」而非「之和」。
     * 注意 [awaitAll] 不会因为某个协程失败而取消其余协程 ——
     * 异常已在下面各自的 try里消化掉，这里拿到的都是正常返回值。
     */
    private suspend fun LyricSource.searchMerged(track: TrackInfo): SearchOutcome {
        val variants = searchVariants(track)
        // 单变体（歌名本就是简体 / 纯英文）时不必付coroutineScope 的开销，
        // 直接走原路径，行为与并发版完全一致。
        if (variants.size == 1) {
            val o = search(variants[0])
            return SearchOutcome(o.candidates, o.failed)
        }
        val outcomes = coroutineScope {
            variants.map { v -> async { search(v) } }.awaitAll()
        }
        val acc = ArrayList<Candidate>()
        var anyFailed = false
        for (o in outcomes) {
            anyFailed = anyFailed || o.failed
            acc += o.candidates
        }
        val merged = acc.distinctBy { it.sourceId + ":" + it.id }
        return SearchOutcome(merged, anyFailed)
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

    /**
     * 一个源在本次取词里的检索成果：源本身 + 已按分数降序排好的候选。
     *
     * v1.13.8：随 [fetchFromNetwork] 的两阶段改造一同引入。
     * 以前候选是「边遍历边用、用完即弃」，现在必须**留在内存里跨轮次复用**，
     * 所以需要一个容器把「源 ↔ 候选」的对应关系固定下来。
     *
     * [tried] 记录已尝试取过词的候选 id，**八轮共用**。它必须挂在池上而不是
     * 用全局集合：同一个 id 在不同源里可能指向不同曲目，全局按 id 去重会误伤。
     * 有了它，第 3 轮判「仅占位歌词」的候选到第 5 轮不会被重试 ——
     * 那必然还是占位，只白费一次网络请求。
     */
    private class SourcePool(
        val source: LyricSource,
        /** 已过滤掉低于 [MIN_ACCEPT_SCORE] 的候选，并按得分降序。 */
        val ranked: List<Pair<Candidate, Double>>,
    ) {
        val tried = HashSet<String>()

        fun isUntried(c: Candidate): Boolean = c.id !in tried
        fun markTried(c: Candidate) { tried += c.id }
    }

    /**
     * v1.13.8：多源聚合改为「**检索一次 + 八轮递降比对**」。
     *
     * ## 两阶段
     *
     * 1. **检索阶段**：按 [order] 把每个启用的源各搜一次，候选连同得分
     *    存进 [SourcePool]。繁简双路合并、打分、排序都在这里做完。
     * 2. **比对阶段**：按 [SCORE_LADDER] 从 1.00 降到 0.70 逐轮放宽，
     *    每轮都从 `order` 的第一个源重新扫，任一轮取到有效歌词即返回。
     *
     * ## 为什么必须先全部检索、而不是每轮现搜
     *
     * 用户的原始要求：候选只获取一次，后续各轮拿缓存比对，
     * 以免重复占用网络与性能。这不只是省流量 —— 酷狗一次检索要走
     * 「歌曲库 → 补 hash → 取 krcs元」三跳网络，八轮各搜一次是二十四跳。
     *
     * 候选池是方法内的局部变量，返回时即释放，不跨歌、不占常驻内存。
     *
     * ## 为什么每轮都要从头扫
     *
     * 若第一轮就把三个源扫完再降档，退化成「源优先级绝对」：
     * 酷狗有 0.75 就直接用，网易云有 0.99 根本不看。
     * 现在的规则是「全局最高分优先，同分时靠前的源优先」。
     */
    private suspend fun fetchFromNetwork(track: TrackInfo, order: List<String>): LyricResult {
        val attempts = ArrayList<SourceAttempt>()
        val sources = order.mapNotNull { id -> allSources.firstOrNull { it.id == id } }

        // ---------- 阶段一：各源**并发**检索一次，候选留池 ----------
        //
        // v1.13.9：三个源原本是一个搜完再搜下一个，而它们互不依赖。
        // 最坏情形（繁体歌名 + 网易云首个 host 不通）是
        // 「繁简 × 三源」六段等待**相加**；改成并发后取「最长的一段」。
        //
        // 源之间并发、源内部串行（酷狗那 3 次 hash 仍逐个查）：
        // 后者是刻意保留的—— 网易云未认证接口有 IP 级限流，
        // 把总并发数压到「同一时刻 1~2 个请求」换取稳定性。
        // 真正的大头是「等三个源」而不是「源内多跳」。
        val searched = coroutineScope {
            sources.map { source ->
                async {
                    // 异常在这里就地消化成 failed 标记，不让协程整体崩掉 ——
                    // 一个源挂掉不该影响另外两个的结果。
                    // v1.12.1：取消必须放出去，否则用户切歌后旧歌仍在占着 IO 线程。
                    val outcome = try {
                        source.searchMerged(track)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        null
                    }
                    source to outcome
                }
            }.awaitAll()
        }

        // v1.13.9：网络失败判定由「任一源失败」改为「**全部源失败**」。
        //
        // 串行时代那条「任一失败即失败」碰巧是对的 —— 因为只要有一个源
        // 成功给出结果就会直接 return，那标记根本用不上；真正会走到
        // 「三个源都没结果」时，任一源成功查完就足以证明网络是通的。
        //
        // 并发后必须改：假设酷狗与 Lrclib 正常返回「没有」而网易云超时，
        // 旧判定会给这次结果打上 networkFailed，于是**不写负缓存** ——
        // 明明网络大体可用，却让这首歌每次切歌都重新联网三源，
        // 白白浪费流量。改成「全部失败」后，这种情况能正常写负缓存。
        //
        // 极端情况（全部源都失败）时行为与旧版一致：仍然不写负缓存，
        // 下次重试，不会把「没查成」固化成「没有歌词」。
        val allSearchFailed = searched.all { (_, outcome) -> outcome == null || outcome.failed }
        var networkFailed = allSearchFailed

        val pools = ArrayList<SourcePool>(sources.size)
        for ((source, outcome) in searched) {
            if (outcome == null) {
                attempts += failed(source, "搜索失败")
                continue
            }
            // 源自己报告「没查成」（网络/风控/结构异常）
            if (outcome.failed) {
                attempts += failed(source, "网络或接口异常")
                continue
            }
            val candidates = outcome.candidates
            if (candidates.isEmpty()) {
                attempts += failed(source, "无搜索结果")
                continue
            }

            // 打分、降序、截断 —— 全部在检索阶段做完，八轮里不再重算。
            val ranked = candidates.map { it to scoreMerged(track, it) }
                .sortedByDescending { it.second }
                .take(MAX_CANDIDATES_PER_SOURCE)
            // 低于门槛的候选连池都不进：它在八轮里一次都不会被命中。
            val usable = ranked.filter { it.second >= MIN_ACCEPT_SCORE }
            if (usable.isEmpty()) {
                attempts += failed(
                    source,
                    "最佳匹配 %.2f < %.2f".format(ranked.first().second, MIN_ACCEPT_SCORE),
                )
                continue
            }

            pools += SourcePool(source, usable)
        }

        // ---------- 阶段二：按阈值从高到低逐轮放宽，每轮从头扫 ----------
        //
        // 取词阶段的网络异常同样影响负缓存判定（见 [pickFromPools] 内注释），
        // 所以用回调把结果带回，而不是让阶段二自己处理缓存 —— 它不碰缓存。
        var networkFailedByFetch = false
        for ((index, threshold) in SCORE_LADDER.withIndex()) {
            val hit = pickFromPools(pools, threshold, index + 1, attempts) { networkFailedByFetch = true }
            if (hit != null) return hit
        }
        if (networkFailedByFetch) networkFailed = true

        return LyricResult(null, null, attempts, networkFailed)
    }

    /**
     * v1.13.8：在当前阈值下按源顺序扫一遍候选池，取到第一份有效歌词。
     *
     * @param threshold 本轮阈值
     * @param roundNo   第几轮（1 起），只用于 attempts 文案，让调试页能看出命中在哪一档
     * @param onFetchFailed 取词阶段出现网络异常时的回调（检索阶段的异常已在阶段一处理）
     * @return 命中的结果；本轮无人达标返回 null，继续下一轮
     */
    private suspend fun pickFromPools(
        pools: List<SourcePool>,
        threshold: Double,
        roundNo: Int,
        attempts: MutableList<SourceAttempt>,
        onFetchFailed: () -> Unit,
    ): LyricResult? {
        for (pool in pools) {
            val source = pool.source
            // 本轮只考虑达到本档、且前面几轮没试过的候选。
            val usable = pool.ranked.filter { it.second >= threshold && pool.isUntried(it.first) }
            if (usable.isEmpty()) continue

            for ((cand, sc) in usable) {
                pool.markTried(cand)
                val raw = try {
                    source.fetch(cand)
                } catch (t: Throwable) {
                    // v1.12.1：取消放行，理由见上方 search 处同一注释
                    if (t is CancellationException) throw t
                    // 取词阶段的网络异常同样要如实上报，
                    // 否则断网会被当成「没歌词」写进负缓存三天。
                    onFetchFailed()
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
                    note = "$kind · 第$roundNo 轮(≥%.2f)命中《${cand.title}》得分 %.2f".format(threshold, sc),
                    wordLevel = lyric.wordLevel,
                )
                return LyricResult(lyric, source.id, attempts)
            }
        }
        return null
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
     * v1.13.10：磁盘缓存瘦身——**按 mtime 倒序，只删最旧的**。
     *
     * ## 为什么不写在写缓存的路上
     *
     * 写缓存是切歌时的热路径（每首歌一次），在这里加目录列举等于给每首歌
     * 都加一次 `listFiles()`（几百个条目）。而缓存目录的增长是缓慢的，
     * 启动时清一次就够了 —— 冷启动那一次 IO 本来就在 `AppScope.io` 上，
     * 不占用主线程。
     *
     * ## 为什么删除正在读的文件是安全的
     *
     * POSIX 的 unlink 语义：文件被 unlink 后，**已经打开的文件描述符
     * 仍可读到完整内容**。`readCache` 是一次性 `readText()`，
     * 而这里删的是「最旧的、几乎不会被正在读」的那些。
     * 退一步说，即使真删到正在读的，`readCache` 已有 `try/catch` 兜底。
     *
     * ## 为什么按 mtime 而不是文件名
     *
     * 文件名是 `md5(key)`，与时间无关，没法排序。
     */
    private fun pruneDiskCache() {
        val dir = File(app.cacheDir, "lyrics")
        val files = dir.listFiles { f -> f.isFile } ?: return
        // 数量通常远低于上限，直接返回是最常见路径
        if (files.size <= DISK_CACHE_MAX_FILES) {
            var total = 0L
            files.forEach { total += it.length() }
            if (total <= DISK_CACHE_MAX_BYTES) return
        }
        // 最新在前 —— lastModified 拿不到时的文件（少见）排最后
        val byNewest = files.sortedByDescending { it.lastModified() }
        var totalBytes = 0L
        var kept = 0
        byNewest.forEach { f ->
            val len = f.length()
            // 超出任一上限就删；两个上限都超了才继续往下删
            if (kept >= DISK_CACHE_MAX_FILES || totalBytes + len > DISK_CACHE_MAX_BYTES) {
                f.delete()
            } else {
                kept++
                totalBytes += len
            }
        }
    }

    /**
     * 缓存文件格式版本。
     *
     * v1.12.6 起从 1 升到 2：旧版把「熄屏期间网络请求失败」也写成了负缓存，
     * 且这种负缓存有效期 3 天 —— 一次锁屏切歌会让用户以为这首歌没歌词，
     * 必须手动点重取才能恢复。
     *
     * 读缓存时要求版本匹配，等于**一次性作废所有旧版写的缓存**：
     * 既修好了已经中招的用户，又不必去猜测某个负缓存到底是
     * 「真没歌词」还是「当时没网」。代价只是升级后第一次切歌会重新联网，
     * 这本来就是升级后该有的行为。
     */
    private const val CACHE_FORMAT = 2

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
            // 版本不匹配 → 当作无缓存（见 CACHE_FORMAT 的说明）。
            // optInt 对缺失字段返回 0，所以旧文件（没有 ver）必然落到这里。
            if (jo.optInt("ver", 0) != CACHE_FORMAT) return null
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
        // 网络失败不落盘。这里若是把「没查成」写成了负缓存，
        // 熄屏切歌时的一次网络抖动就会把这首歌锁死 3 天，
        // 表现为亮屏后一直「没找到歌词」而手动重取却能拿到。
        if (result.networkFailed) return
        try {
            val jo = JSONObject()
            jo.put("ver", CACHE_FORMAT)
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

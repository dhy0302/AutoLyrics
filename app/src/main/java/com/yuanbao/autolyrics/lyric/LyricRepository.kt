package com.yuanbao.autolyrics.lyric

import android.content.Context
import com.yuanbao.autolyrics.data.Lyric
import com.yuanbao.autolyrics.data.TrackInfo
import com.yuanbao.autolyrics.lyric.parser.KrcParser
import com.yuanbao.autolyrics.lyric.parser.LyricParser
import com.yuanbao.autolyrics.lyric.parser.QrcParser
import com.yuanbao.autolyrics.lyric.parser.YrcParser
import com.yuanbao.autolyrics.lyric.source.KugouSource
import com.yuanbao.autolyrics.lyric.source.LrclibSource
import com.yuanbao.autolyrics.lyric.source.NeteaseSource
import com.yuanbao.autolyrics.util.ChineseConverter
import com.yuanbao.autolyrics.util.TextMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import com.yuanbao.autolyrics.util.SettingsStore

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

    /** 防止同一首歌被并发重复取词。 */
    private val lock = Mutex()

    fun init(context: Context) {
        app = context.applicationContext
        File(app.cacheDir, "lyrics").mkdirs()
        ChineseConverter.init(app)
    }

    suspend fun load(track: TrackInfo, order: List<String>, forceRefresh: Boolean = false): LyricResult {
        if (track.isBlank()) return LyricResult(null, null)
        val key = track.key()

        // 手动锁定来源：跳过缓存与自动回退，直接按指定源/候选取词
        val ovSource = SettingsStore.current().sourceOverride[key]
        val ovCand = SettingsStore.current().sourceOverrideCandidate[key]
        if (ovSource != null && ovSource in SettingsStore.current().enabledSources) {
            val result = fetchForced(track, ovSource, ovCand)
            memory[key] = result
            writeCache(key, result)
            return result
        }

        if (!forceRefresh) {
            memory[key]?.let { return it }
            readCache(key)?.let {
                memory[key] = it
                return it
            }
        }

        return lock.withLock {
            memory[key]?.takeIf { !forceRefresh }?.let { return@withLock it }
            val result = fetchFromNetwork(track, order)
            memory[key] = result
            writeCache(key, result)
            result
        }
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

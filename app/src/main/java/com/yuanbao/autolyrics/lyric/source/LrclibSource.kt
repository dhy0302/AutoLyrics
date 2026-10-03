package com.yuanbao.autolyrics.lyric.source

import com.yuanbao.autolyrics.data.TrackInfo
import com.yuanbao.autolyrics.lyric.Candidate
import com.yuanbao.autolyrics.lyric.LyricSource
import com.yuanbao.autolyrics.lyric.RawLyric
import com.yuanbao.autolyrics.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lrclib（海外公开歌词库，无需登录）。
 *
 * 放在最后做兜底：在日本/海外网络下国内接口可能不通，它能保证「至少有词」。
 * 中文歌覆盖率低于 QQ / 网易。
 */
object LrclibSource : LyricSource {

    override val id = "lrclib"
    override val displayName = "Lrclib（海外兜底）"

    private const val MAX_RESULTS = 10

    private val HEADERS = mapOf(
        "User-Agent" to Http.UA_BOT,
        "Accept" to "application/json",
    )

    override suspend fun search(track: TrackInfo): List<Candidate> = withContext(Dispatchers.IO) {
        // 1) 精确接口：歌名 + 歌手 + 时长全对上才返回，命中即最可信
        val durationSec = (track.durationMs / 1000).coerceAtLeast(0)
        val exactUrl = "https://lrclib.net/api/get?track_name=${Http.enc(track.title)}" +
                "&artist_name=${Http.enc(track.artist)}&duration=$durationSec"
        parseOne(Http.getOrNull(exactUrl, HEADERS) ?: "").let { if (it != null) return@withContext listOf(it) }

        // 2) 退化为模糊搜索
        val keyword = if (track.artist.isBlank()) track.title else "${track.title} ${track.artist}"
        val body = Http.getOrNull("https://lrclib.net/api/search?q=${Http.enc(keyword)}", HEADERS)
            ?: return@withContext emptyList()
        return@withContext try {
            val arr = JSONArray(body)
            val out = ArrayList<Candidate>()
            for (i in 0 until minOf(arr.length(), MAX_RESULTS)) {
                parseOne(arr.optJSONObject(i)?.toString() ?: continue)?.let { out += it }
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 搜索结果里通常已带歌词正文，直接复用可省一次请求；
     * 没有则按 id 再取一次。
     */
    override suspend fun fetch(candidate: Candidate): RawLyric? {
        candidate.extra["synced"]?.takeIf { it.isNotBlank() }?.let { return RawLyric(it) }
        candidate.extra["plain"]?.takeIf { it.isNotBlank() }?.let { return RawLyric(it) }

        val jo = Http.getOrNull("https://lrclib.net/api/get/${candidate.id}", HEADERS)
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val synced = jo.optString("syncedLyrics")
        val plain = jo.optString("plainLyrics")
        return when {
            synced.isNotBlank() -> RawLyric(synced)
            plain.isNotBlank() -> RawLyric(plain)
            else -> null
        }
    }

    private fun parseOne(json: String): Candidate? {
        if (json.isBlank()) return null
        val jo = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val rawId = jo.optLong("id", -1L).takeIf { it > 0 }?.toString()
            ?: jo.optString("id").takeIf { it.isNotBlank() }
            ?: return null
        return Candidate(
            sourceId = id,
            id = rawId,
            title = jo.optString("trackName"),
            artist = jo.optString("artistName"),
            durationMs = (jo.optDouble("duration", 0.0) * 1000).toLong(),
            album = jo.optString("albumName"),
            extra = mapOf(
                "synced" to jo.optString("syncedLyrics"),
                "plain" to jo.optString("plainLyrics"),
            ),
        )
    }
}

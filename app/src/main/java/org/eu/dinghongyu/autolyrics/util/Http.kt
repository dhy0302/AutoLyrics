package org.eu.dinghongyu.autolyrics.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 统一的 HTTP 出口：所有歌词源共用同一个 OkHttpClient，避免重复建连。 */
object Http {

    const val UA_PC =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** lrclib 要求带应用标识的 UA。 */
    const val UA_BOT = "AutoLyrics/1.0 (Android; +https://example.invalid)"

    private const val TIMEOUT_SEC = 10L

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 成功返回 body，非 2xx 或异常一律抛错，由调用方决定回退。 */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).header("User-Agent", UA_PC)
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code} @ $url")
                resp.body?.string() ?: error("empty body @ $url")
            }
        }

    /**
     * POST JSON（QQ 音乐的 `musicu.fcg` 统一网关只接受 POST 的 `comm` 包体）。
     * 成功返回 body，非 2xx 或异常一律抛错。
     */
    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", UA_PC)
                .post(json.toRequestBody(mediaType))
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code} @ $url")
                resp.body?.string() ?: error("empty body @ $url")
            }
        }

    suspend fun postJsonOrNull(url: String, json: String, headers: Map<String, String> = emptyMap()): String? =
        try {
            postJson(url, json, headers)
        } catch (_: Throwable) {
            null
        }

    /**
     * POST `application/x-www-form-urlencoded` 表单，返回 body；失败返回 null。
     */
    suspend fun postFormOrNull(
        url: String,
        form: String,
        cookie: String = "",
    ): String? = try {
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", UA_PC)
                .header("Referer", "https://music.163.com/")
                .header("Origin", "https://music.163.com")
                .header("Accept", "application/json, text/plain, */*")
                .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            if (cookie.isNotBlank()) builder.header("Cookie", cookie)
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code} @ $url")
                resp.body?.string() ?: error("empty body @ $url")
            }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * 「查不到」是正常的业务分支（可能接口风控、地域限制、单曲无词），
     * 不希望它打断多源回退链，所以这里统一吞掉异常返回 null。
     */
    suspend fun getOrNull(url: String, headers: Map<String, String> = emptyMap()): String? =
        try {
            get(url, headers)
        } catch (_: Throwable) {
            null
        }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

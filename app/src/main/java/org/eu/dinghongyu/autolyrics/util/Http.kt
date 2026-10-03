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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    /**
     * v1.12.1：把同步 `execute()` 换成 `enqueue()` + 挂起。
     *
     * ## 为什么必须换
     *
     * 旧版 `execute()` 是**阻塞**调用，它把当前线程（IO 线程）钉死直到拿到响应。
     * 问题是它**听不见协程的取消**：用户快速切歌时，`LyricEngine` 会
     * `loadJob?.cancel()`，但正在飞的那次 HTTP 完全不知道自己该停，
     * 只能干等 socket 超时（10 秒）或响应回来。
     *
     * 表现就是：连点几下切歌，界面要卡好几秒才动 —— 那条旧请求还在霸着锁。
     *
     * `enqueue()` 走OkHttp 自己的调度线程，回调回来后再 `resume` 挂起协程，
     * 于是 `invokeOnCancellation { call.cancel() }` 能在取消发生的**那一刻**
     * 直接掐断连接，socket 立刻释放。
     */
    private suspend fun execute(request: Request): String =
        suspendCancellableCoroutine<String> { cont ->
            val call = client.newCall(request)
            // 取消时立刻断连。这一句是本次改动的核心。
            cont.invokeOnCancellation {
                runCatching { call.cancel() }
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // 已取消时不再 resume，否则会抛 IllegalStateException
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    // 注意这个 `return` 跳出的是 onResponse 自己（局部返回）。
                    // 绝不能写成 `suspendCancellableCoroutine { cont -> if (...) return ... }`
                    // 那种 —— 块参数是 crossinline，跨 lambda 返回会编译失败。
                    if (!cont.isActive) {
                        response.close()
                        return
                    }
                    // v1.12.1：**只让「读 body」这段的异常走 resumeWithException**。
                    //
                    // 旧版把 resume 也包在 try 里，于是若 resume 本身抛了
                    // （极端竞态下的重复 resume），catch 会再调一次
                    // resumeWithException —— 它在 catch 里抛出就没人接了，
                    // 会一路冒到 OkHttp 的 Dispatcher 线程变成**后台线程崩溃**。
                    // 分开写之后，resume 抛出的异常由 OkHttp 自己处理。
                    val text = try {
                        response.use {
                            if (!it.isSuccessful) error("HTTP ${it.code} @ ${request.url}")
                            it.body?.string() ?: error("empty body @ ${request.url}")
                        }
                    } catch (e: Throwable) {
                        cont.resumeWithException(e)
                        return
                    }
                    cont.resume(text)
                }
            })
        }

    /** 成功返回 body，非 2xx 或异常一律抛错，由调用方决定回退。 */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).header("User-Agent", UA_PC)
            headers.forEach { (k, v) -> builder.header(k, v) }
            execute(builder.build())
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
            execute(builder.build())
        }

    suspend fun postJsonOrNull(url: String, json: String, headers: Map<String, String> = emptyMap()): String? =
        try {
            postJson(url, json, headers)
        } catch (e: CancellationException) {
            // v1.12.1：**取消必须放出去，不能当「查不到」**。
            // CancellationException 是 Throwable 的子类，
            // 若被下面的 catch (_: Throwable) 吞掉，协程会带着 null 继续往下走，
            // 于是一路回退到下一个源继续发请求 —— 切歌后仍在为旧歌查歌词，
            // 「可取消」就等于白做了。
            throw e
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
            execute(builder.build())
        }
    } catch (e: CancellationException) {
        // 同 postJsonOrNull：取消不放行
        throw e
    } catch (_: Throwable) {
        null
    }

    /**
     * 「查不到」是正常的业务分支（可能接口风控、地域限制、单曲无词），
     * 不希望它打断多源回退链，所以这里统一吞掉异常返回 null。
     *
     * v1.12.1：但**取消不算「查不到」**，见 [postJsonOrNull] 里的说明。
     */
    suspend fun getOrNull(url: String, headers: Map<String, String> = emptyMap()): String? =
        try {
            get(url, headers)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

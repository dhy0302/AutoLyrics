/*
 * AutoLyrics — 安卓自动歌词
 * Copyright (C) 2026 丁宏宇
 *
 * 本程序遵循 GNU General Public License v3.0 或更高版本发布。
 * 详见仓库根目录的 LICENSE 文件。
 */

package org.eu.dinghongyu.autolyrics.util

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.18.6：**一次性诊断探针** —— 为「通知栏歌词后台停住」取证。
 *
 * ## 为什么需要它
 *
 * 这个 bug 已经误判三轮（进程被冻结 → 协程静默死亡 → 主线程被节流），
 * 三次都是**静态推理**，三次都错。原因很明确：
 * 症状「后台停住、前台恢复」同时符合多个候选根因，
 * 而静态读代码无法区分它们。
 *
 * 用户补充的关键线索是「**前几天的版本没这个问题**」——
 * 这是**回归**，本该用 `git bisect` 定位，但二分要人工逐版安装测试，
 * 成本太高。于是改为：**让程序自己把证据写下来**。
 *
 * ## 为什么写在文件里而不是 Toast / Log
 *
 *  - Logcat 需要连着电脑看，而这个 bug 恰好发生在「用户切到别的 App」时；
 *  - 复现后用户可能立刻切回来，日志缓冲区已被冲掉；
 *  - 文件可以累积，用户复现完再连电脑一次性取走。
 *
 * 写入做**环形缓冲**：只保留最后 [MAX_LINES] 行，
 * 避免无上限增长把存储写满（这本身会引发新的 bug）。
 *
 * ## 采样策略
 *
 * 只记「**变化**」不记「没变化」——
 * 位置每秒变 10~20 次，逐条记会把关键信息冲掉。
 * 于是每条记录都带一个「距上次记录过了多久」，
 * 于是「停住了」表现为**记录之间的时间间隔越来越大**，一眼可见。
 */
object Trace {

    private const val MAX_LINES = 4000
    private const val FILE_NAME = "trace.log"

    private val lock = Any()
    private var file: File? = null

    /** 上一次写入是否发生过（用于算间隔）。 */
    private var lastAt = 0L

    /** 每个 tag 的上一次取值，用于跳过「没变化」。 */
    private val lastValues = HashMap<String, String>()

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * 记录一条**状态变化**。[value] 与该tag 上次相同则不写。
     *
     * @param tag 环节名，如「pos」「idx」
     * @param value 本次的值
     */
    fun changed(tag: String, value: Any?) {
        val v = value?.toString() ?: "null"
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (lastValues[tag] == v) return
            val gap = if (lastAt == 0L) 0L else now - lastAt
            lastValues[tag] = v
            lastAt = now
            append("[${timeFmt.format(Date())}][+${gap}ms][$tag] $v")
        }
    }

    /** 无条件记录一条（用于错误、心跳异常这类低频事件）。 */
    fun log(tag: String, value: String) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val gap = if (lastAt == 0L) 0L else now - lastAt
            lastAt = now
            append("[${timeFmt.format(Date())}][+${gap}ms][$tag] $value")
        }
    }

    private fun append(line: String) {
        val f = file ?: return
        try {
            f.appendText(line + "\n")
        } catch (_: Throwable) {
            // 探针自身绝不能影响主流程
        }
    }

    /** 由 [org.eu.dinghongyu.autolyrics.App] 在启动时调用。 */
    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val dir = File(context.filesDir, "trace").apply { mkdirs() }
            val f = File(dir, FILE_NAME)
            // 冷启动清空上一轮，避免新旧混在一起
            f.writeText("")
            file = f
            append("[${timeFmt.format(Date())}][boot] pid=${android.os.Process.myPid()}")
        }
    }

    /** 记录进程与线程信息 —— 判断「协程是否还活着」的关键证据。 */
    fun markThread(tag: String) {
        log(tag, "thread=${Thread.currentThread().name}")
    }

    /** 供 UI 调用：读出全部内容。 */
    fun readAll(): String = synchronized(lock) {
        file?.readText().orEmpty()
    }

    /** 供 UI 调用：清空。 */
    fun clear() = synchronized(lock) {
        lastValues.clear()
        lastAt = 0L
        file?.writeText("")
    }
}
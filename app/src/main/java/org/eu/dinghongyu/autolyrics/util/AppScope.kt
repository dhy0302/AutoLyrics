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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 进程级协程作用域：不会因为某个任务失败而连坐。 */
object AppScope {
    val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

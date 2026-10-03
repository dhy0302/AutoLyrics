package com.yuanbao.autolyrics.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 进程级协程作用域：不会因为某个任务失败而连坐。 */
object AppScope {
    val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

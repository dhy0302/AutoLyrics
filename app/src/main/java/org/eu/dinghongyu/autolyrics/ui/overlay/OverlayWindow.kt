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

package org.eu.dinghongyu.autolyrics.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 悬浮窗用的极简生命周期宿主。
 * ComposeView 脱离 Activity 时必须自己提供 LifecycleOwner 与 SavedStateRegistryOwner，
 * 否则重组不工作。这里只实现必要的 ON_CREATE → ON_RESUME 与 ON_PAUSE → ON_DESTROY。
 */
class OverlayLifecycleOwner : androidx.lifecycle.LifecycleOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    fun onCreate() {
        controller.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

/**
 * 真正的悬浮窗载体：一个挂在 WindowManager 上的 ComposeView。
 *
 * 窗口参数要点：
 *  - TYPE_APPLICATION_OVERLAY：Android 8+ 唯一可用的悬浮窗类型
 *  - FLAG_NOT_FOCUSABLE / NOT_TOUCH_MODAL：不抢焦点，不拦截背后 App 的操作
 *  - 宽度 MATCH_PARENT、高度 WRAP_CONTENT：只占住顶部一条，不挡整屏
 */
class OverlayWindow(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val lifecycleOwner = OverlayLifecycleOwner()
    private var view: ComposeView? = null
    private var showing = false

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    fun show(startY: Int) {
        if (showing) return
        params.y = startY

        val composeView = ComposeView(context)
        // 必须先绑定生命周期宿主，再 setContent，否则 Compose 拿不到状态保存机制
        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        lifecycleOwner.onCreate()
        composeView.setContent {
            OverlayContent(onDrag = ::moveBy)
        }

        view = composeView
        if (runCatching { windowManager.addView(composeView, params) }.isFailure) {
            view = null
            lifecycleOwner.onDestroy()
            return
        }
        showing = true
    }

    fun hide() {
        if (!showing) return
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        showing = false
        lifecycleOwner.onDestroy()
    }

    fun moveBy(dx: Float, dy: Float) {
        params.x += dx.toInt()
        params.y += dy.toInt()
        updateLayout()
    }

    fun currentY(): Int = params.y

    /**
     * 锁定：加 FLAG_NOT_TOUCHABLE 让点击直接穿透到下层 App。
     * 注意——锁定后悬浮窗自己也不再响应触摸，解锁必须回 App 设置页操作。
     */
    fun setLocked(locked: Boolean) {
        params.flags = if (locked) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        updateLayout()
    }

    private fun updateLayout() {
        view?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }
}

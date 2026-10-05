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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.eu.dinghongyu.autolyrics.util.SettingsStore
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

    // v1.18.7：解锁小窗。与主窗共享 [lifecycleOwner] —— ComposeView 脱离
    // Activity 时需要 LifecycleOwner，同一个宿主可以挂多个 ComposeView。
    private var unlockView: ComposeView? = null

    /**
     * v1.18.7：解锁小窗的窗口参数。
     *
     * ## 为什么必须另开一个窗口，而不是把解锁按钮留在主窗里
     *
     * 锁定是通过 [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] 实现的，
     * 而这个 flag 是**整窗生效**的 —— Android 没有「窗口内某个区域可点、
     * 其余区域穿透」的能力。所以主窗一旦锁定，里面的按钮全都点不到。
     *
     * 以前解锁入口放在通知栏，正是被这一点逼的。
     * 现在通知栏只剩「开启/关闭桌面歌词」一个按钮，
     * 就必须另开一个**独立的小窗**，它自己不带 NOT_TOUCHABLE，
     * 于是在主窗彻底点不动的情况下仍能解锁。
     *
     * 尺寸刻意取 WRAP_CONTENT× WRAP_CONTENT 并靠右上角 ——
     * 主窗在顶部居中、宽度MATCH_PARENT，小窗贴在右上角不会遮歌词。
     */
    private val unlockParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

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

    /**
     * v1.13.10：hide() 里补上 [ComposeView.disposeComposition]。
     *
     * `windowManager.removeView` 只是把视图摘下，
     * **Composition 仍然持有全部 Composable 状态**，要等 GC 才回收。
     * 而 [org.eu.dinghongyu.autolyrics.ui.overlay.OverlayController] 的
     * `autoHideOnPause`（默认开启）会让悬浮窗频繁 hide/show ——
     * 每次暂停/无播放都 abandon + rebuild 一遍 Composition，
     * 在这种高频路径上留着它就是持续的无谓占用。
     *
     * ## 顺序很关键
     *
     * 先 dispose 再 removeView。反过来的话 removeView 之后这一帧还在绘制，
     * 可能撞上 "Cannot access a disposed compose view"。
     *
     * 之后 [show] 会新建一个 ComposeView 并重新 setContent，
     * 所以这里丢掉 Composition 不会丢状态 ——
     * 悬浮窗本来就不承诺跨hide/show 保留滚动位置。
     */
    fun hide() {
        if (!showing) return
        // v1.18.7：解锁小窗要先收。
        //
        // 它与主窗共用 [lifecycleOwner]，而 `onDestroy()` 会把宿主的生命周期
        // 推到底 —— 那时小窗的 ComposeView 还挂在 WindowManager 上，
        // 它下一帧就会撞上 "Cannot access a disposed compose view"。
        //
        // 顺序：先摘小窗 → 再摘主窗 → 最后销毁宿主。
        hideUnlockBar()
        view?.let { v ->
            // 放在 runCatching 外层：dispose 失败不应该阻止窗口被移除，
            // 否则窗口会永远留在屏幕上（比泄漏严重得多）。
            runCatching { v.disposeComposition() }
            runCatching { windowManager.removeView(v) }
        }
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
     * 锁定：加 FLAG_NOT_TOUCHABLE 让点击直接穿透到下层App。
     *
     * v1.18.7：锁定时**同时**挂起解锁小窗（见 [unlockParams] 的说明）——
     * 以前解锁入口在通知栏，现在通知栏只剩一个开关，
     * 所以必须在这里自备出口，否则锁上就解不开了。
     */
    fun setLocked(locked: Boolean) {
        params.flags = if (locked) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        updateLayout()
        if (locked) showUnlockBar() else hideUnlockBar()
    }

    /**
     * v1.18.7：显示解锁小窗（只在锁定时存在）。
     *
     * 用 try/catch 包住 addView —— 悬浮窗权限可能被用户在系统设置里撤掉，
     * 那时 addView 会抛异常。不捕获的话整个 [setLocked] 会连带失败，
     * 连带主窗的 NOT_TOUCHABLE 也加不上，状态就不一致了。
     */
    private fun showUnlockBar() {
        if (unlockView != null) return
        val cv = ComposeView(context)
        cv.setViewTreeLifecycleOwner(lifecycleOwner)
        cv.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        cv.setContent {
            UnlockChip(onUnlock = {
                SettingsStore.update { s -> s.copy(overlayLocked = false) }
            })
        }
        if (runCatching { windowManager.addView(cv, unlockParams) }.isFailure) return
        unlockView = cv
    }

    private fun hideUnlockBar() {
        unlockView?.let { v ->
            runCatching { v.disposeComposition() }
            runCatching { windowManager.removeView(v) }
        }
        unlockView = null
    }

    private fun updateLayout() {
        view?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }
}

/**
 * v1.18.7：解锁小窗的内容 —— 一个两字的「解锁」按钮。
 *
 * 文案刻意压到两字：它贴在上角、尺寸是 WRAP_CONTENT，
 * 但仍会占用屏幕右上角的空间，字越多越挡事。
 *
 * 配色沿用主窗的半透明深底 + 象牙白，两处看起来是一个东西。
 */
@Composable
private fun UnlockChip(onUnlock: () -> Unit) {
    Text(
        text = "解锁",
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFFE8E4DC),
        modifier = Modifier
            .background(Color(0xCC0D0E10), RoundedCornerShape(10.dp))
            .clickable(onClick = onUnlock)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

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

package org.eu.dinghongyu.autolyrics.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.eu.dinghongyu.autolyrics.data.TrackInfo
import org.eu.dinghongyu.autolyrics.data.TransportCapabilities
import org.eu.dinghongyu.autolyrics.ui.components.AlbumArt
import org.eu.dinghongyu.autolyrics.util.SettingsStore

/** 一个 MediaSession 在某一时刻的完整快照。 */
data class SessionSnapshot(
    /** 会话标识（包名 + token 哈希） */
    val key: String,
    val pkg: String,
    val track: TrackInfo,
    val state: Int,
    /** 已按上报时刻推算过的播放位置 */
    val positionMs: Long,
    val albumArt: Bitmap?,
    val albumArtUri: String?,
    val capabilities: TransportCapabilities,
)

/**
 * 通过 MediaSessionManager 抓取所有活跃 MediaSession。
 *
 * 这是获取 Spotify 曲名/歌手/时长/播放位置/封面，以及控制播放的正确姿势：
 *  - 读会话需要通知监听权限（Android 强制，见 [org.eu.dinghongyu.autolyrics.media.MediaNotificationListener]）
 *  - 播放位置 = `PlaybackState.position + (now - lastPositionUpdateTime)`，
 *    播放器上报是稀疏的，必须自己插值，否则进度条会一跳一跳
 */
object MediaSessionWatcher {

    @Volatile
    private var app: Context? = null

    @Volatile
    private var manager: android.media.session.MediaSessionManager? = null

    /**
     * v1.12.1：`MediaController.Callback` 的派发线程。
     *
     * ## 为什么要挪到主线程之外
     *
     * 旧版是 `Handler(Looper.getMainLooper())`，于是**播放器每次改播放态/换歌，
     * 回调都在主线程跑 `PlaybackMonitor.update()`** —— 而它会顺着
     * `best()` → `snapshots()` 走到 `MediaController.metadata`，
     * 那是**跨进程 Binder 调用**，必须等播放器的进程回数据。
     *
     * 主线程本该在等 vsync 画一帧，却被 Binder 往返挡住，
     * 表现出来就是「点暂停图标偶尔顿一下」。而媒体 App 更新进度时
     * 会频繁改 PlaybackState，这条路比 ticker 还密。
     *
     * ## 为什么用 HandlerThread（这里踩过两个坑）
     *
     * 坑一：`MediaController.registerCallback` 在 Android SDK 里
     * **只有接收 `Handler` 的重载，没有接收 `Executor` 的**。所以
     * `registerCallback(callback, Dispatchers.IO.asExecutor())` 编译直接失败：
     *
     *     Argument type mismatch: actual type is 'java.util.concurrent.Executor',
     *     but 'android.os.Handler?' was expected.
     *
     * 坑二：`Dispatchers.IO.asHandler()` 也不存在 ——
     * `kotlinx-coroutines-android` 只暴露 `asCoroutineDispatcher`，
     * 库里的 `asHandler` 是 internal 且接收者是 Looper，跨模块用不了。
     * 而且绕一圈 `Handler(Executor)` 也不行：那个构造器是 **API 28**，
     * 本项目 minSdk = 26，在 Android 8.0/8.1 上会抛 NoSuchMethodError。
     *
     * `HandlerThread` 是正解：自己起一条干净的线程，配一个绑定它的 Handler，
     * 既满足 SDK 要求的 `Handler` 类型，又确保回调不碰主线程。
     *
     * ## 回调体本身为什么可以放心搬到后台
     *
     * 回调体只有 `PlaybackMonitor.update()`，它做的全是 StateFlow 赋值 ——
     * StateFlow 的 `value` setter 是线程安全的，跨线程写没问题。
     * 真正读 UI 状态的一侧（Compose 的 `collectAsState`）本来就在主线程，
     * 不受这里影响。
     */
    private val callbackThread = HandlerThread("autolyrics-media-cb").apply { start() }

    private val callbackHandler = Handler(callbackThread.looper)

    // v1.12.1：**刻意不在 stop() 里 quit 这个 HandlerThread**。
    //
    // 它是 object 的属性，生命周期与进程一致；而 stop() / start() 会被反复调用
    // （权限开关、通知服务重绑），如果 stop() 里 quit，那么之后 start() 再注册
    // 回调时 looper 已经死了，回调**永远不会触发** —— 表现为「重开权限后
    // 歌词再也不更新了」，这种 bug 极难排查。
    //
    // 留着的代价只是一条几乎不占资源的空线程（无消息时 sleeping），
    // 换来的是生命周期绝对安全。这个取舍是划算的。

    /**
     * v1.12.1：保护 [controllers] / [callbacks] / [metaCache] 三个共享容器。
     *
     * ## 为什么需要锁
     *
     * 这三个集合本来是**无保护的普通 HashMap**，而它们至少被两条线程同时碰：
     *  - 主线程：`ensureStarted` → [refresh]（Activity 恢复、通知服务连上）
     *  - IO 线程：ticker → `best()` → [snapshots]（每 50~200ms 一次）
     *
     * v1.12.1 把回调也搬到 IO 线程后，交叉访问的机会变多，
     * 而 `HashMap` 在并发 resize 时可能死循环 / 抛 `ConcurrentModificationException`。
     * 这不是新引入的 bug，但改动会放大它，所以顺手补上。
     *
     * ## 锁的边界（重要）
     *
     * **只锁内存结构的读写，绝不把跨进程 Binder 调用包进锁里**。
     * 否则一次 IPC 的等待时间会把锁占住，
     * 主线程上的 [refresh] 就会跟着一起卡 —— 那正好是本次优化要消除的。
     */
    private val lock = Any()

    private val controllers = LinkedHashMap<String, MediaController>()
    private val callbacks = HashMap<String, MediaController.Callback>()

    // v1.12.1：下面三个是**裸的跨线程可见性**问题（不在 [lock] 保护范围内，
    // 因为它们不是集合、没有「读改写」复合操作，加锁反而多余）。
    // 主线程写（start/ensureStarted）、IO 线程读（isLinked / callback）时，
    // 没有内存屏障就可能读到旧值。
    @Volatile
    private var sessionsListener: android.media.session.MediaSessionManager.OnActiveSessionsChangedListener? = null

    /** 抓取链路是否就绪（权限已授予且 OnActiveSessionsChangedListener 注册成功）。 */
    @Volatile
    private var linked = false

    /** 上一轮选中的会话 key：多个会话同时活跃时保持选择稳定，避免来回跳。 */
    private var lastKey: String? = null

    /** 最近一次 [best] 的结果，供传输控制复用。 */
    private var current: SessionSnapshot? = null

    /**
     * 会话元数据解析缓存（v1.8.2 建立，v1.13.10 重做判据）。
     *
     * key = 会话 key，value = (内容指纹, 解析结果)。
     *
     * ## v1.13.10：为什么不能再用 `===` 比实例
     *
     * 原实现是 `cached.raw === metadata`，理由是「换歌必然产生新的
     * MediaMetadata 实例，内容比对要走 Bundle 序列化，反而把省下的开销还回去」。
     *
     * **这个前提是错的**：AOSP 的 `MediaController.getMetadata()` 每次跨进程调用
     * 都会**反序列化出一个全新的 MediaMetadata 实例**——不是「换歌才新」，
     * 而是「每次读都新」。于是引用永不相等，**缓存命中率恒等于 0，
     * 是纯粹的只写不读**。
     *
     * 代价是每 tick（标准档 10 次/秒）都要走完整解析：`getBitmap` 反序列化
     * 全尺寸封面（最高 4MB）→ `downsample` 缩到 320px → 新建 MetaInfo/TrackInfo
     * → `PlaybackMonitor.albumArt` 引用变化 → **HomeScreen 整页每秒重组 15 次**
     * → Palette 取色 + 模糊各 15 次/秒。每秒 40~80MB 分配 churn，
     * 把v1.12.1 把 positionMs 改成 lambda 化的收益完全抵消了。
     *
     * ## 现在的判据：内容指纹
     *
     * 用**最终生效的那组字段**（title / artist / album / duration / artUri）
     * 拼指纹。这几个字段都是读已反序列化好的 Bundle，**不会触发位图
     * 反序列化**，代价与一次字符串拼接同量级。
     *
     * 指纹相同 ⇒解析结果必然相同 ⇒ 直接复用，**连 `getBitmap` 都不调**。
     *
     * ## v1.16.0：指纹漏了「封面」这一维，曾导致切歌后封面永久空白
     *
     * 指纹只由 title / artist / album / duration / artUri 拼成，**不含封面**。
     * 而播放器切歌时普遍分两步 `setMetadata`：先发文本（封面键还没有），
     * 隔一拍再补封面。这两次的文本字段**一字不差** ⇒ 指纹相同 ⇒ 命中缓存 ⇒
     * 那份「无封面」的旧结果被永久钉死，表现为**切歌后封面一直空白到下一首**。
     * 首曲正常，因为冷启动时播放器一次性把封面带上了。
     *
     * 修法见 [toSnapshot] 里的 `hasArtKey`：命中判据加一个例外 ——
     * 缓存无封面、而当前 metadata 带封面键时**不许命中**。
     * `containsKey` 是 Bundle 上的 O(1) 查询，不触发位图反序列化。
     *
     * ## 已知边界（有意接受）
     *
     * 同名同专辑同时长、但封面 Bitmap 不同（同一首歌的不同版本）时，
     * 若artUri 也相同会漏检。实践中这类场景极少，
     * 且下次切歌必然换指纹。若真遇到，解法是给artUri 之外的
     * `METADATA_KEY_ALBUM_ART` 加一个尺寸/存在性标记。
     * （注意这个「存在性标记」不能直接拼进指纹，理由同上面的例外。）
     *
     * 容量按活跃会话数量级给足（一般 1~5 个），但仍设上限防止
     * 某些 ROM 疯狂重建 controller 时无限增长。
     */
    private val metaCache = HashMap<String, MetaCacheEntry>()

    private class MetaInfo(
        val track: TrackInfo,
        val albumArt: Bitmap?,
        val albumArtUri: String?,
    )

    /**
     * v1.13.10：不再持有 [MediaMetadata] 实例。
     *
     * 旧结构里的 `raw: MediaMetadata` 会把整份元数据（含全尺寸封面，
     * 最高 4MB）**强引用**钉在缓存里。改成指纹后这个字段没有存在必要，
     *顺带把这块常驻内存也一起还掉了。
     */
    private class MetaCacheEntry(
        val fingerprint: String,
        val info: MetaInfo,
    )

    /**
     * v1.13.10：清空元数据解析缓存，由 `Application.onTrimMemory` 调用。
     *
     * 这是最容易重建的一块（下次 tick 重新解析一次即可），
     * 而它里面可能钉着一份带全尺寸封面的 MediaMetadata。
     * 所以内存吃紧时**优先清它**，比清歌词 LRU 的性价比高得多。
     */
    fun trimMetaCache() {
        synchronized(lock) { metaCache.clear() }
    }

    fun start(context: Context) {
        val ctx = context.applicationContext
        app = ctx
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as? android.media.session.MediaSessionManager ?: return
        manager = msm

        // 幂等：onListenerConnected 可能因重绑被多次调用。
        // 先移除旧监听，避免重复注册造成监听泄漏与重复回调
        try { sessionsListener?.let { msm.removeOnActiveSessionsChangedListener(it) } } catch (_: Throwable) {}
        sessionsListener = null
        // v1.12.1：与共享容器相关的重置统一进锁
        synchronized(lock) {
            lastKey = null
            current = null
            // v1.8.2：重建链路时旧缓存全部失效（controller 实例都换了）
            metaCache.clear()
        }

        val component = ComponentName(ctx, MediaNotificationListener::class.java)
        try {
            val listener =
                // v1.8.2：用轻量版，只做 controller 增删。
                // 旧版这里调 refresh()，会在每次系统回调（某些 ROM 上很频繁）
                // 都做一次全量 best() 扫描，与 ticker 重复。
                android.media.session.MediaSessionManager.OnActiveSessionsChangedListener { refreshSessionsOnly() }
            msm.addOnActiveSessionsChangedListener(listener, component)
            sessionsListener = listener
            // 标记为「链路已就绪」：getActiveSessions 能否工作只取决于通知读取权限是否授予，
            // 不取决于 NotificationListenerService 是否已回调 onListenerConnected。
            linked = true
            refresh()
        } catch (_: SecurityException) {
            // 通知监听权限未授予：整个抓取链路不可用，交给 UI 引导用户
            linked = false
        } catch (_: Throwable) {
            // 个别 ROM 的实现有 bug，静默忽略
        }
    }

    /**
     * 只要通知读取权限已授予，就确保抓取链路已建立。
     *
     * 关键修复（用户实测「权限一直开着却检测不到」的根因）：
     * `MediaSessionManager.getActiveSessions()` 的准入条件是**通知读取权限已授予**，
     * 并**不要求** NotificationListenerService 已绑定/已回调 onListenerConnected。
     * 旧实现把 start() 只挂在 onListenerConnected 上，一旦被强杀后系统不再回调该方法，
     * 整条链路就永久失灵——只能靠用户手动关开权限来触发。
     * 现在改为：App 启动 / Activity 恢复时主动调用本方法，与服务回调解耦。
     */
    fun ensureStarted(context: Context) {
        if (linked) {
            // 已建立，只需重新扫一次（会话可能在这期间才出现）
            refresh()
            return
        }
        start(context)
    }

    /** 抓取链路是否就绪（等价于「权限已授予且监听已注册」）。 */
    val isLinked: Boolean get() = linked

    fun stop() {
        try {
            sessionsListener?.let { manager?.removeOnActiveSessionsChangedListener(it) }
        } catch (_: Throwable) {
        }
        sessionsListener = null
        linked = false
        // v1.12.1：**不再 recycle 封面位图**，只清引用，交给 GC。
        //
        // 旧注释说「这是最容易漏的一处」，但recycle 在这里恰恰是错的：
        // `AlbumArt.downsample` 在源图 ≤320px 时**直接返回源对象**
        // （AlbumBackdrop.kt:99），所以 metaCache 里的位图与
        // PlaybackMonitor._albumArt 里的可能是**同一个实例**。
        // 这里是主线程，而 UI 侧（HomeScreen 的封面卡、AlbumBackdrop 的模糊缓存）
        // 可能还持有引用 —— 重组慢一帧就会撞上
        // "Canvas: trying to use a recycled bitmap" 崩溃。
        //
        // 这与 PlaybackMonitor.kt:155-158 / :215-218 的结论一致
        // （那里早就踩过并改掉了），此处属于**同一份教训没有一致落地**。
        // 位图已降采样到 320px（约 410KB），清引用后 GC 回收足够安全。
        //
        // v1.12.1：容器操作收进锁内，但 unregisterCallback 有 IPC，留在锁外。
        val toUnregister: List<Pair<MediaController, MediaController.Callback>>
        synchronized(lock) {
            metaCache.clear()
            toUnregister = controllers.mapNotNull { (key, controller) ->
                callbacks[key]?.let { controller to it }
            }
            controllers.clear()
            callbacks.clear()
            lastKey = null
            current = null
        }
        toUnregister.forEach { (controller, cb) ->
            runCatching { controller.unregisterCallback(cb) }
        }
    }

    /** 重新扫描活跃会话，增新注册/注销回调。 */
    fun refresh() {
        val msm = manager ?: return
        val ctx = app ?: return
        // getActiveSessions 与下面 new MediaController / registerCallback
        // 都是跨进程 Binder，必须在锁外做。
        val tokens = try {
            msm.getActiveSessions(ComponentName(ctx, MediaNotificationListener::class.java))
        } catch (_: Throwable) {
            return
        }

        val alive = HashSet<String>()
        // getActiveSessions 返回的是 MediaController（不是 Token），
        // 构造新的 controller 需要从中取 sessionToken
        for (ctrl in tokens) {
            val key = keyOf(ctrl)
            alive += key
            // 已存在则跳过：判断与注册分开两段锁，避免把 IPC 包进临界区
            val already = synchronized(lock) { controllers.containsKey(key) }
            if (already) continue
            val controller = MediaController(ctx, ctrl.sessionToken)
            val callback = object : MediaController.Callback() {
                // 走 pokeByCallback 而不是 update：留一个明确的「事件驱动入口」，
                // 语义上也更清楚 —— 这里处理的是真事件，ticker 才叫轮询。
                override fun onMetadataChanged(metadata: MediaMetadata?) = PlaybackMonitor.pokeByCallback()
                override fun onPlaybackStateChanged(state: PlaybackState?) = PlaybackMonitor.pokeByCallback()
            }
            // v1.12.1：派发线程由主线程改为 IO，理由见 [callbackHandler]。
            if (runCatching { controller.registerCallback(callback, callbackHandler) }.isFailure) continue
            val registered = synchronized(lock) {
                // 双重检查：可能已被并发的另一次 refresh 抢先注册
                if (controllers.containsKey(key)) {
                    false
                } else {
                    controllers[key] = controller
                    callbacks[key] = callback
                    true
                }
            }
            // 没抢到就把自己这个注销掉，避免回调泄漏
            if (!registered) runCatching { controller.unregisterCallback(callback) }
        }

        val removed = mutableListOf<Pair<MediaController, MediaController.Callback>>()
        synchronized(lock) {
            controllers.keys.filter { it !in alive }.forEach { key ->
                val controller = controllers.remove(key)
                callbacks.remove(key)?.let { cb -> controller?.let { removed += it to cb } }
                // v1.12.1：清引用但**不 recycle**，理由见 stop() 里的说明。
                //
                // 旧注释说「controller 已注销，可以安全 recycle」——
                // 注销只保证**不会有新的读取**，不保证**UI 侧没有旧引用**。
                // Compose 重组慢一帧就会用已回收的位图去绘制，直接崩。
                metaCache.remove(key)
            }
        }
        removed.forEach { (controller, cb) -> runCatching { controller.unregisterCallback(cb) } }
        PlaybackMonitor.update()
    }

    /**
     * 会话增删时的轻量刷新：**只做注册/注销，不做全量扫描**。
     *
     * ## v1.8.2 的性能修正
     * 旧版 [refresh] 既要diff controller 列表，又在结尾调 `PlaybackMonitor.update()`
     * 做一次完整的 `best()`（遍历所有会话 + 读 metadata）。
     * 但 `OnActiveSessionsChangedListener` 在某些 ROM 上会被频繁回调
     * （播放器每次广播媒体通知都算一次），等于在主线程上反复做全量扫描。
     *
     * 现在拆开：会话增删只做O(n) 的 controller 增删（很轻），
     * 把状态更新交给 [PlaybackMonitor] 自己的 ticker（已在 IO 线程、按精度档节流），
     * 周期是 50~200ms 而不是"每次系统回调"。两者合起来等于去掉了高频重复扫描。
     */
    fun refreshSessionsOnly() {
        val msm = manager ?: return
        val ctx = app ?: return
        val tokens = try {
            msm.getActiveSessions(ComponentName(ctx, MediaNotificationListener::class.java))
        } catch (_: Throwable) {
            return
        }

        val alive = HashSet<String>()
        for (ctrl in tokens) {
            val key = keyOf(ctrl)
            alive += key
            val already = synchronized(lock) { controllers.containsKey(key) }
            if (already) continue
            val controller = MediaController(ctx, ctrl.sessionToken)
            val callback = object : MediaController.Callback() {
                // 走 pokeByCallback 而不是 update：留一个明确的「事件驱动入口」，
                // 语义上也更清楚 —— 这里处理的是真事件，ticker 才叫轮询。
                override fun onMetadataChanged(metadata: MediaMetadata?) = PlaybackMonitor.pokeByCallback()
                override fun onPlaybackStateChanged(state: PlaybackState?) = PlaybackMonitor.pokeByCallback()
            }
            // v1.12.1：派发线程改为 IO，理由见 [callbackHandler]。
            if (runCatching { controller.registerCallback(callback, callbackHandler) }.isFailure) continue
            val registered = synchronized(lock) {
                if (controllers.containsKey(key)) {
                    false
                } else {
                    controllers[key] = controller
                    callbacks[key] = callback
                    true
                }
            }
            if (!registered) runCatching { controller.unregisterCallback(callback) }
        }

        val removed = mutableListOf<Pair<MediaController, MediaController.Callback>>()
        synchronized(lock) {
            controllers.keys.filter { it !in alive }.forEach { key ->
                val controller = controllers.remove(key)
                callbacks.remove(key)?.let { cb -> controller?.let { removed += it to cb } }
                // v1.12.1：不 recycle，理由见 stop() 里的说明。
                metaCache.remove(key)
            }
        }
        removed.forEach { (controller, cb) -> runCatching { controller.unregisterCallback(cb) } }
    }

    /**
     * 读全部会话的快照。
     *
     * v1.12.1：先在锁内**复制一份 controller 列表**再出锁解析。
     * 因为 [toSnapshot] 里有跨进程 Binder 调用，若持锁解析，
     * 一次 IPC 的等待时间就会把锁占住，反过来卡住主线程上的 [refresh] ——
     * 那正是本次优化要消除的效果。
     */
    fun snapshots(): List<SessionSnapshot> {
        val list = synchronized(lock) { controllers.values.toList() }
        return list.mapNotNull { it.toSnapshot() }
    }

    /**
     * v1.12.1：该包是否已有活跃的 MediaSession 会话。
     *
     * **零 Binder** —— 只看 [controllers] 的 key。
     * key 的构造见 [keyOf]：`"包名#token的identityHashCode"`，
     * 所以取 `substringBefore('#')` 就是包名。
     *
     * ## 用途
     *
     * 通知兜底判定以前用 `snapshots().any { it.pkg == pkg }`，
     * 而 [snapshots] 会遍历所有 controller 各读 2 次跨进程 metadata/playbackState。
     * 通知回调默认在主线程，而媒体 App 更新进度时通常**每秒重发一次通知** ——
     * 于是每秒主线程一次全量 Binder 读取。
     *
     * 这个判定只需要「这个 App 有没有会话」，读 key 足够，**不需要碰位图和 Bundle**。
     */
fun hasSessionFor(pkg: String): Boolean =
synchronized(lock) { controllers.keys.any { it.substringBefore('#') == pkg } }

    /**
     * 选出「当前正在播的那个」，选择必须粘滞，否则会来回跳导致 UI 闪烁。
     *
     * 优先级：
     *  1. 上次选中的会话仍在播放 → 保持不变（多会话同时播放时防抖）
     *  2. 其他正在播放的会话 → 切过去
     *  3. 上次选中的会话还活着但暂时读不到播放态 → **保持不变**
     *     （部分播放器（Spotify 等）更新 PlaybackState 的瞬间可能读到
     *      null/短暂非 playing，若无此兜底，选中会每拍跳到列表第一个会话再跳回来，
     *      表现为封面/主题色/进度条整页一闪一闪）
     *  4. 列表第一个
     */
    fun best(): SessionSnapshot? {
        val ctx = app ?: return null
        val blocked = SettingsStore.current().blockedPackages
        val list = snapshots().filter { it.pkg != ctx.packageName && it.pkg !in blocked }
        val playing = list.filter { it.state == PlaybackState.STATE_PLAYING }
        // v1.12.1：lastKey / current 也在锁内读写 ——
        // best() 会被 ticker（IO）与通知回调（旧主线程）同时调用。
        val last = synchronized(lock) { list.firstOrNull { it.key == lastKey } }
        val chosen = when {
            playing.isEmpty() -> last ?: list.firstOrNull()
            else -> playing.firstOrNull { it.key == lastKey } ?: playing.first()
        }
        synchronized(lock) {
            lastKey = chosen?.key
            current = chosen
        }
        return chosen
    }

    // ---------------- 传输控制 ----------------
    // 全部走 MediaSession 标准 TransportControls，
    // Spotify 与其他本地播放器行为一致；不支持的动作在 capabilities 里已标记为 false。

    fun seek(positionMs: Long) {
        val (controls, caps) = currentControls() ?: return
        if (!caps.canSeek) return
        runCatching { controls?.seekTo(positionMs.coerceAtLeast(0)) }
    }

    fun skipToPrevious() {
        val (controls, caps) = currentControls() ?: return
        if (!caps.canSkipPrev) return
        runCatching { controls?.skipToPrevious() }
    }

    fun skipToNext() {
        val (controls, caps) = currentControls() ?: return
        if (!caps.canSkipNext) return
        runCatching { controls?.skipToNext() }
    }

    fun playOrPause(playing: Boolean) {
        val (controls, caps) = currentControls() ?: return
        if (!caps.canPlayPause) return
        runCatching {
            val c = controls ?: return
            if (playing) c.pause() else c.play()
        }
    }

    /**
     * v1.12.1：取出「当前会话」的传输控制与能力。
     *
     * 必须在**同一个临界区**里同时读 `current` 与 `controllers`，
     * 否则两次读之间会话可能已被 [refresh] 换掉，
     * 就会出现「快照是 A、控制器是 B」的错配（表现为按暂停没反应）。
     *
     * 这些调用来自主线程的按钮，所以临界区刻意做到最小：
     * 只读内存。`transportControls` 这个 getter 本身也是 Binder，
     * 但它只是取一个代理对象的引用、耗时极短，
     * 真正的 `play()` / `seekTo()` 全部在锁外。
     */
    private fun currentControls(): Pair<android.media.session.MediaController.TransportControls?, TransportCapabilities>? {
        var controls: android.media.session.MediaController.TransportControls? = null
        var caps: TransportCapabilities? = null
        synchronized(lock) {
            val snapshot = current ?: return null
            val controller = controllers[snapshot.key] ?: return null
            caps = snapshot.capabilities
            controls = runCatching { controller.transportControls }.getOrNull()
        }
        return Pair(controls, caps ?: TransportCapabilities())
    }

    /**
     * 读取会话快照。
     *
     * ## v1.8.2 性能修正：把「不变的部分」缓存起来
     *
     * 旧版每一轮（默认 100ms 一次）都做这些事：
     *  - `metadata` —— **跨进程 Binder 调用**，取回整个 MediaMetadata Bundle
     *  - 从 Bundle 里 `getString` 取歌名/歌手/专辑
     *  - `getBitmap` 反序列化封面位图
     *
     * 但这些字段**只在换歌时变**，位置和播放状态才是每 100ms 变的。
     * 也就是说 99% 的轮次都在重复 IPC + 重复解析同一份不变的 Bundle。
     *
     * ### v1.13.10：缓存判据从「实例」改成「内容指纹」
     *
     * v1.8.2 的注释写的是「按 MediaMetadata 实例做 key 缓存（换歌会产生新实例，
     * key 自然失效）」——**这个推断是错的**。AOSP 的 `getMetadata()`
     * 每次调用都反序列化出**全新实例**，不是「换歌才新」而是「每次读都新」，
     * 于是这个缓存命中率恒为 0，只写不读。
     *
     * 现在按**最终生效的那组字段**（title/artist/album/duration/artUri）
     * 拼指纹比对，这几个字段读 Bundle 的代价与一次字符串拼接同量级，
     * 不像 `getBitmap` 那样要反序列化整张封面。指纹相同则直接复用，
     * 封面位图**完全不碰**。
     *
     * 顺带把 `getBitmap` 缩到 320px 再交给上层，
     * 避免 4MB 的位图在每次 metadata 变化时都被反序列化出来。
     */
    private fun MediaController.toSnapshot(): SessionSnapshot? {
        val key = keyOf(this)
        // metadata 是跨进程 Binder 调用，必须在锁外取
        val metadata = metadata ?: return null

        // ---- 先取「轻量字段」：这几个都只是读 Bundle，不触发位图反序列化 ----
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isNullOrBlank()) return null
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)

        // ---- 缓存命中判定：比内容指纹，不再比实例引用 ----
        // v1.13.10：原判据 `cached.raw === metadata` 命中率恒为 0（原因见
        // metaCache 的 KDoc）。改成指纹后，下面这个 `getBitmap` 只在
        // 「真的换了歌」时才会执行。
        // v1.16.0：修正上面这句——还有一个「没换歌但封面后到」的情形
        // （播放器切歌分两步发 metadata），此时也要执行。判据见下方 hasArtKey。
        //
        // 锁的边界不变：**只锁内存读写，不把 Binder 包进去**。
        // 读缓存 → 比指纹 → 必要时回写，三步都在锁内完成，
        // 避免「读出 cached 之后、比对之前被别的线程 put 掉」。
        // 分隔符用 '\u0000'（Kotlin/Java 字符串里唯一不可能出现在歌名里的字符），
        // 不能用空格：字段本身可能含空格，那时 "a b"+"c" 与 "a"+"b c" 会撞成同一个指纹。
        val SEP = '\u0000'
        val fingerprint = buildString {
            append(title).append(SEP)
            append(artist).append(SEP)
            append(album).append(SEP)
            append(duration).append(SEP)
            append(artUri)
        }
        // ---- 封面键的存在性（v1.16.0 新增）----
        //
        // 指纹只由文本字段拼成，**不含封面**。而播放器切歌时普遍是分两步
        // setMetadata：先发歌名/歌手/专辑（封面键还没有），隔一拍再补上封面。
        // 这两次的五个文本字段**一字不差**，于是指纹相同、缓存命中，
        // 那份「无封面」的旧结果就被永久钉死 —— 表现为切歌后封面一直空白，
        // 直到切下一首。首曲正常是因为冷启动时播放器一次性把封面带上了。
        //
        // `containsKey` 是 Bundle 上的 O(1) 查询，**不触发位图反序列化**，
        // 代价与读一个字符串相当，不需要为了它去调 getBitmap。
        val hasArtKey = metadata.containsKey(MediaMetadata.METADATA_KEY_ALBUM_ART) ||
            metadata.containsKey(MediaMetadata.METADATA_KEY_ART)

        val cached = synchronized(lock) { metaCache[key] }
        val info: MetaInfo
        // 指纹相同 ⇒ 解析结果必然相同，**唯一的例外**：
        // 缓存里是「无封面」而当前 metadata 带着封面键 ⇒ 当前这份更新鲜，
        // 必须重解析，否则上面那个切歌空白就复现了。
        //
        // 例外只收窄到「缓存无封面」这一种情况，命中率基本不受影响；
        // 若改成把 hasArtKey 拼进指纹，播放器补封面那一刻指纹会变，
        // 「同一首歌封面 URI 换了」也会跟着触发重解析，得不偿失。
        val reusable = cached != null &&
            cached.fingerprint == fingerprint &&
            !(cached.info.albumArt == null && hasArtKey)
        if (reusable) {
            info = cached.info
        } else {
            // 封面：优先直接给 Bitmap 的字段，其次退化为 URI（交给 Coil 加载）
            val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            info = MetaInfo(
                track = TrackInfo(title, artist.orEmpty(), album.orEmpty(), duration, packageName),
                // downsample 在锁外做。它要把 1000×1000 缩到 320px，
                // 是本函数里最耗 CPU 的一段，绝不能占着锁。
                albumArt = art?.let { AlbumArt.downsample(it) },
                albumArtUri = artUri,
            )
            synchronized(lock) { metaCache[key] = MetaCacheEntry(fingerprint, info) }
        }

        // playbackState 同样是 Binder，留在锁外
        val state = playbackState
        val stateCode = state?.state ?: PlaybackState.STATE_NONE
        val position = estimatePosition(stateCode, state?.position ?: 0L, state?.lastPositionUpdateTime ?: 0L)

        return SessionSnapshot(
            key = key,
            pkg = packageName,
            track = info.track,
            state = stateCode,
            positionMs = position,
            albumArt = info.albumArt,
            albumArtUri = info.albumArtUri,
            capabilities = TransportCapabilities(
                canSeek = hasAction(state, PlaybackState.ACTION_SEEK_TO),
                canSkipPrev = hasAction(state, PlaybackState.ACTION_SKIP_TO_PREVIOUS),
                canSkipNext = hasAction(state, PlaybackState.ACTION_SKIP_TO_NEXT),
                canPlayPause = hasAction(state, PlaybackState.ACTION_PLAY_PAUSE),
            ),
        )
    }

    /**
     * 播放位置推算：播放器只在状态变化/定期广播时上报 position，
     * 播放中需要用「上报时刻到现在的墙钟时间」补上差值。
     */
    private fun estimatePosition(stateCode: Int, reported: Long, updateTime: Long): Long {
        if (reported < 0) return 0L
        if (stateCode != PlaybackState.STATE_PLAYING || updateTime <= 0L) return reported
        val elapsed = (SystemClock.elapsedRealtime() - updateTime).coerceAtLeast(0L)
        return reported + elapsed
    }

    private fun hasAction(state: PlaybackState?, action: Long): Boolean =
        state?.actions?.let { it and action != 0L } ?: false

    /** 会话 key：包名 + token 实例标识（Token 本身没有可比较的公开 id）。 */
    private fun keyOf(controller: MediaController): String =
        "${controller.packageName}#${System.identityHashCode(controller.sessionToken)}"
}

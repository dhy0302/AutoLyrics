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

import android.content.Context
import org.eu.dinghongyu.autolyrics.data.PrecisionMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 全部用户偏好。改任何一项都会立即落盘并通知所有观察者。 */
/**
 * v1.6.0 已下线的歌词源。读回旧配置时按此过滤，
 * 避免歌词源页出现不存在的幽灵条目。
 */
private val REMOVED_SOURCES = setOf("qq")

/**
 * v1.8.1：默认顺序从「网易云 → 酷狗 → Lrclib」改为「酷狗 → 网易云 → Lrclib」。
 *
 * 老用户磁盘里已经存着旧的 `order`，光改 [Settings.DEFAULT_ORDER] 对他们无效。
 * 用一个一次性标记强制重排一次：如果用户当前的顺序正好等于**旧默认值**
 * （说明他从没手动调过），就跟随新默认；否则保留用户自己的排序。
 */
private const val KEY_ORDER_MIGRATED_V181 = "orderMigratedV181"

/**
 * v1.11.5：默认主题从夜间改为白天。
 *
 * 同样的问题——光改 [Settings.darkMode] 的默认值，对**已装过旧版**的老用户
 * 是无效的：他们磁盘里已经写着 `"darkMode": true`，`optBoolean` 会照读不误。
 * 结果就是"我明明更新了怎么还是夜间"。
 *
 * 但不能对老用户无脑改回白天：他把界面**主动**切成夜间，说明他就是喜欢深色，
 * 替他改回去等于无视用户自己的选择。
 *
 * 所以需要一个「用户是否主动点过那个太阳/月亮按钮」的标记：
 *  - 标记为true  → 尊重用户选择，原样保留
 *  - 标记为 false → 他从来没表达过偏好，按新默认走（白天）
 *
 * 与 KEY_ORDER_MIGRATED_V181 同构，都是「一次性判断 + 标记已迁移」。
 */
private const val KEY_DARK_TOUCHED = "darkModeTouchedV115"

data class Settings(
    /** 桌面悬浮窗歌词 */
    val overlayEnabled: Boolean = true,
    /** 通知栏歌词 */
    val notificationEnabled: Boolean = true,
    // v1.12.6：原`inAppEnabled`（App 内歌词页开关）已删除。
    // 它从未被任何逻辑读取，只在设置页与调试页摘要里显示，
    // 是个不起作用的装饰开关；App 内歌词页是主界面，恒定开启。
    // 持久化键「inapp」也不再读写。

    /** 歌词源的回退顺序（元素为 LyricSource.id） */
    val sourceOrder: List<String> = DEFAULT_ORDER,
    /** 实际启用的源；未启用的源会被跳过 */
    val enabledSources: Set<String> = DEFAULT_ORDER.toSet(),

    /** 悬浮窗字号 */
    val fontSizeSp: Float = 18f,
    /** 悬浮窗相对顶部的纵向位置（px） */
    val overlayY: Int = 120,
    /** 悬浮窗锁定后不可拖动、点击穿透 */
    val overlayLocked: Boolean = false,
    /** 悬浮窗透明背景（不渲染半透明底框，纯文字叠加在任意画面上） */
    val overlayTransparentBg: Boolean = false,
    /** 悬浮窗字体颜色（ARGB 整型，默认白色），调色盘可调 */
    val overlayTextColor: Int = 0xFFFFFFFF.toInt(),
    /**
     * 悬浮窗歌词显示模式：
     *  - "current"：仅显示当前句；
     *  - "current_next"：显示当前句 + 下一句（不再显示上一句）。
     */
    val overlayLineMode: String = "current_next",

    /**
     * 悬浮窗歌词是否逐字输出（卡拉 OK 式逐字染色）。
     * 关掉则拿到逐字歌词也按整行高亮。与歌词页的 [wordByWordEnabled] 相互独立。
     */
    val overlayWordByWord: Boolean = true,

    /**
     * 各歌词源的登录态 Cookie（sourceId → Cookie 字符串）。
     *
     * v1.8.0 已移除全部登录功能（网易云扫码），这个字段**不再有任何写入方**，
     * 仅为兼容老配置保留位置。读回旧配置时会主动丢弃其中残留的 Cookie
     * （见 [SettingsStore.load]），避免 MUSIC_U 长期滞留在磁盘上。
     */
    val sourceCookies: Map<String, String> = emptyMap(),

    /**
     * 手动锁定的歌词源（trackKey → sourceId）。
     * 设置后该曲目只从该源取词，忽略默认的自动回退顺序。
     * 在「歌词源」页搜索后用「用此源」写入，可随时清除。
     */
    val sourceOverride: Map<String, String> = emptyMap(),

    /**
     * 手动锁定的具体候选（trackKey → candidateId）。
     * 与 [sourceOverride] 配合：指定后直接取该候选，不再按得分自动选最佳。
     */
    val sourceOverrideCandidate: Map<String, String> = emptyMap(),

    /**
     * v1.15.0：**用户自填**的歌手别名（播报名 → 该歌手在各平台的其他叫法）。
     *
     *## 为什么需要它
     *
     * 同一个歌手在不同平台的元数据里名字不同，于是用播报名去搜会扑空：
     * ```
     * 周兴哲   网易云=周兴哲    酷狗=Eric周兴哲    Lrclib=周兴哲
     * 福禄寿   网易云=福禄寿    酷狗=福禄寿FloruitShow
     * ```
     * 命中与否取决于「哪一家的写法恰好和播报方一致」，纯属运气。
     *
     * ## 它只用于「搜候选」，不用于「认定同一人」
     *
     * 这是本机制最重要的纪律：`[LyricRepository.score]` 永远拿
     * **播报原名**与候选的歌手名比对，别名不会参与打分。
     *
     * 原因是别名可能填错。若让别名也参与「认定」，
     * 一次错误映射就会让**别的歌手的候选**被判为同一个人——
     * 拿错歌词远比取不到歌词糟糕。
     * 所以别名只是「多搜几遍」，最终认定仍走原有的相似度逻辑。
     *
     * ## 一个歌手可以有多个别名
     *
     * 值是列表而非单个字符串：三个平台各叫一个名字时全都能填。
     *
     * ## 与内置表的关系
     *
     * 用户表**优先级更高**：同一个播报名两边都有时，
     * 用用户填的（见 [ArtistAliases]）。内置表只是保守起步的默认值，
     * 填错了用户能覆盖。
     */
    val artistAliases: Map<String, List<String>> = emptyMap(),

    /** **歌词页**是否显示译文 */
    val showTranslation: Boolean = true,
    /** **桌面悬浮窗**是否显示译文；与 [showTranslation] 相互独立 */
    val overlayTranslation: Boolean = false,
    /** 全局歌词偏移（毫秒），正=歌词延后出现 */
    val globalOffsetMs: Long = 0L,

    /** 进度刷新精度档位 */
    val precisionMode: PrecisionMode = PrecisionMode.STANDARD,
    /** 拿到逐字歌词时是否做卡拉 OK 式逐字染色（**歌词页**）；关掉则一律整行高亮 */
    val wordByWordEnabled: Boolean = true,
    /** 歌词页是否启用流动背景（关闭则为静态磨砂封面） */
    val fluidBackground: Boolean = true,
    /**
     * v1.8.2：暂停播放时冻结背景动画。
     *
     * 背景是全屏 GPU 运算，一直开着即使暂停也不停。多数时候暂停意味着
     * 「不看了」，冻住它能省下可观的电量。默认开启——这是省电的保守选择。
     */
    val freezeBackdropOnPause: Boolean = true,
    /** **歌词页**高亮色（已唱歌词/ 逐字擦亮的亮色）；未唱色由它按比例派生 */
    val inAppTextColor: Int = 0xFFFFFFFF.toInt(),
    /** **歌词页**歌词字号（sp） */
    val inAppFontSizeSp: Float = 21f,
    /** 歌词页精简模式：隐藏封面/歌名/歌手/进度条，只留歌词 */
    val inAppMinimal: Boolean = false,

    /**
     * v1.8.3：**处于歌词页时自动隐藏桌面悬浮窗**（普通模式与精简模式都算）。
     *
     * 理由很直接：歌词页本身就是全屏展示同一首歌词，
     * 此时桌面上再飘一个悬浮窗是同一份内容出现两次 —— 挡别的 App、
     * 截屏时也入镜。默认开启。
     *
     * 这是**临时隐藏**，不会改动 [overlayEnabled] 这个持久开关：
     * 离开歌词页（退到后台、跳去别的设置页）后悬浮窗会按原开关状态回来。
     * 早先版本如果直接去改 `overlayEnabled`，用户切走 App 后会发现
     * 悬浮窗"莫名其妙没了"，还得自己去设置里重新打开。
     */
    val hideOverlayInLyricsPage: Boolean = true,

    /** 暂停时自动隐藏悬浮窗 */
    val autoHideOnPause: Boolean = true,
    /** 黑名单模式：名单内的应用不监听 */
    val blockedPackages: Set<String> = emptySet(),

    /**
     * v1.8.1：夜间模式开关。`true` = 夜间（墨黑底+ 象牙白字），`false` = 白天（米白底 + 深墨字）。
     *
     * v1.11.5 起**默认白天**（此前默认夜间）。理由：歌词页之外的界面（设置、
     * 关于、歌词源）都是浅底更耐看的信息型页面，夜间模式只是把同一套布局
     * 反相；真正需要深色的是歌词页，而它的背景本来就由封面流体渐变决定，
     * 跟这个开关无关。所以默认白天、想要暗色再手动切。
     *
     * 只影响 App 内的 Compose 界面（歌词页 / 设置页 / 歌词源页 / 悬浮窗外的所有页面）。
     * 桌面悬浮窗歌词是压在别的 App 之上的，永远用深底浅字（见 [overlayTextColor]），
     * 不跟这个开关走 —— 否则白底白字在别的 App 上会直接消失。
     */
    val darkMode: Boolean = false,
) {
    companion object {
        /**
         * 源优先级：酷狗 → 网易云 → Lrclib。
         *
         * v1.8.1 按用户要求把酷狗提到首位：
         *  - 酷狗 KRC 是**真正的逐字**格式（每字带起止时间），逐字歌词体验最好；
         *  - 匿名取词稳定，不限量词受授权曲目的影响。
         * 网易云降为第二（公开接口的 `lrc.lyric` 常常只是整行，
         *  真正的逐字 YRC 需要登录，而v1.8.0 已移除登录），Lrclib 作为末位兜底。
         *
         * v1.6.0 曾移除 QQ 音乐：官方网关对匿名请求有 IP 级限流，取词稳定性不足。
         */
        val DEFAULT_ORDER = listOf("kugou", "netease", "lrclib")
    }
}

object SettingsStore {

    private const val PREF = "autolyrics"
    private const val KEY = "settings"

    /**
     * v1.12.1：落盘防抖时长（毫秒）。
     *
     * 取 300ms 是权衡：短到几乎察觉不到延迟（用户松手后很快就写盘了），
     * 长到足以把「一次拖动」合并成一次写入。
     */
    private const val SAVE_DEBOUNCE_MS = 300L

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private lateinit var prefs: android.content.SharedPreferences

    /**
     * v1.12.1：待执行的落盘任务。
     *
     * 每次 [update] 都取消它并重新挂一个延迟任务，
     * 于是连续调用只有**最后一次**会真正写盘。
     */
    private var saveJob: kotlinx.coroutines.Job? = null

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        _settings.value = load()
    }

    fun update(block: (Settings) -> Settings) {
        // 内存值立即更新 —— 这是关键：界面必须马上跟着变，
        // 用户拖校准滑块时要能看到歌词实时前后移动。
        // 防抖只针对「写磁盘」，绝不针对「改内存」。
        _settings.value = block(_settings.value)
        scheduleSave()
    }

    /**
     * v1.13.5：改内存并**立刻落盘**，跳过防抖。
     *
     * ## 什么时候该用
     *
     * 防抖是为「连续高频改动」设计的（拖校准滑块一次触发几十次）。
     * 但**低频且用户明确在意**的改动不该走那条路：
     * 调色板选色一年未必改一次，攒那 300ms 毫无收益，
     * 反而开出一个真实的丢失窗口 —— 选完色 300ms 内进程被系统回收，
     * 重开 App 颜色就退回了。App.onTerminate 在真机上根本不会被调用，
     * 平时只靠「离开所有 Activity 时 flush」兜底。
     *
     * 颜色这类改动就属于后者：宁可多写一次磁盘，也不能丢。
     */
    fun updateNow(block: (Settings) -> Settings) {
        _settings.value = block(_settings.value)
        flush()
    }

    /**
     * v1.12.1：把落盘推迟到「用户停下来」之后。
     *
     * ## 为什么需要
     * 旧实现是每次 [update] 立刻 [save]，而 [save] 要把30 个字段
     * 全量序列化成 JSON 再写 SharedPreferences。
     *
     * 最典型的场景是设置页的「全局偏移」滑块（`steps = 59`）：
     * 手指从最左拖到最右会触发**约 60 次** [update]，
     * 也就是 60 次全量序列化 + 60 次磁盘写。
     *
     * 更糟的是 [org.eu.dinghongyu.autolyrics.lyric.LyricEngine] 里
     * 位置流combine 了 `SettingsStore.settings`，
     * 于是每写一次盘就顺带让**整个歌词页重算一次**——
     * 拖动滑块时的卡顿与掉帧就是这么来的。
     *
     * ## 为什么不能简单改成「只在停止时才更新内存」
     * 那会让滑块失去实时反馈，拖起来完全没有反馈，
     * 用户根本没法「边拖边看」校准效果——而实时看效果正是这个滑块的用途。
     */
    private fun scheduleSave() {
        if (!::prefs.isInitialized) return
        saveJob?.cancel()
        saveJob = AppScope.io.launch {
            delay(SAVE_DEBOUNCE_MS)
            save()
        }
    }

    /**
     * 立刻落盘，忽略防抖。
     *
     * 给「进程即将退出」这类没有时间等防抖窗口的场景兜底，
     * 避免用户刚改完设置就划掉 App 导致丢失。
     */
    fun flush() {
        saveJob?.cancel()
        saveJob = null
        if (::prefs.isInitialized) save()
    }

    /**
     * 切换明暗主题。
     *
     * 走这个入口而不是在调用方直接 `update { it.copy(darkMode = ...) }`，
     * 是为了顺手把 [KEY_DARK_TOUCHED] 置true —— 这是「用户主动表达过偏好」
     * 的唯一来源。以后再改默认主题时，就不会把他 resetting 掉。
     */
    fun toggleDarkMode() {
        prefs.edit().putBoolean(KEY_DARK_TOUCHED, true).apply()
        update { s -> s.copy(darkMode = !s.darkMode) }
    }

    /**
     * 读出明暗偏好，处理「默认值变了但老配置还在」的情况。
     *
     * 判据只有一条：[KEY_DARK_TOUCHED]。
     *  - 用户**主动切过**（标记为 true）→ 尊重他的选择，原样返回磁盘上的值；
     *  - 从未切过 → 他没有表达过偏好，一律跟随新默认（白天 = false）。
     *
     * 「从未切过」也涵盖两种磁盘状态：老配置里根本没有 `darkMode` 字段
     * （v1.8.1 之前），或者有但值是旧默认的 true。两者都归为「无偏好」。
     *
     * 这个函数是幂等的：返回 false 之后即便磁盘上仍是 true，下次读还是 false，
     * 所以不需要额外的「已迁移」标记（不像 [KEY_ORDER_MIGRATED_V181] 那样
     * 需要写回，因为这里的结果只取决于 touched 这一个布尔量）。
     */
    private fun resolveDarkMode(jo: JSONObject): Boolean {
        val touched = prefs.getBoolean(KEY_DARK_TOUCHED, false)
        return if (touched) jo.optBoolean("darkMode", false) else false
    }

    fun current(): Settings = _settings.value

    private fun load(): Settings {
        val raw = prefs.getString(KEY, null) ?: return Settings()
        return try {
            val jo = JSONObject(raw)
            val fallback = Settings()
            // v1.6.0 移除了 QQ 音乐源。老配置里仍存着 "qq"，
            // 必须过滤掉，否则歌词源页会出现一个已不存在的幽灵条目。
            val storedOrder = jo.optJSONArray("order")?.toStringList()
                ?.filterNot { it in REMOVED_SOURCES }
                ?.takeIf { it.isNotEmpty() }
            // v1.8.1：旧默认顺序（网易云打头）。用户若从未手动调过顺序，
            // 就把它重排为新的默认；自己动过的保留。
            val oldDefault = listOf("netease", "kugou", "lrclib")
            val order = when {
                storedOrder == null -> fallback.sourceOrder
                storedOrder == oldDefault && !prefs.getBoolean(KEY_ORDER_MIGRATED_V181, false) ->
                    fallback.sourceOrder
                else -> storedOrder
            }
            val migrated = prefs.getBoolean(KEY_ORDER_MIGRATED_V181, false) ||
                (storedOrder != null && storedOrder == oldDefault)
            if (!migrated) {
                prefs.edit().putBoolean(KEY_ORDER_MIGRATED_V181, true).apply()
            }
            val enabled = jo.optJSONArray("enabled")?.toStringList()
                ?.filterNot { it in REMOVED_SOURCES }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() } ?: fallback.enabledSources
            val blocked = jo.optJSONArray("blocked")?.toStringList()?.toSet() ?: emptySet()
            val mode = PrecisionMode.entries.getOrNull(jo.optInt("precision", 1))
                ?: fallback.precisionMode

            Settings(
                overlayEnabled = jo.optBoolean("overlay", true),
                notificationEnabled = jo.optBoolean("notify", true),
                sourceOrder = order,
                enabledSources = enabled,
                fontSizeSp = jo.optDouble("font", 18.0).toFloat(),
                overlayY = jo.optInt("overlayY", 120),
                overlayLocked = jo.optBoolean("locked", false),
                overlayTransparentBg = jo.optBoolean("transparentBg", false),
                overlayTextColor = jo.optInt("textColor", 0xFFFFFFFF.toInt()),
                overlayLineMode = jo.optString("lineMode").takeIf { it == "current" || it == "current_next" }
                    ?: fallback.overlayLineMode,
                overlayWordByWord = jo.optBoolean("overlayWordByWord", true),
                // v1.8.0：不再读取老的 "cookies" 键。登录功能已整体删除，
                // 残留的 MUSIC_U 没有消费方，且随下一次 save() 就会被抹掉。
                sourceCookies = emptyMap(),
                sourceOverride = jo.optJSONObject("override")?.toMap().orEmpty(),
                sourceOverrideCandidate = jo.optJSONObject("overrideCand")?.toMap().orEmpty(),
                artistAliases = jo.optJSONObject("artistAliases")?.toStringListMap().orEmpty(),
                showTranslation = jo.optBoolean("trans", true),
                overlayTranslation = jo.optBoolean("overlayTrans", false),
                globalOffsetMs = jo.optLong("offset", 0L),
                precisionMode = mode,
                wordByWordEnabled = jo.optBoolean("wordByWord", true),
                fluidBackground = jo.optBoolean("fluid", true),
                freezeBackdropOnPause = jo.optBoolean("freezeBackdrop", true),
                inAppTextColor = jo.optInt("inAppTextColor", 0xFFFFFFFF.toInt()),
                inAppFontSizeSp = jo.optDouble("inAppFont", 21.0).toFloat().coerceIn(12f, 40f),
                inAppMinimal = jo.optBoolean("inAppMinimal", false),
                hideOverlayInLyricsPage = jo.optBoolean("hideOverlayInLyrics", true),
                autoHideOnPause = jo.optBoolean("hidePause", true),
                blockedPackages = blocked,
                darkMode = resolveDarkMode(jo),
                // v1.10.x 的 "liquidGlass" 键会被静默忽略：
                // 液态玻璃已整体移除，但老配置里还留着这个键，
                // 读出来丢掉即可（optInt 不用写，缺字段走默认值 0）。
            )
        } catch (_: Throwable) {
            // 配置损坏时回退默认值，避免 App 打不开
            Settings()
        }
    }

    private fun save() {
        val s = _settings.value
        val jo = JSONObject()
        jo.put("overlay", s.overlayEnabled)
        jo.put("notify", s.notificationEnabled)
        jo.put("order", JSONArray().apply { s.sourceOrder.forEach { put(it) } })
        jo.put("enabled", JSONArray().apply { s.enabledSources.forEach { put(it) } })
        jo.put("blocked", JSONArray().apply { s.blockedPackages.forEach { put(it) } })
        jo.put("font", s.fontSizeSp.toDouble())
        jo.put("overlayY", s.overlayY)
        jo.put("locked", s.overlayLocked)
        jo.put("transparentBg", s.overlayTransparentBg)
        jo.put("textColor", s.overlayTextColor)
        jo.put("lineMode", s.overlayLineMode)
        jo.put("overlayWordByWord", s.overlayWordByWord)
        // v1.8.0：不再写入 cookies 键——登录功能已删除，
        // 这次保存会把老配置里残留的登录 Cookie 一并覆盖掉。
        val overrideJo = JSONObject()
        s.sourceOverride.forEach { (k, v) -> overrideJo.put(k, v) }
        jo.put("override", overrideJo)
        val overrideCandJo = JSONObject()
        s.sourceOverrideCandidate.forEach { (k, v) -> overrideCandJo.put(k, v) }
        jo.put("overrideCand", overrideCandJo)
        // v1.15.0：歌手别名。值是字符串数组，故用 JSONArray 套在 JSONObject 里。
        val aliasJo = JSONObject()
        s.artistAliases.forEach { (k, v) ->
            if (v.isNotEmpty()) aliasJo.put(k, JSONArray().apply { v.forEach { put(it) } })
        }
        jo.put("artistAliases", aliasJo)
        jo.put("trans", s.showTranslation)
        jo.put("overlayTrans", s.overlayTranslation)
        jo.put("offset", s.globalOffsetMs)
        jo.put("precision", s.precisionMode.ordinal)
        jo.put("wordByWord", s.wordByWordEnabled)
        jo.put("fluid", s.fluidBackground)
        jo.put("freezeBackdrop", s.freezeBackdropOnPause)
        jo.put("inAppTextColor", s.inAppTextColor)
        jo.put("inAppFont", s.inAppFontSizeSp.toDouble())
        jo.put("inAppMinimal", s.inAppMinimal)
        jo.put("hideOverlayInLyrics", s.hideOverlayInLyricsPage)
        jo.put("hidePause", s.autoHideOnPause)
        jo.put("darkMode", s.darkMode)
        prefs.edit().putString(KEY, jo.toString()).apply()
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotBlank() } }

    private fun JSONObject.toMap(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        keys().forEach { k -> optString(k).takeIf { it.isNotBlank() }?.let { out[k] = it } }
        return out
    }

    /**
     * v1.15.0：读回歌手别名（`{播报名: [别名, ...]}`）。
     *
     * 逐条用 [toStringList] 过滤掉空串，并丢掉解析后为空的条目——
     * 手写 JSON 很容易多打逗号或留空数组，不能让脏数据进来。
     */
    private fun JSONObject.toStringListMap(): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        keys().forEach { k ->
            if (k.isBlank()) return@forEach
            val arr = optJSONArray(k) ?: return@forEach
            val list = arr.toStringList()
            if (list.isNotEmpty()) out[k] = list
        }
        return out
    }
}

package com.yuanbao.autolyrics.util

import android.content.Context
import com.yuanbao.autolyrics.data.PrecisionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

data class Settings(
    /** 桌面悬浮窗歌词 */
    val overlayEnabled: Boolean = true,
    /** 通知栏歌词 */
    val notificationEnabled: Boolean = true,
    /** App 内歌词页 */
    val inAppEnabled: Boolean = true,

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
     * 只影响 App 内的 Compose 界面（歌词页 / 设置页 / 歌词源页 / 悬浮窗外的所有页面）。
     * 桌面悬浮窗歌词是压在别的 App 之上的，永远用深底浅字（见 [overlayTextColor]），
     * 不跟这个开关走 —— 否则白底白字在别的 App 上会直接消失。
     */
    val darkMode: Boolean = true,
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

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        _settings.value = load()
    }

    fun update(block: (Settings) -> Settings) {
        _settings.value = block(_settings.value)
        save()
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
                inAppEnabled = jo.optBoolean("inapp", true),
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
                darkMode = jo.optBoolean("darkMode", true),
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
        jo.put("inapp", s.inAppEnabled)
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
}

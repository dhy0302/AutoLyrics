# Auto Lyrics 混淆规则
#
# 开启 R8 后，规则的核心任务是：**保住那些"名字被写进了别处"的符号**。
# 本项目已全量排查过反射风险，结论见文件末尾的"排查结论"。

# ---------------------------------------------------------------- 1. 组件
# Manifest 里用点号声明的组件，混淆后类名变了，系统按名字找不到它们，
# 表现为「点图标没反应」或「通知服务的通知栏歌词不出」。
# AGP 通常会自动 keep Manifest 组件，但显式写一遍更保险 ——
# 尤其本项目的 Service 是靠 NotificationListenerService 绑定唤醒的，出错隐蔽。
-keep class org.eu.dinghongyu.autolyrics.App
-keep class org.eu.dinghongyu.autolyrics.ui.MainActivity
-keep class org.eu.dinghongyu.autolyrics.media.MediaNotificationListener
-keep class org.eu.dinghongyu.autolyrics.ui.notify.OverlayActionReceiver

# 组件内的入口方法（onCreate / onStartCommand / onReceive）。
# 上一条的 { } 里不加 *，因为这些类里的其他代码都该被混淆。
-keep class org.eu.dinghongyu.autolyrics.media.MediaNotificationListener {
    public void onCreate();
    public boolean onListenerConnected();
    public void onListenerDisconnected();
    public void onNotificationPosted(...);
    public void onNotificationRemoved(...);
}
-keep class org.eu.dinghongyu.autolyrics.ui.notify.OverlayActionReceiver {
    public void onReceive(android.content.Context, android.content.Intent);
}

# ---------------------------------------------------------------- 2. 歌词源
# LyricSource 是「接口 + 通过 id 字符串查找实现」的注册表：
# SettingsStore 里存的是 sourceId 字符串，运行时才 resolve 成实现类。
# 混淆会把实现类的名字改掉，而 findSource 是按枚举/常量表遍历的（安全），
# 但为保证 id→实现的映射永不受影响，整体保留。
-keep class org.eu.dinghongyu.autolyrics.lyric.** { *; }
-keep interface org.eu.dinghongyu.autolyrics.lyric.LyricSource { *; }

# ---------------------------------------------------------------- 3. 数据模型
# Settings / TrackInfo 等是 JSONObject 手写序列化，不依赖反射；
# 但 SettingsPage 枚举带 @DrawableRes 注解引用资源 id，
# 保留枚举可避免资源 id 因混淆错位（虽然 R8 通常会一并修正）。
-keep class org.eu.dinghongyu.autolyrics.data.** { *; }
-keepclassmembers enum org.eu.dinghongyu.autolyrics.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------- 4. Compose
# Compose 编译器插件会生成 $stable/$changed 等标记类，它们由 R8 自动处理；
# 但 @Composable lambda 若被混淆出奇怪的类名，可能影响 devtools 与调试。
# 正式版不需要 keep —— 强跳过（strong skipping）由编译器插件保证，
# 与 R8 的类混淆无关。所以这里只保留必要的注解，不 keep 全部 Composable。
-dontwarn org.jetbrains.annotations.**

# ---------------------------------------------------------------- 5. salt-ui
# 2.0.10 是 KMP 库，consumerProguardFiles 不完整，
# 且它的 @Composable 里用到了 rememberSaveable（需要 SavedState 能保存）。
# 保留整个包最稳妥 —— 它在 APK 里的占比很小（几百 KB）。
-keep class io.github.moriafly.** { *; }
-dontwarn io.github.moriafly.**

# ---------------------------------------------------------------- 6. Coil
# Coil 用 OkHttp 做Fetcer，并按类型做多态分发。
-keep class coil.** { *; }
-dontwarn coil.**

# ---------------------------------------------------------------- 7. 网络
-keepclassmembers class okhttp3.** { volatile <fields>; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------- 8. 第三方杂项
-dontwarn androidx.palette.**
-dontwarn org.json.**
-dontwarn kotlinx.coroutines.**

# ---------------------------------------------------------------- 排查结论（2026-10-04）
#
# 已全量 grep 确认：
#  -无 Class.forName / newInstance / getMethod / javaClass 反射
#  - 无 Gson / Jackson / Moshi，序列化全是手写 JSONObject
#  - 无 @SerializedName 之类依赖反射的注解
#  - SettingsStore 用 precisionMode.ordinal 存枚举（ordinal 在混淆下稳定）
#  -⚠️ LyricRepository 曾用 RawFormat.name 存磁盘缓存 —— 这在混淆下不稳定，
#    已改成字符串字面量 FMT_LRC/FMT_KRC 等（见该文件 formatNameOf 的注释）。
#    若将来又引入带反射的序列化库，务必在这里补 keep 规则。
#
# keep 规则不是越多越好：每keep 一个包都在缩小混淆范围、增大 APK。
# 上面每一条都对应一个具体的失效场景。
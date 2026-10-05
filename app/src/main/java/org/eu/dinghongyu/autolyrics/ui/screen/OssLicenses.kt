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
 *
 * 上述三个项目同样收录在 [OSS_REFERENCES] 里，会显示在 App 的「开源许可」页 ——
 * GPL-3.0 §5(d) 要求保留署名，只写在源码注释里等于没署。
 */

package org.eu.dinghongyu.autolyrics.ui.screen

/**
 * 开源许可清单的数据模型与数据表。
 *
 * ## 为什么手写而不用 AboutLibraries 库
 *
 * 常见的做法是引入 `com.mikepenz:aboutlibraries`，让它在构建时扫描
 * Gradle 依赖并生成列表。但本项目有两个不适合：
 *
 *  1. **它自己就是一个要署名的开源库**，加了它这份清单里还得再列自己一行，
 *     有点讽刺。
 *  2. 它要求 Gradle 插件在构建期生成 JSON 资源，会和本项目已有的
 *     AGP 8 + Kotlin 2.0 + Compose 1.7.5 这套版本组合产生额外的兼容风险——
 *     为了一个纯展示页面去动构建链路，不划算。
 *
 * ## 版本号的来源
 *
 * **直接依赖**的版本号逐条抄自 `app/build.gradle.kts` 的 `dependencies`，
 * 带 BOM 解析结果的另在条目里注明。**传递依赖**的版本号取自
 * `./gradlew :app:dependencies` 的实际输出，不是凭记忆写的。
 *
 * ⚠️ 两类都只覆盖「用户能感知到、且有独立项目主页」的库，不是完整的依赖树
 * （完整树有 290 余项，绝大多数是 AndroidX / Compose 的内部模块）。
 * 升级依赖时记得同步这里。
 */

/** 一个开源库条目。 */
internal data class OssLib(
    /** 展示名，例如 "SaltUI"。 */
    val name: String,
    /** Maven 坐标，同一项目的多个构件用 " / " 并列。 */
    val artifact: String,
    /**
     * 实际使用的版本号。
     *
     * 为 null 表示这不是代码依赖，而是解析流程的参考实现——
     * 它们没有「本项目用的版本」这回事，版本号列出来反而误导。
     */
    val version: String?,
    /** 许可证全称。 */
    val license: String,
    /** 项目主页 / 仓库。 */
    val url: String,
    /** 一句话说明它在本项目里干什么用。 */
    val usage: String,
)

/**
 * 本项目通过 Gradle 依赖实际使用的开源库。
 *
 * ## 收录标准
 *
 *收录的是**用户能感知到、且有独立项目主页**的，分三类：
 *
 *  - `dependencies` 里点名声明的（SaltUI、OkHttp、Coil、AndroidX 全家桶等）
 *  - 构成了主要功能基础的（Compose、协程、Palette）
 *  - 随依赖树带进来的独立第三方（Accompanist、Material Components、Guava）
 *
 * AndroidX 的内部模块（`foundation-layout`、`collection-jvm` 之类）成百上千，
 * 全部列出只会淹没真正需要致谢的第三方，故不列。
 *
 * `debugImplementation` 的 `ui-tooling` 不在列表里 —— 它只进调试包，
 * 发布出去的 APK 里不存在，署它名属于虚假声明。
 */
private val OSS_LIBS: List<OssLib> = listOf(
    OssLib(
        name = "SaltUI",
        artifact = "salt-ui / salt-ui-android",
        version = "2.0.10",
        license = "Apache License 2.0",
        url = "https://github.com/Moriafly/SaltUI",
        usage = "整套界面设计语言，设置页与歌词页的视觉规范来自它",
    ),
    OssLib(
        name = "Jetpack Compose",
        artifact = "androidx.compose.ui:ui / ui-tooling-preview",
        version = "1.7.5",
        license = "Apache License 2.0",
        url = "https://developer.android.com/jetpack/compose",
        usage = "全部界面基于它构建，版本由 Compose BOM 2024.10.01 统一管理",
    ),
    OssLib(
        name = "Compose Material 3",
        artifact = "androidx.compose.material3:material3",
        version = "1.3.1",
        license = "Apache License 2.0",
        url = "https://developer.android.com/jetpack/compose/designsystems/material3",
        usage = "设置页与歌词页的 Material 3 主题、控件与对话框实现",
    ),
    OssLib(
        name = "Compose Multiplatform",
        artifact = "org.jetbrains.compose",
        version = "1.7.0-alpha03",
        license = "Apache License 2.0",
        url = "https://github.com/JetBrains/compose-multiplatform",
        usage = "SaltUI 依赖链引入的 JetBrains 版 Compose 产物，本项目未直接声明",
    ),
    OssLib(
        name = "AndroidX Core KTX",
        artifact = "androidx.core:core-ktx",
        version = "1.13.1",
        license = "Apache License 2.0",
        url = "https://developer.android.com/jetpack/androidx/releases/core",
        usage = "Kotlin 扩展函数与向后兼容的系统 API",
    ),
    OssLib(
        name = "AndroidX Lifecycle",
        artifact = "androidx.lifecycle:lifecycle-runtime-ktx / -compose",
        version = "2.8.7",
        license = "Apache License 2.0",
        url = "https://developer.android.com/jetpack/androidx/releases/lifecycle",
        usage = "生命周期感知的状态与协程作用域，界面状态按生命周期启停订阅",
    ),
    OssLib(
        name = "AndroidX Activity Compose",
        artifact = "androidx.activity:activity-compose",
        version = "1.9.2",
        license = "Apache License 2.0",
        url = "https://developer.android.com/jetpack/androidx/releases/activity",
        usage = "Activity 与 Compose 的桥接，承载「关于」等各个子页",
    ),
    OssLib(
        name = "AndroidX SavedState",
        artifact = "androidx.savedstate:savedstate-ktx",
        version = "1.2.1",
        license = "Apache License 2.0",
        url = "https://developer.android.com/jetpack/androidx/releases/savedstate",
        usage = "界面状态在进程被系统回收重建后的恢复",
    ),
    OssLib(
        name = "AndroidX Palette",
        artifact = "androidx.palette:palette-ktx",
        version = "1.0.0",
        license = "Apache License 2.0",
        url = "https://developer.android.com/jetpack/androidx/releases/palette",
        usage = "从专辑封面提取主色，用于歌词高亮与背景基调",
    ),
    OssLib(
        name = "OkHttp",
        artifact = "okhttp",
        version = "4.12.0",
        license = "Apache License 2.0",
        url = "https://github.com/square/okhttp",
        usage = "向酷狗、网易云、Lrclib 请求歌词与专辑封面",
    ),
    OssLib(
        name = "Coil",
        artifact = "coil-compose",
        version = "2.6.0",
        license = "Apache License 2.0",
        url = "https://github.com/coil-kt/coil",
        usage = "专辑封面的异步加载与磁盘缓存",
    ),
    OssLib(
        name = "Accompanist Drawable Painter",
        artifact = "accompanist-drawablepainter",
        version = "0.32.0",
        license = "Apache License 2.0",
        url = "https://github.com/google/accompanist",
        usage = "把 SVG 矢量图转成 Painter 绘制（随 SaltUI 依赖链引入）",
    ),
    OssLib(
        name = "Material Components for Android",
        artifact = "material",
        version = "1.10.0",
        license = "Apache License 2.0",
        url = "https://github.com/material-components/material-components-android",
        usage = "Material3 主题与部分控件的基础实现（随依赖链引入）",
    ),
    OssLib(
        name = "Kotlin Coroutines",
        artifact = "kotlinx-coroutines-android / -core",
        version = "1.8.1",
        license = "Apache License 2.0",
        url = "https://github.com/Kotlin/kotlinx.coroutines",
        usage = "歌词抓取、播放状态轮询、逐字动画时钟的异步调度",
    ),
    OssLib(
        name = "Kotlin Standard Library",
        artifact = "kotlin-stdlib",
        version = "2.0.21",
        license = "Apache License 2.0",
        url = "https://github.com/JetBrains/kotlin",
        usage = "语言运行时",
    ),
    OssLib(
        name = "Guava ListenableFuture",
        artifact = "listenablefuture",
        version = "1.0",
        license = "Apache License 2.0",
        url = "https://github.com/google/guava",
        usage = "OkHttp 依赖链带入的占位接口，本项目未直接调用",
    ),
)

/**
 * 歌词格式解析流程参考过的开源项目。
 *
 * ## 为什么必须出现在 App 里
 *
 * 此前这三个项目只写在源码头部注释与 README 的致谢表里，装App 的用户在
 * 「开源许可」页看不到 —— 而其中两个是 GPL-3.0。GPL-3.0 §5(d) 要求
 * 「以合理方式标注作品的版权，并保留版权声明」，只藏在源码注释里对
 * 最终用户不构成署名。
 *
 * ## 到底用到了什么程度
 *
 * **仅用于理解协议与格式**。所有解析器（`KrcParser` / `YrcParser` /
 * `QrcParser` / `LyricParser`）均为本项目独立实现，以正则重新写出，
 * 未复制上述项目的源代码。这也是本项目选择 GPL-3.0 发布的原因之一。
 */
private val OSS_REFERENCES: List<OssLib> = listOf(
    OssLib(
        name = "lx-music-desktop",
        artifact = "lyswhut/lx-music-desktop",
        version = null,
        license = "Apache License 2.0（附加限制条款）",
        url = "https://github.com/lyswhut/lx-music-desktop",
        usage = "KRC 解密流程参考：base64 → 去 krc1 头 → 16 字节密钥循环异或 → zlib",
    ),
    OssLib(
        name = "ESLyric-LyricsSource",
        artifact = "Robotxm/ESLyric-LyricsSource",
        version = null,
        license = "GNU General Public License v3.0",
        url = "https://github.com/Robotxm/ESLyric-LyricsSource",
        usage = "KRC 解密的交叉验证，以及 YRC 逐字格式的参考",
    ),
    OssLib(
        name = "QQMusicApi",
        artifact = "jsososo/QQMusicApi",
        version = null,
        license = "GNU General Public License v3.0",
        url = "https://github.com/jsososo/QQMusicApi",
        usage = "第三方歌词网关的接口契约参考",
    ),
)

/** 供「开源许可」页渲染「本应用使用了以下开源项目」分组。 */
internal fun ossLibs(): List<OssLib> = OSS_LIBS

/** 供「开源许可」页渲染「解析流程参考」分组。 */
internal fun ossReferences(): List<OssLib> = OSS_REFERENCES
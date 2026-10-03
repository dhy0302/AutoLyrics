package com.yuanbao.autolyrics.ui.screen

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
 * 清单里的版本号是从 `./gradlew :app:dependencies` 的实际输出里取的，
 * 不是凭记忆写的；升级依赖时记得同步这里。
 */

/** 一个开源库条目。 */
internal data class OssLib(
    /** 展示名，例如 "SaltUI"。 */
    val name: String,
    /** Maven 坐标的 artifactId，例如 "salt-ui"。 */
    val artifact: String,
    /** 实际使用的版本号。 */
    val version: String,
    /** 许可证全称。 */
    val license: String,
    /** 项目主页 / 仓库。 */
    val url: String,
    /** 一句话说明它在本项目里干什么用。 */
    val usage: String,
)

/**
 * 本项目实际使用到的开源库。
 *
 * ## 收录标准
 *
 * 不是把 290 个传递依赖全列出来——那里面绝大多数是 AndroidX / Compose 的
 * 内部模块（`foundation-layout-android`、`collection-jvm` 之类），
 * 列出来反而淹没真正需要致谢的第三方。收录的是**用户能感知到、且有独立项目主页**的：
 *
 *  - 直接依赖里点名使用的（SaltUI、OkHttp、Coil 等）
 *  - 构成了主要功能基础的（JetBrains Compose、协程）
 *  - 随依赖树带进来的独立第三方（Accompanist、Material、Guava）
 */
private val OSS_LIBS: List<OssLib> = listOf(
    OssLib(
        name = "SaltUI",
        artifact = "salt-ui",
        version = "2.0.10",
        license = "Apache License 2.0",
        url = "https://github.com/Moriafly/SaltUI",
        usage = "整套界面设计语言，设置页与歌词页的视觉规范来自它",
    ),
    OssLib(
        name = "Compose Multiplatform",
        artifact = "org.jetbrains.compose",
        version = "1.7.0-alpha03",
        license = "Apache License 2.0",
        url = "https://github.com/JetBrains/compose-multiplatform",
        usage = "JetBrains 版 Compose（SaltUI 依赖它），本项目全部界面基于它构建",
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
        artifact = "kotlinx-coroutines-core",
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

/** 供「开源许可」页渲染用。 */
internal fun ossLibs(): List<OssLib> = OSS_LIBS

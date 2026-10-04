# AutoLyrics — 安卓自动歌词

读系统播放状态，自动抓取歌词并在桌面悬浮窗 / 通知栏 / App 内实时逐字高亮。
不指定播放器：任何暴露 MediaSession 的播放器（Spotify、Apple Music、YouTube Music、网易云、QQ 音乐、本地播放器等）都能用。

<p align="center">
  <img src="assets/logo-192.png" alt="logo" width="96">
</p>

---

## ⬇️ 下载

**[Releases 页面](https://github.com/dhy0302/AutoLyrics/releases)** 拉最新 APK。

| 项 | 值 |
| --- | --- |
| 系统要求 | Android 8.0（API 26）+ |
| 架构 | 通用单包，无 native 库，全平台可装 |
| 体积 | release 约 2.9 MB / debug 约 14.6 MB |

首次安装需允许「安装未知来源应用」。仓库采用 GitHub Actions 自动构建：推送代码即自动编译并发布，无需本地打包。

### 版本与历史

每个版本对应一个独立 Release，tag 形如 `v1.18.1-build58`（版本名 + Android `versionCode`）。**所有历史版本都保留着**，在 Releases 页面往下翻即可下载任意旧构建——排查问题时可以回退到之前某版。

APK 文件名格式：`AutoLyrics-1.18.1-build58-e4855e7-release.apk`
（版本名 - 构建号 - 提交短 SHA - 签名类型）

> 历史版本都保留着，往下翻可找到任意旧构建。仓库的 `Latest` 标记始终指向最新一次发布。

>⚠️ **自行构建时务必递增 `versionCode`**（`app/build.gradle.kts`）。
> 每次发布都靠它生成新 tag；重复使用同一数字会撞上已有 Release，
> 新包会追加进同一个页面而不是生成新版本。

### 发布新版本的三步（每次推送都要做）

推送代码会自动触发构建并发布 Release，但**发布前的三件事需要人工做**，漏掉会导致用户看不到信息或读到过期文档：

| # | 做什么 | 漏掉的后果 |
| --- | --- | --- |
| 1 | 在 [`CHANGELOG.md`](CHANGELOG.md) **最顶部**追加一段 `## v{版本名} · build{构建号}` | Release 页面显示「本次发布未在 CHANGELOG.md 中记录改动。」 |
| 2 | 同步更新本 README 中受影响的描述 | 文档与实际行为不符（功能已删、规则已改、示例版本号过期） |
| 3 | 同步更新 [`BUILD.md`](BUILD.md) 中的当前版本号与产物表 | 构建说明停留在几个月前，照着它找不到当前产物 |

段落标题格式必须是 `## v{版本名} · build{构建号}`，例如 `## v1.12.6 · build40`。
工作流按这个标题截取到下一个 `## ` 之前的内容，渲染到 Release 页面顶部。

**写更新日志时**用面向用户的语言：说清「哪个操作会有什么变化」，
而不是罗列改了哪个文件。Release 页面的排版顺序是
「标题 → 本次更新 → 版本信息 → 选哪个包 → 安装说明」，
更新日志在最上面，所以第一句就该是最有用的信息。

**检查 README 时重点看这几处**，它们最容易过期：

- 「版本与历史」里的示例版本号
- 首屏表格里的体积（release / debug 两个数）
- 「显示」一节的渠道数量与开关说明
- 「缓存」一节的规则（负缓存什么时候写）
- 「功能」各表格里的能力描述

**检查 BUILD.md 时重点看**：第一节「产物」表格的当前版本行、
包名/版本号那行、以及末尾的最新版本记录段。

---

## 快速开始（三步权限）

| # | 权限 | 路径 | 为什么需要 |
| --- | --- | --- | --- |
| 1 | **通知读取** | 系统设置 → 通知 → 通知使用权 → 勾选 AutoLyrics | **必需**。没有它 `getActiveSessions()` 直接抛 `SecurityException`，抓不到任何播放信息 |
| 2 | **悬浮窗** | 设置 → 应用 → 显示在其他应用上层 | 桌面歌词 |
| 3 | **通知权限** | 首次启动弹窗（Android 13+） | 通知栏歌词 |

App 首页会逐个检测并给出跳转入口，授权后返回 App 立即生效。

> 建议顺手关掉本 App 的电池优化，否则后台容易被杀。

---

## 功能

### 播放状态抓取

| 能力 | 说明 |
| --- | --- |
| 会话扫描 | `MediaSessionManager` 读取所有活跃会话：标题 / 歌手 / 专辑 / 时长 / 实时进度 / 封面 / 播放状态 |
| 会话选择 | 多会话并存时**粘滞选择**，不会来回跳（同优先级：上次选中且在播 → 首个在播 → 上次选中 → 首个） |
| 通知兜底 | 个别 App 不暴露 MediaSession 时，从媒体通知解析歌名歌手（进度靠墙钟估算） |
| 黑名单 | 指定包名不监听 |
| 传输控制 | 标准 `TransportControls`：seek / 上一首 / 播放暂停 / 下一首，按 `PlaybackState.actions` 标记可用性 |

### 歌词获取（多源聚合）

默认顺序 **酷狗 → 网易云 → Lrclib**，可在设置里调整，也可单独启用/禁用某个源。

单个源的流程：

```
search → 打分选最佳候选 → fetch → 按格式选解析器 → 校验
```

任何一步不合格（无结果 / 得分过低 / 仅占位歌词）就自动回退到下一个源。

| 源 | 格式 | 逐字 | 备注 |
| --- | --- | --- | --- |
| **酷狗音乐** | KRC | ✅ 真逐字 | 默认首位。每字带起止时间，逐字体验最好；匿名取词稳定 |
| **网易云音乐** | LRC / YRC-JSON | ⚠️ 部分 | 公开接口的 `lrc.lyric` 常只是整行；真正的 YRC 需登录，故逐字覆盖率有限 |
| **Lrclib** | LRC | ❌ | 社区开源库，末位兜底 |

> **QQ 音乐已于 v1.6.0 移除**：官方网关对匿名请求有 IP 级限流，取词稳定性不足。

### 逐字与整行

拿到逐字歌词就做卡拉OK 式逐字染色，拿不到自动退回整行高亮。

支持的歌词格式：

| 格式 | 来源 | 结构 |
| --- | --- | --- |
| **LRC** | 通用 | `[mm:ss.xx]text`，支持一行多时间戳、`[offset:]`、译文合并（±300ms 就近对齐） |
| **KRC** | 酷狗 | `[行首,行长]<相对起,时长,0>字`，内嵌 `<1>原词<2>译词<3>注音` 内容标签 |
| **YRC** | 网易云 | `{"t":行首,"c":[{"t":词起,"c":"字"}]}` |
| **QRC** | QQ（遗留） | `[行首,行长]<起,长,0>字` |

### 匹配算法

跨源匹配的核心，宁可漏也不愿配错歌。

```
综合得分 = 0.55 × 标题相似度 + 0.25 × 歌手相似度 + 0.20 × 时长一致度
                        得分 < 0.55 → 换下一个源
```

- **归一化**：小写 → 繁体折叠简体 → 去括号补充 → 去 feat → 去版本后缀（remaster/live/MV/explicit/deluxe…）→ 全角转半角 → 去标点
- **相似度**：取「编辑距离相似度」与「token 重合度」的较大值（前者抗语序变化，后者抗长标题稀释）；包含关系单独给 0.85~1.0
- **时长校验**：差 ≤2s 满分，≤5s 0.85，≤10s 0.55，更大按距离衰减——同名不同版本是很强的排除信号
- **简繁双语检索**：歌名含繁体时额外生成简体副本，两个变体都拿去检索再合并候选（Spotify 广播态常是繁体，国内源基本只有简体）

### 缓存

| 类型 | 有效期 | 说明 |
| --- | --- | --- |
| 内存缓存 | 64 槽 LRU | 来回切歌不重复读文件 |
| 磁盘命中 | 30 天 | 存原文 + 译文两串文本，读出重新解析 |
| 磁盘未命中 | 3 天 | 负缓存，只记时间戳，避免同一首歌反复打网络 |

**只有「确实查过但没有」才写负缓存**：因网络/接口异常而「没查成」的结果不写缓存，
否则熄屏切歌时的一次断网会把歌锁死 3 天（表现为亮屏后一直「没找到歌词」，
只有手动点「重取」才能恢复）。

为此歌词源的**搜索与取词两个阶段**分别用 `SearchOutcome` / `FetchOutcome` 包装，
**必须如实上报成败**：`failed = true` 表示请求没打通（网络/风控/结构异常），
与「查完了但确实没有」严格区分。各源内部不得再把异常吞成空值或空列表——
那会让上层把断网误判成「没歌词」。

未取到时还会**自动退避重试**（3/10/30/60/120 秒，累计约 4 分钟），
网络恢复后无需任何操作即可补上，不依赖是否打开 App。

磁盘缓存**只存原始文本**而非解析结果——解析器的改进会自动应用到旧缓存上。
缓存文件带格式版本号，格式变更时旧缓存会自动失效一次。

### 显示

| 模式 | 说明 |
| --- | --- |
| 桌面悬浮窗 | 可拖动 / 锁定（不接收点击）/ 透明背景 / 字号 / 字体颜色（调色盘）/ 单行或双行 / 逐字开关 / 纵向位置。暂停自动隐藏，进入歌词页临时隐藏 |

### 调色盘

三处都有 HSV 色轮：设置 → 歌词页 → 歌词颜色、设置 → 悬浮窗 → 字体颜色、
以及桌面悬浮窗工具栏上的「颜色」按钮。预览区显示 `RGB 130, 83, 195` 这样的
十进制读数（不用十六进制——那串数字用户既看不懂也没法照着微调）。

前两处带 R/G/B 三个输入框，可直接键入数值。**桌面悬浮窗那个只有读数**：
悬浮窗是 `FLAG_NOT_FOCUSABLE` 窗口，收不到键盘输入，放了输入框也点不出键盘。
两处读写的是同一个设置项，改动实时同步。

**「取消」是撤销本次改动**：没动过就什么都不发生；动过（拖了色轮或输了 RGB）
就回到打开面板时的样子；还原之后再点取消不再有反应，不会一路往回退。
桌面悬浮窗上的「取消」还兼作收起面板。

颜色**立刻落盘**，不走设置通用的 300ms 防抖——防抖是为拖校准滑块那种
连续改动设计的，选色用它会留下「改完立刻被杀就丢」的窗口。

| 通知栏 | 常驻通知 + 自定义进度条；展开后带两个悬浮窗控制按钮（见下） |
| App 内歌词页 | 全屏歌词 + 专辑封面流体背景 + 高亮色取自封面主色 + 精简模式 + 夜间/白天主题 |
| 歌词页顶部 | 封面 + 歌名/歌手/歌词源，右上角「重取」与精简切换 |

歌词页的**高亮行固定在屏幕从上往下 7/16 处**（约 43.8%），不是垂直居中：
上方要容纳歌曲信息与上一句、下方只有下一句，视觉重量天然偏上，
居中反而显得高亮行被压在下半屏。该位置由 `HomeScreen.kt` 的 `ANCHOR_FRACTION`
统一控制，普通模式与精简模式共用。

悬浮窗与通知栏两个渠道可分别开关（设置 → 显示方式）；**App 内歌词页是主界面，恒定开启**。
译文开关与逐字开关各自独立。

### 通知栏上的两个按钮

通知栏歌词展开后，下面有两个按钮，**文案写的都是「点一下会发生什么」**：

| 按钮 | 当前状态 → 显示文案 |
| --- | --- |
| 悬浮窗开关 | 开着 →「关闭桌面歌词」／关着 →「开启桌面歌词」 |
| 透明背景 | 不透明 →「歌词背景透明」／透明 →「歌词背景不透明」 |

两个都走广播而非悬浮窗本身，所以**悬浮窗被锁定（点不动）时照样能调**——
等于把通知栏当成悬浮窗的遥控器。透明背景与设置页的「透明背景」是同一个开关，状态同步。

> 改这两个按钮的文案时，**通知的去重键必须同步加上对应状态**
> （`overlayEnabled` / `overlayTransparentBg`）。
> 否则在设置页改了开关后通知不重发，按钮会一直停在旧文案上，
> 显示的和实际的对不上。

### 播放控制与交互

- 进度条拖动 seek，传输控制走标准 `TransportControls`
- 歌词可自由上下滑动，点击任意行跳到对应时间
- 三档刷新精度：

| 档位 | 间隔 | 特点 |
| --- | --- | --- |
| 流畅省电 | 200ms | 够用且最省电，适合整行歌词 |
| 标准 | 100ms | 插值，逐字级准确（默认） |
| 极致精准 | 50ms | 插值 + 低通平滑滤波，逐字最贴合，耗电较高 |

三档都带**时间插值**（用 `PlaybackState` 上报时刻推算真实位置），只有「极致」额外做平滑滤波。

### 其他

- 全局歌词偏移（正 = 歌词延后出现），用于修正个别源的时间轴偏差
- 手动锁定歌词源：某首歌搜错了，可在「歌词源」页搜索后「用此源」指定，下次直接取该源/该候选
- 调试页：列出各源候选与得分、取词回退过程、原始歌词文本
- 开源许可归属页（OssLicenses）
- 关于页「检测更新」：查 GitHub 最新 Release 并与本机比对，
  分别是「已是最新 / 有新版 / 本机更领先 / 检测失败」四种提示。
  **以构建号（versionCode）为准**判断新旧 —— 它才是 Android 判断能否覆盖升级的依据，
  且单调递增；版本名可能因补发旧分支而倒退。
  仓库地址放在 `res/values/strings.xml` 的 `repo_url`，不在代码里写死。

---

## 架构

```
app/src/main/java/org/eu/dinghongyu/autolyrics/
├── App.kt                          初始化：设置 → 缓存 → 引擎 → 轮询
├── data/Model.kt                   TrackInfo / Lyric / LyricLine / LyricWord / PrecisionMode
├── media/
│   ├── MediaNotificationListener   通知监听服务（权限载体 + 兜底解析 + 拉起 UI）
│   ├── MediaSessionWatcher         MediaSession 抓取、会话选择、传输控制
│   └── PlaybackMonitor             对外唯一状态源：曲目 / 进度 / 封面 / 控制能力
├── lyric/
│   ├── LyricEngine.kt              曲目变化 → 取词；位置变化 → 算当前行
│   ├── LyricRepository.kt          多源聚合、打分选优、缓存
│   ├── LyricSource.kt              Candidate / RawLyric / RawFormat / SourceAttempt / SearchOutcome 契约
│   ├── parser/
│   │   ├── LyricParser.kt          LRC（多时间戳 / offset / 增强标签 / 译文合并）
│   │   ├── KrcParser.kt            酷狗逐字 KRC
│   │   ├── YrcParser.kt            网易云逐字 YRC
│   │   └── QrcParser.kt            QQ 逐字 QRC（遗留）
│   └── source/
│       ├── KugouSource.kt          酷狗
│       ├── NeteaseSource.kt        网易云
│       └── LrclibSource.kt         Lrclib
├── ui/
│   ├── MainActivity.kt             Compose 外壳 + 权限引导
│   ├── Theme.kt                    夜间/白天主题
│   ├── components/                 AlbumBackdrop / LyricText / ColorWheel / KaraokeClock / PlayerBar
│   ├── notify/NotifyLyrics.kt      通知栏歌词
│   ├── overlay/                    OverlayController / OverlayWindow / OverlayContent
│   └── screen/                     HomeScreen / SettingsScreen / SettingsPages / AboutPage / DebugScreen
└── util/
    ├── SettingsStore.kt            全部偏好（StateFlow，改动即时落盘）
    ├── TextMatch.kt                归一化 + 编辑距离 + token 重合 + 时长校验
    ├── ChineseConverter.kt         繁简折叠
    ├── BitmapBlur.kt               封面降采样与模糊
    └── Http.kt                     网络封装
```

**分层原则**：`media` 只管抓状态，`lyric` 只管取词，`ui` 只管显示，三者靠 `data/Model.kt` 的数据结构解耦。

---

## 技术栈

| 项 | 版本 |
| --- | --- |
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose（BOM 2024.10.01）+ Material 3 |
| Android Gradle Plugin | 8.6.1 |
| Gradle | 8.7 |
| compileSdk / targetSdk | 34 |
| minSdk | 26 |
| JDK | 17 |
| 组件库 | [moriafly/salt-ui](https://github.com/moriafly/salt-ui) 2.0.10 |
| 网络 | OkHttp 4.12 + kotlinx-coroutines |
| 封面加载 | Coil 2.6 |
| 主色提取 | androidx.palette |

> **盐选 UI 版本锁定 2.0.10**：3.0.0-beta01 要求 Compose 1.12 + AGP 9.1 + compileSdk 37，还会拖来 `AndroidHiddenApiBypass`（隐藏 API，上架 Google Play 会被拒）等一大堆新依赖。2.0.10 的 aar-metadata 是 `minCompileSdk=1 / minAGP=1.0.0`，零门槛。
>
> 另注：README 里写的 `io.github.moriafly:salt-ui` 只是 KMP 元数据入口，直接写会 404，真实 Android 产物是 `salt-ui-android`，两个都要声明。

---

## 构建

```bash
./gradlew assembleDebug     # 调试包
./gradlew assembleRelease   # 正式包（需签名配置）
```

签名凭据**不在仓库里**，需在本地 `gradle.properties` 提供：

```properties
RELEASE_STORE_FILE=autolyrics-release.p12
RELEASE_STORE_PASSWORD=<你的密码>
RELEASE_KEY_ALIAS=autolyrics
RELEASE_KEY_PASSWORD=<你的密码>
```

凭据齐备才配置 release 签名，缺失时会warn 并退回未签名——这是**有意设计**，防止密码被硬编码进源码。详见 [`BUILD.md`](BUILD.md)。

CI 走 GitHub Actions（`.github/workflows/build.yml`）：JDK 17 + Gradle 8.7 云端构建，同时产出 debug 与 release 包并发布到 Releases。

---

## 已知限制

- **通知读取权限是硬前提**：未授予时整个抓取链路不可用，App 只能引导无法工作
- **网易云逐字覆盖率有限**：真正的 YRC 需登录，公开接口拿到的多半是整行
- **逐字歌词依赖播放器上报时长**：进度靠墙钟插值，播放器上报稀疏时可能有轻微漂移
- 部分 ROM（如 MIUI、EMUI）对后台服务有额外限制，需手动允许后台运行 + 关闭省电策略
- 证书为**自签名**，仅供侧载，不能上架 Google Play 等应用商店

---

## 致谢

歌词格式的解析流程参考了开源社区的实现（调研记录见 [`BUILD.md`](BUILD.md)）：

| 项目 | 许可证 | 用到的部分 |
| --- | --- | --- |
| [lyswhut/lx-music-desktop](https://github.com/lyswhut/lx-music-desktop) | Apache-2.0（附加限制条款） | KRC 解密流程（base64 → 去 `krc1` 头 → 16 字节密钥循环异或 → zlib） |
| [Robotxm/ESLyric-LyricsSource](https://github.com/Robotxm/ESLyric-LyricsSource) | GPL-3.0 | KRC 解密交叉验证、YRC 格式参考 |
| [jsososo/QQMusicApi](https://github.com/jsososo/QQMusicApi) | GPL-3.0 | 第三方网关接口契约参考 |

上述参考仅用于**理解协议与格式**。所有解析器（`KrcParser` / `YrcParser` / `QrcParser` / `LyricParser`）均为本项目独立实现，以正则重新实现，未复制上述项目的源代码。

组件库：[moriafly/salt-ui](https://github.com/moriafly/salt-ui)（Apache-2.0） · 封面：[Coil](https://github.com/coil-kt/coil)（Apache-2.0）

---

## 许可

**GPL-3.0-or-later** —— 完整文本见 [`LICENSE`](LICENSE)。

本项目之所以采用 GPL-3.0 而非 Apache-2.0，是因为调研过程中参考了 GPL-3.0 项目的实现思路。选 GPL-3.0 可避免出现「声明 Apache-2.0 却因衍生关系需改 GPL」的许可冲突，也确保衍生作品同样保持开源。

这意味着：

- 你可以自由使用、修改、复制、发布本项目
- **分发或修改后的版本必须同样以 GPL-3.0 发布，并保留本声明与版权信息**
- 本项目**不提供任何担保**，且作者不对使用后果负责

> 选用 GPL-3.0 亦符合 lx-music-desktop 上游的附加条款要求（其中明确要求使用者接受协议后按其条款使用）。

歌词内容来自第三方服务，本项目仅提供检索与展示能力，不存储、不分发任何音频。版权数据归各平台及权利人所有。
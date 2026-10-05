# AutoLyrics 构建说明

源码从 GitHub 仓库构建，GitHub Actions 自动编译并发布 APK，无需本地打包。

> ⚠️ **每次发版记得同步这份文档**：第一节的产物表、下面那行版本号、
> 以及末尾的最新版本记录段。只改 CHANGELOG 不改这里，
> 本文档就会停留在几个月前，照着它找不到当前产物。

## 一、产物

产物托管在 [Releases 页面](https://github.com/dhy0302/AutoLyrics/releases)，
每个版本一个独立 Release，tag 形如 `v1.18.8-build65`。
**所有历史版本都保留**，往下翻即可下载任意旧构建。

| 文件 | 类型 | 大小 | 说明 |
| --- | --- | --- | --- |
| `AutoLyrics-1.18.8-build65-*-release.apk` | 发布版 | 约 2.9 MB | **推荐安装**：R8 混淆 + 资源裁剪，无 native 库全平台可装 |
| `AutoLyrics-1.18.8-build65-*-debug.apk` | 调试版 | 约 14.6 MB | 不混淆、不裁剪，带调试符号，便于抓 log |

文件名格式：`AutoLyrics-{版本名}-build{构建号}-{提交短SHA}-{签名类型}.apk`
（`*` 是提交短 SHA，每版都变）

- 包名：`org.eu.dinghongyu.autolyrics`，当前 versionCode 65 / versionName 1.18.8
- `minSdk 26`（Android 8.0+）/ `targetSdk 34`，通用 dex（无 native 库，全平台可装）
- **含前台服务** `LyricsForegroundService`（v1.18.2 新增）。
  `targetSdk 34` 下 `foregroundServiceType` 是必填的，缺了会直接抛异常；
  需同时声明 `FOREGROUND_SERVICE` 与 `FOREGROUND_SERVICE_DATA_SYNC` 两个权限。
- **release 包开启 R8 混淆与资源裁剪**（`isMinifyEnabled = true` /
  `isShrinkResources = true`）。debug 包**完全不混淆**——任何人 clone 后
  `assembleDebug` 都可断点调试，这是刻意保留的：源码以 GPL-3.0 公开，
  混淆不影响任何人自行构建修改。
  ⚠️ 因此**仓库没有 mapping.txt，也不打算上传**。排查线上崩溃需临时本地构建取 mapping。
- 签名：release 包需仓库密钥（`*.jks`，**不纳入版本控制**）。
  仓库未内置密钥时 CI 只产出 debug 包，Release 页面会给出说明。

## 二、安装后要开的三个权限（缺一不可）

1. **通知使用权**：系统设置 → 通知 → 通知使用权 → 勾选 AutoLyrics。没有它，读取 MediaSession 会直接抛 `SecurityException`，歌词功能完全不可用。
2. **悬浮窗权限**：设置 → 应用 → 显示在其他应用上层（桌面歌词需要）。
3. **通知权限**：Android 13+ 首次弹窗授权（通知栏歌词需要）。

建议顺手关掉本 App 的电池优化，否则后台轮询容易被系统杀掉。

## 三、构建方式（GitHub Actions）

构建与发布已完全自动化：**向 `master` 推送代码即自动编译并创建 Release**，
无需本地环境。当前本机**没有 JDK**，编译验证只能靠 CI。

工作流：`.github/workflows/build.yml`

| 环节 | 说明 |
| --- | --- |
| 触发 | push 到 `master`（`paths-ignore` 挡掉 `**/*.md`、`assets/**` 等纯文档/资源改动） |
| 版本来源 | `app/build.gradle.kts` 的 `versionName` + `versionCode`，tag 形如 `v{versionName}-build{versionCode}` |
| 更新日志 | 工作流按 `## v{版本名} · build{构建号}` 从 `CHANGELOG.md` 抽取段落写进 Release 正文 |
| 产物 | `dist/*.apk`，`release` 与 `debug` 各一个 |
| 并发 | `concurrency.cancel-in-progress: false` —— 排查时需连续推送，开了会让后一次掐断前一次 |

⚠️ **版本号不变会导致复用已有 tag**，新 APK 追加进同一个 Release 页面。
这也是「升版本号」是唯一可靠触发方式的原因——只改 CHANGELOG 不会触发构建。

如需本地构建：装JDK 17 + Android SDK（`platforms;android-34`、
`build-tools;34.0.0`），然后 `./gradlew :app:assembleRelease`（或 `:app:assembleDebug`）。

### 排查 CI 失败

`gh run view <runId>` / `gh run watch <runId>`。
**API 取`/jobs/{id}/logs` 会返回空，日志要从
`/actions/runs/{id}/artifacts` 的 `build-log` 下载**（zip 需解压）。
本项目曾因此花了6 次往返才看到真实报错。

推送前可先跑 `python scripts/check_kotlin.py`（见文末）。

## 四、已知风险

- 未使用前台服务，悬浮窗挂在 `NotificationListenerService` 进程里，国内 ROM 上可能被杀。
- 歌词接口（网易云 / 酷狗 / 虎牙 / Bilibili）偶发风控或需要 Cookie。
  **取词失败会与「确实没有歌词」严格区分**：失败不写负缓存，并自动退避重试
  （3/10/30/60/120 秒），网络恢复后自动补上。海外兜底 Lrclib 中文覆盖率低。
- 通知兜底路径没有精确进度（墙钟估算），也无法控制播放器。
- release 崩溃日志类名被R8 混淆形如 `a.b.c`，且**仓库不提供 mapping.txt**，
  排查线上崩溃需临时本地构建取 mapping。

> 早期版本记录的「为让代码编译通过所做的修改」（15 处 Kotlin 编译错误修复）
> 已全部随版本迭代失效。**下文 `## v1.x.x` 各段是历史变更记录**，
> 其中的签名指纹、旧路径、旧接口均已不适用于当前版本，看的时候留意。

## 五、本次功能迭代（v1.0.0 → 歌词源修复 + 桌面歌词外观）

针对"QQ / 网易云拿不到歌词"以及"桌面歌词要透明背景 + 可调字体颜色"三件事做的改动：

### 1. QQ 音乐源：改为可配置网关（官方接口已无法直连）

经实测确认：QQ 音乐官方接口 `c.y.qq.com` / `u.y.qq.com/musicu.fcg` 现已强制
**VMP + AES-GCM 签名**，旧的 md5 sign（`zza`+随机串+`md5("CJBPACrRuNy7"+data)`）实测返回
`code 500003` 失效，Kotlin 端无法复刻。因此 `QqMusicSource` 改为走用户自托管 / 可信的
第三方网关（兼容开源 [jsososo/QQMusicApi](https://github.com/jsososo/QQMusicApi) 契约）：

- `Settings.qqGateway` 新增字段（设置页"歌词源"区可填）。
- 留空 → QQ 源返回空，自动回退到网易云 / 酷狗 / Lrclib。
- 填入基址（如 `https://你的网关/qqmusic`）后：`{基址}/search?key=` 搜索、`{基址}/lyric?id=` 取词。
- 解析对 `data` 为数组 / 对象 / `result` 数组等多种返回形态做了容错。

> 说明：本沙箱网络下无可验证的公开 QQ 代理，故未硬编码默认网关，需用户自备。

### 2. 网易云音乐源：规避 -460 风控

- `NeteaseSource` 请求 Cookie 增加随机 `__csrf`（进程内固定 32 位十六进制），规避匿名请求常见的 `-460` 风控。
- 实测 `music.163.com/api/search/get/web` 与 `/api/song/lyric` 对正常歌曲返回真实歌词正常（已验证《海阔天空》等）。

### 3. 多候选回退（所有源受益）

- `LyricRepository.fetchFromNetwork` 重构：对单个源按匹配度降序尝试最多 3 个候选，
  命中"暂无歌词""纯音乐"等占位歌词时自动换下一个候选，不再浪费整个源的机会。
- 新增 `isPlaceholder()` 判断（含"暂无歌词 / 纯音乐 / instrumental"）。

### 4. 桌面歌词外观

- `Settings.overlayTransparentBg`：透明背景开关（不渲染半透明底框，纯文字叠加）。
- `Settings.overlayTextColor`（ARGB）：字体颜色，设置页"字体颜色"调色盘 12 个预设色块点选。
- `OverlayContent` 背景与全部文字（标题 / 当前行 / 译文 / 来源标签 / 提示）改用该颜色及其透明度派生。


---

## v1.1.0（2026-10-02）：歌词源全面重写

针对「酷狗时长对不上 / 繁体歌名搜不到 / 酷狗没有逐字」三个问题，先调研开源实现再做重写。
结论全部经**真实请求实测**验证，不是照抄文档。

### 调研来源（开源项目）

| 项目 | 用到的部分 |
| --- | --- |
| [lyswhut/lx-music-desktop](https://github.com/lyswhut/lx-music-desktop) `src/common/utils/lyricUtils/kg.js` | 酷狗 KRC 解密（base64 → 去 4 字节 `krc1` → 16 字节密钥循环异或 → zlib），以及 KRC 内置译文 `[language:<base64>]` 的提取 |
| ESLyric-LyricsSource `current/krc/parser/krc.js` | 与 lx-music 一致的 KRC 解密流程（交叉验证密钥） |
| ESLyric-LyricsSource `current/qrc/lib/qrc-decryptor/` | QQ 加密 QRC 的解密（本项目暂未用到，原因见下） |
| ESLyric-LyricsSource `current/yrc/parser/yrc.js` | 网易云 YRC 逐字格式（本项目的 `YrcParser` 已覆盖） |

### 实测结论（关键）

1. **酷狗必须用 FileHash 精确检索**，不能拿「歌名+歌手」直接打 `krcs` 接口：
   - `songsearch.kugou.com/song_search_v2?keyword=` → `FileHash` + `Duration`（秒，准确）
   - `krcs.kugou.com/search?hash=<FileHash>` → 歌词 `id` + `accesskey`
   - 旧做法（关键词直搜 `krcs`）的 `duration` **秒/毫秒混用且夹带用户上传脏数据**：
     同一首《再见的时候》同时返回 `32000`、`35000`、`234000`；而《稻香》返回 `223`（秒）。
     这就是「时长 32000 秒」的直接原因。走 hash 后统一为 `234000`ms（与 Spotify 的 235s 一致）。
   - 同时修复了部分条目 `song`/`singer` 填反（如《稻香》条目 song=周杰伦）。
2. **KRC 是加密的**，解密流程与 lx-music 完全一致（已用真实数据解出 46 行逐字歌词）：
   `base64 → 去 "krc1" 4 字节头 → 与 16 字节固定密钥 `[0x40,0x47,...,0x6E,0x69]` 循环异或 → zlib 解压 → UTF-8`。
   解压后是 `[行起始,行时长]<字起始,字时长,0>字…`，交给 `KrcParser` → **酷狗逐字歌词**。
3. **QQ 音乐官方接口并没有全死**。`client_search_cp` 返回 500、`fcg_query_lyric_new.fcg`
   返回 `retcode 1101` 是真的，但 `u.y.qq.com/cgi-bin/musicu.fcg` 这两个模块仍免签名可用：
   - 搜索 `music.search.SearchCgiService / DoSearchForQQMusicDesktop`（POST `comm` 包体）
   - 取词 `music.musichallSong.PlayLyricInfo / GetPlayLyricInfo`
     → `lyric`（base64 LRC）、`trans`（base64 译文）、`qrc`（逐字，加密时 `crypt≠0`）
   实测匿名请求只下发整行 LRC（`qrc` 为空、`crypt=0`），故 QQ 为整行源，
   逐字由酷狗 KRC / 网易 YRC 提供；已保留第三方网关作为查不到时的备用通道。
4. **繁体→简体**：酷狗 `songsearch` 带纠错、繁简都能命中；网易云/QQ 则需简体。
   检索时同时发「原词」与「简体变体」两个查询并合并候选；打分前用内置 OpenCC
   字表（`res/raw/t2s.txt`，4227 组）统一字形 → Spotify 的繁体元数据不再扑空。

### 改动文件

- `lyric/source/KugouSource.kt`：重写为 hash 检索 + KRC 解密 + 译文提取 + LRC 回退；
  关键词兜底路径保留时长单位归一化（≥100000 视为毫秒）与可信区间校验（30 秒~30 分钟，
  越界置 0 走中性分，不再拉低正确候选）。
- `lyric/source/QqMusicSource.kt`：重写为官方 `musicu.fcg`（POST），第三方网关降级为备用。
- `lyric/parser/KrcParser.kt`：新增酷狗 KRC 逐字解析（兼容 `<1>原词<2>译词` 内嵌标签）。
- `lyric/LyricSource.kt`：`RawFormat` 增加 `KRC`。
- `lyric/LyricRepository.kt`：繁简双检索变体、缓存格式改用 KRC、`init` 初始化转换器。
- `util/ChineseConverter.kt` + `res/raw/t2s.txt`：离线繁→简（OpenCC 字符表，无第三方依赖）。
- `util/TextMatch.kt`：归一化时统一字形。
- `util/Http.kt`：新增 `postJson`（QQ 网关只需 POST）。
- `ui/screen/DebugScreen.kt`：候选时长未知时显示「时长未知」而非 `0s`。

## 六、v1.3.0 变更（Apple Music 风格歌词页）

参考开源项目 [dokar3/amlv](https://github.com/dokar3/amlv)（纯 Jetpack Compose 的 Apple Music 歌词视图，
其招牌动画为"当前行 spring 弹跳放大 + alpha 渐亮、左对齐大字号、顶/底淡出边缘"）重做了 App 内歌词页。

### 问题
旧版歌词页背景 `AlbumFluidBackdrop` 用"大模糊 + 缓慢旋转(60s/圈) + 呼吸缩放"，
旋转时四角会露空必须整体放大 1.25~1.45 倍，观感廉价、且不符合 Apple Music 的静态磨砂风格。

### 改动
- `ui/components/AlbumBackdrop.kt`：
  - `AlbumFluidBackdrop`（旋转 + 呼吸）删除，改为 `AlbumBackdrop`：**静态**磨砂封面背景
    （64px 降采样大模糊 + 50% 黑压暗 + 顶/底渐变压暗），**不再旋转、不再呼吸**。
  - 新增 `AlbumArtCard`：清晰圆角封面卡（18dp 圆角 + 14dp 投影），不做任何旋转，
    作为歌词页顶部"正在播放"主视觉，直接替代旧版只能当模糊背景的封面。
- `ui/screen/HomeScreen.kt`：
  - 顶部 `NowPlayingBar` → `NowPlayingHeader`：左侧清晰封面卡 + 右侧歌名/歌手/来源标签。
  - 歌词列表 `LyricList` → `AppleLyricList` / `AppleLyricLine`：左对齐，当前行
    `scale 1.0→1.1`（spring 低弹度 `DampingRatioLowBouncy`）+ `alpha 0.42→1.0`（500ms 渐亮），
    transformOrigin 在左中(0f,0.5f)，文字从左基线"长"出来；首/末行叠加渐变淡出边缘；
    逐字歌词时当前行内已唱字高亮、未唱字半透明，叠加整行弹跳。
- `ui/screen/SettingsScreen.kt`：`专辑图流体背景` 开关文案改为"专辑图磨砂背景"
  （说明：Apple Music 风格静态磨砂封面，不旋转；关闭则为纯深色）。

### 签名 / 构建
- 复用同一密钥库（`autolyrics-release.jks`，本地保管），SHA-256 `a948e972…08645` 一致，覆盖安装不受影响。
- `app/build.gradle.kts`：versionCode 6 / versionName 1.3.0。
- 已 `./gradlew assembleRelease` 编译通过、`apksigner verify` 通过，产物 `dist/AutoLyrics-v1.3.0-release.apk`。

### 此前已修复、本次复核确认仍有效的问题
1. 强杀后检测失效：`MediaNotificationListener.requestRebind()` 在 `App.onCreate` / `MainActivity.onResume`
   权限已授予但未连上时主动重绑，`HomeScreen` 给出"正在恢复播放检测"提示。
2. 网易云扫码登录取不到：`NeteaseLogin` 端点改为 `/weapi/login/qrcode/unikey`，轮询码 800/801/802/803 已对齐。
3. 悬浮窗行数开关：`Settings.overlayLineMode`（`current_next` / `current`）已接 `OverlayContent`。
4. 色轮调色盘：HSV 圆盘 + 明度条 + 透明度条 + 取消/确定，已重写。
5. 悬浮窗 ✕ 关闭（未锁定时右上角）+ 通知栏"关闭悬浮窗"动作（`OverlayActionReceiver` 广播，锁/未锁均生效）。

## v1.4.0 歌词页视觉重做（用户反馈"割裂/不美观"）

针对用户截图指出的三类问题（状态栏白条、顶栏信息排列杂乱、背景死黑）做的整体视觉重构：

- **沉浸式全屏**：`MainActivity` 改用 `enableEdgeToEdge(statusBarStyle = dark, navigationBarStyle = dark)`，
  状态栏/导航栏全透明、浅色图标，背景铺满整个屏幕（含系统栏）。根因：旧版 `themes.xml` 父主题是
  `Theme.Material.Light`，未开 edge-to-edge，透明状态栏露出了白色 windowBackground。
- **英雄式布局**：歌词页改为「居中『正在播放』小字 caption → 居中封面大卡 → 居中歌名/歌手/来源一行
  → 歌词区 → 播放条」。删除了旧版 `NowPlayingHeader` 里会折行（`Media Session`→三行）的 `AssistChip` 标签堆，
  来源信息合并为单行副标题；`重取`/`开权限`收进顶部超薄操作栏右侧。底部 TabBar 在歌词页改为半透明悬浮层
  （其余页为 Scaffold 实体 bottomBar）。
- **流体渐变背景** `FluidBackdrop`：模糊封面打底 + 压暗 + 4 个大半径径向渐变色块缓慢漂移
  （周期 26s/34s/41s 互质，永不同步构图）。颜色由 Palette 从封面提取双主色（`rememberAlbumColors`），
  经 `saturate` 提饱和/压明度整形，再 `shiftHue` 衍生第二、第三色。顶部/底部渐变压暗保证歌词可读。
- **歌词真透明淡出**：`AppleLyricList` 用 `graphicsLayer{compositingStrategy = Offscreen}` + `drawWithContent`
  叠加 `BlendMode.DstIn` 竖向渐变，实现真正的 alpha 淡出（露出流体背景），取代旧版的黑色渐变色块
  （旧版在彩色背景上会呈现一圈突兀的黑色矩形）。
- `ui/screen/SettingsScreen.kt`：背景开关文案改为"流体渐变背景"。
- `app/build.gradle.kts`：versionCode 7 / versionName 1.4.0。重新编译 + 签名校验通过。

### v1.4.0 修订：播放页 TabBar 跑到顶部
- 现象：沉浸式播放页（HOME）的半透明 TabBar 贴在屏幕顶部而非底部。
- 根因：TabBar 在 `Box` 中是 wrapContent 高度，而 `Box` 默认把子元素对齐到 `TopStart`；
  代码里只加了 `navigationBarsPadding()`（它只是在内容下方补空白，不会把元素推到底边）。
- 修复：`TabBar` 新增 `modifier` 参数，播放页调用处传 `Modifier.align(Alignment.BottomCenter)`，
  并给根 `Column` 补 `fillMaxWidth()` 让半透明背景铺满整宽。签名不变，覆盖安装不受影响。

## v1.4.1 播放检测修复 + 两个逐字开关

### 1. 修复「权限一直开着，但重开 APP 检测不到播放」（架构级修复）

**根因**：`MediaSessionManager.getActiveSessions()` 的准入条件只是**通知读取权限已授予**，
并不要求 `NotificationListenerService` 已绑定或已回调 `onListenerConnected()`。
但旧实现把整条抓取链路挂在 `onListenerConnected()` 上：
- `MediaSessionWatcher.start()` 只在 `onListenerConnected()` 里被调用；
- `onListenerDisconnected()` 里还会 `MediaSessionWatcher.stop()`，一旦系统临时断连就彻底拆掉链路。
应用被强杀或被系统回收后，系统常常不再回调 `onListenerConnected`，于是**权限明明还在、
检测却永久失灵**，只能靠用户手动关开权限触发（这正是此前用户观察到的现象）。

**修复**（链路与通知服务回调解耦）：
- `MediaSessionWatcher` 新增 `linked` 标记 + `isLinked` 属性 + 幂等的 `ensureStarted(context)`：
  已就绪则只 `refresh()`，未就绪则建立监听。
- `App.onCreate`：权限已授予即 `ensureStarted(this)` + `requestRebind(this)`，不再等回调。
- `MainActivity.onResume`：权限已授予即 `ensureStarted(this)`（覆盖从系统设置返回、后台切回等场景）。
- `onListenerDisconnected()`：**不再** `stop()` 抓取链路（权限有效时 MediaSession 仍可用），
  仅标记 `listenerConnected=false`。
- `onListenerConnected()` 改用 `ensureStarted()`，与服务回调解耦且保持幂等。
- `HomeScreen` 的「播放检测未就绪」提示改用真实口径 `MediaSessionWatcher.isLinked`，
  且「重试」按钮**就地重建链路**（多数情况无需跳系统设置），1.5s 无效才显示「去设置」。

### 2. 新增两个独立的逐字输出开关
- `Settings.overlayWordByWord`（存盘键 `overlayWordByWord`，默认 true）：控制**悬浮窗**逐字/整行。
- `Settings.wordByWordEnabled`（存盘键 `wordByWord`，默认 true）：控制**歌词页**逐字/整行。
  两者相互独立，此前共用一个开关。
- 设置页新增两个 `SwitchRow`：「歌词页：逐字输出」/「悬浮窗：逐字输出」。
- 悬浮窗工具条新增就地切换按钮「逐字」（高亮=当前逐字），未锁定时可直接切换，无需进设置。
- 签名不变（SHA-256 `a948e972…08645`），可覆盖安装；versionCode 8 / versionName 1.4.1。

## v1.5.0 取词能力升级（参考开源项目 Replica0110/Lyrico 与 Lyrico-Plugins）

用户实测反馈现有取词效果不理想，指定参考 Lyrico 及其插件仓库。经比对其插件实现，
定位到两处关键差距并完成改造（架构与打分/回退链不变，只升级源内实现）：

### 1. 网易云：从 weapi 切到 eapi —— 匿名即可拿逐字歌词（最大改进）
- 新增 `util/EapiCrypto.kt`：eapi 加密（`sign = md5("nobody"+path+"use"+params+"md5forencrypt")`，
  AES-128-ECB/PKCS5Padding，固定密钥 `e82ckenh8dichen8`，密文转大写hex）。
  已用 Java/Python 独立实现交叉验证：签名与密文字节级一致。
- `lyric/source/NeteaseSource.kt` 重写：
  - 取词改用 `/eapi/song/lyric/v1`，`lv/tv/rv/yv` 全传 -1，**匿名**一次即可拿到
    `yrc`(逐字) / `lrc`(整行) / `tlyric`(译文) / `romalrc`(音译) 四件套。
    此前走 weapi，匿名常年被风控（-460/空），逐字 YRC 实际依赖扫码登录 Cookie 才能出现。
  - 搜索优先 eapi `/eapi/search/song/list/page`，失败回退公开 `/api/cloudsearch/pc`。
  - 登录态仍会带上（可解锁更多受限曲目），但不再是拿逐字的前置条件。
- `util/Http.kt` 新增 `postFormOrNull`（`application/x-www-form-urlencoded`）。

### 2. QQ 音乐：补齐取词参数
- `lyric/source/QqMusicSource.kt`：
  - `GetPlayLyricInfo` 的 param 补齐 `trans=1, roma=1, lyricType=1, crypt=0`。
    此前只传 `songMID` 时服务端不下发 `trans`(译文) 与 `qrc`(逐字)，只剩整行 lrc。
  - comm 公共包对齐 Lyrico 实测可用的一组：`ct=11/cv=1003006/tmeAppID=qqmusiclight`。
  - 搜索结果路径兼容三种返回位置，提高命中率；译文缺失时用 `roma` 音译兜底。
  - QQ 一直是免签名的 `u.y.qq.com/cgi-bin/musicu.fcg` 统一网关（与 Lyrico 同一思路），
    保留 `crypt!=0` 加密 QRC 时自动退回 LRC 的行为。

### 验证与兼容性
- 编译 + `apksigner verify` 通过，签名 SHA-256 `a948e972…08645` 不变，可覆盖安装。
- versionCode 9 / versionName 1.5.0。含v1.4.x 全部修复（播放检测解耦、两个逐字开关、TabBar 位置）。

---

## v1.5.1 歌词源修复（QQ 搜不到 / 网易云歌词为空）

v1.5.0 上线后用户实测反馈两个 bug：
> 「QQ音乐还是搜索不到歌词，网易云能显示搜索到，但是获取到的歌词为空，显示某某歌无歌词。」

本次不做猜测，**直接对两个平台的接口做矩阵化实测**（沙箱出口 IP，逐个参数组合验证），
定位到两个完全不同的根因。

### 1. 网易云：eapi 路由已整体下线 → fetch 恒为 null

实测记录（`music.163.com` 与 `interface.music.163.com` 双域名一致）：

| 接口 | 结果 |
|------|------|
| `/eapi/song/lyric/v1` | **404「接口未找到」** |
| `/eapi/song/lyric`、`/lyric/new`、`/lyric/detail`、`/lyric/web`、`/lyric/v2` | 全部 404 |
| `/eapi/search/song/list/page`、`/eapi/cloudsearch/pc`、`/eapi/search/song` | 全部 404 |
| `/api/song/lyric/v1` |200 ✅（`lrc` + `tlyric`） |
| `/api/song/lyric/v2` | 404 |
| `/api/search/get/web` | 200 ✅（候选带 `artists` + `duration`） |
| `/api/cloudsearch/pc` | 200 ✅（但 `artists`/`duration` 常为 null） |

**这正是用户现象的成因**：v1.5.0 里搜索走的是公开 `cloudsearch/pc`（能通，所以「能显示搜索到」），
取词走的是 eapi（404 → `fetch` 返回 null），于是 `LyricRepository` 把每个候选都标成
「《xxx》无歌词」。

修复（`lyric/source/NeteaseSource.kt` 重写）：
- 取词只用 `/api/song/lyric/v1?id=&lv=-1&tv=-1&rv=-1&yv=-1`，双域名依次回退。
- 搜索优先 `/api/search/get/web`（字段全，匹配打分靠 `artists`/`duration`），
  再退 `/api/cloudsearch/pc`。
- **删除 `util/EapiCrypto.kt`**（eapi 已死，留着是误导）。

### 2. 网易云歌词格式陷阱：YRC-JSON 行与 LRC 行混排

实测 `/api/song/lyric/v1` 的 `lrc.lyric`字段并**不是纯 LRC**，而是混排：

```
{"t":0,"c":[{"tx":"作词: "},{"tx":"周杰伦","li":"http://…"}]}
{"t":1000,"c":[{"tx":"作曲: "},{"tx":"周杰伦"}]}
[00:30.542]故事的小黄花
[00:34.165]从出生那年就飘着
…
```

- 若整份丢给 `YrcParser`（只认 `{` 开头的行），**正文 LRC 行会被全部丢弃**；
- 但这些 JSON 行经检查**没有词级时长**（`c[].t` 全部缺省），只是「作词/作曲/编曲/制作人」元信息。

因此新增判定与归一化：
- `hasWordLevelYrcJson()`：检查 `c` 数组内是否出现数值型 `"t"`。
  - **有** → 真逐字，整份走 `RawFormat.YRC`。
  - **无** → `normalizeYrcJson()` 把 JSON 行转成 `[mm:ss.xx]文本`，与普通 LRC 行合并后
    走 `RawFormat.LRC`。
- Python 等价实现对 3 首真实歌曲回归验证：周杰伦《晴天》恢复 **+11 行**、
  《晴天(深情版)》+2 行、《测试》+10 行，正文完整。

### 3. QQ 音乐：`search_id` 缺失导致稳定 2001

参数矩阵实测（`DoSearchForQQMusicDesktop` / `Lite` × ct=11/19 × 有无 `search_id`/`remoteplace`）：

| 参数组合 | 结果 |
|----------|------|
| 无 `search_id` | `code=2001`（参数无效），列表空 |
| 无 `remoteplace` | `code=2001` |
| 有 `search_id` + `remoteplace` | `code=0`，但列表**可能为空** |
| 换 `guid` / 缩短间隔重试 | 偶发成功 |

v1.5.0 只带了 `remoteplace`、**漏了 `search_id`**，所以稳定 2001 —— 这就是「QQ 搜不到」。

修复（`lyric/source/QqMusicSource.kt` 重写）：
- 补上 `search_id`（32 位大写 hex，每次请求重新生成）。
- 每次请求生成新的 `guid`（QQ 用它做设备指纹，固定值易被判异常）。
- **搜索失败自动重试 3 次**、间隔递增（1.2s / 2.4s）—— 实测 QQ 对匿名请求有
  **IP 级限流**：同一秒连发 4 次必被限流，冷却 2~3 分钟恢复。
- 取词通道实测**很稳**：`crypt=0` 返回明文 base64 LRC + `trans` + `roma`，
  4 首测试 3 首成功、不存在的曲子正确返回 `24001`。

### 4. 逐字能力现状（如实记录，未做夸大）
- **QQ 逐字**：`crypt=1` 的加密 QRC 用的是 QQ **自定义 S-box 的 3DES 变体**，
  标准 3DES 解出乱码（已实测确认）。逆向成本过高，本版不做，逐字退回整行 LRC。
- **网易云逐字**：匿名时 `yrc` 字段为 null，逐字实际依赖登录 Cookie。
- 逐字目前主要由**酷狗 KRC** 承担（`KugouSource` 匿名可用）。

### 5. 设置页登录区调整（回答用户「cookie 登录还需要保留吗」）

实测结论：**QQ 带真实 uin 的 Cookie 同样搜不到**（返回空列表），即 Cookie 不是搜索的前置条件。
因此：
- QQ 卡片从「未登录 / 已填入 Cookie」改为「**无需登录**」，输入框折叠进
  「仍然要填？」二级入口，并加「清除」按钮，文案如实说明实测结论。
- 网易云扫码登录**保留**：登录能解锁灰度/需授权曲目，覆盖率更高，
  但文案从「未登录（会拿不到逐字）」改为「**未登录（可用）**」，不再暗示是必需。

### 验证
- `./gradlew assembleRelease` 编译通过；`apksigner verify` 通过，
  签名 SHA-256仍为 `a948e972…08645`，**可覆盖安装**。
- versionCode 10 / versionName 1.5.1。
- 产物：`dist/AutoLyrics-v1.5.1-release.apk`。

---

## v1.6.0 移除 QQ 音乐 + 修复译文重叠 + 悬浮窗翻译开关

### 1. 彻底移除 QQ 音乐源

用户决定放弃 QQ 音乐（「算了算了，放弃了，把 QQ 音乐全部删掉吧」）。
v1.5.1 已实测确认 QQ 官方网关对匿名请求有 IP 级限流，稳定性不足。

删除范围（不只是源文件，是整条链路）：
- 删除 `lyric/source/QqMusicSource.kt`
- `LyricRepository.allSources` 去掉 `QqMusicSource`
- 删除 `Settings.qqGateway` 字段（读写 JSON 的两处一并删除）
- 删除设置页「QQ 音乐 Cookie」整张卡片
- 删除设置页「QQ 音乐网关地址（备用）」配置块
- `Settings.DEFAULT_ORDER`：`["qq","netease","kugou","lrclib"]` → `["netease","kugou","lrclib"]`

**老配置兼容**（关键）：`sourceOrder` / `enabledSources` 会从SharedPreferences 读回，
老用户存盘里仍有 `"qq"`，若不过滤歌词源页会出现幽灵条目。新增：

```kotlin
private val REMOVED_SOURCES = setOf("qq")   // 顶层常量

// load() 里对两处都做过滤
?.filterNot { it in REMOVED_SOURCES }
```

保留不动（与歌词源无关）：
- `HomeScreen` 里 `"com.tencent.qqmusic" -> "QQ音乐"`：这是 **MediaSession 播放源 App 名**映射，
  即「从 QQ 音乐 App 播放歌曲时显示来源名」，与取词无关，删了会导致来源显示异常。
- `QrcParser`：QRC 格式解析器，Kugou 之外可能还有源返回 QRC，保留无害。
- `Http.postJson`：网易云登录也在用类似形态。

### 2. 修复歌词页译文与原文重叠（用户截图 bug）

现象：歌词页里译文与原文**叠在同一位置**，互相穿插成一团乱码。

根因：`ui/screen/HomeScreen.kt` 的 `AppleLyricLine` 用的是 `Box` 布局，
内部依次放「原文 Text」+「Spacer」+「译文 Text」，但两个 `Text` 都带
`Modifier.fillMaxWidth()` —— **在 `Box` 里 `fillMaxWidth` 只约束宽度、不撑开高度**，
于是译文被绘制在原文的同一偏移上。`Spacer` 在 `Box` 里同样不产生纵向位移。

修复：改成 `Column`（纵向排列会按序占位）。
顺带优化：译文补`lineHeight = 17.sp`（原来 13.sp 字号配默认行高在中文下会挤）、
`Spacer` 高度 3.dp → 4.dp。

悬浮窗的 `OverlayLine` 本来就是 `Column`，无此问题，但顺手补了 `Spacer(2.dp)` 与
`lineHeight` 避免译文紧贴原文。

### 3. 新增「悬浮窗：显示译文」独立开关

原来 `showTranslation` 被歌词页和悬浮窗**共用**，用户无法单独控制悬浮窗。

- `Settings` 新增 `overlayTranslation: Boolean = false`（默认关：悬浮窗空间小）
- 存盘键 `overlayTrans`；`trans` 键保持不变（歌词页），**老用户配置不丢**
- `OverlayContent` 改读 `settings.overlayTranslation`
- 设置页两个开关都加了副标题说明：
  - 「歌词页：显示译文」— 原文下方以小字显示翻译行
  - 「悬浮窗：显示译文」— 悬浮窗空间小，默认关闭（与歌词页独立）

现在共 4 个独立显示开关：歌词页译文 / 歌词页逐字 / 悬浮窗译文 / 悬浮窗逐字。

### 验证
- 编译通过；`apksigner verify` 通过，签名 SHA-256 仍 `a948e972…08645`，**可覆盖安装**。
- 旧配置 `"qq"` 过滤逻辑已加，升级后歌词源页不会出现幽灵条目。
- versionCode 11 / versionName 1.6.0；产物 `dist/AutoLyrics-v1.6.0-release.apk`。

---

## v1.7.0 歌词页改版+ 逐字平滑擦除 + 暂停隐藏修复

### 1. 逐字歌词：从「一格一格跳」改成「按字时长平滑擦亮」

用户原话：「我希望我的逐字歌词不是一个字一个字亮起来的，而是有平滑过渡，
就比如说根据歌词获取到这个字的时间是1 秒钟，那么这个字就会从左至右花 1 秒钟时间亮起来」

**旧实现的病根**（`LyricText.kt`）：

```kotlin
val sung = positionMs >= word.startMs        // 布尔判断，只看是否越过起点
color = if (sung) highlightColor else dimColor
```

这是**开关**而非**渐变**。字在startMs 到达的瞬间整块变色，
`LyricWord.durationMs` 完全没用上——所以 1 秒时长的字和 30ms 的字视觉表现一样，都是"啪"一下跳变。

**新实现**（`ui/components/LyricText.kt` 重写）：

```
字内进度 p = (positionMs - word.startMs) / word.durationMs   → clamp 0..1
```

用 `SpanStyle(brush = Brush.horizontalGradient(...))` 给「正在唱的那一个字」单独
挂一个横向渐变，colorStop 按 `p` 推进，并留 6% 宽度的羽化带避免硬边锯齿：

```kotlin
i == curIndex -> withStyle(
    SpanStyle(
        brush = Brush.horizontalGradient(
            0f to highlightColor,
            (curProgress * 0.94f).coerceIn(0f, 1f) to highlightColor,
            (curProgress * 0.94f + 0.06f).coerceIn(0f, 1f) to dimColor,
            1f to dimColor,
        ),
        fontWeight = fontWeight,
    )
) { append(w.text) }
```

效果：1 秒时长的字，左半亮 → 右半暗 → 走满 1 秒才整个亮。
**位置直接来自播放进度**，不存在独立动画，所以暂停 / 拖进度条 / 跳歌都完全正确。

两个短路优化（避免无谓的每帧重组）：
- `allDone`（整行唱完）→ 直接整行纯高亮，不走渐变
- `notStarted`（还没开始）→ 直接整行纯暗色

**为什么不用 Canvas + 遮罩**：先尝试过 `drawWithContent` + `BlendMode.DstIn` 和
自建 `Canvas` + `getBoundingBox` 量像素宽度的方案，但都要处理
「亮色/暗色两层文字 + Offscreen 图层 + 布局失效重算」三重复杂度，
且 `SrcIn` 会覆盖整画布导致暗色底丢失。`SpanStyle.brush` 是官方 API，
一行搞定且不碰绘制管线——**方案B 优于方案 A 的典型例子**。

顺带修了 `LyricText` 逐字分支**没传 `fontWeight`** 的 bug（整行分支传了），
所以此前逐字模式下「已唱的字变粗」其实一直没生效。

### 2. 歌词页布局改版：专辑图只占一小块，歌词占主体

用户给了 Spotify 风格参考图，要求「专辑图片只占一小部分位置，歌词占大部分位置」。

**旧布局**（Apple Music 英雄式）：封面 `screenW * 0.50`（最大 196dp）居中大卡 +
下方居中歌名/歌手/来源，下面才是歌词。歌词被挤到下半屏。

**新布局**：
```
┌──────────────────────────────┐
│  [正在播放]              [重取] │  ← 顶栏不变
│ ┌────┐ Beautiful In White│
│ │封面│ Shane Filan        │  ← 56dp 小封面 + 右侧歌名/歌手/来源
│ └────┘ 网易云 · 逐字        │     整行一个 Row，高度只有 ~68dp
│轻触封面 → 只看歌词              │
├──────────────────────────────┤
│                              │
│  歌词区域（weight 1f，最大化） │  ← 拿到绝大部分空间
│                              │
├──────────────────────────────┤
│  ▶ ⏮ ⏭0:34 / 3:52│  ← 精简模式下保留迷你播放条
└──────────────────────────────┘
```

- 封面从最大 196dp 缩到 **56dp**，圆角 8dp
- 歌名/歌手从「居中 22sp」改为「左对齐 18sp」，与封面同行
- 来源标签压缩成 10sp 小字（`逐字` / `整行`）
- 歌词区 `weight(1f)` 不变，但因为顶部省下~130dp，实际可用高度大幅增加

**点击封面进精简模式**：隐藏封面/歌名/歌手/进度条，只留歌词 + 迷你播放条
（保留播放键，否则暂停了没法恢复）。右上角「显示全部 ✕」退出。
同时设置页也加了「精简模式（只显示歌词）」开关，两边互为入口。

### 3. 歌词页颜色与字号自定义

- `Settings` 新增 `inAppTextColor`（高亮色，存盘键 `inAppTextColor`，默认纯白）
- `Settings` 新增 `inAppFontSizeSp`（存盘键 `inAppFont`，范围 12..40sp，默认 21）
- `Settings` 新增 `inAppMinimal`（存盘键 `inAppMinimal`）
- 设置页新增「歌词页」分区：精简模式开关 + 字号滑块 + `ColorWheel` 调色盘 + 6 个快选色
- 与悬浮窗的配置**完全独立**，互不影响

按用户要求「颜色只调一个主色，暗色自动派生」：
```kotlin
val lyricColor = Color(settings.inAppTextColor)      // 已唱 / 逐字擦亮的亮色
val lyricDim = lyricColor.copy(alpha = 0.45f)          // 未唱色自动派生
```
非当前行也改用 `dimColor`（此前硬编码 `Color.White`），保证整页配色统一。
设置页文案已注明「未唱部分会自动按此色降低亮度，配色始终协调」。

### 4. 修复：歌曲暂停时桌面悬浮窗不消失

用户报告：「歌曲暂停的时候，桌面悬浮状格式不会消失」

**根因**（不是表面看到的那行）：
`OverlayController.attach()` 只 `collect` 了 `SettingsStore.settings`，
**完全没有监听播放状态**。所以暂停后窗口一直留着。

而 `OverlayContent` 里那句看起来像在处理这个的
```kotlin
if (settings.autoHideOnPause && !playing && state.track == null) return
```
有两个问题：
1. `state.track == null` 是**多余条件**——暂停时 track 依然存在（只是不播了），
   所以这个 `return` 永远不触发
2. 就算触发了，Compose `return` 只是让内容区不渲染，**Window 本身还在屏幕上**，
   用户看到的是「悬浮窗还在，只是空了」

**修复**（`ui/overlay/OverlayController.kt`）：把 `PlaybackMonitor.isPlaying`
纳入 `combine`，暂停时真正 `hide()` 窗口：

```kotlin
combine(SettingsStore.settings, PlaybackMonitor.isPlaying) { s, playing -> s to playing }
    .collect { (settings, playing) ->
        val permitted = settings.overlayEnabled && Permissions.overlayGranted(app)
        val shouldShow = permitted && (!settings.autoHideOnPause || playing)
        if (shouldShow && window == null) { /* 创建 */ }
        else if (!shouldShow && window != null) {
            val y = window?.currentY() ?: settings.overlayY
            window?.hide(); window = null
            // 只在「用户主动关闭」时记录位置；暂停导致的临时隐藏不覆盖用户摆放的位置
            if (permitted) SettingsStore.update { it.copy(overlayY = y) }
        }
    }
```

同时把 `OverlayContent` 里那行改成只处理「从未播放过」的空状态：
```kotlin
// 真正的「暂停时隐藏」由 OverlayController 负责
if (state.track == null) return
```

### 验证
- 编译通过；`apksigner verify` 通过，签名 SHA-256 仍 `a948e972…08645`，**可覆盖安装**
- 老配置兼容：三个新字段都有默认值，`optBoolean/optDouble/optInt` 读不到时回退默认
- versionCode 12 / versionName 1.7.0；产物 `dist/AutoLyrics-v1.7.0-release.apk`

---

## v1.12.0 build37：逐字高亮第二轮修复（踩坑记录）

### 两个 bug 的根因（同一条因果链）

**1. 整行一瞬间从左到右亮完**

build36 为修「高亮卡住不动」改用增量式推进，但基准**只在异常分支里更新**：

```kotlin
val deltaMs = (nowNanos - baseAt) / 1_000_000L
if (deltaMs in 0..MAX_FRAME_GAP_MS) {
    value += deltaMs          // 每帧累加「距时钟启动」的总时长
} else {
    baseAt = nowNanos         // ← 基准只在 else 更新！
}
```

于是 `deltaMs` 算的是「距时钟启动」而非「距上一帧」，
每帧把总经过时间整个累加 ⇒ **平方级增长**
第 n 帧累计 ≈ 16.7 × n(n+1)/2 毫秒，**0.17 秒就冲过一整秒的歌词**。

> **教训：写增量式时钟必须每帧无条件更新基准。**
> 写成「只在异常分支更新」立刻退化成平方级增长。
> 这类 bug 视觉上像「进度条飞快」，很容易误判成阈值或动画参数问题。

**2. 暂停整行变暗、恢复时整行全亮**

```kotlin
if (!active || !playing) return remember { mutableLongStateOf(0L) }
```

- 暂停返回常量 0 → `LyricText` 收到 `pos=0` → 命中 `pos < first.startMs`
  快路径 → 整行 dim 色（用户看到「所有歌词不高亮」）
- 恢复时 `produceState` 重建，`initialValue` 是全新采样的真实位置
  → 从 0 瞬间跳到当前进度（用户看到「整行全部亮起来」）

> 这两个症状其实是**同一条链**：平方级增长让进度几帧内冲出行尾，
> 命中 `pos >= last.startMs + last.durationMs` 快路径变成整行纯亮。

### 修法

1. `lastFrameAt` **每帧无条件更新**（正常/异常都更）
2. **暂停改为挂起协程**而非归零：
   ```kotlin
   if (!currentPlaying.value) {
       snapshotFlow { currentPlaying.value }.first { it }
       lastFrameAt = System.nanoTime()
       syncCounter = 0
       continue
   }
   ```
   用 `snapshotFlow` 而非 `while(!playing)` 空转——
   后者会让应用永远停在 60fps 帧回调里、无法进入 idle。
3. **新增周期性校准**：每 16 帧与真实播放位置比对，
   `abs(real - value) > 80ms` 才纠正（**必须双向**，
   只判 `real > value` 的话往回 seek 后会一直超前到冲出行尾、整行全亮）。

---

### 编译踩坑：`by` 委托缺 `getValue` 导入

本次编译失败的真实原因：

```kotlin
val currentPosition by rememberUpdatedState(positionMs)   // ❌ 编译失败
```

报错：
```
Type 'State<Function0<Long>>' has no method 'getValue(Nothing?, KProperty0<*>)',
so it cannot serve as a delegate.
```

`State` 接口**本身没有** `getValue` 方法，它在扩展函数
`androidx.compose.runtime.getValue` 里。**报错文本既不提 import 也不提 getValue**，
只看这句话完全想不到是缺导入。

连带效应：`snapshotFlow { currentPlaying }.first { it }` 报
「Cannot infer type / Not enough information to infer type argument for 'T'」——
这不是独立问题，是上一条的连锁反应。

**修法**：改用显式 `.value`，不依赖那个 import：

```kotlin
val currentPosition = rememberUpdatedState(positionMs)   // State<() -> Long>
if (!currentPlaying.value) { ... }
snapshotFlow { currentPlaying.value }.first { it }
val real = currentPosition.value()
```

> 全项目已有 `by rememberUpdatedState` 等用法的文件都正确导入了 `getValue`，
> 只有本次新增的两处漏了。改用显式 `.value` 后一劳永逸。

---

### CI 排查教训（本次花了 6 次往返）

**1. `check-runs` 的 annotations API 会返回 0，但 annotation 实际已生成。**
连续 6 次查询 `annotations_count` 都是 0，据此推断「脚本没走完诊断代码」——
**推断是错的**，`##[error]` 行一直都在日志里。

正确做法是下载完整日志（本机 Windows 沙箱可用）：

```bash
export GH_TOKEN=<PAT>
curl -sS -L --ssl-no-revoke -H "Authorization: Bearer $GH_TOKEN" \
  -o run_logs.zip "https://api.github.com/repos/dhy0302/AutoLyrics/actions/runs/<run_id>/logs"
```

`--ssl-no-revoke` **必须加**：本机会报
`CRYPT_E_REVOCATION_OFFLINE (0x80092013)`（吊销服务器不可达），
不加容易被误判成 token 无效。

**2. 时长判据必须跟本仓库历史成功构建比，不能拍脑袋。**
历史成功 = 147~186 秒，失败 = 54~82 秒。
我曾说「正常需 8~12 分钟」——这个基准是错的，导致往 CI 配置方向白排查好几轮。

**3. 连续加诊断层数不是好策略。**
连加 4 层诊断（行数报告、起止打点、原始尾部、`set +e`）都没解决问题，
因为真正的错误一直在日志里躺着。
**正确顺序**：查 step conclusion → 查运行时长 → **下载完整日志** → 再改代码。

**4. `concurrency.cancel-in-progress` 已改为 `false`。**
排查时需连续推送，`true` 会让后一次推送掐断前一次构建，
被掐断的 job 同样标记 failure 且日志不完整。

---

### Release 更新日志机制（v1.12.0 新增）

用户要求「以后每个版本推上去都写更新日志」。

- 新增 `CHANGELOG.md`，段落标题格式固定 `## v{版本名} · build{构建号}`
- 工作流新增 `id=changelog` 的「抽取更新日志」步骤：
  用 awk 按标题定位，截到下一个 `## ` 或 `---` 为止
- 关闭 `generate_release_notes`：它生成的是 commit 流水账，
  对使用者没意义，且会把合并进来的所有提交都算上
- 找不到对应段落时只发 `::warning::` **不失败**：
  文档漏写不该卡住用户下载

**本机验证时踩的坑**：去尾部空行的 awk 第一次写成 `for (i = n-1; i>=0; i--)`，
**倒序打印导致整个段落顺序颠倒**。凡是「重排/反序」类脚本，本机必须先跑一遍看实际输出。

---

## v1.16.0～v1.18.0（build55～build57）：三个歌词/封面缺陷的根因

这一段记 2026-10-04 一天内连发的三个版本。它们有个共同点：
**根因都是「读到的东西不完整，就当成完整结论用了」**。

### v1.16.0 build55：切歌后专辑封面永久空白

`MediaSessionWatcher.toSnapshot()` 用内容指纹（title/artist/album/duration/artUri）
做缓存判据。指纹里**没有封面位图本身**，于是播放器「先发歌词文本、后补封面」的
两步 `setMetadata` 时序下，第一步读到的空封面会被指纹钉死，
之后 uri 再也不变 → 永不重读 → 整首歌都显示默认图。

修法：在命中判据上加例外，而不是把封面数据并进指纹——
`!(cached.info.albumArt == null && hasArtKey)`。
`hasArtKey` 用 `metadata.containsKey(...)`，这是 O(1) 查询，
**不会触发位图反序列化**（`getBitmap()` 会）。

> 教训：指纹命中判据 ≠ 指纹相等。判据必须覆盖「所有会被独立修正的维度」，
> 否则中间状态会被固化成最终结论。

### v1.17.0 build56：歌词页流动背景暂停后突变

**变的是恢复播放那一刻，不是暂停时。** `timeSec` 来自
`rememberInfiniteTransition`，框架驱动、不受业务 `animationScale` 影响，
暂停期间照跑。原写法 `iTime = timeSec * animationScale` 只是把
消费者乘 0（画面倒回 t=0），恢复时 `animationScale` 回 1 → 时间瞬间跳到
「暂停时长之后」。

修法：改成自维护时间轴 `rememberFluidClock(running)`，
用 `withFrameNanos` 按帧累加，`LaunchedEffect(running)` 控制协程生死。

> 教训：**用乘法/标志位去「模拟」一个状态变化，只关掉了消费者，没拦住源头。**
> 框架驱动的动画没法真正暂停，得自己维护一条可停的时间轴。

### v1.18.0 build57：网易云取词失败被当成「没有歌词」

最严重的一个。`fetch(): RawLyric?` 里 `null` 有两种含义：
「确实没歌词」与「没查成」（风控/网络/结构异常）。上层一律记成前者，
**写进负缓存 3 天**，于是点「重取」也命中缓存，界面像是卡住了。
同段代码里「抛异常」被正确上报为失败，**只有「返回空值」这条路漏了**——
v1.12.7 修了搜索阶段的同一个问题，取词阶段一直漏着。

修法：新增 `FetchOutcome(lyric, failed)`，与 `SearchOutcome` 同一套范式；
失败不写负缓存，接上早已存在的退避重试。

> 教训：**同一个坑会在不同阶段各犯一次。** 引入这类结果类型时，
> 要grep 出所有阶段（search / fetch / refresh…），而不是只改眼下出错的那个。

### 本次两处编译踩坑

**1. 三个源里漏一个 import。** 给 `NeteaseSource` / `KugouSource` / `LrclibSource`
新增 `FetchOutcome` 后，漏了 `NeteaseSource` 的 import，CI 报 10 处
`Unresolved reference`。报错落在调用点（`FetchOutcome.none()`），
看不出是 import 缺失；三个文件长得几乎一样，视觉扫过去觉得「都加了」。
⇒ 已给 `scripts/check_kotlin.py` 增加跨文件 import 检查（见下）。

**2. 返回类型按「封装多干净」写而不是按「调用方需要」写。**
`rememberFluidClock` 声明成 `State<Float>` → `Unresolved reference 'floatValue'`，
因为 `floatValue` 是 `MutableFloatState` 的扩展属性，接口上没有这个成员。

### 版本号规则（用户定规，长期有效）

`versionName` 形如 `a.b.c`：**只能改 c**，c 无上限
（1.18.1 → 1.18.2 → … → 1.18.99 → 1.18.100 都合法，不要因为「看着太大」擅自进位）。
**b / a 未经用户明确要求一律不许动。**

2026-10-04 这一天连发 v1.16.0 / v1.17.0 / v1.18.0，三次都在动 b，
而实质全是修 bug —— 典型的「把补丁发布做成小版本」，已被用户纠正。
判断标准：只修 bug、只改实现细节 → 只加 c；新增用户可见功能 → 才考虑加 b，
且**必须先问用户**。

### 发版三步（缺一不可）

1. `CHANGELOG.md` 最顶部追加 `## v{版本名} · build{构建号}`
2. 同步更新 `README.md`（示例版本号、体积、渠道数、缓存规则）
3. 同步更新 `BUILD.md`（第一节产物表、包名/版本号那行、末尾版本记录段）

> 本文档在 v1.16.0 之前长期停在 v1.4.1 / v1.12.0 没人更新，
> 而工作流的 `paths-ignore` 挡掉了 `**/*.md`，改文档不会触发构建——
> 于是「文档过期」和「版本号不变导致复用 tag」两个问题叠在一起。
> v1.18.0 起把三步写进 README 发布清单，并在此处留提醒。

### 静态自查脚本

`scripts/check_kotlin.py`（**注意不在 git 仓库内**，只在
`D:/WorkBuddy/Auto Lyrics` 下，不随 commit 提交）。六类检查：
括号配平、局部函数前置引用、未使用 import、空字符字面量、
新增的**跨文件 import 缺失**。本项目已因此救场多次。

---

## v1.18.1 build58：占位图不是「取图失败」

用户报「v1.18.0 修了封面还是没好」，并**提供了截图**——一张灰底唱片图标。
这张截图是本次诊断的转折点。

### v1.18.0 错在哪

它假设「取图失败 → 返回 null → 加退避重试」。但实际是：
**成功读到了一张图，只是那张图是占位图**。于是
`if (bmp != null) { loaded = bmp; return }` 第一次就收工，
三次重试一次都没跑。方向错了，加多少重试都没用。

顺带查出v1.18.0 自己埋的三个 bug：

1. `COVER_RETRY_DELAYS_MS = [500, 2000, 5000]` 配`if (i == lastIndex) break`
   → 实际尝试时刻 0s / 0.5s / 2.5s，**最后一次等待被 break 掉**，
   总覆盖 2.5 秒，而注释写的是「约 7.5 秒」。
2. KDoc 写「Coil 失败不缓存，所以不用动缓存策略」—— 对 null 成立，
   **对占位图不成立**（那是次成功加载，会被缓存）。
3. `PlaybackMonitor.applySession` 的防抖判据用**上一首**的封面状态，
   切歌那一瞬会把新歌的空封面当抖动吞掉，导致 UI 侧连 uri 都收不到。

### 走过的弯路：别用图像特征判占位图

量过那张占位图 —— 平均饱和度 0.000、100% 纯灰阶、只有 166 种颜色，
看着非常好判别。**但它会误杀真实封面**：

| 图 | 平均饱和度 | 低饱和占比 |32x32 边缘能量 |
| --- | --- | --- | --- |
| 占位图 | 0.000 | 100% | 58.7 |
| 本项目 logo-192 | 0.048 | 93.8% | 13.4 |
| 本项目 logo-512 | 0.048 | 94.1% | 13.3 |

项目自己的 logo 就已经 93.8% 低饱和；而占位图的边缘能量**反而更高**
（几何图形 vs 平滑 logo），与直觉相反。黑胶类封面会被杀掉。

⇒ **结论：不要靠猜图像内容。** 改用「观察封面有没有变」——
只依赖「同一首歌封面换了」这个事实，对任何封面都安全。

### 修法

`rememberAlbumCover` 拆两阶段：取到第一张图**不收工**，继续观察
20 秒、每 2 秒重问一次（`fresh=true` 绕过 Coil 缓存），
发现指纹变了就换。指纹用 5×5 采样 + FNV 哈希，不逐像素
（`Bitmap.equals()` 在 Android 上是逐像素，320px 一次 10 万次读）。

差分测试验证过：占位图 1.5 秒后变真图的场景下，
v1.18.0 在 t=0 就结束并停在占位图，v1.18.1 在 t=2 秒换上真图。

### 自查脚本抓到的真bug

FNV offset basis 我先写成 `-3750763034362895579L`（以为超了 2^63 要转有符号），
**实际 1469598103934665603 < 2^63，根本不用转**。写错会让哈希退化。
`python -c` 一算就发现。**推算出来的常数一定要验证，不能看着像就写。**

---

## v1.18.2 build59：通知栏歌词僵死 —— 根因是「没写前台服务」

用户报：「切到别的 App 后，通知栏歌词停在退出歌词页时的那一句，
永久不变，切歌也不变；但打开 App 进歌词页就正常，
**点通知栏的『开启桌面歌词』也会恢复正常**。」

### 决定性证据

我完整读了 `OverlayActionReceiver`（被那个按钮点到的接收器）：
它全文只做 `SettingsStore.update { overlayEnabled = true }`，
**没有任何一行 `NotifyLyrics.attach()`**。

也就是说它根本没重启任何协程。那通知凭什么恢复更新？
只有一种解释：**点通知按钮这个动作本身唤醒了进程**
（已死则拉起，被冻结则解冻）。恢复的是「能执行代码」，
不是「重启了某个 job」。

### 于是根因浮出水面

`AndroidManifest.xml` 里当时**只有 `MediaNotificationListener`**，
而它**不是前台服务** —— 它只在系统连接时回调一次
`onListenerConnected`，之后不提供任何持续运行保证。

没有前台服务 ⇒ 退到后台后进程随时被系统冻结 ⇒
所有后台协程（`PlaybackMonitor` 的 ticker、`LyricEngine` 的 index 计算、
`NotifyLyrics` 的 collect）**全部停止执行**。

### 为什么静态读代码永远找不到它

因为**没有任何一行代码是错的**。`combine`、去重键、`indexAt`
全部正确 —— 它们只是「根本没机会被调用」。

我在纯逻辑层反复排查了很多轮（combine conflation、去重键漏字段、
`smooth()` 整数取整停滞……），**全部是方向性错误**。
纯逻辑差分测试对「进程被冻结」这类问题天然无效。

> **教训：「一边正常一边僵死」+「某个 UI 动作能恢复」，
> 优先怀疑执行环境（进程生命周期/调度），而不是数据流。**
> 差分测试只能验证「代码在被执行时算得对不对」，
> 而这个 bug 的代码压根没被执行。

### 修法

新增 `LyricsForegroundService`：

- `App.onCreate` 调用 `ensureStarted`（放这里而不是 `onResume`——
  后台被杀后重启时根本没有 Activity 回调）
- `foregroundServiceType="dataSync"`（targetSdk 34 下必填）
- `START_STICKY`：被回收后系统自动重拉，链路自愈
- **与歌词通知共用同一个通知 ID** —— 前台服务必须挂通知，
  而多一条会污染通知栏；共用后占位通知被歌词内容直接覆盖
- `NotifyLyrics.cancel()` 在前台服务运行时**改成占位内容而非移除**，
  否则服务失去前台身份 → 进程被回收 → 老问题立刻复发

### 副作用（已知且可接受）

用户手动清掉那条通知 = 停掉前台服务，歌词在后台停止更新。
重新打开 App 会自动恢复。

---

## v1.18.3 · build60 —— 通知栏三个控件+ 点通知跳歌词页

### 背景与问题

用户反馈三件事：点通知栏要跳**歌词页**（不是「打开 App」）、
通知栏缺一个桌面歌词锁定开关、以及**关掉通知栏歌词后整条通知消失**。

第三条是真正的设计缺陷。v1.18.2 为了修「后台歌词僵死」引入了前台服务，
`NotifyLyrics.cancel()` 被改成「改成占位内容而非移除」，
于是「关掉通知栏歌词」的实际表现变成了：

> 通知还在，但只剩一句「通知栏歌词已关闭」，**没有歌名、没有歌手、没有按钮**。

也就是说这个开关实际管的不是「显不显示歌词」，而是「关掉整个通知栏遥控器」——
与用户预期完全相反。用户常常就是想要遥控器，只是不想歌词占屏。

### 修法

**1. 通知恒在，只换标题。** `collect` 里三条分支合并成两种「形态」：

| 形态 | 触发条件 | 标题 |
| --- | --- | --- |
| 歌词形态 | 歌词开着且有内容 | 当前这句歌词 |
| 歌名形态 | 歌词关着 / 暂停无歌 / 歌词取不到 | 歌名 - 歌手 |

两形态共用 [baseBuilder]（含 contentIntent）与 [addOverlayActions]（三个按钮），
**唯一差别就是标题**。`cancel()` 因此只在 `detach()` 与服务未运行时才被调用。

**2. 点通知跳歌词页。** 加 `setContentIntent` →
`MainActivity` + `EXTRA_GOTO_LYRICS`。

关键坑：`launchMode="singleTop"` 意味着 App 已在后台时走 **`onNewIntent`**
而不是 `onCreate`。只在 `onCreate` 读 intent 的话，第二次及以后点通知全无效 ——
而且 `setIntent(intent)` 不能省，否则 Activity 重建后读回旧值。

跳转时调 `LyricEngine.refresh(force = true)`：onResume 里那个
`retryIfUnresolved()` 用的是 `force=false`（走负缓存），对「熄屏自动补取」正确，
但对「用户主动点进来」不对，他要的是立刻看到歌词。

**3. 第三个按钮：锁定/解锁。** 新增 `ACTION_TOGGLE_LOCK`。

requestCode 用了 1005/1006 —— `PendingIntent` 靠 `(requestCode, action)`
判定是否同一个，`FLAG_UPDATE_CURRENT` 会**就地替换** Extras，
复用同一组码会让几个按钮互相覆盖（v1.8.1 已踩过一次）。
点通知的 contentIntent 用 `REQUEST_CONTENT = 2000`，与广播区间隔开。

### 踩坑

**跨形态去重键。** 新增 `lastTrackOnlyText`，且两种形态**互斥清零**：
`post` 里清 `lastTrackOnlyText`，`postTrackOnly` 里清 `lastText`。
否则「歌词文本恰好等于歌名」时，形态切换那次通知会被误判成「没变」而漏发。

**`private companion object` 不能与裸const 共存。** Kotlin 一个类只能有一个
companion object，所以 `REQUEST_CONTENT` 写成普通 `private const val`。

**Edit 工具写CRLF。** `OverlayActionReceiver.kt` 改完后 125 行变 CRLF，
git diff 会把34 行改动显示成整文件重写。已在提交前批量转回 LF。

**heredoc 里的中文可能损坏。** 用 `python - <<'PYEOF'` 写 CHANGELOG 时，
`锁定之后` 的「定」被写成了两个损坏字节（`\ufffd\ufffd`）。
写完必须 `w.count('\ufffd')` 复查一遍 —— 字节体检只看 CRLF/NUL，
查不出这种 UTF-8 层面的损坏。

### 已知行为

- 关掉「通知栏歌词」后，通知仍占一条（前台服务需要它），
  显示「歌名 - 歌手」，副标题为「通知栏歌词已关闭」。
- 用户在系统设置里手动清掉这条通知 = 停掉前台服务（v1.18.2 起就有）。

---

## v1.18.4 · build61 —— 真正的根因：协程静默死亡

### 为什么 v1.18.2 没修好

v1.18.2 的判断是「App 退到后台被系统冻结」，方向错了。
前台服务确实保住了进程，但**进程活着不等于协程活着**。

`PlaybackMonitor.startTicker` 的循环体里跑着
`MediaSessionWatcher.best()` → `MediaController.getMetadata()`，
这是**跨进程 Binder 调用**。播放器进程被系统回收时，
这次调用抛 `DeadObjectException`。

而那个 `while` 循环 **没有任何 try**。异常穿透循环 → 协程终止。

### 关键：协程死亡是**静默**的

不崩溃、不打日志、不通知任何人。只是从那一刻起
`positionMs` 与 `track` 永远不再变化。

子协程挂在 `AppScope`（SupervisorJob）下，死了父协程**不会**重新拉起它。
所以**一次异常 = 永久失效**，直到进程重启。

### 为什么被误导

症状与「进程被冻结」**完全一致**：歌词停住、切歌无反应、打开 App 就恢复。

而「打开 App 就恢复」这条**对两种根因都成立**，因此根本无法区分：

| 根因 | 为什么打开 App 会好 |
| --- | --- |
| 进程被冻结 | 解冻了进程 |
| 协程已死 | `MainActivity.onResume` 手动调了一次 `PlaybackMonitor.update()` |

v1.18.2 就是被这张表的第二行骗了。

⇒ **教训：判据必须能区分候选根因，否则就是在盲猜。**
这就是v1.18.4 加健康面板的动机——把「协程是否活着」变成可观测量。

### 修法

1. **异常防护**：`runCatching { update(mode) }` 包住单轮。
   单次 Binder 失败不再致命，下一轮继续。
   同时把异常摘要记进 `lastError` 供诊断。
2. **换代退出**：`_tickerGate` 引用比对代替 `cancel()`。
   旧循环下一次醒来时发现令牌已换就主动退出 ——
   比 cancel 稳，因为 cancel 会在任意挂起点抛异常。
3. **看门狗**：前台服务每 3 秒查一次心跳，
   停滞超 5 秒就`ensureStarted()` + `restartTicker()`。

心跳阈值取 5 秒的依据：空闲档轮询间隔 1000ms，
连续 5 轮没动才判死，排除偶发调度延迟；最坏恢复时间 5 秒。

### 新增文件

`ui/screen/HealthPanel.kt` —— 歌词源页顶部的健康面板。
每秒刷新，显示轮询状态 / 前台服务 / 会话抓取 / 重启次数 / 最近异常。

面板里有一个易踩的 Compose 细节：
**每秒 +1 的 State 必须在组合期被读一次**，否则没有订阅者、
重组不发生，年龄会永远停在打开面板那一秒。

### 诊断面板怎么用

---

## v1.18.5 build62：后台流水线挂在主线程上（第三次修这个问题）

### 用户报的症状（关键）

> 通知栏歌词会放当前这一句，然后再切换到下一句，然后就不切换，
> 一直停留那一句，即使后面音乐软件切换了歌曲，它也不会变动。
> 重新打开软件，进入到软件内部的**任意**页面，
> 或者从通知栏打开桌面歌词，通知栏开始正常滚动更新了。

前两次的结论都被这两条信息推翻了。

### 根因：主线程消息队列在后台无可见窗口时被限制处理时机

**「先切一句再卡住」**说明数据流**开始是工作的**——
积压的消息被处理掉一两条，之后队列不再被处理。

**「任意**页面都恢复」说明与歌词页特有逻辑无关——
只要出现任何可见界面，进程可见性提升，队列就恢复。

而后台流水线的三个协程有两个跑在 `AppScope.main`
（`Dispatchers.Main.immediate`）上：

| 环节 | 原线程 |
| --- | --- |
| `PlaybackMonitor` 播放轮询 | `Dispatchers.IO`（内层）|
| `LyricEngine` 歌词行下标计算 | **`Dispatchers.Main`** |
| `NotifyLyrics` 通知组装 | **`Dispatchers.Main`** |
| `LyricsForegroundService` 看门狗 | **`Dispatchers.Main`** |

### 为什么 v1.18.4 的看门狗 完全无效

**自愈机制与被自愈对象在同一根线程上。**

v1.18.4 加了看门狗（每 3 秒查心跳、停滞就重启），逻辑一行没错，
但它跑在 `AppScope.main` 上。主线程被限制时，看门狗自己也没被执行。

⇒ **不是判定逻辑写错了，是它一次都没跑起来。**
这是本项目至今最值得记住的一条教训。

### 改动清单

1. `LyricEngine` 下标协程 → `Dispatchers.Default`
   （纯计算无阻塞；不用 IO，避免占用取词请求的线程池）
2. `NotifyLyrics` 通知组装 collect → `AppScope.io`
3. 前台服务看门狗 → `AppScope.io`
4. `PlaybackMonitor` 档位 collect → 随调用方改`AppScope.io`
   （原先外层 collect 在主线程、内层 `launch(Dispatchers.IO)`，
   看似分层合理，实际档位变化时仍依赖主线程）
5. `lyricPosition`：`mutableLongStateOf` → `@Volatile Long`
6. 看门狗新增第② 级检查，判定方式从 `indexRunning` 改为**心跳停滞**
7. 诊断面板扩到三级（新增「③ 通知渲染」）
8. `LyricEngine.start()` 拆成 `startFetchPipeline` + `startIndexLoop`

`OverlayController` **保持主线程**——它操作 `WindowManager`，必须如此。

### 顺带修掉的一个协程泄漏

早期版本让「重启下标协程」直接调 `start()`，
于是每次自愈都会**额外挂一条取词协程**。
取词要发网络请求 ⇒ 看门狗每救一次火就多泄漏一个协程。
拆成两条独立入口后，重启只重建下标协程。

### `lyricPosition` 为什么从 Compose State 改成 volatile

它的两个消费点（`OverlayContent.kt`、`HomeScreen.kt`）都是
`remember { { LyricEngine.lyricPositionSample() } }` 的**lambda 形式**，
**不订阅 State**，只在逐帧动画里按需读一次。

既然没有订阅者，Compose 快照就是纯开销；
更要紧的是 **后台线程写 Compose 快照并不安全**。

### 诊断面板：每级都要有自己的心跳

v1.18.4 的面板只观测第 ① 级，
于是「第 ① 正常、第 ② 已停」显示为**一片正常**——
比没有面板更误导，因为它给了虚假的安心感。

| 显示 | 含义 |
| --- | --- |
| ① 播放进度「已停止」 | 播放轮询死了 |
| ② 歌词行下标「已停止」 | 下标计算停了 |
| ③ 通知渲染「已停止」 | 通知层停了，不在数据流 |
| 任一级「最近异常」有内容 | 直接写明被什么打断 |

### 通用教训（勿回退）

  - **后台流水线一律不要放主线程**。哪怕「只是读几个 State」——
    后台可见性变化时它们随时可能停摆，
    且症状与协程抛异常**完全一样**，极易误判方向。
  - **自愈机制必须与被自愈对象在不同线程上。**
  - **判据要能区分候选根因。**「打开 App 就恢复」同时符合
    「进程被冻结」「协程已死」「主线程被节流」三者，区分力为零。
  - **重启一个协程不要连带重启整条链路**（见上面的协程泄漏）。

本项目为此还建了技能 `silent-background-failure`，
以及本文件里的 `pure-logic-diff-test` 都不适用于这类问题——
纯逻辑差分测试对「代码压根没被执行」天然无效，会全绿通过。

---

## v1.18.6 build63：停止猜测，改为取证（探针版）

### 这一版没有修复，只有诊断

**前三次修复全部落空**，且是同一个错误重复了三次：

| 版本 | 我判断的根因 | 结果 |
| --- | --- | --- |
| v1.18.2 | 进程被冻结 | 错 |
| v1.18.4 | 协程静默死亡 | 错 |
| v1.18.5 | 主线程被节流 | 错 |

三次错在同一个地方：**每一轮都在验证「这段代码有没有写错」**。
用户这次给出的线索把问题的性质整个变了——

> **在前几天以前的版本的通知栏歌词就不会这样子出这个问题。**

这不是「新 bug」，是**回归（regression）**。回归意味着
**这段代码本来就是对的，是别的东西变了**。
于是继续在代码里找错的推理方向，从第一秒起就是错的。

### 教训：用户说「以前没有」时，先怀疑自己的方向

这类线索出现得比想象中晚——因为它不是症状描述，
而是**关于历史的事实陈述**，容易被当成抱怨的附带说明略过。

判断一个症状描述是「新 bug」还是「回归」的信号：

  - 「最近变差了」「之前好好的」→ 回归，直接上 `git bisect`
  - 描述了具体行为、时机、边界 → 当新 bug 处理

代价是三轮无效发版。**回归 bug 的第一反应应该是查历史，不是查代码。**

### 埋点：Trace.kt 的三条设计取舍

| 取舍 | 选择 | 原因 |
| --- | --- | --- |
| 写Logcat 还是写文件 | **写文件** | bug 恰好发生在「用户切到别的 App」时，那正是 Logcat 被冲掉的时候 |
| 记录每一轮还是只记变化 | **只记变化** | 100ms 一轮写 4000 行，什么都看不清 |
| 有无「距上一条间隔」 | **有** | 停摆不用推理，间隔拉长一眼可见 |
| 缓冲方式 | **4000 行环形** | 探针本身不能变成新 bug |

### 五路埋点各自回答什么

| tag | 回答 |
| --- | --- |
| `ticker` | 进度轮询协程还活着吗 |
| `upd` | `best()` 拿到会话了，还是走了 FALLBACK |
| `idx` | 歌词行下标还在推进吗 |
| `ncollect` / `npost` | 通知层收到帧吗，是真发了还是被去重挡掉 |
| `lifecycle` | onResume / onPause / onStop 的确切时刻 |
| `service` / `watchdog` | 前台服务生死，看门狗还在转吗 |

**`ncollect` 与 `npost` 必须分开**：v1.18.5 的去重逻辑
（文本未变 + 开关未变就跳过）本来是正确优化，
但它会把「收到帧但故意不更新」和「没收到帧」显示成同一个结果。
不分开的探针会给出误导性的绿灯。

### 头号嫌疑：controllers 被清空

`MediaSessionWatcher.refresh()` 在 `getActiveSessions()` 返回空时会
**清空 controllers**。一旦这一步被误触发：

```
controllers 被清空 → best() 返回 null → update() 走 FALLBACK
→ 拿不到真实播放进度 → 歌词永远停在同一句 → 连切歌都不变
```

这条链与症状**完全吻合**，所以单独加了
`hasAnySession()` / `describeSessions()` 两个方法专门盯它。
但**没有提前改它**——探针的意义正是先确认，而不是先猜。

### 导出入口

排查页新增一行：实时行数 + 刷新 / 清空 / 导出。
走系统分享（`ACTION_SEND`）而不是写 Downloads，
因为不需要存储权限，用户也能顺手贴出来。

写盘读盘用 `remember(readKey)` 缓存而非每次重组都读——
最多 4000 行，重组时读盘没道理；但清空后必须能看到新行数，
所以给一个显式的重读开关。

### 通用教训（补充上一节的「勿回退」清单）

  - **用户描述里夹带的历史线索，权重不低于症状本身。**
    「前几天以前没这问题」比「切歌也不变」信息量更大。
  - **探针要能区分「被去重挡掉」和「压根没收到」。**
    否则会造出一个虚假的绿灯，比没有探针更糟。
  - **怀疑代码写错之前，先花两分钟查一下git 历史。**

---

## v1.18.7 build64：删掉通知栏歌词，开关搬进悬浮窗

### 决策：功能被删，而不是继续修

「切到别的 App 后通知栏歌词停住」这个 bug，从 v1.14 前后开始，
为它连发 v1.18.2 / 1.18.4 / 1.18.5 三版修复，三次判断全错，
v1.18.6 又埋了一整版探针仍未定位。

用户的最终选择是**把这个功能删掉**。于是问题从「修不好」变成「不存在」。

**这不是技术上的最优解**——如果那个 bug 将来影响了别的功能，
根因还在那里。但对用户来说功能本身没有不可替代的价值，
继续为一个次要功能消耗发版是更大的浪费。

### 删了什么

  - 通知栏的歌词渲染（`NotifyLyrics.post` 整个方法）
  - 设置页的「通知栏歌词」开关
  - `Settings.notificationEnabled` 与持久化项 `notify`
  - 两个广播 action（`TOGGLE_TRANSPARENT_BG` / `TOGGLE_LOCK`）
  - 两个去重键（`lastText` / `lastTrackOnlyText`）与 `sameSwitches`

### 没删什么（以及为什么）

**通知本身必须保留。** 它是 `LyricsForegroundService` 的前台身份载体：
撤掉 → 服务失去前台通知 → 进程被回收 → **桌面歌词也一起停**。
一个次要功能的删除，会连带砍掉主要功能的保活依托。

同理，`jobRunning`（诊断面板第三级）也保留：
它观测的已经不是歌词，而是「通知是否在更新」，
而这件事直接决定前台服务能不能保住。

### 开关搬进悬浮窗：为什么必须新开一个窗口

用户要求把「透明」与「锁定」两个开关从通知栏移进悬浮窗。
迁移本身简单，但**锁定这个开关有个硬约束**：

锁定是通过 `WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE` 实现的，
而它**整窗生效**——Android 没有「窗口内某区域可点、其余区域穿透」的能力。
所以主窗一旦锁定，标题行里的「已锁」按钮就点不到了。

以前解锁入口放通知栏，正是被这一点逼的。
现在通知栏只剩一个按钮，于是必须**另开一个独立小窗**：
右上角、`WRAP_CONTENT`、一个两字的「解锁」。

| 窗口 | 宽度 | 锁定时是否可点 |
| --- | --- | --- |
| 主窗 | MATCH_PARENT | **否**（NOT_TOUCHABLE） |
| 解锁小窗 | WRAP_CONTENT | 是（不带该 flag） |

两者共用同一个 `OverlayLifecycleOwner`（一个宿主可挂多个 ComposeView）。

### 共享数据源，所以不可能「冲突」

两个开关在悬浮窗和设置页读写的是**同一份** `SettingsStore` 数据，
不存在两套状态。所以用户担心的「不要与设置页冲突」在结构上不会发生。

### `hide()` 里的顺序坑

两个窗口共用生命周期宿主，所以 `hide()` 必须是：

```
先摘解锁小窗 → 再摘主窗 → 最后 lifecycleOwner.onDestroy()
```

`onDestroy()` 会把宿主生命周期推到底。
若主窗先销毁而小窗还挂在 `WindowManager` 上，
小窗的下一帧就会撞 `Cannot access a disposed compose view`。

###顺带的性能优化

通知的 collect 原本订阅 `LyricEngine.state`，而 `State` 里带着
`lyric / attempts / message` —— 歌词每换一行整个 State 就不同，
combine 随之发射。改成 `state.map { it.track }` 后，
唤醒源只剩「换歌」与「悬浮窗开关变化」。

每首歌的 `notify()` 次数从数十次降到 1~2 次。

⚠️ 这也解释了为什么这个 bug **难查**：症状是「通知不更新」，
而 `ncollect` 每秒被唤醒十几次却次次都被去重挡掉——
日志里全是「收到帧」，看起来完全正常。

### 自查脚本补了一条规则

本次改写踩到：`combine(A, B) { a, b -> Pair(a, b) }` 产出 2 元，
下游却写 `collect { (state, playing, settings) -> }` 解构 3 个。
编译期必然报错，但项目原有的检查没抓到——

`check_map_destructuring` 只管 `map` / `let`，不管 `collect`，
也不比对元数。已新增 `check_collect_destructuring`。

⚠️ 脚本在**仓库外的父目录**（`D:/WorkBuddy/Auto Lyrics/scripts/`），
不受版本控制。新增规则不会随仓库分发。

### 教训

  - **功能删掉前，先查清它承担了哪些隐式职责。**
    通知看起来只是「显示歌词」，实际还背着前台服务保活与遥控器两项。
    删之前必须顺着 `NOTIFICATION_ID` 查一遍引用。
  - **「减少按钮」类需求会连带触发架构改动。**
    三个按钮减到一个，意味着另外两个要自己解决「锁定后点不到」的问题，
    而这个问题的解法（独立窗口）在原需求里完全看不出来。

---

## v1.18.8 build65：开关放错层级导致永久不可达

### 病灶：开关被放进了「可能整体不渲染」的容器

v1.18.7 把「透明」「锁定」从通知栏搬进悬浮窗，放在**标题行**。
而标题行的渲染条件是 `if (!transparent)`。于是：

```
开启桌面歌词 → 标题行渲染 → 用户点「透明」→ 标题行整个消失
            → 「透明」「锁定」两个开关一起没了
            → 窗口在透明模式下彻底失控，且无法自救
```

用户原话：「当打开桌面歌词并且点击透明后，
锁定那两个字的按钮看不见也点击不了」。

### 修法与那条通用规律

挪到**工具栏**。工具栏的渲染条件是 `if (!locked)`，与 `transparent` 无关
（透明时只是 `barBg` 变成 `Color.Transparent`）。

> **通用规律：开关类控件必须放在「永远渲染」的那一层。**

这类 bug 的特征是**单向不可逆**：一旦触发就再也回不来，
且用户没有任何自救手段。

⇒ 审 UI 时，对每个开关反问一句：
**「它所在的容器，在哪些状态下不渲染？那些状态下用户怎么回来？」**

### 解锁小窗被撤销

v1.18.7 加过一个「解锁小窗」（屏幕右上角两字的「解锁」按钮），
本版按用户要求删掉 —— 理由是它本身就是用户不要的那类东西：
「锁定时其他什么按钮都不要显示」。

解锁入口因此只剩设置页一处，副标题已写明「解锁需回到此页面」。

### 锁定时的布局收尾

上下padding 从 8dp 归零。那 8dp 是给标题行与工具栏腾的位置，
两者隐藏后就变成一截空隙，让歌词显得比窗口中心略低—— 看着像没对齐。

⚠️ 这类「容器隐藏但留白保留」的副作用不会报错、也不会崩，
只是看起来不对。改布局时凡是条件性隐藏某块内容，
要连带检查它占用的间距是否也该归零。

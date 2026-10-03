# AutoLyrics — 安卓自动歌词（悬浮窗 / 通知栏 / App 内）

根据系统媒体状态（MediaSession）自动识别当前播放的歌曲，从国内歌词源抓取并实时高亮显示。
以 Spotify 为主，其他任何暴露 MediaSession 的播放器（Apple Music、YouTube Music、网易云、QQ音乐、系统本地播放器）同样适用。

---

## ⬇️ 下载安装

前往 **[Releases 页](https://github.com/dhy0302/AutoLyrics/releases)** 下载最新版本的 APK。

- **系统要求**：Android 8.0（API 26）及以上
- **架构**：通用单包，无 native 库，全平台可装
- **首次安装**：需在系统设置中允许「安装未知来源应用」
- **首次启动**：按 App 内引导依次开启**通知读取**、**悬浮窗**、**通知权限**三项

> 仓库采用 GitHub Actions 自动构建：推送代码即自动编译并发布 Release，无需手动打包。
> 若云端构建失败或需排查，可在 [Actions 页面](https://github.com/dhy0302/AutoLyrics/actions) 查看日志。

---

## 一、能做什么

| 能力 | 说明 |
| --- | --- |
| 播放状态抓取 | `MediaSessionManager` 读取所有活跃 MediaSession：标题 / 歌手 / 专辑 / 时长 / 实时进度 / 播放状态 / 专辑封面 |
| 通知兜底 | 个别 App 不暴露 MediaSession 时，从媒体通知解析歌名歌手（进度靠墙钟估算） |
| 多源聚合 | **QQ音乐 → 网易云 → 酷狗 → Lrclib**，逐个尝试，失败自动回退 |
| 逐字歌词 | QQ 走 QRC、网易走 YRC，拿到就做**卡拉 OK 式逐字染色**；拿不到自动退回整行 LRC |
| 中文匹配 | 标题归一化 + 编辑距离 + token 重合 + 时长校验，加权 ≥0.55 才取词 |
| 译文 | 网易 `tlyric` / QQ `trans` 自动合并双行 |
| 三种显示 | 桌面悬浮窗、通知栏、App 内歌词页，可同时开 |
| 播放控制 | 进度条拖动 seek、上一首 / 播放暂停 / 下一首，走标准 TransportControls |
| 歌词交互 | 可自由上下滑动；点击任意歌词行跳转到对应时间 |
| 流体背景 | 专辑封面大模糊 + 缓慢旋转 + 呼吸缩放；高亮色从封面取主色 |
| 精度可选 | 三档刷新精度（省电 / 标准 / 极致），切换即时生效 |
| 本地缓存 | 命中缓存 30 天，未命中（负缓存）3 天，避免重复请求 |

---

## 二、跑起来要做的三件事

1. **通知读取权限**（必需，Android 强制要求）
   系统设置 → 通知 → 通知使用权 → 勾选 **AutoLyrics**。
   没有它，`MediaSessionManager.getActiveSessions()` 会直接抛 `SecurityException`。
2. **悬浮窗权限**（桌面歌词）
   设置 → 应用 → 显示在其他应用上层。
3. **通知权限**（通知栏歌词）
   Android 13+ 运行时授权 `POST_NOTIFICATIONS`。

App 首页会逐个检测并给出跳转入口；打开权限后返回 App（触发 `onResume`）即生效。

> 建议同时关掉本 App 的电池优化，否则后台容易被杀。

---

## 三、目录结构

```
app/src/main/java/com/yuanbao/autolyrics/
├── App.kt                          初始化：设置 → 缓存 → 引擎 → 轮询
├── data/Model.kt                   TrackInfo / Lyric / LyricLine / LyricWord / PrecisionMode
├── media/
│   ├── MediaNotificationListener   通知监听服务（权限载体 + 兜底解析 + 拉起 UI）
│   ├── MediaSessionWatcher         MediaSession 抓取、选择"当前在播"、传输控制
│   └── PlaybackMonitor             对外唯一状态源：曲目/进度/封面/控制能力
├── lyric/
│   ├── LyricEngine.kt              曲目变化 → 取词；位置变化 → 算当前行
│   ├── LyricRepository.kt          多源聚合、打分选优、本地缓存
│   ├── LyricSource.kt              Candidate / RawLyric / SourceAttempt 契约
│   ├── parser/
│   │   ├── LyricParser.kt          普通 LRC（多时间戳 / offset / 增强标签 / 译文合并）
│   │   ├── QrcParser.kt            QQ 逐字 QRC
│   │   └── YrcParser.kt            网易逐字 YRC
│   └── source/                     QqMusic / Netease / Kugou / Lrclib
├── ui/
│   ├── MainActivity.kt             Compose 三 Tab 外壳 + 权限引导
│   ├── Theme.kt                    深色沉浸式主题
│   ├── components/
│   │   ├── LyricText.kt            逐字卡拉 OK 染色渲染
│   │   ├── AlbumBackdrop.kt        封面获取 / 取主色 / 流体背景
│   │   └── PlayerBar.kt            进度条 + 三键播放控制
│   ├── overlay/                    悬浮窗 WindowManager + ComposeView
│   ├── notify/NotifyLyrics.kt      通知栏歌词（去重后更新）
│   └── screen/                     HomeScreen / DebugScreen / SettingsScreen
└── util/                           Http / TextMatch / BitmapBlur / SettingsStore / Permissions
```

数据流：

```
MediaSession ─┐
Notification ─┴→ PlaybackMonitor ─→ LyricEngine ─┬→ 悬浮窗 Compose
                                                 ├→ 通知栏 Notification
                                                 └→ App 内歌词页
```

---

## 四、歌词源细节

| 源 | 搜索 | 取词 | 备注 |
| --- | --- | --- | --- |
| **QQ音乐**（第 1） | `c.y.qq.com/soso/fcgi-bin/client_search_cp` | ① 逐字：`qqmusic/fcgi-bin/lyric_download.fcg`（base64 的 QRC）<br>② 退回：`lyric/fcgi-bin/fcg_query_lyric_new.fcg?nobase64=1` | **必须带 `Referer: https://y.qq.com/`** |
| **网易云**（第 2） | `/api/search/get/web`（3 个备用域名） | ① 逐字：`yrc` 字段<br>② 退回：`lrc` + `tlyric` 译文 | 偶发 -460（风控），多域名依次重试 |
| **酷狗**（第 3） | `krcs.kugou.com/search`（带 duration 秒） | `lyrics.kugou.com/download`（base64 的 LRC） | 时长是匹配关键信号 |
| **Lrclib**（兜底） | `lrclib.net/api/get` + `/api/search` | 同上 | 海外公开库，无需登录，中文覆盖率低 |

**回退判定**：某个源满足以下任一条件就跳到下一个源——
搜索无结果 / 最佳得分 < 0.55 / 取词失败 / 解析后行数 < 2（多为「纯音乐，请欣赏」占位）。

**打分公式**：`0.55 × 标题相似度 + 0.25 × 歌手相似度 + 0.20 × 时长一致度`
时长差 ≤2s 记满分，≤5s 记 0.85，>60s 视为 0。Spotify 与国内平台时长基本一致，这个信号很能排除同名不同版本。
归一化会去掉括号内容、`feat.`、remaster / live / official audio 等后缀、标点与全角符号。

**QRC 时间口径**：`<字起始, 字时长>` 的「字起始」相对行首还是绝对时间，两种版本都出现过。
代码用「行时长」做判据——两种解释分别算出本行结束时间，取更贴近 `[行起始+行时长]` 的那个。

---

## 五、App 内歌词页

### 播放器控制条
- 进度条显示 `当前时间 / 总时长`，拖动时**自持数值**（屏蔽 200ms 轮询推来的位置，避免手指被打回去），松手才真正 `seekTo`。
- 上一首 / 播放暂停 / 下一首：走 `TransportControls`。不支持的动作按钮置灰，进度条不可用时提示「该播放器不支持拖动进度」。
- 控制对象就是 `MediaSessionWatcher.best()` 选中的会话，Spotify 与本地播放器走同一套代码。

### 歌词列表
- 可自由上下滑动。手动拖动时**立即停止自动跟随**，手指离开后 **3 秒内也不跟随**（避免刚滑走就被拉回），3 秒后恢复。
- 点击任意行 → `seekTo(行时间 + 全局偏移 + 歌词自带偏移)`。
- 逐字歌词只对**当前行**做卡拉 OK 染色，非当前行按整行淡显，避免整屏都在变色。

### 流体背景
- 封面优先取 `METADATA_KEY_ALBUM_ART` / `METADATA_KEY_ART` 的 Bitmap；拿不到时用 `ART_URI` 交给 Coil 加载（部分播放器只给 URI）。
- 模糊算法：降采样到 40px → 两趟可分离盒式模糊 → 放大铺满。**不依赖 `RenderEffect` / `Modifier.blur`**，minSdk 26 也能用。
- 旋转时四角会露空，所以整体放大到 1.25~1.45 倍；叠两层遮罩（纯黑压暗 + 上下渐变）保证任何封面下歌词都可读。
- 高亮色用 `Palette` 从封面取主色；暂停时动画自动停止，省电。设置里可整项关闭。

### 精度档位
| 档位 | 轮询 | 说明 |
| --- | --- | --- |
| 流畅省电 | 200ms | 够用且最省电，适合整行歌词 |
| 标准（默认） | 100ms + 时间插值 | 逐字级准确 |
| 极致精准 | 50ms + 插值 + 平滑滤波 | 逐字最贴合，耗电较高 |

三档都用 `PlaybackState.position + (now - lastPositionUpdateTime)` 做时间插值。
PRECISE 额外加一阶低通滤波抑制抖动，并对「跳变 >1.5s」（seek / 切歌）直接采用新值，避免平滑拖尾。

---

## 六、已知限制与坑

1. **地域/风控**：国内接口偶发需要 Cookie 或返回 -460。代码已内置多域名 / 多路径回退，仍失败会自动跳下一源。海外网络下 QQ / 酷狗可能超时，此时 Lrclib 兜底。可在「歌词源」页手动搜索逐个验证。
2. **不要高频请求**：默认只在切歌时请求一次并缓存。请勿把轮询改成逐秒请求，容易被封 IP。
3. **悬浮窗锁定后无法点击解锁**（`FLAG_NOT_TOUCHABLE` 会屏蔽全部触摸），请在 App 设置里解锁。
4. **未使用前台服务**：悬浮窗挂在 `NotificationListenerService` 进程里。若在国内 ROM 上被杀，可自行加一个 `foregroundServiceType="mediaPlayback"` 的前台服务并把 `OverlayController` 迁过去（上架 Play 需填 FGS 声明）。
5. **通知兜底没有精确进度**：用「通知发布时间 + 墙钟」估算，暂停再播会有偏差；且无法控制播放器。
6. **逐字覆盖率**：QQ / 网易的逐字歌词并非每首歌都有，没有时自动退回整行，UI 上会标注「逐字 / 整行」。

---

## 七、编译

- Android Studio Ladybug+ ，JDK 17，Gradle 8.7（wrapper 已配置）。
- `minSdk 26 / targetSdk 34`，Kotlin 2.0.21 + Compose BOM 2024.10.01。
- 打开本目录同步 Gradle 即可运行；命令行：`./gradlew :app:assembleDebug`。

---

## 八、合规提醒

歌词版权归各平台与权利人所有。本项目仅做**个人设备上的临时展示**，缓存文件写在 `cacheDir`（系统清理即失效）。
请勿用于分发、转售，或搭建公开歌词 API。

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

package org.eu.dinghongyu.autolyrics.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.eu.dinghongyu.autolyrics.data.PrecisionMode
import org.eu.dinghongyu.autolyrics.lyric.LyricEngine
import org.eu.dinghongyu.autolyrics.lyric.LyricRepository
import org.eu.dinghongyu.autolyrics.media.MediaSessionWatcher
import org.eu.dinghongyu.autolyrics.ui.components.ColorWheel
import org.eu.dinghongyu.autolyrics.util.SettingsStore

/**
 * 各设置二级页的正文实现。
 *
 * 单独一个文件，因为 [SettingsScreen] 只负责「目录 → 二级页」的路由，
 * 而具体设置项有 30 多条，混在一起会让目录部分被淹没。
 *
 * 共同约定：
 *  - 每页开头是 [GroupHeader]，下方跟 [SettingCard]
 *  - 行间用 [SettingDivider]，不给每行套卡片
 *  - 页面级操作（清缓存等）单独一个整宽按钮，放在末尾
 */

/* ==================================================================== *
 *  显示方式
 * ==================================================================== */

@Composable
fun DisplayPage() {
    val s by SettingsStore.settings.collectAsState()
    Column {
        GroupHeader("显示位置")
        SettingCard {
            SwitchRow(
                "桌面悬浮窗歌词",
                s.overlayEnabled,
                subtitle = "在所有App 上方浮一层歌词，可拖动",
            ) { v -> SettingsStore.update { it.copy(overlayEnabled = v) } }
        }
        // v1.18.7：「通知栏歌词」开关已删除。
        //
        // 通知栏不再显示歌词（功能与开关一起删掉了），
        // 但**通知本身仍然存在** —— 它是前台服务的身份载体，
        // 也是桌面歌词的开启/关闭入口。撤掉它等于自断保活。
        //
        // 字段 `notificationEnabled` 与持久化项 `notify` 一并删除，
        // 与 v1.12.6 删 `inAppEnabled` 同一做法。
        // v1.12.6：原先这里还有「App 内歌词页」开关，现已删除。
        //
        // App 内歌词页是本App 的主界面（底部「播放」标签页），属于基本功能，
        // 不该被关掉。而且那个开关**本来就没起过作用**：
        // `inAppEnabled` 只在设置页自己和调试页摘要里被读取，
        // 没有任何逻辑拿它去控制歌词页的显示 —— 关掉它，歌词页照样照常显示。
        // 留着只会被误以为「关掉就没歌词了」，属于误导。
        //
        // 因此字段与持久化项（inapp）一并删除，不再读写。
        HintText(
            "关闭后 App 仍会监听播放信息并在通知栏显示当前歌曲，" +
                "只是不在桌面上浮动歌词。"
        )
    }
}

/* ==================================================================== *
 *  歌词页
 * ==================================================================== */

/**
 * 歌词页的全部设置。也被 [org.eu.dinghongyu.autolyrics.ui.screen.HomeScreen]
 * 右上角的滑块按钮直接调用（所以它必须是 public 且不依赖任何导航）。
 */
@Composable
fun LyricPageSettings() {
    val s by SettingsStore.settings.collectAsState()
    Column {
        GroupHeader("外观")
        SettingCard {
            SliderRow(
                label = "歌词字号",
                valueLabel = "${s.inAppFontSizeSp.toInt()} sp",
                value = s.inAppFontSizeSp,
                valueRange = 12f..40f,
            ) { v -> SettingsStore.update { st -> st.copy(inAppFontSizeSp = v) } }
            SettingDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    "歌词颜色",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "只调一个主色，未唱部分会自动按它降低亮度",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorWheel(
                        initial = s.inAppTextColor,
                        onConfirm = { argb -> SettingsStore.updateNow { st -> st.copy(inAppTextColor = argb) } },
                        onCancel = {},
                        size = 150.dp,
                    )
                    Spacer(Modifier.width(16.dp))
                    QuickColorRow(
                        current = s.inAppTextColor,
                        onPick = { argb -> SettingsStore.updateNow { st -> st.copy(inAppTextColor = argb) } },
                    )
                }
            }
            SettingDivider()
            SwitchRow(
                "保持屏幕常亮",
                s.keepScreenOn,
                subtitle = "停留在歌词页时不自动熄屏；离开歌词页自动恢复系统设置",
            ) { v -> SettingsStore.update { it.copy(keepScreenOn = v) } }
            SettingDivider()
            SwitchRow(
                "精简模式",
                s.inAppMinimal,
                subtitle = "隐藏封面、歌名与进度条，只留歌词（也可直接点封面切换）",
            ) { v -> SettingsStore.update { it.copy(inAppMinimal = v) } }
        }

        GroupHeader("歌词内容")
        SettingCard {
            SwitchRow(
                "逐字染色",
                s.wordByWordEnabled,
                subtitle = "拿到逐字歌词时按字擦亮；关闭则整行高亮",
            ) { v -> SettingsStore.update { it.copy(wordByWordEnabled = v) } }
            SettingDivider()
            SwitchRow(
                "显示译文",
                s.showTranslation,
                subtitle = "在原文下方以小字显示翻译行",
            ) { v -> SettingsStore.update { it.copy(showTranslation = v) } }
        }

        GroupHeader("桌面悬浮窗")
        SettingCard {
            // v1.8.3。归在这一组而不是「歌词内容」里，是因为它描述的是
            // 歌词页**对悬浮窗的影响**，用户找的时候是冲着"悬浮窗"去的。
            SwitchRow(
                "在歌词页时隐藏悬浮窗",
                s.hideOverlayInLyricsPage,
                subtitle = "普通与精简模式都算：看歌词时桌面不再飘同一份内容，离开后自动回来",
            ) { v -> SettingsStore.update { it.copy(hideOverlayInLyricsPage = v) } }
        }

        GroupHeader("背景")
        SettingCard {
            SwitchRow(
                "流动背景",
                s.fluidBackground,
                subtitle = "封面主色驱动的动态色域（Android 13+ 用着色器，更流畅）；关闭则为静态磨砂封面",
            ) { v -> SettingsStore.update { it.copy(fluidBackground = v) } }
            SettingDivider()
            SwitchRow(
                "暂停时冻结背景动画",
                s.freezeBackdropOnPause,
                subtitle = "只听歌不看屏时停止背景运算，可省下 GPU 与电量",
            ) { v -> SettingsStore.update { it.copy(freezeBackdropOnPause = v) } }
        }

        GroupHeader("当前生效")
        SettingCard {
            InfoRow("已唱 / 逐字擦亮色", colorLabel(Color(s.inAppTextColor), s.inAppTextColor))
            SettingDivider()
            InfoRow("未唱部分", colorLabel(Color(s.inAppTextColor).copy(alpha = 0.45f), s.inAppTextColor))
        }
    }
}

/** 把 ARGB 显示成 #AARRGGBB 文本。 */
private fun colorLabel(c: Color, argb: Int): String =
    "#%06X".format(argb and 0xFFFFFF)

/* ==================================================================== *
 *  悬浮窗
 * ==================================================================== */

@Composable
fun OverlaySettings() {
    val s by SettingsStore.settings.collectAsState()
    Column {
        GroupHeader("文字")
        SettingCard {
            SliderRow(
                label = "字号",
                valueLabel = "${s.fontSizeSp.toInt()} sp",
                value = s.fontSizeSp,
                valueRange = 12f..30f,
            ) { v -> SettingsStore.update { it.copy(fontSizeSp = v) } }
            SettingDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    "字体颜色",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorWheel(
                        initial = s.overlayTextColor,
                        onConfirm = { argb -> SettingsStore.updateNow { it.copy(overlayTextColor = argb) } },
                        onCancel = {},
                        size = 150.dp,
                    )
                    Spacer(Modifier.width(16.dp))
                    QuickColorRow(
                        current = s.overlayTextColor,
                        onPick = { argb -> SettingsStore.updateNow { it.copy(overlayTextColor = argb) } },
                    )
                }
            }
        }

        GroupHeader("显示内容")
        SettingCard {
            OptionGroup(
                options = listOf(
                    "仅当前句" to "current",
                    "当前句 + 下一句" to "current_next",
                ),
                selected = s.overlayLineMode,
                onSelect = { v -> SettingsStore.update { it.copy(overlayLineMode = v) } },
            )
            SettingDivider()
            SwitchRow(
                "显示译文",
                s.overlayTranslation,
                subtitle = "悬浮窗空间小，默认关闭（与歌词页独立）",
            ) { v -> SettingsStore.update { it.copy(overlayTranslation = v) } }
            SettingDivider()
            SwitchRow(
                "逐字染色",
                s.overlayWordByWord,
                subtitle = "开启后按字擦亮；关闭则整行高亮（与歌词页独立）",
            ) { v -> SettingsStore.update { it.copy(overlayWordByWord = v) } }
        }

        GroupHeader("行为")
        SettingCard {
            SwitchRow(
                "锁定位置",
                s.overlayLocked,
                // v1.18.8：解锁入口只剩这里了。
                //
                // 原本有三个（设置页 / 悬浮窗标题行 / 右上角解锁小窗），
                // 但用户要求「锁定时屏幕上除歌词外什么都不显示」——
                // 标题行与工具栏在锁定时都隐藏（且主窗整窗点击穿透，
                // 本来也点不到），右上角小窗也被撤掉了。
                //
                // 于是一句话把这个取舍说清楚：锁了就得回设置页解。
                subtitle = "锁定后只显示歌词、不可拖动、点击穿透；解锁需回到此页面",
            ) { v -> SettingsStore.update { it.copy(overlayLocked = v) } }
            SettingDivider()
            SwitchRow(
                "透明背景",
                s.overlayTransparentBg,
                subtitle = "不渲染底框，纯文字叠加在任意画面上；悬浮窗标题行也能切",
            ) { v -> SettingsStore.update { it.copy(overlayTransparentBg = v) } }
            SettingDivider()
            SwitchRow(
                "暂停时自动隐藏",
                s.autoHideOnPause,
                subtitle = "歌曲暂停或停止时收起悬浮窗",
            ) { v -> SettingsStore.update { it.copy(autoHideOnPause = v) } }
        }
    }
}

/* ==================================================================== *
 *  歌词源
 * ==================================================================== */

@Composable
fun SourceSettings() {
    val s by SettingsStore.settings.collectAsState()
    Column {
        GroupHeader("启用与顺序")
        HintText("从上到下依次尝试，前一个拿不到就自动回退到下一个。")
        SettingCard {
            s.sourceOrder.forEachIndexed { i, sourceId ->
                if (i > 0) SettingDivider()
                SourceRow(
                    sourceId = sourceId,
                    enabled = sourceId in s.enabledSources,
                    canMoveUp = i > 0,
                    canMoveDown = i < s.sourceOrder.lastIndex,
                    onToggle = { on ->
                        SettingsStore.update { st ->
                            st.copy(
                                enabledSources = if (on) {
                                    st.enabledSources + sourceId
                                } else {
                                    st.enabledSources - sourceId
                                },
                            )
                        }
                    },
                    onMove = { delta ->
                        SettingsStore.update { st ->
                            st.copy(sourceOrder = st.sourceOrder.move(i, i + delta))
                        }
                    },
                )
            }
        }
        HintText("至少保留一个源，全部禁用将拿不到任何歌词。")
    }
}

@Composable
private fun SourceRow(
    sourceId: String,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!enabled) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = LyricRepository.sourceName(sourceId),
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            )
            Text(
                text = if (enabled) "已启用" else "已禁用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        // 排序箭头：两个 28dp 触控目标，但视觉上只有细线
        OrderArrow(up = true, enabled = canMoveUp) { onMove(-1) }
        Spacer(Modifier.width(4.dp))
        OrderArrow(up = false, enabled = canMoveDown) { onMove(1) }
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = ivorySwitchColors(),
        )
    }
}

@Composable
private fun OrderArrow(up: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (up) "↑" else "↓",
            fontSize = 14.sp,
            color = tint,
        )
    }
}

/* ==================================================================== *
 *  监听范围
 * ==================================================================== */

@Composable
fun BlockedAppsPage(permTick: Int) {
    val s by SettingsStore.settings.collectAsState()
    var refreshKey by remember { mutableIntStateOf(0) }
    val detected = remember(permTick, refreshKey) {
        MediaSessionWatcher.snapshots().map { it.pkg }.distinct()
    }

    Column {
        GroupHeader("不监听的应用")
        HintText("关掉某个应用后，它播放时不会再抓取信息，也不会出歌词。")

        if (detected.isEmpty()) {
            SettingCard {
                Text(
                    "暂未检测到任何播放中的应用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            SettingCard {
                detected.forEachIndexed { i, pkg ->
                    if (i > 0) SettingDivider()
                    val blocked = pkg in s.blockedPackages
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                SettingsStore.update { st ->
                                    st.copy(
                                        blockedPackages = if (blocked) {
                                            st.blockedPackages - pkg
                                        } else {
                                            st.blockedPackages + pkg
                                        },
                                    )
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = friendlyAppName(pkg),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (blocked) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = pkg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = !blocked,
                            onCheckedChange = { on ->
                                SettingsStore.update { st ->
                                    st.copy(
                                        blockedPackages = if (on) {
                                            st.blockedPackages - pkg
                                        } else {
                                            st.blockedPackages + pkg
                                        },
                                    )
                                }
                            },
                            colors = ivorySwitchColors(),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        SecondaryButton("重新检测播放中的应用") { refreshKey++ }
    }
}

private fun friendlyAppName(pkg: String): String = when (pkg) {
    "com.spotify.music" -> "Spotify"
    "com.apple.android.music" -> "Apple Music"
    "com.google.android.apps.youtube.music" -> "YouTube Music"
    "com.netease.cloudmusic" -> "网易云音乐"
    "com.tencent.qqmusic" -> "QQ 音乐"
    "com.kugou.android", "com.kugou.android.lite" -> "酷狗音乐"
    "cn.kuwo.player" -> "酷我音乐"
    "com.tencent.qqmusiccar" -> "QQ 音乐（车机）"
    "com.music.163.mobile" -> "网易云音乐（旧版）"
    else -> pkg.substringAfterLast('.')
}

/* ==================================================================== *
 *  取词引擎
 * ==================================================================== */

@Composable
fun EngineSettings() {
    val s by SettingsStore.settings.collectAsState()
    Column {
        GroupHeader("时间精度")
        SettingCard {
            OptionGroup(
                options = PrecisionMode.entries.map { it.label to it.name },
                selected = s.precisionMode.name,
                onSelect = { name ->
                    PrecisionMode.entries.firstOrNull { it.name == name }?.let { mode ->
                        SettingsStore.update { it.copy(precisionMode = mode) }
                    }
                },
            )
        }
        HintText(s.precisionMode.desc)

        GroupHeader("时间校准")
        SettingCard {
            SliderRow(
                label = "全局偏移",
                valueLabel = "${s.globalOffsetMs} ms",
                value = s.globalOffsetMs.toFloat(),
                valueRange = -3000f..3000f,
                steps = 59,
            ) { v -> SettingsStore.update { st -> st.copy(globalOffsetMs = v.toLong()) } }
        }
        HintText("歌词比歌曲快/慢时用它微调。正数=歌词延后出现。")

        GroupHeader("维护")
        SettingCard {
            // 版本号在 v1.11.0 迁到了「关于」页，这里不再重复。
            // 这一分组只剩一个动作，所以去掉了原先分隔它与版本行的 SettingDivider。
            SecondaryButton("清除缓存并重新获取当前歌词") {
                LyricEngine.refresh(force = true)
            }
        }
        HintText(
            "缓存以「歌手 - 歌名」为键。切歌太快或歌词刚更新过时，" +
                "手动刷新一次能解决大部分「歌词不对」的问题。"
        )
    }
}

/* ==================================================================== *
 *  小组件
 * ==================================================================== */

/**
 * 「标签 — 值」一行。
 *
 * internal 而非 private：关于页的「应用版本」行是同一套视觉，
 * 复制一份只会让两处样式日后分叉。
 *
 * v1.13.6：新增可选的 [trailing] 插槽（行内最右端）。
 * 默认空 —— 全项目若干处调用都只传两个参数，行为完全不变。
 * 关于页的「应用版本」用它挂「检测更新」入口。
 */
@Composable
internal fun InfoRow(
    label: String,
    value: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        trailing?.let {
            Spacer(Modifier.width(12.dp))
            it()
        }
    }
}

/**
 * 次要按钮：透明底+ 细描边，而不是 M3 默认那块实心彩色圆角矩形。
 * 这是把「AI 味」从按钮上去掉的关键——默认 Button 的容器色在深色主题下
 * 永远是一块突兀的色块。
 */
@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 把列表里的元素从 [from] 挪到 [to]，越界时原样返回。 */
internal fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices) return this
    val mutable = toMutableList()
    val item = mutable.removeAt(from)
    mutable.add(to, item)
    return mutable
}

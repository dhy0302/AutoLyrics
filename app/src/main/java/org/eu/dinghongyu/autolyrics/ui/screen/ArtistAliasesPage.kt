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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.eu.dinghongyu.autolyrics.lyric.ArtistAliases
import org.eu.dinghongyu.autolyrics.util.SettingsStore
import org.eu.dinghongyu.autolyrics.util.TextMatch

/**
 * v1.15.0：歌手别名管理页。
 *
 * 同一个歌手在不同平台的元数据里名字不同（酷狗叫「Eric周兴哲」、
 * 网易云叫「周兴哲」），用播报名去搜会扑空。这里让用户自己补上
 * 「TA 在别的平台叫什么」，由 [ArtistAliases] 在检索时展开成额外变体。
 *
 * ## 一条重要的边界：别名只用于「搜候选」
 *
 * 别名**不参与打分**—— [LyricRepository.score] 永远拿播报原名与
 * 候选歌手名比对。所以：
 *  - 别名填对了：多几种写法去搜，命中率提升；
 *  - 别名填错了：只是白发几次请求，不会导致选错歌。
 *
 * 这也是为什么这一页不提供「别名的别名」或优先级排序——
 * 机制简单才不会出难以排查的错歌问题。
 */
@Composable
fun ArtistAliasesPage() {
    val s by SettingsStore.settings.collectAsState()
    var editing by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var adding by remember { mutableStateOf(false) }

    // 按写入顺序展示；Map 本身无序，先按 key 排一下避免每次重组顺序乱跳
    val entries = remember(s.artistAliases) {
        s.artistAliases.toList().sortedBy { it.first }
    }

    Column {
        GroupHeader("歌手别名")
        HintText(
            "同一个歌手在不同平台的名字不一样时，用播报的名字搜不到歌词。" +
                "在这里补上 TA 在别的平台的叫法，取词时会多搜几遍。",
        )

        SettingCard {
            NavigationRow(
                title = "添加别名",
                subtitle = "播报名 → 该歌手的其他叫法",
                icon = null,
                onClick = { adding = true },
            )
        }

        if (entries.isEmpty()) {
            SettingCard {
                Text(
                    "还没有添加别名。内置表里的歌手无需重复添加。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            SettingCard {
                entries.forEachIndexed { i, entry ->
                    if (i > 0) SettingDivider()
                    AliasRow(
                        name = entry.key,
                        aliases = entry.value,
                        onClick = { editing = entry.key to entry.value },
                    )
                }
            }
        }

        // 内置表只读展示：让用户知道「哪些不用自己填」，
        // 也让填错的人意识到内置值未必正确。
        val builtIn = ArtistAliases.builtInForDisplay()
        if (builtIn.isNotEmpty()) {
            GroupHeader("内置别名")
            HintText("内置表由应用维护，是你填的条目之外的补充，两者都会用于检索。")
            SettingCard {
                // 刻意不用 `forEachIndexed { i, (name, aliases) -> }` 的解构写法：
                // 在这个 lambda 里对 Map.Entry 做解构会让 Kotlin 报
                // `component1() is ambiguous` + `Unresolved reference 'forEachIndexed'`。
                // 取分量写就完全没问题。
                builtIn.entries.forEachIndexed { i, entry ->
                    if (i > 0) SettingDivider()
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = entry.key,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = entry.value.joinToString("、"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        AliasEditorDialog(
            initialName = "",
            initialAliases = emptyList(),
            onDismiss = { adding = false },
            onConfirm = { name, aliases ->
                SettingsStore.update { st ->
                    st.copy(
                        artistAliases = st.artistAliases + (name to aliases),
                    )
                }
                adding = false
            },
        )
    }

    editing?.let { (name, aliases) ->
        AliasEditorDialog(
            initialName = name,
            initialAliases = aliases,
            onDismiss = { editing = null },
            onConfirm = { newName, newAliases ->
                SettingsStore.update { st ->
                    // 改名 = 删掉旧的再加新的，否则改名会留下一个僵尸条目
                    val next = st.artistAliases - name
                    st.copy(artistAliases = next + (newName to newAliases))
                }
                editing = null
            },
            onDelete = {
                SettingsStore.update { st ->
                    st.copy(artistAliases = st.artistAliases - name)
                }
                editing = null
            },
        )
    }
}

@Composable
private fun AliasRow(
    name: String,
    aliases: List<String>,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = aliases.joinToString("、"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Text(
            text = "编辑",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 别名编辑弹窗。
 *
 * ## 键要用归一化形式存
 *
 * 存进 `Settings.artistAliases` 的键必须经 [TextMatch.normalize] 处理，
 * 与 [ArtistAliases] 查表时用的键**完全一致**。
 * 否则会出现「填了却查不到」——最隐蔽的一种失效：
 * 用户看到自己填的别名在那儿，却完全不生效，且没有任何报错。
 */
@Composable
private fun AliasEditorDialog(
    initialName: String,
    initialAliases: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, aliases: List<String>) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var aliasesText by remember {
        mutableStateOf(initialAliases.joinToString(","))
    }

    // 别名按中英文逗号都切分：用户大概率会随手打中文逗号，
    // 而 `split(",")` 不会切「，」——那会存进去一个带逗号的怪别名。
    val parsedAliases = aliasesText
        .split(',', '，', '、')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    val canConfirm = name.isNotBlank() && parsedAliases.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialName.isBlank()) "添加歌手别名" else "编辑歌手别名") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("播报里的歌手名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = aliasesText,
                    onValueChange = { aliasesText = it },
                    label = { Text("其他平台的叫法") },
                    placeholder = { Text("用逗号分隔，可填多个") },
                    supportingText = {
                        Text(
                            if (parsedAliases.isEmpty()) "至少填一个"
                            else "将额外搜索：${parsedAliases.joinToString("、")}",
                        )
                    },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                HintText("别名只用于「多搜几遍」，不会影响选歌判断，所以填错不会导致选错歌。")
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    // 键归一化，与 ArtistAliases.keyOf 完全一致
                    val key = TextMatch.normalize(name.trim(), isArtist = true)
                    if (key.isNotBlank() && parsedAliases.isNotEmpty()) {
                        onConfirm(key, parsedAliases)
                    }
                },
            ) {
                Text("保存", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("删除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

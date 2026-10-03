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

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.eu.dinghongyu.autolyrics.R

/**
 * 「关于」页（v1.11.0）。
 *
 * ## 这一页存在的理由
 * 用户从 v1.10.x 升上来时第一个疑问是「装的是哪一版」——之前版本号藏在
 * 「取词引擎 → 维护」里，要点两层才看得到。把它提到一级目录的「关于」下，
 * 副标题直接显示版本号，不用点进去也能确认。
 *
 * 视觉上没有另起炉灶：GroupHeader / SettingCard / NavigationRow / InfoRow
 * 全部复用现有组件，配色只取MaterialTheme.colorScheme，跟其他设置页天然一致。
 */

/** 项目仓库地址。 */
private const val REPO_URL = "https://github.com/dhy0302/AutoLyrics/"

/** QQ 群号。 */
private const val QQ_GROUP = "925271317"

/** 联系邮箱。 */
private const val EMAIL = "dhy_0302@foxmail.com"

/**
 * 读安装包里的版本号，显示为 `versionName(versionCode)`。
 *
 * 例：`1.12.0(29)`。
 *
 * ## 为什么要带 versionCode
 * [android.content.pm.PackageInfo.versionCode] 是 Android 判断「新版本」的依据
 * （安装时系统靠它决定能否覆盖升级），但它在 UI 上默认不可见——而我们发布时
 * 恰恰是靠它区分每次构建的（tag 形如 `v1.12.0-build29`）。把两者一起显示，
 * 用户报问题时能直接对上包，对我们排查也有用。
 *
 * 不用 BuildConfig.VERSION_NAME：AGP 8 默认不生成 BuildConfig，为了一行版本号
 * 去开 buildConfig 开关不划算——PackageManager 读的是 APK 里真实写入的值，
 * 反而更能反映「你手机上装的是哪一版」。
 */
internal fun resolveAppVersionName(ctx: Context): String = try {
    @Suppress("DEPRECATION")
    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    val name = pi.versionName?.takeIf { it.isNotBlank() } ?: "未知"
    // longVersionCode 是 API 28+ 的字段；低版本回退到已废弃的 versionCode。
    val code = try {
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toString()
        else pi.versionCode.toString()
    } catch (_: Throwable) {
        null
    }
    if (code != null) "$name($code)" else name
} catch (_: Throwable) {
    "未知"
}

@Composable
fun AboutPage() {
    val ctx = LocalContext.current
    var showLicenses by rememberSaveable { mutableStateOf(false) }

    // 许可页是这一页的下级：自己管一份布尔状态而不是塞进 SettingsPage 枚举，
    // 因为它是**从关于页内部**进去的，不在设置一级目录里，
    // 放进枚举会让主页多出一行「开源许可」入口，和用户要求的层级不符。
    if (showLicenses) {
        OssLicensesPage(onBack = { showLicenses = false })
        return
    }

    Column {
        AboutHeader()
        GroupHeader("应用")
        SettingCard {
            InfoRow("应用版本", resolveAppVersionName(ctx))
            SettingDivider()
            NavigationRow(
                title = "GitHub",
                subtitle = REPO_URL,
                icon = R.drawable.ic_about_github,
                onClick = { openUrl(ctx, REPO_URL) },
            )
        }

        GroupHeader("交流")
        SettingCard {
            CopyableRow(
                icon = R.drawable.ic_about_qq,
                title = "QQ 群",
                value = QQ_GROUP,
                onCopy = {
                    copyToClipboard(ctx, "Auto Lyrics", QQ_GROUP)
                    Toast.makeText(ctx, "已复制QQ群号", Toast.LENGTH_SHORT).show()
                },
            )
        }
        HintText("加群前建议先看一下Issues 里有没有同类问题，能省一次来回。")

        GroupHeader("法律")
        SettingCard {
            NavigationRow(
                title = "开源许可",
                subtitle = "${ossLibs().size} 个开源项目",
                icon = R.drawable.ic_about_license,
                onClick = { showLicenses = true },
            )
        }

        // 邮箱单独起一个「联系」分组而不是塞进上面的「法律」——
        // 开源许可属于法律声明，联系邮箱属于联系方式，混在一张卡片里语义是错的。
        // 分组顺序放在「法律」之后，位置上正好落在开源许可的下方。
        GroupHeader("联系")
        SettingCard {
            CopyableRow(
                icon = R.drawable.ic_about_email,
                title = "联系邮箱",
                value = EMAIL,
                onCopy = {
                    copyToClipboard(ctx, "Auto Lyrics 邮箱", EMAIL)
                    Toast.makeText(ctx, "已复制邮箱地址", Toast.LENGTH_SHORT).show()
                },
            )
        }
        HintText("报bug 或提功能建议，直接发邮件到上面这个地址。")

        Spacer(Modifier.height(24.dp))
        AppFooter()
    }
}

/**
 * 页首：应用图标 + 名称 + 一句话定位。
 *
 * 参考的Android「关于」页都有这块，但系统那张是纯 Material 大标题，插进这个
 * 卡片式列表里会显得突兀；所以压缩成 56dp 图标 + 标题 + 副标题的紧凑版，
 * 高度只占四行，不挤压下面真正的内容。
 */
@Composable
private fun AboutHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 4.dp),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = "自动滚动 · 逐字歌词 · 桌面悬浮窗",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 可复制行：左图标 + 标题 + 值，右端一枚「复制」。
 *
 * 单独写而不用 [NavigationRow]：那个组件右侧固定带「›」，
 * 暗示「点进去还有一层」；而这两个条目（QQ 群、邮箱）是**原地复制**，
 * 不跳转，带箭头会误导。所以右端换成动作提示，点击后立刻 Toast 反馈。
 *
 * 抽成通用组件而不是复制两份：QQ 群和邮箱的交互完全一致，
 * 两份样式日后必然分叉（改了一处忘了另一处，就是那种最难查的不一致）。
 */
@Composable
private fun CopyableRow(
    icon: Int,
    title: String,
    value: String,
    onCopy: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy)
            .padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "复制",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 开源许可列表。
 *
 * 每条都可点击跳到该项目的仓库——只列名字等于让用户自己去搜，
 * 那这份清单就没起到「署名」的作用。
 */
@Composable
fun OssLicensesPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val libs = ossLibs()
    // 与上方「应用版本」用同一个来源，避免两处显示不一致。
    // 此前这里硬编码版本号，每次发版都要记得同步改，漏一次就会自相矛盾。
    val selfVersion = resolveAppVersionName(ctx)

    Column {
        BackHeader("开源许可", onBack)
        GroupHeader("本应用")
        SettingCard {
            OssRow(
                lib = OssLib(
                    name = "Auto Lyrics",
                    artifact = "AutoLyrics",
                    version = "v$selfVersion",
                    license = "GNU General Public License v3.0",
                    url = REPO_URL,
                    usage = "本应用自身采用的协议，同样是 GPL v3",
                ),
                onClick = { openUrl(ctx, REPO_URL) },
            )
        }
        GroupHeader("本应用使用了以下开源项目")
        SettingCard {
            libs.forEachIndexed { index, lib ->
                if (index > 0) SettingDivider()
                OssRow(lib = lib, onClick = { openUrl(ctx, lib.url) })
            }
        }
        HintText(
            "点击任意一项可跳转到该项目主页。各库的完整许可证文本均在其仓库内。" +
                "上述第三方库各自遵循其原有的许可证（多为 Apache License 2.0 与 MIT）；" +
                "本应用自身遵循 GNU GPL v3，两者互相独立。"
        )
    }
}

/** 许可列表的一行：名称 / 版本·许可证 / 用途。 */
@Composable
private fun OssRow(lib: OssLib, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = lib.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = lib.version,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = lib.license,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = lib.usage,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
    }
}

/** 子页返回行。只在「关于」内部使用（许可页不属于设置一级目录）。 */
@Composable
private fun BackHeader(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onBack)
            .padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_back),
            contentDescription = "返回",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 页脚：版权 + 再次点明开源。 */
@Composable
private fun AppFooter() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        Text(
            text = "Auto Lyrics",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "基于 GNU GPL v3 开源",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        )
    }
}

/**
 * 用系统浏览器打开链接。
 *
 * 设备上可能压根没有能处理 https 的 Activity（某些定制 ROM 的极简模式），
 * 那种情况下静默失败比崩掉好，所以 catch 住并给一句提示。
 */
private fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
    }
}

/** 写系统剪贴板。 */
private fun copyToClipboard(ctx: Context, label: String, text: String) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    } catch (_: Throwable) {
        // 少数 ROM 上剪贴板服务不可用，Toast 已经提示过复制意图了，静默即可
    }
}

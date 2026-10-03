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

package org.eu.dinghongyu.autolyrics.util

import android.content.Context
import org.json.JSONObject

/**
 * v1.13.6：应用版本，以及「有没有新版本」的判定。
 *
 * ## 为什么不用 BuildConfig
 *
 * AGP 8 默认不生成 BuildConfig，为一行版本号去开 `buildConfig` 开关不划算。
 * [localVersion] 走 [android.content.pm.PackageManager] 读**安装包里真实写入的值**，
 * 反而更能反映「这台手机上装的是哪一版」——
 * 用户 sideload 了旧包、或从备份恢复过，BuildConfig 未必对得上。
 * `AboutPage.resolveAppVersionName` 当初也是出于同样的理由才这么写的。
 */

/** 一个版本：版本名（用户可见）+ 构建号（Android 判断能否覆盖升级的依据）。 */
data class AppVersion(val name: String, val code: Int) {
    /** 显示用：`1.13.5(47)`。 */
    override fun toString(): String = "$name($code)"
}

/** 读本机安装的版本。读不到时返回 null（由调用方决定怎么提示）。 */
fun localVersion(ctx: Context): AppVersion? = try {
    @Suppress("DEPRECATION")
    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    val name = pi.versionName?.takeIf { it.isNotBlank() }
    val code = try {
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
        else pi.versionCode
    } catch (_: Throwable) {
        null
    }
    if (name != null && code != null) AppVersion(name, code) else null
} catch (_: Throwable) {
    null
}

/**
 * tag 与版本。
 *
 * tag 形如 `v1.13.5-build47` —— 格式由工作流保证
 * （`.github/workflows/build.yml`：`TAG="v${VERSION}-build${BUILD_NO}"`）。
 * 解析不出来就返回 null，**绝不猜**：宁可报「检测失败」，
 * 也不能把一个没读懂的 tag 当成「已是最新」骗用户。
 */
private val TAG_REGEX = Regex("""^v(.+)-build(\d+)$""")

fun parseTag(tag: String): AppVersion? {
    val m = TAG_REGEX.findEntire(tag.trim()) ?: return null
    val name = m.groupValues[1]
    val code = m.groupValues[2].toIntOrNull() ?: return null
    return AppVersion(name, code)
}

/** 查更新的结论。 */
sealed interface UpdateResult {
    /** 本机就是最新。 */
    data object UpToDate : UpdateResult

    /** 官方有更新的版本。 */
    data class Newer(val remote: AppVersion) : UpdateResult

    /** 本机版本比官方还新（自己编译的、或装的是预发布）。 */
    data class Ahead(val remote: AppVersion) : UpdateResult

    /** 没查成（网络/限流/tag 异常）。[reason] 直接展示给用户。 */
    data class Failed(val reason: String) : UpdateResult
}

/**
 * 比对本机与远端。
 *
 * ## 为什么以构建号为准，而不是版本名
 *
 * Android 判断「能不能覆盖安装」靠的就是 `versionCode`，
 * 它单调递增、不会因为改版策略而乱。
 * 版本名则可能倒退：比如 1.13.5 之后补发一个旧分支的 1.12.9 补丁，
 * 按版本名比会得出「本机 1.13.5 > 官方 1.12.9 ⇒ 你领先了」这种荒谬结论。
 *
 * 构建号相同时按「已最新」处理：版本名只是显示用，
 * 构建号相同就意味着 Android 眼里是同一个版本。
 */
fun compareVersion(local: AppVersion, remote: AppVersion): UpdateResult = when {
    local.code < remote.code -> UpdateResult.Newer(remote)
    local.code > remote.code -> UpdateResult.Ahead(remote)
    else -> UpdateResult.UpToDate
}

/**
 * 查官方最新版本。
 *
 * ## 为什么用 `releases/latest`
 *
 * 只需要「最新那一个」，不需要列表。注意 GitHub 的 `latest` 是按发布时间自动判定的，
 * 而本项目的 tag 未必严格按时间递增，所以拿到之后**仍要自己比对**——
 * 这正是 [compareVersion] 存在的原因。
 *
 * ## 关于未认证的限流
 *
 * 不用 token：公开仓库的 `latest` 接口匿名可读，额度是每 IP 每小时 60 次。
 * 这是个用户手动点才触发的按钮，正常使用一年也用不到几次；
 * 真撞上限时会返回 403，如实提示「检测失败」而不是假装没有新版本 ——
 * 后者更糟，会让用户以为自己是最新的。
 */
suspend fun checkUpdate(repoUrl: String, local: AppVersion): UpdateResult {
    val apiUrl = releasesApiUrl(repoUrl)
        ?: return UpdateResult.Failed("检测失败，请稍后再试")

    val body = Http.getOrNull(apiUrl, mapOf("Accept" to "application/vnd.github+json"))
        ?: return UpdateResult.Failed("检测失败，请检查网络后重试")

    val tag = try {
        JSONObject(body).optString("tag_name")
    } catch (_: Throwable) {
        ""
    }
    val remote = parseTag(tag)
        ?: return UpdateResult.Failed("检测失败（服务端返回异常）")

    return compareVersion(local, remote)
}

/**
 * 由仓库页地址推出 API 地址。
 *
 * 例：`https://github.com/dhy0302/AutoLyrics/` →
 * `https://api.github.com/repos/dhy0302/AutoLyrics/releases/latest`
 *
 * 仓库地址来自字符串资源，不在代码里写死；这里只做形状转换。
 * 认不出来就返回 null，由调用方提示失败。
 */
internal fun releasesApiUrl(repoUrl: String): String? {
    val path = repoUrl.trim()
        .removePrefix("https://github.com/")
        .removePrefix("http://github.com/")
        .trimEnd('/')
    val seg = path.split('/').filter { it.isNotBlank() }
    if (seg.size < 2) return null
    return "https://api.github.com/repos/${seg[0]}/${seg[1]}/releases/latest"
}

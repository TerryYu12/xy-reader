package com.xyreader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.xyreader.feedback.AppLog
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 内置更新器：从公开仓库的 GitHub Releases 拉取最新版本、下载 APK 并调起系统安装器。
 *
 * 设计约束：
 * - 只依赖已有家底：okhttp（已引入）+ Android 自带 org.json，不新增第三方依赖；
 * - 网络与解析的全部失败都包进 [Result.failure]，调用方（启动自检 / 手动检查）静默处理即可，不弹异常；
 * - 版本比较与 JSON 解析抽成纯函数（[isNewer] / [parseLatestRelease]），便于单元测试。
 */

/** GitHub 最新发布接口（公开仓库，无需鉴权） */
private const val LATEST_RELEASE_URL =
    "https://api.github.com/repos/TerryYu12/xy-reader/releases/latest"

/** 统一 UA：GitHub API 要求带 User-Agent，否则返回 403 */
private const val UPDATE_USER_AGENT = "XY-Reader-Updater"

/** 日志标签 */
private const val TAG = "UpdateChecker"

/** FileProvider authority，必须与 AndroidManifest 中 provider 的 android:authorities 完全一致 */
const val UPDATE_FILE_PROVIDER_AUTHORITY = "com.xyreader.fileprovider"

/** APK 的 MIME 类型：安装器 Intent 必须带，否则部分 ROM 无法解析 */
private const val APK_MIME = "application/vnd.android.package-archive"

/** 检查更新客户端：单次调用超时 10s（含连接/读取/整通调用） */
private val UPDATE_API_CLIENT: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(10, TimeUnit.SECONDS)
    .build()

/** 下载客户端：连上后要持续读几分钟，不用 callTimeout，只在单次读取上设超时 */
private val UPDATE_DOWNLOAD_CLIENT: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

/** 一次「有可用更新」的全部信息；[assetUrl] 为 null 表示该发布没有可下载的 APK 资产 */
data class UpdateInfo(
    /** 规范版本号：tag 去掉 `v`/`V` 前缀（如 `v0.5.1` → `0.5.1`） */
    val version: String,
    /** 原始 tag 名（如 `v0.5.1`） */
    val tag: String,
    /** 更新说明正文（GitHub Release body，Markdown 原文） */
    val notes: String,
    /** 选中的 APK 资产下载地址；无可用资产时为 null */
    val assetUrl: String?,
    /** 选中的 APK 资产字节数；未知为 0 */
    val assetSize: Long,
    /** 该发布的网页地址（浏览器打开用） */
    val htmlUrl: String,
)

/**
 * 查询最新版本。
 *
 * @return [Result.success] 里：非 null = 有比 [currentVersion] 更新的正式版；null = 已是最新
 *         （含「latest 恰好是预发布/草稿」的情况，此时按无更新处理）。
 *         [Result.failure] = 网络 / HTTP / 解析任一环节失败。
 */
suspend fun checkForUpdate(currentVersion: String): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("User-Agent", UPDATE_USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .build()
        UPDATE_API_CLIENT.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // 404（仓库无 release 或改名）/ 403（限流）等统一按失败处理：上层静默
                throw IOException("GitHub Releases 请求失败：HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val info = parseLatestRelease(body)
            val update = when {
                info == null -> null
                isNewer(info.version, currentVersion) -> info
                else -> null
            }
            Result.success(update)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w(TAG, "检查更新失败", e)
        Result.failure(e)
    }
}

/**
 * 解析 GitHub `releases/latest` 响应。
 *
 * @return 正式版（非 prerelease 且非 draft）的 [UpdateInfo]；预发布/草稿返回 null。
 *         响应格式错误或缺少必需字段时抛异常，避免把解析失败误当作「已是最新」。
 *         纯函数（只依赖 [JSONObject]），供单测直接调用。
 */
internal fun parseLatestRelease(json: String): UpdateInfo? {
    val root = JSONObject(json)
    // latest 接口理论上只返回正式版，但仓库若把预发布设为 latest，这里显式跳过
    if (root.getBoolean("prerelease") || root.getBoolean("draft")) {
        return null
    }
    val tag = root.getString("tag_name")
    val version = stripV(tag)
    if (parseSegments(version) == null) {
        throw IOException("GitHub Release 的版本标签无效：$tag")
    }
    val notes = root.optString("body", "")
    val htmlUrl = root.getString("html_url")
    if (htmlUrl.isBlank()) throw IOException("GitHub Release 缺少发布页地址")

    // 资产选择：只认 .apk，跳过名含 debug 的调试包；都没有则 assetUrl = null
    var assetUrl: String? = null
    var assetSize = 0L
    val assets = root.optJSONArray("assets")
    if (assets != null) {
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (name.contains("debug", ignoreCase = true)) continue
            assetUrl = asset.optString("browser_download_url").ifEmpty { null }
            assetSize = asset.optLong("size", 0L)
            if (assetSize < 0L) throw IOException("GitHub Release APK 的大小字段无效")
            break
        }
    }
    return UpdateInfo(
        version = version,
        tag = tag,
        notes = notes,
        assetUrl = assetUrl,
        assetSize = assetSize,
        htmlUrl = htmlUrl,
    )
}

/**
 * 版本比较纯函数：remote 是否比 local 新。
 *
 * 规则：剥掉可选的 `v`/`V` 前缀后按 `.` 分段做数字比较（缺位按 0，如 `1.0` == `1.0.0`）；
 * 任一段非数字（非法版本串）时 fail closed，视为没有可用更新。
 */
fun isNewer(remote: String, local: String): Boolean {
    val r = stripV(remote)
    val l = stripV(local)
    val rs = parseSegments(r)
    val ls = parseSegments(l)
    if (rs == null || ls == null) {
        // 非法版本串无法安全排序；不把任意字符串差异冒充为新版
        return false
    }
    val n = maxOf(rs.size, ls.size)
    for (i in 0 until n) {
        val a = rs.getOrElse(i) { 0 }
        val b = ls.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

/** 去掉首尾空白与可选的首位 `v`/`V` 前缀 */
private fun stripV(raw: String): String {
    val t = raw.trim()
    return if (t.startsWith("v") || t.startsWith("V")) t.substring(1) else t
}

/** 按 `.` 分段转 Int；任一段为空或非数字返回 null */
private fun parseSegments(v: String): List<Long>? {
    if (v.isEmpty()) return null
    val out = ArrayList<Long>(4)
    for (part in v.split('.')) {
        if (part.isEmpty() || part.any { it !in '0'..'9' }) return null
        out += part.toLongOrNull() ?: return null
    }
    return out
}

/**
 * 流式下载 APK 到 `cacheDir/update/<资产名>`。
 *
 * 先写 `.tmp` 再改名，避免下载中断留下半截文件被误当完整包；
 * [onProgress] 回调 0-100（HTTP 未给 Content-Length 时为 -1），**在 IO 线程回调**。
 */
suspend fun downloadApk(
    context: Context,
    info: UpdateInfo,
    onProgress: (Int) -> Unit,
): Result<File> = withContext(Dispatchers.IO) {
    var temporaryFile: File? = null
    try {
        val coroutineContext = currentCoroutineContext()
        val url = info.assetUrl ?: throw IOException("该版本没有可下载的 APK")
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val fileName = url.substringAfterLast('/').substringBefore('?')
            .ifEmpty { "XY-READER-${info.version}.apk" }
        val target = File(dir, fileName)
        val tmp = File(dir, "$fileName.tmp")
        temporaryFile = tmp

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UPDATE_USER_AGENT)
            .build()
        UPDATE_DOWNLOAD_CLIENT.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("下载失败：HTTP ${response.code}")
            val body = response.body ?: throw IOException("下载响应为空")
            val total = body.contentLength()
            validateDownloadSizes(info.assetSize, total, downloadedBytes = null)
            var done = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buf)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buf, 0, read)
                        done += read
                        onProgress(if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1)
                    }
                }
            }
            validateDownloadSizes(info.assetSize, total, downloadedBytes = done)
        }
        // 原子落地：旧文件先删，tmp 改名失败则退回复制
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        Result.success(target)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w(TAG, "下载更新包失败", e)
        Result.failure(e)
    } finally {
        temporaryFile?.let { if (it.exists()) it.delete() }
    }
}

/** 对照 Release API 大小、HTTP Content-Length 和实际落盘字节数。0/负数表示来源未提供。 */
internal fun validateDownloadSizes(
    expectedAssetSize: Long,
    contentLength: Long,
    downloadedBytes: Long?,
) {
    val expected = expectedAssetSize.takeIf { it > 0L }
    val declared = contentLength.takeIf { it > 0L }
    if (expected != null && declared != null && expected != declared) {
        throw IOException("APK 大小与 Release 元数据不一致")
    }
    if (downloadedBytes != null) {
        if (downloadedBytes <= 0L) throw IOException("下载的 APK 为空")
        if (expected != null && expected != downloadedBytes) {
            throw IOException("APK 实际下载大小与 Release 元数据不一致")
        }
        if (declared != null && declared != downloadedBytes) {
            throw IOException("APK 实际下载大小与 HTTP 声明不一致")
        }
    }
}

/**
 * 调起系统安装器安装已下载的 APK。
 *
 * 前置：若本应用未被允许「安装未知应用」，先跳到系统设置页让用户授权（不安装），
 * 用户授权后再次点「立即更新」即可。content:// 经 [FileProvider] 授权读取，
 * 兼容 Android 7+ 的 FileUriExposedException 限制。
 */
enum class InstallerLaunchResult {
    /** 系统安装器已成功打开。 */
    LAUNCHED,
    /** 已打开未知来源安装权限页；用户返回后可再次点按钮继续安装。 */
    PERMISSION_REQUESTED,
}

fun launchInstaller(context: Context, apkFile: File): InstallerLaunchResult {
    // minSdk 为 26，canRequestPackageInstalls 在所有受支持版本上都可用。
    if (!context.packageManager.canRequestPackageInstalls()) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return InstallerLaunchResult.PERMISSION_REQUESTED
    }
    val uri = FileProvider.getUriForFile(context, UPDATE_FILE_PROVIDER_AUTHORITY, apkFile)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, APK_MIME)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    return InstallerLaunchResult.LAUNCHED
}

/** 用系统浏览器打开发布页（无 APK 资产或下载失败时的兜底入口） */
fun openReleasePage(context: Context, url: String) {
    if (url.isEmpty()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

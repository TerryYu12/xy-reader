package com.xyreader.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xyreader.BuildConfig
import kotlinx.coroutines.CancellationException
import com.xyreader.data.UpdateInfo
import com.xyreader.data.UpdatePrefsStore
import com.xyreader.data.InstallerLaunchResult
import com.xyreader.data.checkForUpdate
import com.xyreader.data.isNewer
import com.xyreader.data.UpdateDownloadManager
import com.xyreader.data.UpdateDownloadSnapshot
import com.xyreader.data.UpdateDownloadStatus
import com.xyreader.data.launchInstaller
import com.xyreader.data.openReleasePage
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 进首页后延迟多久开始静默自检，避开首帧渲染与扫描 */
private const val AUTO_CHECK_DELAY_MS = 1_500L

/**
 * 更新对话框的共享状态。
 *
 * 主页自检（[UpdateHost]）与设置页手动检查（[runManualCheck]）通过它复用同一套弹窗：
 * 任何一方发现新版本都只是把 [info] 写进来，由导航根组合树里的 [UpdateHost] 负责渲染，
 * 避免两处各写一份弹窗逻辑。进程内单例（更新弹窗全局至多一个）。
 */
internal object UpdateHostState {
    var visible by mutableStateOf(false)
    var info: UpdateInfo? by mutableStateOf(null)
    var currentVersion: String by mutableStateOf("")

    fun show(info: UpdateInfo, currentVersion: String) {
        this.info = info
        this.currentVersion = currentVersion
        visible = true
    }

    fun dismiss() {
        visible = false
    }
}

/**
 * 启动自检与弹窗宿主：挂在导航根组合树里，保证任意路由都能显示手动检查结果。
 *
 * 每次进入前台延迟 [AUTO_CHECK_DELAY_MS] 再检查，不受历史检查时间限制；
 * 有新版本才设状态弹窗，任何失败静默（不打扰用户）。
 */
@Composable
internal fun UpdateHost(check: suspend (String) -> Result<UpdateInfo?> = ::checkForUpdate) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val downloads = remember(context) { UpdateDownloadManager(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var download by remember { mutableStateOf<UpdateDownloadSnapshot?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var readError by remember { mutableStateOf<String?>(null) }
    var submitError by remember { mutableStateOf<String?>(null) }
    val currentVersion = BuildConfig.VERSION_NAME

    LaunchedEffect(lifecycle, downloads) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                delay(AUTO_CHECK_DELAY_MS)
                runAutomaticCheck(currentVersion, check) { info ->
                    // 继续展示现有下载，避免新发布替换正在下载的版本。
                    if (download == null && !UpdateHostState.visible) {
                        UpdateHostState.show(info, currentVersion)
                    }
                }
            }
            while (isActive) {
                try {
                    download = downloads.snapshot()
                    readError = null
                    val saved = download
                    if (saved != null && !isNewer(saved.info.version, currentVersion)) {
                        downloads.forget()
                        download = null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    readError = "无法读取更新下载状态，请稍后重试。"
                }
                delay(1_000)
            }
        }
    }

    val task = download
    if (!UpdateHostState.visible && task != null) {
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.BottomEnd) {
            Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
                TextButton(onClick = { UpdateHostState.show(task.info, currentVersion) }) {
                    Text(when (task.status) {
                        UpdateDownloadStatus.DOWNLOADING -> if (task.progress < 0) "更新正在后台下载" else "更新下载 ${task.progress}%"
                        UpdateDownloadStatus.READY -> "安装更新 v${task.info.version}"
                        UpdateDownloadStatus.FAILED -> "更新下载失败 · 重试"
                    })
                }
            }
        }
    }
    val info = UpdateHostState.info
    if (UpdateHostState.visible && info != null) {
        UpdateDialog(
            info = info,
            currentVersion = UpdateHostState.currentVersion,
            onDismiss = { UpdateHostState.dismiss() },
            download = download?.takeIf { it.info.tag == info.tag },
            submitting = submitting,
            hostError = submitError ?: readError,
            onDownload = {
                if (!submitting) {
                    submitting = true
                    submitError = null
                    scope.launch {
                        try {
                            downloads.enqueue(info)
                            download = downloads.snapshot()
                            download?.let { actual ->
                                if (UpdateHostState.visible) UpdateHostState.show(actual.info, currentVersion)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            submitError = "无法开始下载：${e.message ?: "系统下载服务不可用"}"
                        } finally {
                            submitting = false
                        }
                    }
                }
            },
        )
    }
}

/** 每次前台会话都实际请求；不读取旧时间戳，也不缓存失败为“已是最新”。 */
internal suspend fun runAutomaticCheck(
    currentVersion: String,
    check: suspend (String) -> Result<UpdateInfo?> = ::checkForUpdate,
    onUpdate: (UpdateInfo) -> Unit,
) {
    check(currentVersion).onSuccess { info -> if (info != null) onUpdate(info) }
}

/**
 * 设置页手动检查。结果经 [onResult] 回传用户可读文案（null = 有新版，已弹窗，无需提示）：
 * - 已最新 → 「已是最新版本」
 * - 失败 → 「检查失败，请稍后重试」
 * - 有新版 → 写入 [UpdateHostState] 触发主页同一套对话框
 */
internal suspend fun runManualCheck(context: Context, onResult: (String?) -> Unit) {
    val currentVersion = BuildConfig.VERSION_NAME
    checkForUpdate(currentVersion)
        .onSuccess { info ->
            markCheckedSafely(UpdatePrefsStore(context))
            if (info == null) {
                onResult("已是最新版本 v$currentVersion")
            } else {
                UpdateHostState.show(info, currentVersion)
                onResult(null)
            }
        }
        .onFailure {
            onResult("检查失败，请稍后重试")
        }
}

private suspend fun markCheckedSafely(prefs: UpdatePrefsStore) {
    try {
        prefs.markChecked()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 检查结果仍有效，不让诊断时间戳写入失败阻断提示。
    }
}

/**
 * 更新弹窗（Material3 AlertDialog，风格对齐设置页「强调色」弹窗）：
 * 标题「发现新版本 vX.Y.Z」；正文可滚动（最高 ≈300dp）显示更新说明；
 * 主按钮「立即更新」→ 系统下载中显示进度 → 完成后点击「安装更新」；
 * 次按钮「后台下载」收起弹窗，任务由系统继续执行。
 * 下载失败时显示错误和重试按钮，保留「浏览器打开」兜底。
 */
@Composable
internal fun UpdateDialog(
    info: UpdateInfo,
    currentVersion: String,
    onDismiss: () -> Unit,
    download: UpdateDownloadSnapshot? = null,
    submitting: Boolean = false,
    hostError: String? = null,
    onDownload: () -> Unit = {},
) {
    val context = LocalContext.current
    val downloading = download?.status == UpdateDownloadStatus.DOWNLOADING || submitting
    val progress = download?.progress ?: -1
    val downloadedApk = download?.takeIf { it.status == UpdateDownloadStatus.READY }?.file
    var error by remember { mutableStateOf<String?>(null) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }

    fun launchDownloadedInstaller(file: java.io.File) {
        runCatching { launchInstaller(context, file) }
            .onSuccess { result ->
                if (result == InstallerLaunchResult.LAUNCHED) {
                    onDismiss()
                } else {
                    permissionMessage = "允许本应用安装未知应用后，返回此页点“继续安装”。"
                }
            }
            .onFailure { error = "无法调起安装器：${it.message ?: "未知错误"}" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                "发现新版本 v${info.version}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column {
                Text(
                    "当前版本 v$currentVersion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        sanitizeNotes(info.notes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (downloading) {
                    Spacer(Modifier.height(14.dp))
                    if (progress < 0) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (progress < 0) "下载中…" else "下载中… $progress%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val message = error ?: hostError ?: download?.error
                if (message != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (permissionMessage != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        permissionMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            when {
                downloadedApk != null && !downloading -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { launchDownloadedInstaller(downloadedApk) }) {
                            Text(if (permissionMessage == null) "安装更新" else "继续安装")
                        }
                        TextButton(onClick = { openReleasePage(context, info.htmlUrl) }) {
                            Text("浏览器打开")
                        }
                    }
                }
                info.assetUrl == null -> {
                    TextButton(onClick = { openReleasePage(context, info.htmlUrl) }) {
                        Text("浏览器打开")
                    }
                }
                !downloading -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            error = null
                            permissionMessage = null
                            onDownload()
                        }) {
                            Text(if (download?.status == UpdateDownloadStatus.FAILED || hostError != null) "重试下载" else "立即更新")
                        }
                        if (download?.status == UpdateDownloadStatus.FAILED || hostError != null) {
                            TextButton(onClick = { openReleasePage(context, info.htmlUrl) }) { Text("浏览器打开") }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (downloading) "后台下载" else "稍后再说") }
        },
    )
}

/** 最简粗净化：去掉 Markdown 的 `##` 标题与 `**` 加粗符号，正文原样保留 */
private fun sanitizeNotes(raw: String): String {
    val cleaned = raw
        .replace("**", "")
        .replace("##", "")
        .replace("`", "")
        .trim()
    return cleaned.ifEmpty { "该版本暂无更新说明。" }
}

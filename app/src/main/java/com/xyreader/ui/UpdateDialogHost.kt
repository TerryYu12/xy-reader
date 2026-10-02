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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xyreader.BuildConfig
import kotlinx.coroutines.CancellationException
import com.xyreader.data.UpdateInfo
import com.xyreader.data.UpdatePrefsStore
import com.xyreader.data.InstallerLaunchResult
import com.xyreader.data.checkForUpdate
import com.xyreader.data.downloadApk
import com.xyreader.data.launchInstaller
import com.xyreader.data.openReleasePage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 24 小时检查节流窗口（毫秒）：上次成功检查在此窗口内则启动自检跳过 */
private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

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
 * 进首页后延迟 [AUTO_CHECK_DELAY_MS] 再检查；距上次成功检查不足 24h 直接跳过；
 * 有新版本才设状态弹窗，任何失败静默（不打扰用户）。
 */
@Composable
internal fun UpdateHost() {
    val context = LocalContext.current
    val prefs = remember(context) { UpdatePrefsStore(context) }
    val currentVersion = BuildConfig.VERSION_NAME

    LaunchedEffect(Unit) {
        delay(AUTO_CHECK_DELAY_MS)
        val lastCheckAt = try {
            prefs.lastCheckAt.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0L // 偏好文件读取失败时仍执行检查，不把异常升级成应用崩溃
        }
        if (System.currentTimeMillis() - lastCheckAt < CHECK_INTERVAL_MS) return@LaunchedEffect
        checkForUpdate(currentVersion).onSuccess { info ->
            // 只有「请求成功」（有新版或已最新）才刷新时间戳；失败不占用 24h 窗口，便于重试
            markCheckedSafely(prefs)
            if (info != null) UpdateHostState.show(info, currentVersion)
        }
    }

    val info = UpdateHostState.info
    if (UpdateHostState.visible && info != null) {
        UpdateDialog(
            info = info,
            currentVersion = UpdateHostState.currentVersion,
            onDismiss = { UpdateHostState.dismiss() },
        )
    }
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
        // 检查结果仍有效；偏好存储故障只会导致下次启动再次自检。
    }
}

/**
 * 更新弹窗（Material3 AlertDialog，风格对齐设置页「强调色」弹窗）：
 * 标题「发现新版本 vX.Y.Z」；正文可滚动（最高 ≈300dp）显示更新说明；
 * 主按钮「立即更新」→ 下载中显示进度 → 完成后调起系统安装器并关闭；
 * 次按钮「取消」= 关闭，本次不再提醒（下次启动若超出 24h 窗口仍会重查）。
 * 下载失败时正文显示错误文案，并把主按钮换成「浏览器打开」兜底。
 */
@Composable
internal fun UpdateDialog(
    info: UpdateInfo,
    currentVersion: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var downloadedApk by remember { mutableStateOf<java.io.File?>(null) }

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

    fun startDownload() {
        downloading = true
        progress = 0
        error = null
        permissionMessage = null
        scope.launch {
            downloadApk(context, info) { p -> progress = p }.fold(
                onSuccess = { file ->
                    downloading = false
                    downloadedApk = file
                    launchDownloadedInstaller(file)
                },
                onFailure = { e ->
                    downloading = false
                    error = "下载失败：${e.message ?: "网络错误"}"
                },
            )
        }
    }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
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
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        error!!,
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
                        TextButton(onClick = { launchDownloadedInstaller(downloadedApk!!) }) {
                            Text("继续安装")
                        }
                        TextButton(onClick = { openReleasePage(context, info.htmlUrl) }) {
                            Text("浏览器打开")
                        }
                    }
                }
                info.assetUrl == null || error != null -> {
                    TextButton(onClick = { openReleasePage(context, info.htmlUrl) }) {
                        Text("浏览器打开")
                    }
                }
                else -> {
                TextButton(
                    onClick = { if (!downloading) startDownload() },
                    enabled = !downloading,
                ) {
                    Text(if (downloading) "下载中…" else "立即更新")
                }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !downloading) { Text("取消") }
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

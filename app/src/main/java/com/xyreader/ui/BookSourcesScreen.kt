package com.xyreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xyreader.booksource.BookSourceCheckService
import com.xyreader.booksource.BookSourceImporter
import com.xyreader.booksource.ImportResult
import com.xyreader.booksource.LegadoBookSource
import com.xyreader.booksource.LegadoSourceStore
import com.xyreader.booksource.SourceCheckResult
import com.xyreader.booksource.SourceCheckStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 在线书源管理页（设置 → 在线书源 beta）。
 *
 * 只管理**用户自己导入**的源：导入（粘贴 JSON）/ 启停 / 删除 / 可用性检测与批量清理。
 * 应用不携带任何源数据，也不上传任何内容——见页面顶部说明。
 */
@Composable
fun BookSourcesScreen(
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    checkService: BookSourceCheckService = remember { BookSourceCheckService() },
) {
    val context = LocalContext.current
    val store = remember { LegadoSourceStore(context) }
    val sources by store.sources.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var showImport by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<LegadoBookSource?>(null) }
    var pendingBatchDelete by remember { mutableStateOf<Set<String>?>(null) }

    // 可用性检测与批量多选状态
    var checking by remember { mutableStateOf(false) }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    var checkDone by remember { mutableStateOf(0) }
    var checkTotal by remember { mutableStateOf(0) }
    var currentCheckingUrl by remember { mutableStateOf<String?>(null) }
    var checkResults by remember { mutableStateOf<Map<String, SourceCheckResult>>(emptyMap()) }

    var isBatchMode by remember { mutableStateOf(false) }
    var selectedUrls by remember { mutableStateOf<Set<String>>(emptySet()) }

    val availableCount = checkResults.values.count { it.status == SourceCheckStatus.AVAILABLE }
    val unavailableCount = checkResults.values.count { it.status == SourceCheckStatus.UNAVAILABLE }

    fun stopCheck() {
        checkJob?.cancel()
        checkJob = null
        checking = false
        currentCheckingUrl = null
    }

    fun startCheck() {
        if (checking || sources.isEmpty()) return
        checkJob?.cancel()
        checkJob = scope.launch {
            checking = true
            checkDone = 0
            checkTotal = sources.size
            currentCheckingUrl = null
            checkResults = emptyMap()
            try {
                checkService.checkSourcesProgressive(
                    sources = sources,
                    onSourceStart = { source ->
                        currentCheckingUrl = source.url
                    },
                    onSourceChecked = { source, result, done, total ->
                        checkDone = done
                        checkTotal = total
                        checkResults = checkResults + (source.url to result)
                    },
                )
                currentCheckingUrl = null
                val unavail = checkResults.values.count { it.status == SourceCheckStatus.UNAVAILABLE }
                val avail = checkResults.values.count { it.status == SourceCheckStatus.AVAILABLE }
                if (unavail > 0) {
                    snackbar.showSnackbar("检测完成：可用 $avail 条，不可用 $unavail 条，可点击「一键选中不可用」清理")
                } else {
                    snackbar.showSnackbar("检测完成：全部 $checkTotal 条书源均可用")
                }
            } catch (e: CancellationException) {
                currentCheckingUrl = null
                snackbar.showSnackbar("已停止检测，保留已检测的 $checkDone 条结果")
            } catch (e: Throwable) {
                currentCheckingUrl = null
                snackbar.showSnackbar("检测过程发生错误：${e.message}")
            } finally {
                checking = false
            }
        }
    }

    fun checkSingle(source: LegadoBookSource) {
        scope.launch {
            currentCheckingUrl = source.url
            try {
                checkService.checkSourcesProgressive(
                    sources = listOf(source),
                    onSourceChecked = { _, result, _, _ ->
                        checkResults = checkResults + (source.url to result)
                    },
                )
            } finally {
                if (currentCheckingUrl == source.url) currentCheckingUrl = null
            }
        }
    }

    fun selectUnavailable() {
        val unavailUrls = checkResults.filterValues { it.status == SourceCheckStatus.UNAVAILABLE }.keys
        if (unavailUrls.isEmpty()) {
            scope.launch { snackbar.showSnackbar("当前未发现不可用的书源") }
        } else {
            selectedUrls = unavailUrls.toSet()
            isBatchMode = true
            scope.launch { snackbar.showSnackbar("已选中 ${unavailUrls.size} 条不可用书源") }
        }
    }

    fun toggleSelectAll() {
        selectedUrls = if (selectedUrls.size == sources.size) {
            emptySet()
        } else {
            sources.map { it.url }.toSet()
        }
    }

    fun toggleSourceSelect(url: String) {
        selectedUrls = if (url in selectedUrls) {
            selectedUrls - url
        } else {
            selectedUrls + url
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SourcesSubpageHead(
                title = "在线书源",
                subtitle = "导入你自己收集的源 · 应用不提供任何内容",
                onBack = onBack,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "notice") { SourceNoticeCard() }
                item(key = "links") { SourceLinksCard() }
                item(key = "actions") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { showImport = true },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        ) {
                            Text("导入源", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { if (checking) stopCheck() else startCheck() },
                            enabled = sources.isNotEmpty(),
                            modifier = Modifier.weight(1.2f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        ) {
                            Text(if (checking) "停止检测" else "检测可用性", maxLines = 1)
                        }
                        Button(
                            onClick = onOpenSearch,
                            enabled = sources.any { it.enabled && it.canSearch },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        ) {
                            Text("搜索", maxLines = 1)
                        }
                    }
                }

                // 检测中或已有检测结果时展示状态条
                if (checking || checkResults.isNotEmpty()) {
                    item(key = "check-status") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (checking) {
                                    val progress = if (checkTotal > 0) (checkDone.toFloat() / checkTotal).coerceIn(0f, 1f) else 0f
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (checking) {
                                                "正在检测可用性 ($checkDone/$checkTotal)"
                                            } else {
                                                "检测完成：可用 $availableCount · 不可用 $unavailableCount"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        if (checking && currentCheckingUrl != null) {
                                            Text(
                                                "当前: ${sources.find { it.url == currentCheckingUrl }?.name ?: currentCheckingUrl}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                    if (unavailableCount > 0) {
                                        FilledTonalButton(
                                            onClick = { selectUnavailable() },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp),
                                        ) {
                                            Text("一键选中不可用 ($unavailableCount)", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 多选批量操作栏
                if (isBatchMode) {
                    item(key = "batch-actions") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    "已选 ${selectedUrls.size} 条",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = { toggleSelectAll() },
                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                ) {
                                    Text(if (selectedUrls.size == sources.size) "全不选" else "全选")
                                }
                                if (unavailableCount > 0) {
                                    TextButton(
                                        onClick = { selectUnavailable() },
                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                    ) {
                                        Text("选不可用")
                                    }
                                }
                                Button(
                                    onClick = {
                                        if (selectedUrls.isNotEmpty()) {
                                            pendingBatchDelete = selectedUrls
                                        }
                                    },
                                    enabled = selectedUrls.isNotEmpty(),
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError,
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp),
                                ) {
                                    Text("删除 (${selectedUrls.size})", style = MaterialTheme.typography.labelMedium)
                                }
                                IconButton(
                                    onClick = {
                                        isBatchMode = false
                                        selectedUrls = emptySet()
                                    },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "退出多选",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "external-sources") { ExternalSourcesSection() }
                item(key = "count") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "共 ${sources.size} 条源，启用 ${sources.count { it.enabled }} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (sources.isNotEmpty() && !isBatchMode) {
                            TextButton(
                                onClick = { isBatchMode = true },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp),
                            ) {
                                Text("批量管理", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (sources.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "还没有导入任何书源。点「导入源」粘贴你收集到的 Legado 格式书源 JSON；" +
                                "本应用不内置、不提供任何源。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(sources, key = { it.url }) { source ->
                    SourceCard(
                        source = source,
                        checkResult = checkResults[source.url],
                        isCheckingThis = currentCheckingUrl == source.url,
                        isBatchMode = isBatchMode,
                        isSelected = source.url in selectedUrls,
                        onToggleSelect = { toggleSourceSelect(source.url) },
                        onToggle = { enabled -> scope.launch { store.setEnabled(source.url, enabled) } },
                        onCheckSingle = { checkSingle(source) },
                        onRemove = { pendingRemove = source },
                        onSearch = onOpenSearch,
                    )
                }
            }
        }
    }

    if (showImport) {
        ImportSourceDialog(
            store = store,
            onDismiss = { showImport = false },
            onSuccess = { count, added ->
                showImport = false
                scope.launch {
                    snackbar.showSnackbar("导入完成：新增 $added 条，共处理 $count 条")
                }
            },
        )
    }

    pendingRemove?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("删除书源") },
            text = { Text("将移除「${source.name}」及其规则，已下载的书不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    val url = source.url
                    pendingRemove = null
                    scope.launch {
                        store.remove(url)
                        checkResults = checkResults - url
                        selectedUrls = selectedUrls - url
                        snackbar.showSnackbar("已删除")
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("取消") } },
        )
    }

    pendingBatchDelete?.let { urls ->
        AlertDialog(
            onDismissRequest = { pendingBatchDelete = null },
            title = { Text("删除选中的 ${urls.size} 条书源？") },
            text = { Text("将移除选中的 ${urls.size} 条书源及其规则，已下载的书不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    val count = urls.size
                    pendingBatchDelete = null
                    scope.launch {
                        store.removeBatch(urls)
                        checkResults = checkResults.filterKeys { it !in urls }
                        selectedUrls = selectedUrls - urls
                        if (selectedUrls.isEmpty()) isBatchMode = false
                        snackbar.showSnackbar("已批量删除 $count 条书源")
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingBatchDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SourceNoticeCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("关于书源", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "· 书源由你自己导入，规则里指向的站点与本应用无关；请自行确认来源合法。\n" +
                    "· 源数据只保存在本机，不上传；应用不代存任何账号密码。\n" +
                    "· 抓取成整本后会作为普通本地书籍加入书架，可离线阅读。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun SourceCard(
    source: LegadoBookSource,
    checkResult: SourceCheckResult?,
    isCheckingThis: Boolean,
    isBatchMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onCheckSingle: () -> Unit,
    onRemove: () -> Unit,
    onSearch: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isBatchMode) Modifier.clickable { onToggleSelect() } else Modifier),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isBatchMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            source.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (isCheckingThis) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp)
                                Text(
                                    "检测中...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else if (checkResult != null) {
                            when (checkResult.status) {
                                SourceCheckStatus.AVAILABLE -> {
                                    Text(
                                        "● 可用 (${checkResult.message})",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF2E7D32),
                                    )
                                }
                                SourceCheckStatus.UNAVAILABLE -> {
                                    Text(
                                        "● 不可用 (${checkResult.message})",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                else -> {}
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildString {
                            append(source.type.name)
                            source.group?.let { append(" · ").append(it) }
                            if (source.requiresLogin) append(" · 需登录（本版本不支持）")
                            if (!source.canSearch) append(" · 不支持搜索")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        source.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Switch(checked = source.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onSearch, enabled = source.enabled && source.canSearch) { Text("去搜索") }
                TextButton(onClick = onCheckSingle) { Text(if (isCheckingThis) "检测中" else "检测") }
                TextButton(onClick = onRemove) { Text("删除") }
            }
        }
    }
}

/** 导入弹层：支持粘贴 JSON、输入 URL 抓取、或选择本地 .json 文件 */
@Composable
private fun ImportSourceDialog(
    store: LegadoSourceStore,
    onDismiss: () -> Unit,
    onSuccess: (count: Int, added: Int) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val importer = remember(store) { BookSourceImporter(store) }

    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            isLoading = true
            loadingMessage = "正在读取文件..."
            error = null
            scope.launch {
                when (val result = importer.importFromUri(context, uri)) {
                    is ImportResult.Success -> onSuccess(result.sources.size, result.addedCount)
                    is ImportResult.Failure -> {
                        error = result.message
                        isLoading = false
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("导入书源") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    label = { Text("粘贴 JSON 规则或输入 URL 链接") },
                    placeholder = { Text("可贴 JSON 规则，或输入 http(s):// 书源链接") },
                    minLines = 4,
                    maxLines = 8,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isLoading) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            loadingMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboard.getText()?.text?.takeIf { it.isNotBlank() }?.let {
                                text = it
                                error = null
                            }
                        },
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("从剪贴板粘贴", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            error = null
                            filePicker.launch(arrayOf("application/json", "text/*", "*/*"))
                        },
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("选择本地文件", maxLines = 1)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isLoading && text.isNotBlank(),
                onClick = {
                    isLoading = true
                    loadingMessage = if (BookSourceImporter.isUrl(text)) "正在网络抓取并解析..." else "正在解析并导入..."
                    error = null
                    scope.launch {
                        when (val result = importer.importFromText(text)) {
                            is ImportResult.Success -> onSuccess(result.sources.size, result.addedCount)
                            is ImportResult.Failure -> {
                                error = result.message
                                isLoading = false
                            }
                        }
                    }
                },
            ) { Text("导入") }
        },
        dismissButton = {
            TextButton(enabled = !isLoading, onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 子页头（本文件私有实现，与其他页面同款，未改 Common.kt） */
@Composable
private fun SourcesSubpageHead(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            onClick = onBack,
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(13.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

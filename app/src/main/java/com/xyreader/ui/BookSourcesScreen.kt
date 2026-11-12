package com.xyreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xyreader.booksource.BookSourceImporter
import com.xyreader.booksource.ImportResult
import com.xyreader.booksource.LegadoBookSource
import com.xyreader.booksource.LegadoSourceStore
import kotlinx.coroutines.launch

/**
 * 在线书源管理页（设置 → 在线书源 beta）。
 *
 * 只管理**用户自己导入**的源：导入（粘贴 JSON）/ 启停 / 删除。
 * 应用不携带任何源数据，也不上传任何内容——见页面顶部说明。
 */
@Composable
fun BookSourcesScreen(onBack: () -> Unit, onOpenSearch: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LegadoSourceStore(context) }
    val sources by store.sources.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var showImport by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<LegadoBookSource?>(null) }

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
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { showImport = true }, modifier = Modifier.weight(1f)) {
                            Text("导入源")
                        }
                        Button(
                            onClick = onOpenSearch,
                            enabled = sources.any { it.enabled && it.canSearch },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("搜索")
                        }
                    }
                }
                item(key = "external-sources") { ExternalSourcesSection() }
                item(key = "count") {
                    Text(
                        "共 ${sources.size} 条源，启用 ${sources.count { it.enabled }} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        onToggle = { enabled -> scope.launch { store.setEnabled(source.url, enabled) } },
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
                        snackbar.showSnackbar("已删除")
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("取消") } },
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
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onSearch: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(source.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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

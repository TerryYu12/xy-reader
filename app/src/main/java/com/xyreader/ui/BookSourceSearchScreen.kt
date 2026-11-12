package com.xyreader.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xyreader.booksource.BookFetchPhase
import com.xyreader.booksource.BookFetchService
import com.xyreader.booksource.BookFetchState
import com.xyreader.booksource.BookFetchStatus
import com.xyreader.booksource.LegadoBookSource
import com.xyreader.booksource.LegadoSearchService
import com.xyreader.booksource.LegadoSourceStore
import com.xyreader.booksource.SourceBook
import com.xyreader.booksource.SourceSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 书源搜索页：在已启用的源里搜索，结果按来源分组，逐本「抓取整本」入库。
 *
 * 支持选择搜索范围、实时搜索进度指示、停止搜索（保留已有结果）与刷新搜索。
 */
private const val TAG = "XYBSearch"

@Composable
fun BookSourceSearchScreen(
    onBack: () -> Unit,
    searchService: LegadoSearchService = remember { LegadoSearchService() },
) {
    val context = LocalContext.current
    val store = remember { LegadoSourceStore(context) }
    val sources by store.sources.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var searchStopped by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SourceSearchResult>>(emptyList()) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searchTotal by remember { mutableStateOf(0) }
    var searchDone by remember { mutableStateOf(0) }
    var currentSearchingSourceName by remember { mutableStateOf<String?>(null) }

    /** 可搜索的源；页面里所有按钮的可用性都看它 */
    val searchableSources: List<LegadoBookSource> = remember(sources) {
        sources.filter { it.enabled && it.canSearch }
    }

    // 搜索范围：已勾选的源 URL 集合；null 表示默认全选
    var selectedUrls by remember { mutableStateOf<Set<String>?>(null) }
    var showScopeDialog by remember { mutableStateOf(false) }

    val effectiveSelectedUrls: Set<String> = selectedUrls ?: searchableSources.map { it.url }.toSet()
    val activeSources: List<LegadoBookSource> = searchableSources.filter { it.url in effectiveSelectedUrls }

    val scopeSummary = when {
        searchableSources.isEmpty() -> "无可用源"
        effectiveSelectedUrls.size >= searchableSources.size -> "全部 (${searchableSources.size})"
        effectiveSelectedUrls.isEmpty() -> "未选 (0/${searchableSources.size})"
        else -> "已选 ${activeSources.size}/${searchableSources.size}"
    }

    // 抓取跑在前台服务里，进度/结局从进程级 BookFetchState 读（页面重建也能恢复）
    val states by BookFetchState.statuses.collectAsStateWithLifecycle()

    // Android 13+ 通知是运行时权限：被拒也不影响抓取（服务照跑，只是没有通知）。
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // 终态（完成/失败/取消）弹一次提示并清掉，行回到「抓取整本」
    LaunchedEffect(states) {
        states.values
            .filter { it.phase != BookFetchPhase.RUNNING }
            .forEach { status ->
                BookFetchState.clear(status.bookUrl)
                snackbar.showSnackbar(
                    when (status.phase) {
                        BookFetchPhase.DONE -> "已加入书架：${status.title}"
                        BookFetchPhase.CANCELLED -> "已取消：${status.title}"
                        else -> "抓取失败：${status.message ?: status.title}"
                    },
                )
            }
    }

    fun startSearch(isRefresh: Boolean = false) {
        if (searching) return
        val keyword = query.trim()
        Log.d(
            TAG,
            "startSearch query=[$keyword] sources=${sources.size} " +
                "searchable=${searchableSources.size} active=${activeSources.size}",
        )
        if (keyword.isEmpty()) {
            scope.launch { snackbar.showSnackbar("请输入关键词") }
            return
        }
        if (searchableSources.isEmpty()) {
            scope.launch { snackbar.showSnackbar("没有可用的源（先在上一页导入并启用）") }
            return
        }
        if (activeSources.isEmpty()) {
            scope.launch { snackbar.showSnackbar("未选择任何书源，请点击「范围」重新勾选") }
            return
        }

        searchJob?.cancel()
        searchJob = scope.launch {
            searching = true
            searched = false
            searchStopped = false
            searchDone = 0
            searchTotal = activeSources.size
            currentSearchingSourceName = null
            results = emptyList()

            try {
                searchService.searchProgressive(
                    sources = activeSources,
                    query = keyword,
                    onSourceStart = { source, _, _ ->
                        currentSearchingSourceName = source.name
                    },
                    onSourceResult = { _, result, done, _ ->
                        searchDone = done
                        results = results + result
                    },
                )
                searched = true
                currentSearchingSourceName = null
                val books = results.sumOf { it.books.size }
                val firstError = results.firstNotNullOfOrNull { it.error }
                Log.d(TAG, "results=${results.size} books=$books firstError=$firstError")
                when {
                    results.isEmpty() -> snackbar.showSnackbar("没有可用的源（先在上一页导入并启用）")
                    books == 0 -> snackbar.showSnackbar(firstError ?: "没搜到结果（检查源规则或网络）")
                }
            } catch (e: CancellationException) {
                // 点击「停止搜索」取消协程：停止搜索进度，保留已搜出的结果！
                searchStopped = true
                currentSearchingSourceName = null
                Log.d(TAG, "search cancelled by user, current results kept: ${results.size}")
            } catch (e: Throwable) {
                Log.w(TAG, "search failed", e)
                snackbar.showSnackbar(e.message ?: "搜索失败")
            } finally {
                searching = false
            }
        }
    }

    fun stopSearch() {
        if (!searching) return
        searchJob?.cancel()
        searchJob = null
        searching = false
        searchStopped = true
        currentSearchingSourceName = null
        val totalFound = results.sumOf { it.books.size }
        scope.launch {
            snackbar.showSnackbar("已停止搜索，保留已搜出的 $totalFound 条结果")
        }
    }

    fun refreshSearch() {
        if (searching) {
            searchJob?.cancel()
            searchJob = null
            searching = false
        }
        startSearch(isRefresh = true)
    }

    /** Android 13+ 才需要通知权限；被拒也不拦抓取（只是没通知） */
    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** 点「抓取整本」：交给前台服务（带通知与取消），页面只观察 [BookFetchState] */
    fun startDownload(source: LegadoBookSource, book: SourceBook) {
        if (states.containsKey(book.bookUrl)) return
        requestNotificationPermissionIfNeeded()
        BookFetchService.start(context, source, book)
    }

    /** 点「取消」：通知与页面同一个入口，取消后不入库、不留半本书 */
    fun cancelDownload(book: SourceBook) {
        BookFetchService.cancel(context, book.bookUrl)
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SearchSubpageHead(
                title = "搜索书源",
                subtitle = "共 ${searchableSources.size} 个可用源 · 结果来自你导入的源",
                onBack = onBack,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            // 搜索输入与搜索按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("书名 / 作者") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { startSearch() },
                    enabled = !searching,
                ) {
                    Text(if (searching) "搜索中" else "搜索")
                }
            }

            Spacer(Modifier.height(8.dp))

            // 搜索范围与停止/刷新控制行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { showScopeDialog = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Tune, contentDescription = "搜索范围", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "范围：$scopeSummary",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                OutlinedButton(
                    onClick = { stopSearch() },
                    enabled = searching,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "停止搜索", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("停止搜索", style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = { refreshSearch() },
                    enabled = query.isNotBlank() && activeSources.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新搜索", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("刷新搜索", style = MaterialTheme.typography.labelMedium)
                }
            }

            // 搜索进度展示
            if (searching || searchStopped || (searched && results.isNotEmpty())) {
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val total = searchTotal.coerceAtLeast(1)
                    val progress = (searchDone.toFloat() / total).coerceIn(0f, 1f)
                    val totalBooks = results.sumOf { it.books.size }

                    if (searching) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "正在搜索 (${searchDone}/$searchTotal)：${currentSearchingSourceName ?: "连接中…"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "已搜出 ${totalBooks} 本",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    } else if (searchStopped) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = MaterialTheme.colorScheme.error,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "已停止搜索 · 进度 ${searchDone}/$searchTotal",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = "已保留 ${totalBooks} 本书",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (searched) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "搜索完成 · 共搜索 $searchTotal 个源",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "共搜出 ${totalBooks} 本书",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // 搜索结果列表（显示在下方，实时渐进更新）
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!searched && !searching && !searchStopped) {
                    item(key = "hint") {
                        Text(
                            "输入关键词后点「搜索」。抓取到的书会合成 TXT 加入书架，之后可离线阅读。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (searchStopped && results.isEmpty()) {
                    item(key = "stopped-empty") {
                        Text(
                            "已停止搜索，未发现结果。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                results.forEach { result ->
                    val source = sources.firstOrNull { it.url == result.sourceUrl }
                    item(key = "head-${result.sourceUrl}") {
                        SourceHeader(
                            result = result,
                            progressText = null,
                        )
                    }
                    result.books.forEach { book ->
                        item(key = book.bookUrl) {
                            BookRow(
                                book = book,
                                status = states[book.bookUrl],
                                canDownload = source != null,
                                onDownload = { source?.let { startDownload(it, book) } },
                                onCancel = { cancelDownload(book) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showScopeDialog) {
        SearchScopeDialog(
            sources = searchableSources,
            initiallySelected = effectiveSelectedUrls,
            onDismiss = { showScopeDialog = false },
            onConfirm = { updated ->
                selectedUrls = updated
                showScopeDialog = false
            },
        )
    }
}

/** 选择搜索范围弹层：支持全选、反选、清空、按源分组筛选及逐源勾选 */
@Composable
private fun SearchScopeDialog(
    sources: List<LegadoBookSource>,
    initiallySelected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var selected by remember { mutableStateOf(initiallySelected) }
    val allUrls = remember(sources) { sources.map { it.url }.toSet() }

    val groups = remember(sources) {
        sources.mapNotNull { it.group?.takeIf { g -> g.isNotBlank() } }.distinct()
    }
    var selectedGroupFilter by remember { mutableStateOf<String?>(null) }

    val displayedSources = remember(sources, selectedGroupFilter) {
        if (selectedGroupFilter == null) sources
        else sources.filter { it.group == selectedGroupFilter }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("选择搜索范围", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "已选 ${selected.size} / ${sources.size} 个可用源",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { selected = allUrls },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("全选")
                    }
                    TextButton(
                        onClick = { selected = allUrls - selected },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("反选")
                    }
                    TextButton(
                        onClick = { selected = emptySet() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("清空")
                    }
                }

                if (groups.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = selectedGroupFilter == null,
                                onClick = { selectedGroupFilter = null },
                                label = { Text("全部") },
                            )
                        }
                        items(groups) { group ->
                            FilterChip(
                                selected = selectedGroupFilter == group,
                                onClick = {
                                    selectedGroupFilter = if (selectedGroupFilter == group) null else group
                                },
                                label = { Text(group) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(displayedSources, key = { it.url }) { src ->
                        val isChecked = src.url in selected
                        Surface(
                            onClick = {
                                selected = if (isChecked) selected - src.url else selected + src.url
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isChecked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                            else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + src.url else selected - src.url
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        src.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!src.group.isNullOrBlank()) {
                                        Text(
                                            src.group,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selected) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun SourceHeader(result: SourceSearchResult, progressText: String?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(result.sourceName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
            Text(
                when {
                    result.error != null -> "失败"
                    else -> "${result.books.size} 条"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (result.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            progressText?.let {
                Spacer(Modifier.size(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
        result.error?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun BookRow(
    book: SourceBook,
    status: BookFetchStatus?,
    canDownload: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    book.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    book.author?.takeIf { it.isNotBlank() },
                    book.kind?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                status != null && status.phase == BookFetchPhase.RUNNING -> {
                    Text(
                        if (status.total > 0) "抓取中 ${status.done}/${status.total}" else "准备中…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = onCancel) { Text("取消") }
                }
                else -> {
                    if (status != null) {
                        Text(
                            if (status.phase == BookFetchPhase.FAILED) "失败" else "已加入",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.phase == BookFetchPhase.FAILED) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    TextButton(onClick = onDownload, enabled = canDownload) { Text("抓取整本") }
                }
            }
        }
    }
}

/** 子页头（本文件私有实现，与其他页面同款，未改 Common.kt） */
@Composable
private fun SearchSubpageHead(
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

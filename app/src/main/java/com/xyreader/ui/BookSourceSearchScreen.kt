package com.xyreader.ui

import android.util.Log
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import com.xyreader.booksource.BookFetchPhase
import com.xyreader.booksource.BookFetchService
import com.xyreader.booksource.BookFetchState
import com.xyreader.booksource.BookFetchStatus
import com.xyreader.booksource.LegadoBookSource
import com.xyreader.booksource.LegadoSearchService
import com.xyreader.booksource.LegadoSourceStore
import com.xyreader.booksource.SourceBook
import com.xyreader.booksource.SourceSearchResult
import kotlinx.coroutines.launch

/**
 * 书源搜索页：在已启用的源里搜索，结果按来源分组，逐本「抓取整本」入库。
 *
 * 抓取进度来自 [LegadoBookDownloader] 的 onProgress 回调；进度状态放在 StateFlow 里
 * （回调在 IO 线程触发，直接改 Compose 状态不安全）。
 */
private const val TAG = "XYBSearch"

@Composable
fun BookSourceSearchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LegadoSourceStore(context) }
    val searchService = remember { LegadoSearchService() }
    val sources by store.sources.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SourceSearchResult>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
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

    /** 可搜索的源；页面里所有按钮的可用性都看它 */
    val searchableSources: List<LegadoBookSource> = sources.filter { it.enabled && it.canSearch }

    fun startSearch() {
        if (searching) return
        val keyword = query.trim()
        // 诊断日志：查"点了搜索却没发请求"时，先看这一行（adb logcat -s XYBSearch）
        Log.d(
            TAG,
            "startSearch query=[$keyword] sources=${sources.size} " +
                "searchable=${searchableSources.size} canSearch=${searchableSources.map { it.canSearch }}",
        )
        if (keyword.isEmpty()) {
            scope.launch { snackbar.showSnackbar("请输入关键词") }
            return
        }
        if (searchableSources.isEmpty()) {
            scope.launch { snackbar.showSnackbar("没有可用的源（先在上一页导入并启用）") }
            return
        }
        scope.launch {
            searching = true
            results = runCatching { searchService.search(searchableSources, keyword) }.getOrElse {
                Log.w(TAG, "search failed", it)
                snackbar.showSnackbar(it.message ?: "搜索失败")
                emptyList()
            }
            searched = true
            searching = false
            val books = results.sumOf { it.books.size }
            val firstError = results.firstNotNullOfOrNull { it.error }
            Log.d(TAG, "results=${results.size} books=$books firstError=$firstError")
            when {
                results.isEmpty() -> snackbar.showSnackbar("没有可用的源（先在上一页导入并启用）")
                books == 0 -> snackbar.showSnackbar(firstError ?: "没搜到结果（检查源规则或网络）")
            }
        }
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
                TextButton(onClick = { startSearch() }, enabled = !searching) {
                    Text(if (searching) "搜索中" else "搜索")
                }
            }
            Spacer(Modifier.height(8.dp))
            if (searching) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!searched && !searching) {
                    item(key = "hint") {
                        Text(
                            "输入关键词后点「搜索」。抓取到的书会合成 TXT 加入书架，之后可离线阅读。",
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

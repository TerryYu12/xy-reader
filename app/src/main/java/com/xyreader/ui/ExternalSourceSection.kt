package com.xyreader.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xyreader.booksource.ExternalSearchItem
import com.xyreader.booksource.ExternalSourceProvider
import com.xyreader.booksource.ExternalSourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「内置来源（本构建含）」区块。
 *
 * 这些来源由可选 Gradle 模块通过 SPI 注册（见 `ExternalSourceProvider`）；
 * **主仓不含任何实现**，不带该模块构建时本区块整段不出现。
 */
@Composable
fun ExternalSourcesSection() {
    val providers = remember { ExternalSourceRegistry.providers() }
    if (providers.isEmpty()) return

    var searching by remember { mutableStateOf<ExternalSourceProvider?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "内置来源（本构建含）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        providers.forEach { provider ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(provider.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            provider.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (provider.note.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                provider.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    TextButton(onClick = { searching = provider }) { Text("搜索") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    searching?.let { provider ->
        ExternalSearchDialog(provider = provider, onDismiss = { searching = null })
    }
}

@Composable
private fun ExternalSearchDialog(provider: ExternalSourceProvider, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<ExternalSearchItem>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }

    fun runSearch() {
        val keyword = query.trim()
        if (keyword.isEmpty() || loading) return
        scope.launch {
            loading = true
            error = null
            runCatching { withContext(Dispatchers.IO) { provider.createSession().search(keyword, 10) } }
                .onSuccess {
                    results = it
                    searched = true
                }
                .onFailure {
                    results = emptyList()
                    searched = true
                    error = it.message ?: "搜索失败"
                }
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(provider.displayName) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("书名 / 作者") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { runSearch() }, enabled = !loading) {
                        Text(if (loading) "搜索中" else "搜索")
                    }
                    if (loading) CircularProgressIndicator(modifier = Modifier.height(18.dp).fillMaxWidth(0.08f))
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (searched && results.isEmpty() && error == null) {
                    Text(
                        "没有结果",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(results, key = { it.externalUrl + it.title }) { item ->
                        Surface(
                            onClick = {
                                // 下载/详情交给浏览器：应用不代存凭据、不代登录
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.externalUrl)))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Column(modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
                                Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                val meta = listOfNotNull(
                                    item.author,
                                    item.format?.uppercase(),
                                    item.sizeBytes?.let { size ->
                                        if (size >= 1024 * 1024) "%.1f MB".format(size / 1024.0 / 1024.0) else "${size / 1024} KB"
                                    },
                                ).joinToString(" · ")
                                if (meta.isNotBlank()) {
                                    Text(
                                        meta,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

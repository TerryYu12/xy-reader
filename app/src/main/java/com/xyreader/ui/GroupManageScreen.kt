package com.xyreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xyreader.core.BookGroupEntity
import kotlinx.coroutines.launch

/**
 * 书架管理页：自定义分组的增删改。
 * 删除分组只把组内书移回未分组，不删任何书。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupManageScreen(onBack: () -> Unit) {
    val repo = rememberLibraryRepository()
    val scope = rememberCoroutineScope()
    val groups by repo.groups.collectAsStateWithLifecycle(initialValue = emptyList())

    var showAdd by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<BookGroupEntity?>(null) }
    var deleting by remember { mutableStateOf<BookGroupEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书架管理", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAdd = true },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加分组")
            }
        },
    ) { padding ->
        if (groups.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.FolderOpen,
                title = "还没有分组",
                subtitle = "点右下角 + 创建分组，然后在封面上长按即可把书移进来",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(groups, key = { it.id }) { group ->
                    GroupRow(
                        group = group,
                        onRename = { renaming = group },
                        onDelete = { deleting = group },
                    )
                }
            }
        }
    }

    // 添加分组
    if (showAdd) {
        var text by remember { mutableStateOf("") }
        val duplicate = groups.any { it.name.equals(text.trim(), ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("新建分组") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("分组名称") },
                    singleLine = true,
                    isError = duplicate,
                    supportingText = { if (duplicate) Text("已有同名分组") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAdd = false
                        scope.launch { repo.addGroup(text.trim()) }
                    },
                    enabled = text.isNotBlank() && !duplicate,
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("取消") }
            },
        )
    }

    // 重命名分组（预填当前名）
    renaming?.let { group ->
        var text by remember(group.id) { mutableStateOf(group.name) }
        val duplicate = groups.any { it.id != group.id && it.name.equals(text.trim(), ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名分组") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = duplicate,
                    supportingText = { if (duplicate) Text("已有同名分组") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renaming = null
                        scope.launch { repo.renameGroup(group.id, text.trim()) }
                    },
                    enabled = text.isNotBlank() && !duplicate,
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消") }
            },
        )
    }

    // 删除分组确认：组内书回到未分组，不删书
    deleting?.let { group ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除分组「${group.name}」？") },
            text = { Text("组内的书将回到未分组状态，不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { repo.removeGroup(group.id) }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

/** 分组行卡片：紫色图标块 + 名称 + 重命名 / 删除 */
@Composable
private fun GroupRow(
    group: BookGroupEntity,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val purpleTint = accentColor(AccentColor.PURPLE)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(13.dp),
                color = purpleTint.copy(alpha = 0.12f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = purpleTint,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                group.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            IconButton(onClick = onRename) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "重命名",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "删除分组",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

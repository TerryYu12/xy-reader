package com.xyreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.xyreader.core.BookGroupEntity
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 书架管理页（设计源 index.html groupsView）：自定义分组的增删改。
 * 子页头（返回圆钮 + 标题 + 副标题）→ settings-card 里的 setting-row（强调色 folder 图标块
 * + 分组名 + 「N 本书」副标题 + 改名 / 删除）→ 卡下「新建分组」软按钮；空态为卡内文案。
 * 删除分组只把组内书移回未分组，不删任何书。
 */
@Composable
fun GroupManageScreen(onBack: () -> Unit) {
    val repo = rememberLibraryRepository()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val groups by repo.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    val books by repo.books.collectAsStateWithLifecycle(initialValue = emptyList())

    var showAdd by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<BookGroupEntity?>(null) }
    var deleting by remember { mutableStateOf<BookGroupEntity?>(null) }
    var managingCover by remember { mutableStateOf<BookGroupEntity?>(null) }
    var pendingCoverGroupId by rememberSaveable { mutableStateOf<Long?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val groupId = pendingCoverGroupId
        pendingCoverGroupId = null
        if (uri != null && groupId != null) {
            scope.launch {
                val imported = try {
                    repo.importGroupCover(groupId, uri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                snackbar.showSnackbar(if (imported) "分组封面已更新" else "分组封面导入失败")
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SubpageHead(title = "书架管理", subtitle = "整理书籍分组", onBack = onBack)

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(19.dp),
                    ),
                shape = RoundedCornerShape(19.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column {
                    if (groups.isEmpty()) {
                        Text(
                            "还没有自定义分组。",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        groups.forEachIndexed { index, group ->
                            if (index > 0) {
                                HorizontalDivider(
                                    thickness = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                            GroupRow(
                                group = group,
                                bookCount = books.count { it.groupId == group.id },
                                onManageCover = { managingCover = group },
                                onRename = { renaming = group },
                                onDelete = { deleting = group },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(13.dp))
            // 「新建分组」软按钮（对应设计 .button.soft）
            Surface(
                onClick = { showAdd = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "新建分组",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
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

    managingCover?.let { target ->
        val group = groups.firstOrNull { it.id == target.id } ?: target
        AlertDialog(
            onDismissRequest = { managingCover = null },
            title = { Text("分组封面") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (group.coverPath != null) {
                        AsyncImage(
                            model = File(group.coverPath),
                            contentDescription = "${group.name}分组封面",
                            modifier = Modifier
                                .size(104.dp)
                                .clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Text(
                            "当前封面",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "尚未设置封面",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingCoverGroupId = group.id
                    managingCover = null
                    coverPicker.launch("image/*")
                }) {
                    Text(if (group.coverPath == null) "选择图片" else "更换图片")
                }
            },
            dismissButton = {
                Row {
                    if (group.coverPath != null) {
                        TextButton(onClick = {
                            managingCover = null
                            scope.launch {
                                val cleared = try {
                                    repo.setGroupCover(group.id, null)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    false
                                }
                                snackbar.showSnackbar(if (cleared) "分组封面已清除" else "分组封面清除失败")
                            }
                        }) { Text("清除封面", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { managingCover = null }) { Text("取消") }
                }
            },
        )
    }
}

/**
 * 子页头（对应设计 .subpage-head）：40dp 圆角描边返回钮 + 标题（titleLarge/Bold）+ 副标题。
 * 本文件私有实现，避免与其他子代理页面中的同名共用组件冲突（未改 Common.kt）。
 */
@Composable
private fun SubpageHead(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
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
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 分组行（对应设计 groupsView 的 .setting-row）：紫色 folder 图标块 + 名称 + 「N 本书」 + 改名 / 删除 */
@Composable
private fun GroupRow(
    group: BookGroupEntity,
    bookCount: Int,
    onManageCover: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val purpleTint = accentColor(AccentColor.PURPLE)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (group.coverPath != null) {
            AsyncImage(
                model = File(group.coverPath),
                contentDescription = "${group.name}分组封面",
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(12.dp),
                color = purpleTint.copy(alpha = 0.12f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = purpleTint,
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                group.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "$bookCount 本书",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onManageCover) {
            Text(
                "封面",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRename) {
            Text(
                "改名",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

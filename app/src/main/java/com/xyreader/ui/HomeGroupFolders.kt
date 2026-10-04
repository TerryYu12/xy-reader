package com.xyreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xyreader.core.BookGroupEntity
import java.io.File

/** 首页的紧凑木质分组条；每个抽屉独立响应打开和更多操作。 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun HomeGroupFolderSection(
    groups: List<BookGroupEntity>,
    bookCounts: Map<Long, Int>,
    dragState: BookGridDragState,
    onOpenGroup: (Long) -> Unit,
    onRenameGroup: (Long, String) -> Unit,
    onPickCover: (Long) -> Unit,
    onClearCover: (Long) -> Unit,
    onCreateGroup: () -> Unit = {},
    onDeleteGroup: (Long) -> Unit = {},
) {
    var groupPendingDeletion by remember { mutableStateOf<BookGroupEntity?>(null) }
    var groupPendingRename by remember { mutableStateOf<BookGroupEntity?>(null) }
    var renameText by remember { mutableStateOf("") }

    val railShape = RoundedCornerShape(13.dp)
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp)
            .clip(railShape)
            .background(Brush.linearGradient(listOf(Color(0xFF5D4037), Color(0xFF3E2723))))
            .border(1.dp, Color(0xFF70513B), railShape)
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 2.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "书架分组",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE2C89F),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (groups.isEmpty()) "暂无分组" else "${groups.size} 个抽屉",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFC1A98B),
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onCreateGroup,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF0E1CB)),
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFFF0E1CB),
                )
                Spacer(Modifier.width(3.dp))
                Text("新建", style = MaterialTheme.typography.labelMedium, color = Color(0xFFF0E1CB))
            }
        }
        if (groups.isEmpty()) {
            Text(
                "创建分组后，可把书籍收进木抽屉。",
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 3.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFEAD8BF),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                groups.forEach { group ->
                    HomeGroupFolderCard(
                        group = group,
                        bookCount = bookCounts[group.id] ?: 0,
                        dragState = dragState,
                        onOpenGroup = { onOpenGroup(group.id) },
                        onRenameRequested = {
                            renameText = group.name
                            groupPendingRename = group
                        },
                        onDeleteGroup = { groupPendingDeletion = group },
                    )
                }
            }
        }
    }

    groupPendingDeletion?.let { group ->
        AlertDialog(
            onDismissRequest = { groupPendingDeletion = null },
            title = { Text("删除分组「${group.name}」？") },
            text = {
                Text("组内 ${bookCounts[group.id] ?: 0} 本书将移至未分组，书籍文件不会删除。")
            },
            confirmButton = {
                TextButton(onClick = {
                    groupPendingDeletion = null
                    onDeleteGroup(group.id)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { groupPendingDeletion = null }) { Text("取消") }
            },
        )
    }

    groupPendingRename?.let { group ->
        val duplicateName = groups.any {
            it.id != group.id && it.name.equals(renameText.trim(), ignoreCase = true)
        }
        AlertDialog(
            onDismissRequest = { groupPendingRename = null },
            title = { Text("重命名分组") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("分组名称") },
                    singleLine = true,
                    isError = duplicateName,
                    supportingText = if (duplicateName) {
                        { Text("已有同名分组") }
                    } else null,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank() && !duplicateName,
                    onClick = {
                        groupPendingRename = null
                        onRenameGroup(group.id, renameText.trim())
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { groupPendingRename = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun HomeGroupFolderCard(
    group: BookGroupEntity,
    bookCount: Int,
    dragState: BookGridDragState,
    onOpenGroup: () -> Unit,
    onRenameRequested: () -> Unit,
    onDeleteGroup: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    val dropKey = "home-folder-${group.id}"
    var menuExpanded by remember(group.id) { mutableStateOf(false) }
    val isDropTarget = dragState.draggedBookId != null &&
        dragState.targetBoundsInRoot[dropKey]?.contains(dragState.pointerInRoot) == true

    DisposableEffect(dragState, dropKey) {
        onDispose { dragState.unregisterTarget(dropKey) }
    }

    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onOpenGroup,
                modifier = Modifier.width(124.dp).height(42.dp)
                    .onGloballyPositioned { dragState.registerTarget(dropKey, it.boundsInRoot()) },
                shape = shape,
                border = BorderStroke(
                    if (isDropTarget) 2.dp else 1.dp,
                    if (isDropTarget) Color(0xFFFFE6A8) else Color(0xFF89664E),
                ),
                color = Color(0xFF4D342D),
                contentColor = Color(0xFFF4E8D8),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(Brush.horizontalGradient(listOf(Color(0xFF68483C), Color(0xFF3E2723))))
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (group.coverPath != null) {
                        AsyncImage(
                            model = File(group.coverPath),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                            tint = Color(0xFFD9B783),
                        )
                    }
                    Text(
                        group.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF4E8D8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "$bookCount 本",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = Color(0xFFD0B89A),
                        maxLines = 1,
                    )
                }
            }
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = "更多分组操作：${group.name}",
                    modifier = Modifier.size(18.dp),
                    tint = Color(0xFFDCC7A5),
                )
            }
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("重命名") },
                leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                onClick = {
                    menuExpanded = false
                    onRenameRequested()
                },
            )
            DropdownMenuItem(
                text = { Text("删除分组", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { menuExpanded = false; onDeleteGroup() },
            )
        }
    }

}

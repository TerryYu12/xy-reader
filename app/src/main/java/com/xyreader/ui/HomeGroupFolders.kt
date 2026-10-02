package com.xyreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xyreader.core.BookGroupEntity
import java.io.File

/** 首页分组抽屉区：木质卡片与扁平书封卡区分，横向滚动以保留首页网格空间。 */
@Composable
internal fun HomeGroupFolderSection(
    groups: List<BookGroupEntity>,
    bookCounts: Map<Long, Int>,
    dragState: BookGridDragState,
    onOpenGroup: (Long) -> Unit,
    onRenameGroup: (Long, String) -> Unit,
    onPickCover: (Long) -> Unit,
    onClearCover: (Long) -> Unit,
) {
    if (groups.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "书柜分组",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFC5A059),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${groups.size} 个抽屉",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            groups.forEach { group ->
                HomeGroupFolderCard(
                    group = group,
                    bookCount = bookCounts[group.id] ?: 0,
                    groups = groups,
                    dragState = dragState,
                    onOpenGroup = { onOpenGroup(group.id) },
                    onRenameGroup = { name -> onRenameGroup(group.id, name) },
                    onPickCover = { onPickCover(group.id) },
                    onClearCover = { onClearCover(group.id) },
                )
            }
        }
    }
}

@Composable
private fun HomeGroupFolderCard(
    group: BookGroupEntity,
    bookCount: Int,
    groups: List<BookGroupEntity>,
    dragState: BookGridDragState,
    onOpenGroup: () -> Unit,
    onRenameGroup: (String) -> Unit,
    onPickCover: () -> Unit,
    onClearCover: () -> Unit,
) {
    val shape = RoundedCornerShape(15.dp)
    val dropKey = "home-folder-${group.id}"
    var menuExpanded by remember(group.id) { mutableStateOf(false) }
    var renameDialogOpen by remember(group.id) { mutableStateOf(false) }
    var renameText by remember(group.id) { mutableStateOf(group.name) }
    val duplicateName = groups.any {
        it.id != group.id && it.name.equals(renameText.trim(), ignoreCase = true)
    }
    val isDropTarget = dragState.draggedBookId != null &&
        dragState.targetBoundsInRoot[dropKey]?.contains(dragState.pointerInRoot) == true

    DisposableEffect(dragState, dropKey) {
        onDispose { dragState.unregisterTarget(dropKey) }
    }

    Box {
        Surface(
            onClick = onOpenGroup,
            modifier = Modifier.width(176.dp).height(128.dp)
                .shadow(if (isDropTarget) 9.dp else 5.dp, shape)
                .onGloballyPositioned { dragState.registerTarget(dropKey, it.boundsInRoot()) },
            shape = shape,
            border = BorderStroke(
                if (isDropTarget) 2.dp else 1.dp,
                if (isDropTarget) Color(0xFFFFE6A8) else Color(0xFFC5A059),
            ),
            color = Color(0xFF5D4037),
            contentColor = Color(0xFFFFF8E8),
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF795548), Color(0xFF5D4037), Color(0xFF3E2723)),
                    ),
                ),
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "分组文件夹",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFD7C2A1),
                            maxLines = 1,
                        )
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                contentDescription = "更多分组操作：${group.name}",
                                modifier = Modifier.size(18.dp),
                                tint = Color(0xFFFFE6A8),
                            )
                        }
                    }
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (group.coverPath != null) {
                            AsyncImage(
                                model = File(group.coverPath),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                        } else {
                            Row(
                                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                listOf(
                                    Color(0xFF7C6A52),
                                    Color(0xFF48616A),
                                    Color(0xFF9A674F),
                                ).forEachIndexed { index, color ->
                                    Box(
                                        Modifier.width(18.dp).height((30 + index * 7).dp)
                                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                            .background(color),
                                    ) {
                                        Box(
                                            Modifier.fillMaxWidth().height(2.dp)
                                                .background(Color.White.copy(alpha = 0.28f)),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().height(30.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF9B753B), Color(0xFFE1C27B), Color(0xFF9B753B)),
                                ),
                            )
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            group.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF382719),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "$bookCount 本",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF382719),
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("重命名") },
                leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                onClick = {
                    menuExpanded = false
                    renameText = group.name
                    renameDialogOpen = true
                },
            )
            DropdownMenuItem(
                text = { Text("更换封面") },
                leadingIcon = { Icon(Icons.Outlined.Image, null) },
                onClick = { menuExpanded = false; onPickCover() },
            )
            if (group.coverPath != null) {
                DropdownMenuItem(
                    text = { Text("清除封面") },
                    onClick = { menuExpanded = false; onClearCover() },
                )
            }
        }
    }

    if (renameDialogOpen) {
        AlertDialog(
            onDismissRequest = { renameDialogOpen = false },
            title = { Text("重命名分组") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    isError = duplicateName,
                    supportingText = { if (duplicateName) Text("已有同名分组") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank() && !duplicateName,
                    onClick = {
                        renameDialogOpen = false
                        onRenameGroup(renameText.trim())
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogOpen = false }) { Text("取消") }
            },
        )
    }
}

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Tune
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
    totalCount: Int,
    selectedGroupId: Long?,
    dragState: BookGridDragState,
    onSelectGroup: (Long?) -> Unit,
    onOpenGroupManage: () -> Unit,
    onRenameGroup: (Long, String) -> Unit,
    onPickCover: (Long) -> Unit,
    onClearCover: (Long) -> Unit,
    onCreateGroup: () -> Unit = {},
    onDeleteGroup: (Long) -> Unit = {},
) {
    var groupPendingDeletion by remember { mutableStateOf<BookGroupEntity?>(null) }
    var groupPendingRename by remember { mutableStateOf<BookGroupEntity?>(null) }
    var renameText by remember { mutableStateOf("") }

    // 首页分组栏（规范 3.3）：最左固定「全部」系统聚合项 → 中间真实自定义分组（横向滚动）→ 最右固定「分组管理」入口
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 「全部」：含未分组书籍，总数按书籍去重；不可重命名 / 删除 / 拖动
        HomeFilterChip(
            label = "全部",
            count = totalCount,
            selected = selectedGroupId == null,
            onClick = { onSelectGroup(null) },
        )
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            groups.forEach { group ->
                HomeGroupFolderCard(
                    group = group,
                    bookCount = bookCounts[group.id] ?: 0,
                    selected = selectedGroupId == group.id,
                    dragState = dragState,
                    onOpenGroup = { onSelectGroup(group.id) },
                )
            }
        }
        // 固定的管理入口：只留图标（带 contentDescription），不占文字宽度；
        // 打开后能新建 / 编辑 / 删除分组（不再是只能显示分类的菜单）
        Surface(
            onClick = onOpenGroupManage,
            modifier = Modifier.size(34.dp),
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Tune,
                    contentDescription = "分组管理",
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
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
    selected: Boolean,
    dragState: BookGridDragState,
    onOpenGroup: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    val dropKey = "home-folder-${group.id}"
    val groupCoverFile = remember(group.coverPath) { group.coverPath?.let(::File) }
    val isDropTarget = dragState.draggedBookId != null &&
        dragState.targetBoundsInRoot[dropKey]?.contains(dragState.pointerInRoot) == true

    DisposableEffect(dragState, dropKey) {
        onDispose { dragState.unregisterTarget(dropKey) }
    }

    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onOpenGroup,
                modifier = Modifier.height(28.dp).widthIn(max = 84.dp)
                    .onGloballyPositioned { dragState.registerTarget(dropKey, it.boundsInRoot()) },
                shape = shape,
                border = BorderStroke(
                    if (isDropTarget) 2.dp else 1.dp,
                    when {
                        isDropTarget -> MaterialTheme.colorScheme.primary
                        selected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    },
                ),
                // 选中胶囊 = 浅粉底 + 主色描边；未选中 = 白底描边
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (group.coverPath != null) {
                        AsyncImage(
                            model = groupCoverFile,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        group.name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

}

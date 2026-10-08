package com.xyreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xyreader.core.BookEntity
import com.xyreader.core.BookGroupEntity
import com.xyreader.core.BookmarkEntity
import com.xyreader.core.ShelfSection

/** 宽屏侧栏；所有计数和继续阅读目标来自当前书库 flow。 */
@Composable
internal fun AppSideRail(
    route: String?,
    selectedSection: ShelfSection?,
    selectedGroupId: Long?,
    books: List<BookEntity>,
    groups: List<BookGroupEntity>,
    bookmarks: List<BookmarkEntity>,
    onHome: () -> Unit,
    onShelf: () -> Unit,
    onOpenSection: (ShelfSection) -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenGroup: (Long) -> Unit,
    onContinueReading: (Long) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var collapsed by rememberSaveable { mutableStateOf(false) }
    val recentBook = books
        .asSequence()
        .filter { it.lastReadAt != null && it.currentPage > 0 }
        .maxByOrNull { it.lastReadAt ?: 0L }
    val expandedWidth = 244.dp
    val collapsedWidth = 72.dp

    Surface(
        modifier = modifier.width(if (collapsed) collapsedWidth else expandedWidth).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (collapsed) 8.dp else 12.dp, vertical = 14.dp),
        ) {
            if (collapsed) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    BrandMark()
                    RailCollapseButton(collapsed = true, onClick = { collapsed = false })
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BrandMark()
                    Column(Modifier.weight(1f)) {
                        Text(
                            "XY-READER",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        Text(
                            "READ AT YOUR PACE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    RailCollapseButton(collapsed = false, onClick = { collapsed = true })
                }
            }

            Spacer(Modifier.height(18.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!collapsed) RailSectionLabel("阅读空间")
                RailItem(
                    icon = Icons.Outlined.Home,
                    label = "首页",
                    selected = route == "home",
                    collapsed = collapsed,
                    onClick = onHome,
                )
                RailItem(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    label = "书架",
                    count = books.size,
                    selected = route == "shelf" || selectedSection != null || selectedGroupId != null,
                    collapsed = collapsed,
                    onClick = onShelf,
                )

                if (!collapsed) {
                    Spacer(Modifier.height(10.dp))
                    RailSectionLabel("收藏与记录")
                } else {
                    Spacer(Modifier.height(8.dp))
                }
                RailItem(
                    icon = Icons.Outlined.FavoriteBorder,
                    label = "收藏",
                    count = books.count { it.isFavorite },
                    selected = selectedSection == ShelfSection.FAVORITE,
                    collapsed = collapsed,
                    onClick = { onOpenSection(ShelfSection.FAVORITE) },
                )
                RailItem(
                    icon = Icons.Outlined.History,
                    label = "历史",
                    count = books.count { it.lastReadAt != null },
                    selected = selectedSection == ShelfSection.HISTORY,
                    collapsed = collapsed,
                    onClick = { onOpenSection(ShelfSection.HISTORY) },
                )
                RailItem(
                    icon = Icons.Outlined.Bookmarks,
                    label = "书签",
                    count = bookmarks.size,
                    selected = route == "bookmarks" || selectedSection == ShelfSection.BOOKMARK,
                    collapsed = collapsed,
                    onClick = onOpenBookmarks,
                )
                RailItem(
                    icon = Icons.Outlined.DateRange,
                    label = "阅读统计",
                    selected = route == "stats",
                    collapsed = collapsed,
                    onClick = onOpenStats,
                )

                if (!collapsed) {
                    Spacer(Modifier.height(10.dp))
                    RailSectionLabel("自定义分组")
                } else {
                    Spacer(Modifier.height(8.dp))
                }
                groups.forEach { group ->
                    RailItem(
                        icon = Icons.Outlined.FolderOpen,
                        label = group.name,
                        count = books.count { it.groupId == group.id },
                        selected = selectedGroupId == group.id,
                        collapsed = collapsed,
                        onClick = { onOpenGroup(group.id) },
                    )
                }
                if (!collapsed && groups.isEmpty()) {
                    Text(
                        "暂无自定义分组",
                        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            if (recentBook == null) {
                RailItem(
                    icon = Icons.Outlined.History,
                    label = "暂无阅读记录",
                    collapsed = collapsed,
                    enabled = false,
                    onClick = {},
                )
            } else if (collapsed) {
                RailItem(
                    icon = Icons.Outlined.History,
                    label = "继续阅读：${recentBook.title}",
                    collapsed = true,
                    onClick = { onContinueReading(recentBook.id) },
                )
            } else {
                Surface(
                    onClick = { onContinueReading(recentBook.id) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Column(Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
                        Text(
                            "继续阅读",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(7.dp))
                        Text(
                            recentBook.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val progress = if (recentBook.totalPages > 0) {
                            (recentBook.currentPage * 100 / recentBook.totalPages).coerceIn(0, 100)
                        } else 0
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "$progress% 已读",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(9.dp))
            RailItem(
                icon = Icons.Outlined.Settings,
                label = "设置",
                selected = route?.startsWith("settings") == true,
                collapsed = collapsed,
                onClick = onSettings,
            )
        }
    }
}

@Composable
private fun BrandMark() {
    Surface(
        modifier = Modifier.size(38.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun RailCollapseButton(collapsed: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = if (collapsed) "展开侧栏" else "折叠侧栏",
            modifier = Modifier
                .size(20.dp)
                .rotate(if (collapsed) 0f else 180f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RailSectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RailItem(
    icon: ImageVector,
    label: String,
    count: Int? = null,
    selected: Boolean = false,
    collapsed: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (collapsed) 0.dp else 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.spacedBy(11.dp),
        ) {
            Icon(
                icon,
                contentDescription = if (collapsed) label else null,
                modifier = Modifier.size(20.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!collapsed) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (count != null) {
                    Text(
                        count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

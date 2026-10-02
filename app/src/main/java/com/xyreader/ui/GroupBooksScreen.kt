package com.xyreader.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xyreader.core.BookEntity
import com.xyreader.core.SortOption
import kotlinx.coroutines.launch

/** 自定义分组书列表；长按拖到上方分类可移组，拖到删除区会先请求确认。 */
@Composable
fun GroupBooksScreen(groupId: Long, onBack: () -> Unit, onOpenBook: (Long) -> Unit) {
    val repo = rememberLibraryRepository()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val groups by repo.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    val groupName = remember(groups, groupId) { groups.firstOrNull { it.id == groupId }?.name ?: "分组" }
    val booksFlow = remember(repo, groupId) { repo.booksInGroup(groupId, SortOption.ADDED) }
    val books by booksFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val dragState = remember { BookGridDragState() }
    val dropTargets = remember(groups, groupId) {
        listOf(BookDropTarget("group-ungrouped", null, "未分组")) + groups
            .filterNot { it.id == groupId }
            .map { BookDropTarget("group-target-${it.id}", it.id, it.name) }
    }

    val onToggleFavorite: (BookEntity) -> Unit = { book -> scope.launch { repo.toggleFavorite(book.id) } }
    val onDeleteBook: (BookEntity) -> Unit = { book -> scope.launch { repo.deleteBook(book.id) } }
    val onMoveToGroup: (BookEntity, Long?) -> Unit = { book, targetGroupId ->
        scope.launch { repo.moveBookToGroup(book.id, targetGroupId) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ShelfSubpageHead(
                title = groupName,
                subtitle = "${books.size} 本书",
                onBack = onBack,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                dropTargets.forEach { target -> BookDropTargetChip(target, dragState) }
            }
            if (books.isEmpty()) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().weight(1f)) {
                    EmptyState(
                        icon = Icons.Outlined.FolderOpen,
                        title = "这个分组还是空的",
                        subtitle = "回到首页，从封面三点菜单移动书籍，或拖到分组标签",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                BookGrid(
                    books = books,
                    onOpenBook = onOpenBook,
                    onToggleFavorite = onToggleFavorite,
                    onDeleteBook = onDeleteBook,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    onMoveToGroup = onMoveToGroup,
                    dropTargets = dropTargets,
                    dragState = dragState,
                    onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
                )
            }
        }
    }
}

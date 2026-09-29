package com.xyreader.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xyreader.core.BookEntity
import com.xyreader.core.QuickRead
import com.xyreader.core.ShelfSection
import com.xyreader.core.SortOption
import com.xyreader.data.LibraryLayoutStore
import com.xyreader.data.applyHomeBookOrder
import com.xyreader.data.reorderVisibleBookIds
import kotlinx.coroutines.launch

/** 首页：搜索、分类、封面网格；长按拖动可手动排序、分组或删除；右下角「开始阅读」菜单（四选项）。 */
@Composable
fun HomeScreen(
    onOpenBook: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReader: (Long) -> Unit,
) {
    val repo = rememberLibraryRepository()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val layoutStore = remember(context) { LibraryLayoutStore(context) }
    val layout by layoutStore.layout.collectAsStateWithLifecycle(initialValue = com.xyreader.data.LibraryLayout())
    val groups by repo.groups.collectAsStateWithLifecycle(initialValue = emptyList())

    val scan = rememberRepoScanHandle(repo) { report ->
        scope.launch { snackbar.showSnackbar("新增 ${report.added} 本") }
    }

    var sort by rememberSaveable { mutableStateOf(SortOption.RECENT_READ) }
    var query by rememberSaveable { mutableStateOf("") }
    var activeCategory by rememberSaveable { mutableStateOf("all") }
    var showAddGroup by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var previewOrder by remember { mutableStateOf<List<Long>?>(null) }

    val allBooks by remember(repo, sort) {
        repo.books(ShelfSection.ALL, sort, "")
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(groups, activeCategory) {
        if (activeCategory.startsWith("group:")) {
            val id = activeCategory.removePrefix("group:").toLongOrNull()
            if (id == null || groups.none { it.id == id }) activeCategory = "all"
        }
    }
    LaunchedEffect(layout.homeBookOrder) {
        if (previewOrder == layout.homeBookOrder) previewOrder = null
    }

    val storedOrder = previewOrder ?: layout.homeBookOrder
    val manualOrderActive = layout.homeManualOrder || previewOrder != null
    val orderedBooks = remember(allBooks, storedOrder, manualOrderActive) {
        if (manualOrderActive) applyHomeBookOrder(allBooks, storedOrder) else allBooks
    }
    val queryBooks = remember(orderedBooks, query) {
        if (query.isBlank()) orderedBooks else orderedBooks.filter { it.title.contains(query, ignoreCase = true) }
    }
    val visibleBooks = remember(queryBooks, activeCategory) {
        when {
            activeCategory == "ungrouped" -> queryBooks.filter { it.groupId == null }
            activeCategory.startsWith("group:") -> {
                val id = activeCategory.removePrefix("group:").toLongOrNull()
                queryBooks.filter { it.groupId == id }
            }
            else -> queryBooks
        }
    }
    val selectedLabel = when {
        activeCategory == "ungrouped" -> "未分组"
        activeCategory.startsWith("group:") -> groups.firstOrNull {
            it.id == activeCategory.removePrefix("group:").toLongOrNull()
        }?.name ?: "分组"
        else -> "全部"
    }

    val dragState = remember { BookGridDragState() }
    val dropTargets = remember(groups) {
        listOf(BookDropTarget("home-ungrouped", null, "未分组")) + groups.map { group ->
            BookDropTarget("home-group-${group.id}", group.id, group.name)
        }
    }
    val onToggleFavorite: (BookEntity) -> Unit = { book -> scope.launch { repo.toggleFavorite(book.id) } }
    val onDeleteBook: (BookEntity) -> Unit = { book -> scope.launch { repo.deleteBook(book.id) } }
    val onMoveToGroup: (BookEntity, Long?) -> Unit = { book, groupId ->
        scope.launch { repo.moveBookToGroup(book.id, groupId) }
    }
    val onReorder: (List<BookEntity>) -> Unit = { reorderedVisible ->
        val reorderedIds = reorderVisibleBookIds(
            allBookIds = orderedBooks.map(BookEntity::id),
            visibleBookIds = visibleBooks.map(BookEntity::id),
            reorderedVisibleIds = reorderedVisible.map(BookEntity::id),
        )
        previewOrder = reorderedIds
        scope.launch { layoutStore.setHomeManualOrder(reorderedIds) }
    }

    // —— 右下角「开始阅读」菜单：四个动作的选取与兜底提示 ——
    var readMenuOpen by remember { mutableStateOf(false) }
    val onQuickRead: (QuickReadKind) -> Unit = { kind ->
        readMenuOpen = false
        val book = when (kind) {
            QuickReadKind.SHELF_LAST -> QuickRead.lastRead(visibleBooks)
            QuickReadKind.LAST -> QuickRead.lastRead(allBooks)
            QuickReadKind.SHELF_RANDOM -> QuickRead.random(visibleBooks)
            QuickReadKind.RANDOM -> QuickRead.random(allBooks)
        }
        if (book == null) {
            val message = when (kind) {
                QuickReadKind.LAST -> "还没有任何阅读记录"
                QuickReadKind.SHELF_LAST ->
                    if (visibleBooks.isEmpty()) "当前分类是空的" else "当前分类还没有阅读记录"
                QuickReadKind.SHELF_RANDOM ->
                    if (allBooks.isEmpty()) "书架空空如也，先导入一些书吧" else "当前分类是空的"
                QuickReadKind.RANDOM -> "书架空空如也，先导入一些书吧"
            }
            scope.launch { snackbar.showSnackbar(message) }
        } else {
            onOpenReader(book.id)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
            SearchPill(
                query = query,
                onQueryChange = { query = it },
                sort = sort,
                manualOrderActive = manualOrderActive,
                onSortChange = { option ->
                    sort = option
                    previewOrder = null
                    scope.launch { layoutStore.useAutomaticHomeOrder() }
                },
                onManualOrder = {
                    previewOrder = orderedBooks.map(BookEntity::id)
                    scope.launch { layoutStore.setHomeManualOrder(orderedBooks.map(BookEntity::id)) }
                },
                onOpenSettings = onOpenSettings,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selectedLabel,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(10.dp))
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(
                        "${visibleBooks.size} 本",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (scan.isScanning) {
                    Spacer(Modifier.width(12.dp))
                    ScanningCaption()
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeCategoryChip("全部", activeCategory == "all", onClick = { activeCategory = "all" })
                HomeCategoryChip(
                    label = "未分组",
                    selected = activeCategory == "ungrouped",
                    onClick = { activeCategory = "ungrouped" },
                    modifier = Modifier.onGloballyPositioned {
                        dragState.registerTarget("home-ungrouped", it.boundsInRoot())
                    },
                )
                groups.forEach { group ->
                    val key = "group:${group.id}"
                    HomeCategoryChip(
                        label = group.name,
                        selected = activeCategory == key,
                        onClick = { activeCategory = key },
                        modifier = Modifier.onGloballyPositioned {
                            dragState.registerTarget("home-group-${group.id}", it.boundsInRoot())
                        },
                    )
                }
                Surface(
                    onClick = { newGroupName = ""; showAddGroup = true },
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新建分组", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }

            when {
                visibleBooks.isEmpty() && !scan.isScanning -> {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        EmptyState(
                            icon = Icons.Outlined.FolderOpen,
                            title = when {
                                allBooks.isEmpty() -> "书架空空如也"
                                query.isNotBlank() -> "没有找到匹配的书"
                                else -> "这个分类还是空的"
                            },
                            subtitle = when {
                                allBooks.isEmpty() -> "点「书架」页右上角 + 导入你的漫画或小说文件夹"
                                query.isNotBlank() -> "试试其他关键词，或切换分类"
                                else -> "在封面三点菜单选择分组，或长按拖到上方分类"
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                visibleBooks.isEmpty() && scan.isScanning -> Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
                else -> BookGrid(
                    books = visibleBooks,
                    onOpenBook = onOpenBook,
                    onToggleFavorite = onToggleFavorite,
                    onDeleteBook = onDeleteBook,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    onMoveToGroup = onMoveToGroup,
                    onReorderBooks = onReorder,
                    dropTargets = dropTargets,
                    dragState = dragState,
                    onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
                )
            }
        }
            // —— 右下角「开始阅读」：播放键 + 悬浮胶囊菜单（作用于当前所选分类）——
            QuickReadMenuOverlay(
                open = readMenuOpen,
                onToggle = { readMenuOpen = !readMenuOpen },
                onDismiss = { readMenuOpen = false },
                onPick = onQuickRead,
            )
        }
    }

    if (showAddGroup) {
        val name = newGroupName.trim()
        val duplicate = groups.any { it.name.equals(name, ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { showAddGroup = false },
            title = { Text("新建分组") },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
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
                        scope.launch {
                            val id = repo.addGroup(name)
                            activeCategory = "group:$id"
                            showAddGroup = false
                        }
                    },
                    enabled = name.isNotEmpty() && !duplicate,
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showAddGroup = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    sort: SortOption,
    manualOrderActive: Boolean,
    onSortChange: (SortOption) -> Unit,
    onManualOrder: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(52.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, contentDescription = "搜索", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("搜索漫画或小说", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
            Box {
                IconButton(onClick = { sortMenuOpen = true }) {
                    Icon(Icons.Outlined.FilterList, contentDescription = "排序筛选", tint = if (manualOrderActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("手动排序（长按拖动）", color = if (manualOrderActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                        leadingIcon = { if (manualOrderActive) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) else Spacer(Modifier.size(18.dp)) },
                        onClick = { sortMenuOpen = false; onManualOrder() },
                    )
                    SortOption.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label, color = if (!manualOrderActive && option == sort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                            leadingIcon = {
                                if (!manualOrderActive && option == sort) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                else Spacer(Modifier.size(18.dp))
                            },
                            onClick = { sortMenuOpen = false; onSortChange(option) },
                        )
                    }
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HomeCategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

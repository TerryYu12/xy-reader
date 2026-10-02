package com.xyreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.PlayArrow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.xyreader.R
import com.xyreader.core.BookEntity
import com.xyreader.core.QuickRead
import com.xyreader.core.ShelfSection
import com.xyreader.core.SortOption
import com.xyreader.data.LibraryLayoutStore
import com.xyreader.data.applyHomeBookOrder
import com.xyreader.data.reorderVisibleBookIds
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 首页：品牌顶栏（搜索 + 主题切换 + 设置）、继续阅读焦点卡、分类、封面网格；长按拖动可手动排序、分组或删除；右下角「开始阅读」菜单（四选项）。 */
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

    // —— 「继续阅读」焦点卡：取最近阅读（lastReadAt 最大且有进度）的一本 ——
    val recentBook = remember(allBooks) {
        allBooks.filter { it.lastReadAt != null && it.currentPage > 0 }
            .maxByOrNull { it.lastReadAt ?: 0L }
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
                HomeTopBar(
                    query = query,
                    onQueryChange = { query = it },
                    onOpenSettings = onOpenSettings,
                )

                // 「继续阅读」焦点卡：无符合条件的书时整卡不显示
                recentBook?.let { book ->
                    ContinueReadingCard(book = book, onContinue = { onOpenReader(book.id) })
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
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
                        color = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
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

                // 数量 + 排序行：左侧「N 本书」，右侧带当前排序标签的下拉按钮
                LibraryToolbar(
                    count = visibleBooks.size,
                    scanning = scan.isScanning,
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
                )

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

/**
 * 首页顶栏：左侧品牌（图标 + XY-READER）+ 描边胶囊搜索框 + 主题切换钮 + 设置齿轮。
 * 版式取自设计源 `.topbar` / `.mobile-brand` / `.top-search` / `.top-action`：
 * 单行 flex，窄屏（<=360dp）隐藏品牌文字只留图标，搜索框收缩占满剩余宽度。
 */
@Composable
private fun HomeTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val themeMode = LocalThemeMode.current
    val setThemeMode = LocalSetThemeMode.current
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        // 窄屏隐藏品牌文字（对应设计稿 max-width:360px 断点）
        val showBrandText = maxWidth > 360.dp
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(30.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (showBrandText) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "XY-READER",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(10.dp))
            SearchField(query = query, onQueryChange = onQueryChange, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { setThemeMode(if (dark) ThemeMode.LIGHT else ThemeMode.DARK) }) {
                Icon(
                    // 深色时显太阳（切浅色），浅色时显月亮（切深色）
                    if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                    contentDescription = "切换明暗主题",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 描边胶囊搜索框：放大镜 + 提示文案「搜索书名」。 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(start = 13.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = "搜索",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "搜索书名",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/**
 * 「继续阅读」焦点卡（对应设计源 `.continue-panel` / `.continue-strip`）：
 * 左列书名 + 「第 N 页 · P%」+ 细进度条 + 「继续阅读」胶囊按钮，右侧微倾的小封面。
 */
@Composable
private fun ContinueReadingCard(book: BookEntity, onContinue: () -> Unit) {
    val progress = readProgress(book)
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    ),
                ),
            )
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), shape)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "继续阅读",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "第 ${book.currentPage} 页 · $progress%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (progress > 0) {
                    Spacer(Modifier.height(12.dp))
                    ProgressTrack(progress = progress, height = 5.dp)
                }
                Spacer(Modifier.height(14.dp))
                Surface(
                    onClick = onContinue,
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("继续阅读", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            // 小封面：无封面时降级为底色 + 首字（演示稿的倾斜艺术封面属演示素材）
            Box(
                modifier = Modifier.size(width = 84.dp, height = 116.dp)
                    .rotate(4f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = book.coverPath?.let(::File),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                if (book.coverPath == null) {
                    Text(
                        book.title.firstOrNull()?.toString() ?: "书",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

/** 数量 + 排序行（对应设计源 `.library-toolbar`）：左侧「N 本书」，右侧带当前排序标签的下拉按钮。 */
@Composable
private fun LibraryToolbar(
    count: Int,
    scanning: Boolean,
    sort: SortOption,
    manualOrderActive: Boolean,
    onSortChange: (SortOption) -> Unit,
    onManualOrder: () -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$count 本书",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (scanning) {
            ScanningCaption()
            Spacer(Modifier.width(12.dp))
        }
        Box {
            Surface(
                onClick = { sortMenuOpen = true },
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.FilterList,
                        contentDescription = "排序筛选",
                        tint = if (manualOrderActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (manualOrderActive) "手动排序" else sort.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
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
    }
}

/** 细进度条：底色轨道 + 主色填充；只填 progress>0 的部分。 */
@Composable
private fun ProgressTrack(progress: Int, height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(height)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f)),
    ) {
        if (progress > 0) {
            Box(
                Modifier.fillMaxHeight()
                    .fillMaxWidth((progress.coerceIn(1, 100)) / 100f)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** 阅读进度百分比（无总页数时按 0 处理）。 */
private fun readProgress(book: BookEntity): Int =
    if (book.totalPages > 0 && book.currentPage > 0) {
        (book.currentPage * 100f / book.totalPages).roundToInt().coerceIn(0, 100)
    } else 0

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
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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

package com.xyreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
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
import com.xyreader.data.ArkDatabase
import com.xyreader.data.LibraryLayoutStore
import com.xyreader.data.applyHomeBookOrder
import com.xyreader.data.reorderVisibleBookIds
import com.xyreader.stats.computeReadingStats
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 首页：品牌顶栏、继续阅读、紧凑分组条与封面网格；
 * 长按封面不动松手进入多选（批量转移 / 收藏 / 清除记录 / 删除），长按并拖动可排序、分组或删除。
 */
@Composable
fun HomeScreen(
    onOpenBook: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReader: (Long) -> Unit,
    onOpenGroup: (Long) -> Unit,
    onOpenGroupManage: () -> Unit,
    onOpenStats: () -> Unit,
) {
    val repo = rememberLibraryRepository()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val layoutStore = remember(context) { LibraryLayoutStore(context) }
    val layout by layoutStore.layout.collectAsStateWithLifecycle(initialValue = com.xyreader.data.LibraryLayout())
    val groups by repo.groups.collectAsStateWithLifecycle(initialValue = emptyList())

    // 顶部「阅读统计」卡：读本机 daily_reading 表（与统计页同一数据源），
    // 读不到真实记录时就显示真实空状态，不虚构数值。
    val dailyStatsFlow = remember(context) { ArkDatabase.getInstance(context).readingStatsDao().observeAll() }
    val dailyStats by dailyStatsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val readingStats = remember(dailyStats) { computeReadingStats(dailyStats, LocalDate.now()) }

    val scan = rememberRepoScanHandle(repo) { report ->
        scope.launch { snackbar.showSnackbar("新增 ${report.added} 本") }
    }

    var sort by rememberSaveable { mutableStateOf(SortOption.RECENT_READ) }
    var query by rememberSaveable { mutableStateOf("") }
    // 顶栏搜索：默认收起（规范 2.1/3.1 要求删除常驻搜索输入框，改为搜索图标按钮）
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    // 首页分组筛选：null = 系统聚合项「全部」（含未分组书籍）
    var selectedGroupId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAddGroup by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var previewOrder by remember { mutableStateOf<List<Long>?>(null) }
    var pendingGroupCoverId by remember { mutableStateOf<Long?>(null) }
    val groupCoverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val groupId = pendingGroupCoverId
        pendingGroupCoverId = null
        if (uri != null && groupId != null) {
            scope.launch {
                val imported = repo.importGroupCover(groupId, uri)
                snackbar.showSnackbar(if (imported) "分组封面已更新" else "分组封面导入失败")
            }
        }
    }

    val allBooks by remember(repo, sort) {
        repo.books(ShelfSection.ALL, sort, "")
    }.collectAsStateWithLifecycle(initialValue = emptyList())

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
    // 分组筛选（规范 3.3）：只改变下方书籍集合，不改动顶部卡片结构
    val visibleBooks = remember(queryBooks, selectedGroupId) {
        if (selectedGroupId == null) queryBooks else queryBooks.filter { it.groupId == selectedGroupId }
    }

    // —— 「继续阅读」焦点卡：取最近阅读（lastReadAt 最大且有进度）的一本 ——
    val recentBook = remember(allBooks) {
        allBooks.filter { it.lastReadAt != null && it.currentPage > 0 }
            .maxByOrNull { it.lastReadAt ?: 0L }
    }

    val dragState = remember { BookGridDragState() }
    // 多选状态由首页持有，以便多选时隐藏右下角的「开始阅读」悬浮按钮
    val selection = remember { BookSelectionState() }
    val dropTargets = remember(groups) {
        listOf(BookDropTarget("home-ungrouped", null, "未分组")) + groups.map { group ->
            BookDropTarget("home-folder-${group.id}", group.id, group.name)
        }
    }
    val groupBookCounts = remember(groups, allBooks) {
        groups.associate { group -> group.id to allBooks.count { it.groupId == group.id } }
    }
    // —— 传给网格的回调一律 remember ——
    // 否则每次重组（例如统计卡每 30 秒刷新、搜索框输入）都会生成新的 lambda 实例，
    // BookGrid 因参数 equals 不成立而整块重组，网格里所有卡片的封面、角标、菜单都会跟着重建。
    val onToggleFavorite: (BookEntity) -> Unit = remember(repo, scope) {
        { book -> scope.launch { repo.toggleFavorite(book.id) } }
    }
    val onDeleteBook: (BookEntity) -> Unit = remember(repo, scope) {
        { book -> scope.launch { repo.deleteBook(book.id) } }
    }
    val onMoveToGroup: (BookEntity, Long?) -> Unit = remember(repo, scope) {
        { book, groupId -> scope.launch { repo.moveBookToGroup(book.id, groupId) } }
    }
    val onReorder: (List<BookEntity>) -> Unit = remember(orderedBooks, visibleBooks, scope, layoutStore) {
        { reorderedVisible ->
            val reorderedIds = reorderVisibleBookIds(
                allBookIds = orderedBooks.map(BookEntity::id),
                visibleBookIds = visibleBooks.map(BookEntity::id),
                reorderedVisibleIds = reorderedVisible.map(BookEntity::id),
            )
            previewOrder = reorderedIds
            scope.launch { layoutStore.setHomeManualOrder(reorderedIds) }
        }
    }
    // 网格内边距是常量，固定复用同一个 PaddingValues 实例
    val gridContentPadding = remember { PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp) }

    // —— 右下角「开始阅读」菜单：四个动作的选取与兜底提示 ——
    var readMenuOpen by remember { mutableStateOf(false) }
    val onQuickRead: (QuickReadKind) -> Unit = remember(visibleBooks, allBooks, scope, snackbar) {
      { kind ->
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
                    if (visibleBooks.isEmpty()) "当前搜索结果为空" else "当前书架还没有阅读记录"
                QuickReadKind.SHELF_RANDOM ->
                    if (allBooks.isEmpty()) "书架空空如也，先导入一些书吧"
                    else if (visibleBooks.isEmpty()) "当前搜索结果为空" else "当前书架是空的"
                QuickReadKind.RANDOM -> "书架空空如也，先导入一些书吧"
            }
            scope.launch { snackbar.showSnackbar(message) }
        } else {
            onOpenReader(book.id)
        }
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
                    searchOpen = searchOpen,
                    onToggleSearch = {
                        val next = !searchOpen
                        searchOpen = next
                        // 收起搜索时清空关键词，避免留下看不见的过滤条件
                        if (!next) query = ""
                    },
                    onOpenSettings = onOpenSettings,
                )

                // 首页顶部两卡并排（规范 3.2）：左「继续阅读」较宽（2 份）＋右「阅读统计」（1 份）
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ContinueReadingCard(
                        book = recentBook,
                        onContinue = { recentBook?.let { onOpenReader(it.id) } },
                        modifier = Modifier.weight(2f).fillMaxHeight(),
                    )
                    ReadingStatsCard(
                        todayMs = readingStats.todayDurationMs,
                        streakDays = readingStats.currentStreak,
                        hasRecord = readingStats.totalDurationMs > 0L || readingStats.readingDays > 0,
                        onClick = onOpenStats,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }

                HomeGroupFolderSection(
                    groups = groups,
                    bookCounts = groupBookCounts,
                    totalCount = allBooks.size,
                    selectedGroupId = selectedGroupId,
                    dragState = dragState,
                    onSelectGroup = { selectedGroupId = it },
                    onOpenGroupManage = onOpenGroupManage,
                    onRenameGroup = { groupId, name ->
                        val duplicate = groups.any {
                            it.id != groupId && it.name.equals(name.trim(), ignoreCase = true)
                        }
                        if (duplicate) {
                            scope.launch { snackbar.showSnackbar("已有同名分组") }
                        } else {
                            scope.launch {
                                repo.renameGroup(groupId, name.trim())
                                snackbar.showSnackbar("分组已重命名")
                            }
                        }
                    },
                    onPickCover = { groupId ->
                        pendingGroupCoverId = groupId
                        groupCoverPicker.launch("image/*")
                    },
                    onClearCover = { groupId ->
                        scope.launch {
                            val cleared = repo.setGroupCover(groupId, null)
                            snackbar.showSnackbar(if (cleared) "已清除分组封面" else "清除分组封面失败")
                        }
                    },
                    onCreateGroup = { newGroupName = ""; showAddGroup = true },
                    onDeleteGroup = { groupId ->
                        scope.launch {
                            repo.removeGroup(groupId)
                            snackbar.showSnackbar("分组已删除，组内书已移至未分组")
                        }
                    },
                )

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
                                    else -> "书架里暂时没有书"
                                },
                                subtitle = when {
                                    allBooks.isEmpty() -> "点「书架」页右上角 + 导入你的漫画或小说文件夹"
                                    query.isNotBlank() -> "试试其他书名关键词"
                                    else -> "在封面三点菜单转移书籍，长按封面可多选，或拖到上方分组"
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
                        contentPadding = gridContentPadding,
                        onMoveToGroup = onMoveToGroup,
                        onReorderBooks = onReorder,
                        dropTargets = dropTargets,
                        dragState = dragState,
                        onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
                        selectionState = selection,
                    )
                }
            }
            // —— 右下角「开始阅读」：当前书架动作使用搜索结果作为范围；多选时让位给底部批量操作栏 ——
            if (!selection.active) {
                QuickReadMenuOverlay(
                    open = readMenuOpen,
                    onToggle = { readMenuOpen = !readMenuOpen },
                    onDismiss = { readMenuOpen = false },
                    onPick = onQuickRead,
                )
            }
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
                            repo.addGroup(name)
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
    searchOpen: Boolean,
    onToggleSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val themeMode = LocalThemeMode.current
    val setThemeMode = LocalSetThemeMode.current
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 窄屏隐藏品牌文字（对应设计稿 max-width:360px 断点）
            val showBrandText = maxWidth > 360.dp
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 左上角继续使用应用自身的图标（前景图），底色改为跟随「设置 → 强调色」的渐变，
                // 不再固定使用 ic_launcher_background 的固定色块。
                val accent = LocalThemeAccent.current
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(accent.primary(dark), accent.container(dark)),
                            ),
                        ),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = "XY-READER",
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
                Spacer(Modifier.weight(1f))
                // 右侧固定顺序：搜索｜日夜切换｜设置（设置最右）；常驻搜索输入框已移除
                IconButton(onClick = onToggleSearch) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = if (searchOpen) "关闭搜索" else "搜索",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { setThemeMode(if (dark) ThemeMode.LIGHT else ThemeMode.DARK) }) {
                    Icon(
                        // 深色时显太阳（切浅色），浅色时显月亮（切深色）
                        if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                        contentDescription = if (dark) "切换到浅色模式" else "切换到深色模式",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // 搜索输入框默认隐藏，点搜索图标临时展开；收起时清空关键词，避免不可见的过滤条件
        if (searchOpen) {
            Spacer(Modifier.height(8.dp))
            SearchField(query = query, onQueryChange = onQueryChange, modifier = Modifier.fillMaxWidth())
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
 * 首页顶部左侧「继续阅读」卡（规范 3.2）：标题「继续阅读」+ 小封面 + 真实书名 +
 * 现有位置信息 + 继续阅读按钮；不加独立进度条，标题前不加装饰性小图标。
 * 没有可继续的书时显示真实空状态。
 */
@Composable
private fun ContinueReadingCard(
    book: BookEntity?,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            "继续阅读",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        if (book == null) {
            Text(
                "还没有阅读记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "打开一本书开始阅读",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            val progress = readProgress(book)
            // 位置信息沿用现有定位单位，读不到总页数时不编造页数
            val position = when {
                book.totalPages > 0 && book.currentPage > 0 -> "第 ${book.currentPage} 页 · $progress%"
                book.currentPage > 0 -> "第 ${book.currentPage} 页"
                else -> "尚未开始"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 按路径记住 File：否则父层重组时每次新建对象，Coil 会跟着重建请求
                val coverFile = remember(book.coverPath) { book.coverPath?.let(::File) }
                Box(
                    modifier = Modifier.size(width = 34.dp, height = 46.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (book.coverPath == null) {
                        DefaultBookCover(
                            book = book,
                            modifier = Modifier.fillMaxSize(),
                            compact = true,
                            showFormatBadge = false,
                        )
                    }
                    AsyncImage(
                        model = coverFile,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    MarqueeTitle(
                        text = book.title,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        position,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // 继续阅读按钮：保留三角符号，方角矩形，与右下角圆形悬浮按钮区分
            Surface(
                onClick = onContinue,
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "继续阅读",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 首页顶部右侧「阅读统计」卡（规范 3.2）：标题 + 三根竖柱图标，摘要用真实可取得的
 * 指标（今日时长、当前连续打卡天数）；没有记录时显示真实空状态。整卡点击进入统计详情。
 */
@Composable
private fun ReadingStatsCard(
    todayMs: Long,
    streakDays: Int,
    hasRecord: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThreeBarsIcon()
            Spacer(Modifier.width(4.dp))
            Text(
                "阅读统计",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(10.dp))
        if (hasRecord) {
            val minutes = (todayMs / 60_000L).coerceAtLeast(0L)
            Text(
                "今日阅读",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                if (minutes <= 0L) "<1 分钟" else "$minutes 分钟",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "连续 $streakDays 天",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        } else {
            Text(
                "暂无阅读记录",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/** 阅读统计的简洁三根竖柱图标（不加横线或装饰）。 */
@Composable
private fun ThreeBarsIcon(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        listOf(7.dp, 12.dp, 9.dp).forEach { barHeight ->
            Box(
                Modifier
                    .width(3.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
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

/** 阅读进度百分比（无总页数时按 0 处理）。 */
private fun readProgress(book: BookEntity): Int =
    if (book.totalPages > 0 && book.currentPage > 0) {
        (book.currentPage * 100f / book.totalPages).roundToInt().coerceIn(0, 100)
    } else 0

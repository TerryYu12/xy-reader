package com.xyreader.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xyreader.core.BookEntity
import com.xyreader.core.BookGroupEntity
import com.xyreader.core.BookmarkEntity
import com.xyreader.core.LibraryRepository
import com.xyreader.core.QuickRead
import com.xyreader.core.ShelfSection
import com.xyreader.core.SortOption
import kotlinx.coroutines.launch

/**
 * 书架页（按 Codex 设计稿 `shelfView()` 落地）：
 * `.page-head`（eyebrow「YOUR COLLECTION」+ 大标题「书架」+ 说明行，右侧主色「添加仓库」胶囊——
 * 复用既有扫描入口）→ 三张统计卡（书库 / 正在读 / 收藏）→ `.section-head`「我的书架」→
 * 2×2 `.shelf-tile` 入口磁贴（全部 / 收藏 / 历史 / 书签，各带专属强调色图标块与真实计数）→
 * 「分组」区（`.group-pill` 行 + 「管理分组」胶囊）→「最近阅读」区（最近 3 本，直接复用 BookGrid 书卡）；
 * 右下角保留既有「开始阅读」悬浮菜单。
 *
 * 整页用 [LazyVerticalGrid]（2 列）承载：标题/统计/分组等横跨整行（[GridItemSpan] 全宽），
 * 磁贴与最近阅读书卡各占一格，避免在 `verticalScroll` 里嵌套懒加载网格。
 */
@Composable
fun ShelfScreen(
    onOpenBook: (Long) -> Unit,
    onOpenSection: (ShelfSection) -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenGroup: (Long) -> Unit,
    onOpenGroupManage: () -> Unit,
    onOpenReader: (Long) -> Unit,
) {
    val repo = rememberLibraryRepository()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scan = rememberRepoScanHandle(repo) { report ->
        scope.launch { snackbar.showSnackbar("新增 ${report.added} 本") }
    }

    // 真实数据源：全部书籍 / 书签 / 自定义分组
    val allBooks by repo.books.collectAsStateWithLifecycle(initialValue = emptyList())
    val bookmarks by repo.bookmarks.collectAsStateWithLifecycle(initialValue = emptyList())
    val groups by repo.groups.collectAsStateWithLifecycle(initialValue = emptyList())

    // 计数（真实数据；历史 = 有过阅读记录的书）
    val favoriteCount = allBooks.count { it.isFavorite }
    val readingCount = allBooks.count { it.lastReadAt != null }
    val recentBooks = remember(allBooks) {
        allBooks.filter { it.lastReadAt != null }
            .sortedByDescending { it.lastReadAt ?: 0L }
            .take(3)
    }

    val onToggleFavorite: (BookEntity) -> Unit = { book -> scope.launch { repo.toggleFavorite(book.id) } }
    val onDeleteBook: (BookEntity) -> Unit = { book -> scope.launch { repo.deleteBook(book.id) } }
    val onMoveToGroup: (BookEntity, Long?) -> Unit = { book, groupId ->
        scope.launch { repo.moveBookToGroup(book.id, groupId) }
    }

    // —— 右下角「开始阅读」菜单（书架页无分类筛选，四项均作用于全库）——
    var readMenuOpen by remember { mutableStateOf(false) }
    val onQuickRead: (QuickReadKind) -> Unit = { kind ->
        readMenuOpen = false
        val book = when (kind) {
            QuickReadKind.SHELF_LAST, QuickReadKind.LAST -> QuickRead.lastRead(allBooks)
            QuickReadKind.SHELF_RANDOM, QuickReadKind.RANDOM -> QuickRead.random(allBooks)
        }
        if (book == null) {
            val message = when (kind) {
                QuickReadKind.LAST, QuickReadKind.SHELF_LAST -> "还没有任何阅读记录"
                else -> "书架空空如也，先导入一些书吧"
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
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // —— 页头：eyebrow + 大标题 + 说明行 + 主色添加仓库按钮 ——
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ShelfPageHead(scan)
                }
                // —— 统计卡行：书库 / 正在读 / 收藏 ——
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ShelfSummaryRow(
                        total = allBooks.size,
                        reading = readingCount,
                        favorite = favoriteCount,
                    )
                }
                // —— 我的书架：2×2 入口磁贴 ——
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ShelfSectionHead(
                        title = "我的书架",
                        subtitle = "按内容类型快速打开。",
                        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                    )
                }
                item {
                    ShelfEntryCard(
                        icon = Icons.Outlined.FolderOpen,
                        title = "全部",
                        count = allBooks.size,
                        countSuffix = "项",
                        onClick = { onOpenSection(ShelfSection.ALL) },
                    )
                }
                item {
                    ShelfEntryCard(
                        icon = Icons.Outlined.FavoriteBorder,
                        title = "收藏",
                        count = favoriteCount,
                        countSuffix = "项",
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        onClick = { onOpenSection(ShelfSection.FAVORITE) },
                    )
                }
                item {
                    ShelfEntryCard(
                        icon = Icons.Outlined.History,
                        title = "历史",
                        count = readingCount,
                        countSuffix = "项",
                        iconTint = accentColor(AccentColor.GREEN),
                        onClick = { onOpenSection(ShelfSection.HISTORY) },
                    )
                }
                item {
                    ShelfEntryCard(
                        icon = Icons.Outlined.Bookmarks,
                        title = "书签",
                        count = bookmarks.size,
                        countSuffix = "项",
                        iconTint = accentColor(AccentColor.GOLD),
                        onClick = onOpenBookmarks,
                    )
                }

                // —— 分组区（无分组时整区隐藏）——
                if (groups.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ShelfSectionHead(
                            title = "分组",
                            subtitle = "自定义整理方式",
                            action = "管理",
                            onAction = onOpenGroupManage,
                            modifier = Modifier.padding(top = 20.dp),
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ShelfGroupPills(
                            groups = groups,
                            countOf = { group -> allBooks.count { it.groupId == group.id } },
                            onOpenGroup = onOpenGroup,
                            onOpenGroupManage = onOpenGroupManage,
                        )
                    }
                }

                // —— 最近阅读（进度 > 0 的书按最近阅读序取 3）——
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ShelfSectionHead(
                        title = "最近阅读",
                        subtitle = "继续上一次打开的书。",
                        action = "继续阅读",
                        onAction = { recentBooks.firstOrNull()?.let { onOpenReader(it.id) } },
                        modifier = Modifier.padding(top = 20.dp),
                    )
                }
                items(recentBooks, key = { it.id }) { book ->
                    BookCard(
                        book = book,
                        groups = groups,
                        onOpenBook = onOpenBook,
                        onToggleFavorite = onToggleFavorite,
                        onDeleteBook = onDeleteBook,
                        onMoveToGroup = { groupId -> onMoveToGroup(book, groupId) },
                    )
                }
            }

            QuickReadMenuOverlay(
                open = readMenuOpen,
                onToggle = { readMenuOpen = !readMenuOpen },
                onDismiss = { readMenuOpen = false },
                onPick = onQuickRead,
            )
        }
    }
}

/** `.page-head`：左侧 eyebrow + 大标题 + 说明行，右侧主色「添加仓库」胶囊（复用既有扫描入口）。 */
@Composable
private fun ShelfPageHead(scan: RepoScanHandle) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "YOUR COLLECTION",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "书架",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "收藏、历史记录和书签都在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (scan.isScanning) {
            ScanningCaption(text = "扫描中")
        } else {
            Surface(
                onClick = scan::launch,
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                    Text("添加仓库", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** `.summary-row`：书库 / 正在读 / 收藏 三张统计卡。 */
@Composable
private fun ShelfSummaryRow(total: Int, reading: Int, favorite: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SummaryCard("书库", total, Modifier.weight(1f))
        SummaryCard("正在读", reading, Modifier.weight(1f))
        SummaryCard("收藏", favorite, Modifier.weight(1f))
    }
}

@Composable
private fun SummaryCard(label: String, value: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$value",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    "本",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * `.section-head`：左标题 + 副标题，右侧可选文本钮（带 chevron）。
 * 无 action 时右侧留空。
 */
@Composable
private fun ShelfSectionHead(
    title: String,
    subtitle: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.width(12.dp))
            Surface(
                onClick = onAction,
                shape = RoundedCornerShape(10.dp),
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(action, style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** `.group-list`：分组胶囊（folder + 组名 + 计数）+ 行尾「管理分组」描边胶囊。 */
@Composable
private fun ShelfGroupPills(
    groups: List<BookGroupEntity>,
    countOf: (BookGroupEntity) -> Int,
    onOpenGroup: (Long) -> Unit,
    onOpenGroupManage: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        groups.forEach { group ->
            GroupPill(
                label = group.name,
                count = countOf(group),
                leadingIcon = Icons.Outlined.FolderOpen,
                onClick = { onOpenGroup(group.id) },
            )
        }
        GroupPill(
            label = "管理分组",
            count = null,
            leadingIcon = Icons.Outlined.Add,
            onClick = onOpenGroupManage,
        )
    }
}

@Composable
private fun GroupPill(
    label: String,
    count: Int?,
    leadingIcon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 43.dp).padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (count != null) {
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * `.shelf-tile` 入口磁贴：图标块在上方（39dp、底色 = 强调色 13% 透明），
 * 下方标题 +「N 项」计数。卡片描边、圆角 21dp、surface 底、min-height 148dp。
 *
 * [countSuffix] 默认「本」以兼容既有回归测试（LibraryInteractionTest 断言「123 本」）；
 * 书架磁贴传「项」对齐设计稿文案。
 */
@Composable
internal fun ShelfEntryCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    count: Int,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    countSuffix: String = "本",
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 148.dp),
        shape = RoundedCornerShape(21.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 148.dp).padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                modifier = Modifier.size(39.dp),
                shape = RoundedCornerShape(13.dp),
                color = iconTint.copy(alpha = 0.13f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = iconTint,
                    )
                }
            }
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "$count $countSuffix",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 分区列表页（全部 / 收藏 / 历史）：`.subpage-head` 版式（返回圆钮 + 标题 +「N 本书」），
 * 下方直接复用既有 [BookGrid]，空态沿用 [EmptyState]。
 */
@Composable
fun ListScreen(
    section: ShelfSection,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
) {
    val repo = rememberLibraryRepository()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val books by remember(repo, section) {
        repo.books(section, SortOption.RECENT_READ, "")
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val onToggleFavorite: (BookEntity) -> Unit = { book ->
        scope.launch { repo.toggleFavorite(book.id) }
    }
    val onDeleteBook: (BookEntity) -> Unit = { book ->
        scope.launch { repo.deleteBook(book.id) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ShelfSubpageHead(
                title = section.label,
                subtitle = "${books.size} 本书",
                onBack = onBack,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            )
            if (books.isEmpty()) {
                EmptyState(
                    icon = sectionIcon(section),
                    title = "${section.label}还是空的",
                    subtitle = sectionHint(section),
                    modifier = Modifier.weight(1f),
                )
            } else {
                BookGrid(
                    books = books,
                    onOpenBook = onOpenBook,
                    onToggleFavorite = onToggleFavorite,
                    onDeleteBook = onDeleteBook,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
                )
            }
        }
    }
}

/**
 * `.subpage-head`：返回圆钮 + 标题 + 副标题。子页（分区列表 / 书签）共用。
 * 返回钮保留「返回」contentDescription。
 */
@Composable
internal fun ShelfSubpageHead(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onBack,
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(13.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
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
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun sectionIcon(section: ShelfSection): ImageVector = when (section) {
    ShelfSection.ALL -> Icons.Outlined.FolderOpen
    ShelfSection.FAVORITE -> Icons.Outlined.FavoriteBorder
    ShelfSection.HISTORY -> Icons.Outlined.History
    ShelfSection.BOOKMARK -> Icons.Outlined.Bookmarks
}

private fun sectionHint(section: ShelfSection): String = when (section) {
    ShelfSection.ALL -> "还没有漫画，去首页导入吧"
    ShelfSection.FAVORITE -> "长按封面即可收藏喜欢的漫画"
    ShelfSection.HISTORY -> "阅读过的漫画会出现在这里"
    ShelfSection.BOOKMARK -> "在阅读器里添加的书签会出现在这里"
}

/**
 * 书签列表页（`.chapter-row` 版式）：返回圆钮 +「书签 / N 条记录」子页头，
 * 内容为描边圆角列表容器内的行——左侧书名、右侧小字「第 N 页」，尾部删除钮（保留二次确认）。
 * 设计稿行的「· 备注」因 [BookmarkEntity] 无备注字段而省略（不伪造字段）。
 */
@Composable
fun BookmarksScreen(onBack: () -> Unit, onOpenBookPage: (bookId: Long, page: Int) -> Unit) {
    val repo: LibraryRepository = rememberLibraryRepository()
    val scope = rememberCoroutineScope()

    val bookmarks by repo.bookmarks.collectAsStateWithLifecycle(initialValue = emptyList())
    val books by repo.books.collectAsStateWithLifecycle(initialValue = emptyList())
    val booksById = remember(books) { books.associateBy { it.id } }

    var pendingDelete by remember { mutableStateOf<BookmarkEntity?>(null) }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ShelfSubpageHead(
                title = "书签",
                subtitle = "${bookmarks.size} 条记录",
                onBack = onBack,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
            )
            if (bookmarks.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Bookmarks,
                    title = "还没有书签",
                    subtitle = "阅读时通过阅读器菜单添加书签，会集中出现在这里",
                    modifier = Modifier.weight(1f),
                )
            } else {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(bookmarks, key = { _, item -> item.id }) { index, bookmark ->
                            val book = booksById[bookmark.bookId]
                            BookmarkRow(
                                bookmark = bookmark,
                                bookTitle = book?.title ?: "书籍已删除",
                                isLast = index == bookmarks.lastIndex,
                                onClick = {
                                    if (book != null) onOpenBookPage(bookmark.bookId, bookmark.pageIndex)
                                },
                                onDelete = { pendingDelete = bookmark },
                            )
                        }
                    }
                }
            }
        }
    }

    // 删除书签二次确认
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个书签？") },
            text = { Text("第 ${target.pageIndex + 1} 页的书签将被移除。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch { repo.removeBookmark(target.id) }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/** `.chapter-row` 书签行：左书名 + 右「第 N 页」小字 + 删除钮；行间细分隔线（末行无）。 */
@Composable
private fun BookmarkRow(
    bookmark: BookmarkEntity,
    bookTitle: String,
    isLast: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Column {
        Surface(
            onClick = onClick,
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(start = 15.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    bookTitle,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "第 ${bookmark.pageIndex + 1} 页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "删除书签",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!isLast) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 15.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

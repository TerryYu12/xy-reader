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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xyreader.core.BookEntity
import com.xyreader.core.BookmarkEntity
import com.xyreader.core.LibraryRepository
import com.xyreader.core.QuickRead
import com.xyreader.core.ShelfSection
import com.xyreader.core.SortOption
import kotlinx.coroutines.launch

/**
 * 书架页（对应 MH-ARK 书架 tab，Google 相册集合页式排版）：
 * 大标题「书架」+ 右上添加按钮 → 分组标题「我的书架」→ 2x2 入口卡片
 * （全部 / 收藏 / 历史 / 书签，各带数量与专属色相图标块）→ 节标题「分组」+
 * 横向分组 chips（点击进分组书列表，行尾 + 进分组管理；无分组时整行隐藏）；
 * 右下角「开始阅读」菜单（播放键 + 四选项悬浮胶囊，导入入口保持在右上角 +）。
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

    // 入口卡片数量统计
    val allBooks by repo.books.collectAsStateWithLifecycle(initialValue = emptyList())
    val bookmarks by repo.bookmarks.collectAsStateWithLifecycle(initialValue = emptyList())
    // 自定义分组（chips 行）
    val groups by repo.groups.collectAsStateWithLifecycle(initialValue = emptyList())

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
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // 大标题行 + 右上添加按钮（排版与首页标题区一致：24dp 边距）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "书架",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (scan.isScanning) {
                    ScanningCaption(text = "扫描中")
                } else {
                    IconButton(onClick = scan::launch) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = "添加仓库",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            SectionLabel("我的书架", Modifier.padding(horizontal = 16.dp, vertical = 10.dp))

            // 2x2 入口卡片：图标块按各卡专属色相着色（全部=主色 收藏=珊瑚 历史=绿 书签=金）
            val greenTint = accentColor(AccentColor.GREEN)
            val goldTint = accentColor(AccentColor.GOLD)
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ShelfEntryCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.FolderOpen,
                        title = "全部",
                        count = allBooks.size,
                        onClick = { onOpenSection(ShelfSection.ALL) },
                    )
                    ShelfEntryCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.FavoriteBorder,
                        title = "收藏",
                        count = allBooks.count { it.isFavorite },
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        onClick = { onOpenSection(ShelfSection.FAVORITE) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ShelfEntryCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.History,
                        title = "历史",
                        count = allBooks.count { it.lastReadAt != null },
                        iconTint = greenTint,
                        onClick = { onOpenSection(ShelfSection.HISTORY) },
                    )
                    ShelfEntryCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.Bookmarks,
                        title = "书签",
                        count = bookmarks.size,
                        iconTint = goldTint,
                        onClick = onOpenBookmarks,
                    )
                }
            }

            // 节标题「分组」+ 横向分组 chips：点击进分组书列表，行尾 + 进分组管理；无分组时整行隐藏
            if (groups.isNotEmpty()) {
                SectionLabel("分组", Modifier.padding(start = 16.dp, top = 20.dp, bottom = 10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    groups.forEach { group ->
                        Surface(
                            onClick = { onOpenGroup(group.id) },
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    group.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    // 行尾 + → 分组管理页（与分组 chip 同款底色）
                    Surface(
                        onClick = onOpenGroupManage,
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = "管理分组",
                            modifier = Modifier.padding(9.dp).size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(88.dp))
        }
            // —— 右下角「开始阅读」：播放键 + 悬浮胶囊菜单（导入入口在右上角 +）——
            QuickReadMenuOverlay(
                open = readMenuOpen,
                onToggle = { readMenuOpen = !readMenuOpen },
                onDismiss = { readMenuOpen = false },
                onPick = onQuickRead,
            )
        }
    }
}

/** 书架入口卡片：图标、标题和数量按字体大小自然增高。 */
@Composable
internal fun ShelfEntryCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    count: Int,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 108.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(14.dp),
                color = iconTint.copy(alpha = 0.14f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
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
                Text(
                    "$count 本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 收藏 / 历史 / 全部 的分区列表页（复用首页网格） */
@OptIn(ExperimentalMaterial3Api::class)
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
        topBar = {
            TopAppBar(
                title = { Text(section.label, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { padding ->
        if (books.isEmpty()) {
            EmptyState(
                icon = sectionIcon(section),
                title = "${section.label}还是空的",
                subtitle = sectionHint(section),
                modifier = Modifier.padding(padding),
            )
        } else {
            BookGrid(
                books = books,
                onOpenBook = onOpenBook,
                onToggleFavorite = onToggleFavorite,
                onDeleteBook = onDeleteBook,
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
            )
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
 * 书签列表页：书名 + 页码 + 时间，点击跳转阅读器对应页，
 * 尾部删除按钮（带确认）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(onBack: () -> Unit, onOpenBookPage: (bookId: Long, page: Int) -> Unit) {
    val repo: LibraryRepository = rememberLibraryRepository()
    val scope = rememberCoroutineScope()

    val bookmarks by repo.bookmarks.collectAsStateWithLifecycle(initialValue = emptyList())
    val books by repo.books.collectAsStateWithLifecycle(initialValue = emptyList())
    val booksById = remember(books) { books.associateBy { it.id } }

    var pendingDelete by remember { mutableStateOf<BookmarkEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书签", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { padding ->
        if (bookmarks.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Bookmarks,
                title = "还没有书签",
                subtitle = "阅读时通过阅读器菜单添加书签，会集中出现在这里",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(bookmarks, key = { it.id }) { bookmark ->
                    val book = booksById[bookmark.bookId]
                    BookmarkRow(
                        bookmark = bookmark,
                        bookTitle = book?.title ?: "书籍已删除",
                        onClick = { if (book != null) onOpenBookPage(bookmark.bookId, bookmark.pageIndex) },
                        onDelete = { pendingDelete = bookmark },
                    )
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

/** 书签条目卡片：金色图标块 + 书名/页码时间 + 删除按钮 */
@Composable
private fun BookmarkRow(
    bookmark: BookmarkEntity,
    bookTitle: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val goldTint = accentColor(AccentColor.GOLD)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(13.dp),
                color = goldTint.copy(alpha = 0.14f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Bookmarks,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = goldTint,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    bookTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "第 ${bookmark.pageIndex + 1} 页 · ${formatDate(bookmark.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "删除书签",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

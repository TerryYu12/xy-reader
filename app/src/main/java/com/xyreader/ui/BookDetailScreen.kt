package com.xyreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.xyreader.core.BookEntity
import com.xyreader.core.BookFormat
import com.xyreader.core.Chapter
import java.io.File
import kotlin.math.roundToInt

/**
 * 书籍详情页：点击书本先到这里（目录先行），可翻目录选章、继续阅读、从头开始或删除。
 *
 * 布局：顶部返回 + 收藏；封面与书名信息；添加/阅读统计行；目录卡片
 * （当前章节高亮，点击章节直达该章起始页）；底部悬浮胶囊操作栏。
 * 远程书的目录不在此页加载（避免网络 IO），进入阅读器后查看。
 */
@Composable
fun BookDetailScreen(
    bookId: Long,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onStartFromBeginning: () -> Unit,
    onOpenChapter: (Int) -> Unit,
    onDeleted: () -> Unit,
) {
    val viewModel: BookDetailViewModel = viewModel(key = "book_detail_$bookId") {
        // CreationExtras 里取 Application；与 ReaderScreen 同款工厂写法
        BookDetailViewModel(
            bookId = bookId,
            app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!,
        )
    }
    val book by viewModel.book.collectAsState()
    val toc by viewModel.toc.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { message -> snackbar.showSnackbar(message) }
    }

    // 分组名用于子页头副标题「格式 · 分组」
    val repo = rememberLibraryRepository()
    val groups by repo.groups.collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val current = book
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (toc is BookDetailViewModel.Toc.Failed) {
                    EmptyState(
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        title = "找不到这本书",
                        subtitle = "可能已被删除，返回列表看看其他书吧",
                    )
                } else {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            return@Scaffold
        }

        val groupName = groups.firstOrNull { it.id == current.groupId }?.name ?: "未分组"
        val formatName = runCatching { BookFormat.valueOf(current.format).displayName }.getOrDefault("未知")

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 32.dp),
        ) {
                item("head") {
                    DetailSubpageHead(subtitle = "$formatName · $groupName", onBack = onBack)
                }
                item("info") {
                    BookInfoSection(
                        book = current,
                        onContinue = onContinue,
                        onStartFromBeginning = onStartFromBeginning,
                        onToggleFavorite = viewModel::toggleFavorite,
                        onDelete = { confirmDelete = true },
                    )
                }
                item("toc_header") { TocHeader(toc) }

                when (val t = toc) {
                    is BookDetailViewModel.Toc.Loaded -> {
                        if (t.chapters.isEmpty()) {
                            item("toc_empty") { TocCaption("共 ${t.pageCount} 页 · 无章节结构") }
                        } else {
                            val currentIndex = currentChapterIndex(t.chapters, current.currentPage)
                            itemsIndexed(
                                t.chapters,
                                key = { index, chapter -> "chapter_${index}_${chapter.startPage}" },
                            ) { index, chapter ->
                                ChapterRow(
                                    chapter = chapter,
                                    index = index,
                                    isFirst = index == 0,
                                    isLast = index == t.chapters.lastIndex,
                                    isCurrent = index == currentIndex,
                                    isRead = index < currentIndex,
                                    onClick = { onOpenChapter(chapter.startPage) },
                                )
                            }
                        }
                    }
                    BookDetailViewModel.Toc.Loading -> item("toc_loading") { TocCaption("目录生成中…", loading = true) }
                    BookDetailViewModel.Toc.Failed -> item("toc_failed") { TocCaption("目录读取失败，可直接进入阅读") }
                    BookDetailViewModel.Toc.Hidden -> item("toc_hidden") { TocCaption("远程书籍 · 进入阅读器后查看目录") }
                }
            }
    }

    val target = book
    if (confirmDelete && target != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除《${target.title}》？") },
            text = { Text("将移除书库记录、阅读进度和书签，原文件不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteBook(onDeleted)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

/** 子页头（对应设计源 .subpage-head）：圆角返回钮 + 「书籍详情」+ 副标题「格式 · 分组」 */
@Composable
private fun DetailSubpageHead(subtitle: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 18.dp),
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "书籍详情",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 详情首屏：手机和宽屏都把封面放左侧，书名、格式和阅读数据放右侧。 */
@Composable
private fun BookInfoSection(
    book: BookEntity,
    onContinue: () -> Unit,
    onStartFromBeginning: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    val corner = RoundedCornerShape(19.dp)
    val progress = if (book.totalPages > 0 && book.currentPage > 0) {
        (book.currentPage * 100f / book.totalPages).roundToInt().coerceIn(0, 100)
    } else 0
    val compact = LocalConfiguration.current.screenWidthDp <= 800
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)) {
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    DetailBookCover(book, width = 111.dp, compact = true, shape = corner)
                    Column(Modifier.weight(1f)) {
                        DetailBookIdentity(book)
                        Spacer(Modifier.height(10.dp))
                        DetailReadingPills(book, progress)
                    }
                }
                StatsRow(book)
                DetailActions(
                    book = book,
                    progress = progress,
                    onContinue = onContinue,
                    onStartFromBeginning = onStartFromBeginning,
                    onToggleFavorite = onToggleFavorite,
                    onDelete = onDelete,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.Top,
            ) {
                DetailBookCover(book, width = 220.dp, compact = false, shape = corner)
                Column(Modifier.weight(1f)) {
                    DetailBookIdentity(book)
                    Spacer(Modifier.height(16.dp))
                    DetailReadingPills(book, progress)
                    Spacer(Modifier.height(14.dp))
                    StatsRow(book)
                    Spacer(Modifier.height(8.dp))
                    DetailActions(
                        book = book,
                        progress = progress,
                        onContinue = onContinue,
                        onStartFromBeginning = onStartFromBeginning,
                        onToggleFavorite = onToggleFavorite,
                        onDelete = onDelete,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailBookCover(book: BookEntity, width: androidx.compose.ui.unit.Dp, compact: Boolean, shape: RoundedCornerShape) {
    Box(
        modifier = Modifier.width(width)
            .aspectRatio(0.72f)
            .shadow(elevation = 3.dp, shape = shape)
            .clip(shape)
            .background(SolidColor(MaterialTheme.colorScheme.surfaceVariant)),
    ) {
        if (book.coverPath == null) {
            DefaultBookCover(book = book, modifier = Modifier.fillMaxSize(), compact = compact)
        }
        AsyncImage(
            model = book.coverPath?.let(::File),
            contentDescription = book.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun DetailBookIdentity(book: BookEntity) {
    Text(
        book.title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        bookMetaLine(book),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DetailReadingPills(book: BookEntity, progress: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatPill(if (progress > 0) "$progress% 已读" else "尚未开始")
        StatPill(if (book.totalPages > 0) "${book.totalPages} 页" else "页数未知")
    }
}

@Composable
private fun DetailActions(
    book: BookEntity,
    progress: Int,
    onContinue: () -> Unit,
    onStartFromBeginning: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    Column {
        DetailButton(
            label = if (progress > 0) "继续阅读" else "开始阅读",
            icon = Icons.Filled.PlayArrow,
            primary = true,
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            DetailButton(
                label = "从头开始",
                icon = Icons.Outlined.Replay,
                onClick = onStartFromBeginning,
                modifier = Modifier.weight(1f),
            )
            DetailButton(
                label = if (book.isFavorite) "已收藏" else "收藏",
                icon = if (book.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                onClick = onToggleFavorite,
                modifier = Modifier.weight(1f),
            )
            DetailButton(
                label = "删除",
                icon = Icons.Outlined.DeleteOutline,
                danger = true,
                onClick = onDelete,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 统计胶囊（对应设计源 .stat-pill） */
@Composable
private fun StatPill(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 详情操作按钮（对应设计源 .button / .button.primary / .button.danger） */
@Composable
private fun DetailButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        shape = if (primary) RoundedCornerShape(999.dp) else RoundedCornerShape(13.dp),
        color = when {
            primary -> MaterialTheme.colorScheme.primary
            danger -> Color.Transparent
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = when {
            primary -> MaterialTheme.colorScheme.onPrimary
            danger -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        },
        border = if (primary || danger) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 添加 / 阅读 两列统计（中缝细分隔线） */
@Composable
private fun StatsRow(book: BookEntity) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatCell("添加", formatRelative(book.addedAt), Modifier.weight(1f))
        VerticalDivider(
            modifier = Modifier.height(30.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        StatCell("阅读", book.lastReadAt?.let(::formatRelative) ?: "未读", Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 目录标题行：左「目录」右「共 N 章」（加载中/失败/远程时右侧留空） */
@Composable
private fun TocHeader(toc: BookDetailViewModel.Toc) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "目录",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        if (toc is BookDetailViewModel.Toc.Loaded && toc.chapters.isNotEmpty()) {
            Text(
                "共 ${toc.chapters.size} 章",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 单个章节行（对应设计源 .chapter-row）：左「第 N 章　名称」、右小字；当前章高亮并标注「上次读到」 */
@Composable
private fun ChapterRow(
    chapter: Chapter,
    index: Int,
    isFirst: Boolean,
    isLast: Boolean,
    isCurrent: Boolean,
    isRead: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(
        topStart = if (isFirst) 20.dp else 0.dp,
        topEnd = if (isFirst) 20.dp else 0.dp,
        bottomStart = if (isLast) 20.dp else 0.dp,
        bottomEnd = if (isLast) 20.dp else 0.dp,
    )
    Column {
        if (!isFirst) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
                    else MaterialTheme.colorScheme.surfaceContainer,
                )
                .clickable(onClick = onClick)
                .testTag("chapter_$index")
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "第 ${index + 1} 章　${chapter.title}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    isCurrent -> MaterialTheme.colorScheme.primary
                    isRead -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (isCurrent) "上次读到" else "打开章节",
                style = MaterialTheme.typography.labelSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}

/** 目录区的单行说明（加载中 / 失败 / 远程 / 无章节结构） */
@Composable
private fun TocCaption(text: String, loading: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 元信息一行：格式 · 文件大小；页数显示在独立统计胶囊中。 */
private fun bookMetaLine(book: BookEntity): String {
    val parts = mutableListOf<String>()
    runCatching { BookFormat.valueOf(book.format).displayName }.getOrNull()?.let(parts::add)
    if (book.size > 0) parts.add(formatSize(book.size))
    return if (parts.isEmpty()) "—" else parts.joinToString(" · ")
}

/** 文件大小：B / KB / MB / GB */
private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    else -> "%.2f GB".format(bytes / 1024f / 1024f / 1024f)
}

/** epoch 毫秒 -> 相对时间（刚刚 / N 分钟前 / N 小时前 / 昨天 / N 天前 / 日期） */
private fun formatRelative(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000} 分钟前"
        diff < 24 * 3_600_000L -> "${diff / 3_600_000} 小时前"
        diff < 48 * 3_600_000L -> "昨天"
        diff < 7 * 24 * 3_600_000L -> "${diff / (24 * 3_600_000)} 天前"
        else -> formatDate(epochMs).substringBefore(' ')
    }
}

package com.xyreader.ui

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.xyreader.core.BookEntity
import com.xyreader.core.BookFormat
import com.xyreader.core.Chapter
import java.io.File

/**
 * 书籍详情页：点击书本先到这里（目录先行），可翻目录选章、继续阅读、从头开始或删除。
 *
 * 布局：顶部返回 + 收藏；封面与书名信息；添加/阅读统计行；目录卡片
 * （当前章节高亮，点击章节直达该章起始页）；底部悬浮胶囊操作栏。
 * 远程书的目录不在此页加载（避免网络 IO），进入阅读器后查看。
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val loaded = book
                    if (loaded != null) {
                        IconButton(onClick = viewModel::toggleFavorite) {
                            Icon(
                                if (loaded.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (loaded.isFavorite) "取消收藏" else "收藏",
                                tint = if (loaded.isFavorite) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
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

        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 128.dp),
            ) {
                item("info") { BookInfoSection(current) }
                item("stats") { StatsRow(current) }
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

            BottomActionPill(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                hasProgress = current.currentPage > 0,
                onContinue = onContinue,
                onStartFromBeginning = onStartFromBeginning,
                onDelete = { confirmDelete = true },
            )
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

/** 封面 + 书名 + 格式/大小/页数一行元信息 */
@Composable
private fun BookInfoSection(book: BookEntity) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val corner = RoundedCornerShape(20.dp)
        Box(
            modifier = Modifier
                .width(112.dp)
                .aspectRatio(0.72f)
                .shadow(elevation = 2.dp, shape = corner)
                .clip(corner)
                .background(
                    if (book.coverPath == null) {
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        )
                    } else {
                        SolidColor(MaterialTheme.colorScheme.surfaceVariant)
                    },
                ),
        ) {
            AsyncImage(
                model = book.coverPath?.let(::File),
                contentDescription = book.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (book.coverPath == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = book.title.firstOrNull()?.toString() ?: "书",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                book.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                bookMetaLine(book),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
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

/** 单个章节行：首行/末行带圆角拼成整块卡片；当前章节高亮，读过章节文字变淡 */
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
                chapter.title,
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
                "第 ${chapter.startPage + 1} 页",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
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

/** 底部悬浮操作胶囊：从头开始 / 继续阅读（主按钮）/ 删除 */
@Composable
private fun BottomActionPill(
    modifier: Modifier = Modifier,
    hasProgress: Boolean,
    onContinue: () -> Unit,
    onStartFromBeginning: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onStartFromBeginning,
                modifier = Modifier.size(44.dp).testTag("start_from_beginning"),
            ) {
                Icon(
                    Icons.Outlined.Replay,
                    contentDescription = "从头开始",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                onClick = onContinue,
                modifier = Modifier.size(52.dp).testTag("continue_reading"),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = if (hasProgress) "继续阅读" else "开始阅读",
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(44.dp).testTag("delete_book"),
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 元信息一行：格式 · 大小 · 页数 */
private fun bookMetaLine(book: BookEntity): String {
    val parts = mutableListOf<String>()
    runCatching { BookFormat.valueOf(book.format).displayName }.getOrNull()?.let(parts::add)
    if (book.size > 0) parts.add(formatSize(book.size))
    if (book.totalPages > 0) parts.add("共 ${book.totalPages} 页")
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

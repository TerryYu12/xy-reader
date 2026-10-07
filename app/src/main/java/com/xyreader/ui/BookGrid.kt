package com.xyreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.xyreader.core.BookEntity
import com.xyreader.core.BookFormat
import com.xyreader.core.BookGroupEntity
import java.io.File
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 首页或分组页上接收拖入书籍的分类标签。 */
internal data class BookDropTarget(val key: String, val groupId: Long?, val label: String)

/** 保存网格外分类标签的根坐标，拖动结束时用于判断落点。 */
internal class BookGridDragState {
    var draggedBookId by mutableStateOf<Long?>(null)
    var pointerInRoot by mutableStateOf(Offset.Zero)
    var dragStartInRoot by mutableStateOf(Offset.Zero)
    var gridBoundsInRoot by mutableStateOf(Rect.Zero)
    var containerBoundsInRoot by mutableStateOf(Rect.Zero)
    var deleteTargetBoundsInRoot by mutableStateOf(Rect.Zero)

    // 坐标表用普通 Map：onGloballyPositioned 在滚动时每帧回调，写快照状态
    // 会平白放大重组压力；这两个表只在拖动事件里读取，不需要驱动 UI。
    val bookBoundsInRoot = mutableMapOf<Long, Rect>()
    val targetBoundsInRoot = mutableMapOf<String, Rect>()

    fun registerTarget(key: String, bounds: Rect) {
        targetBoundsInRoot[key] = bounds
    }

    fun unregisterTarget(key: String) {
        targetBoundsInRoot.remove(key)
    }
}

/** 首页、分组页和书架列表共用的封面网格。 */
@Composable
internal fun BookGrid(
    books: List<BookEntity>,
    onOpenBook: (Long) -> Unit,
    onToggleFavorite: (BookEntity) -> Unit,
    onDeleteBook: (BookEntity) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onMoveToGroup: ((BookEntity, Long?) -> Unit)? = null,
    onReorderBooks: ((List<BookEntity>) -> Unit)? = null,
    dropTargets: List<BookDropTarget> = emptyList(),
    dragState: BookGridDragState? = null,
    onMessage: ((String) -> Unit)? = null,
) {
    val repo = rememberLibraryRepository()
    val groups by repo.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    val state = dragState ?: remember { BookGridDragState() }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val dragEnabled = onReorderBooks != null || dropTargets.isNotEmpty()
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var pendingDelete by remember { mutableStateOf<BookEntity?>(null) }

    val moveToGroup: (BookEntity, Long?) -> Unit = onMoveToGroup ?: { book, groupId ->
        scope.launch { repo.moveBookToGroup(book.id, groupId) }
    }
    val latestMoveToGroup by rememberUpdatedState(moveToGroup)
    val latestReorder by rememberUpdatedState(onReorderBooks)
    val latestDelete by rememberUpdatedState(onDeleteBook)
    val latestBooks by rememberUpdatedState(books)
    val latestDropTargets by rememberUpdatedState(dropTargets)
    val latestMessage by rememberUpdatedState(onMessage)

    // —— 书籍操作（三点菜单）：重命名 / 刷新封面 / 自定义封面 / 删除阅读记录 ——
    var pendingCustomCover by remember { mutableStateOf<BookEntity?>(null) }
    val customCoverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = pendingCustomCover
        pendingCustomCover = null
        if (uri != null && target != null) {
            scope.launch {
                val ok = repo.setCustomCover(target.id, uri)
                latestMessage?.invoke(if (ok) "封面已更新" else "封面设置失败")
            }
        }
    }
    val doRename: (BookEntity, String) -> Unit = { book, title ->
        scope.launch { repo.renameBook(book.id, title) }
    }
    val doRefreshCover: (BookEntity) -> Unit = { book ->
        scope.launch {
            val ok = repo.refreshCover(book.id)
            latestMessage?.invoke(if (ok) "封面已刷新" else "没能生成封面")
        }
    }
    val doClearHistory: (BookEntity) -> Unit = { book ->
        scope.launch {
            repo.clearReadingHistory(book.id)
            latestMessage?.invoke("已删除阅读记录")
        }
    }
    val doPickCustomCover: (BookEntity) -> Unit = { book ->
        pendingCustomCover = book
        customCoverPicker.launch("image/*")
    }

    // 手势监听放在稳定容器上；滚动时原书卡可能离开组合，ghost 和拖动状态仍会保留。
    val columns = if (screenWidthDp > 1120) GridCells.Fixed(4) else GridCells.Fixed(3)
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned {
                rootCoordinates = it
                state.containerBoundsInRoot = it.boundsInRoot()
            }
            .pointerInput(dragEnabled, state) {
                if (dragEnabled) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { local ->
                            val point = rootCoordinates?.localToRoot(local) ?: local
                            val pressedBook = state.bookBoundsInRoot.entries
                                .firstOrNull { (_, bounds) -> bounds.contains(point) }?.key
                            if (pressedBook != null) {
                                state.draggedBookId = pressedBook
                                state.dragStartInRoot = point
                                state.pointerInRoot = point
                            }
                        },
                        onDrag = { change, _ ->
                            if (state.draggedBookId != null) {
                                change.consume()
                                state.pointerInRoot = rootCoordinates?.localToRoot(change.position)
                                    ?: change.position
                            }
                        },
                        onDragEnd = {
                            val draggedId = state.draggedBookId
                            val currentBooks = latestBooks
                            val book = currentBooks.firstOrNull { it.id == draggedId }
                            val point = state.pointerInRoot
                            if (book != null) {
                                val target = latestDropTargets.firstOrNull { candidate ->
                                    state.targetBoundsInRoot[candidate.key]?.contains(point) == true
                                }
                                when {
                                    state.deleteTargetBoundsInRoot.contains(point) -> pendingDelete = book
                                    target != null -> latestMoveToGroup(book, target.groupId)
                                    else -> {
                                        val hoveredId = state.bookBoundsInRoot.entries
                                            .firstOrNull { (id, bounds) -> id != draggedId && bounds.contains(point) }
                                            ?.key
                                        val targetBounds = hoveredId?.let(state.bookBoundsInRoot::get)
                                        val after = targetBounds?.let { isAfterDrop(point, it) } ?: false
                                        val reordered = if (hoveredId == null) currentBooks else
                                            reorderBookList(currentBooks, book.id, hoveredId, after)
                                        if (reordered != currentBooks) latestReorder?.invoke(reordered)
                                    }
                                }
                            }
                            state.draggedBookId = null
                        },
                        onDragCancel = { state.draggedBookId = null },
                    )
                }
            },
    ) {
        LazyVerticalGrid(
            columns = columns,
            state = gridState,
            modifier = Modifier.fillMaxSize().onGloballyPositioned {
                state.gridBoundsInRoot = it.boundsInRoot()
            },
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(books, key = { it.id }) { book ->
                BookCard(
                    book = book,
                    groups = groups,
                    onOpenBook = onOpenBook,
                    onToggleFavorite = onToggleFavorite,
                    onDeleteBook = onDeleteBook,
                    onMoveToGroup = { groupId -> moveToGroup(book, groupId) },
                    dragState = state.takeIf { dragEnabled },
                    onRename = { title -> doRename(book, title) },
                    onRefreshCover = { doRefreshCover(book) },
                    onPickCustomCover = { doPickCustomCover(book) },
                    onClearHistory = { doClearHistory(book) },
                )
            }
        }

        if (state.draggedBookId != null) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                latestDropTargets.firstOrNull { it.key == "home-ungrouped" }?.let { target ->
                    BookDropTargetChip(target, state)
                }
                Surface(
                    modifier = Modifier.onGloballyPositioned {
                        state.deleteTargetBoundsInRoot = it.boundsInRoot()
                    },
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shadowElevation = 8.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                        Text("拖到这里删除", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            books.firstOrNull { it.id == state.draggedBookId }?.let { draggedBook ->
                DragGhost(
                    book = draggedBook,
                    state = state,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
    }

    // 手指靠近列表边缘时逐帧滚动；拖动过程不写入存储。
    LaunchedEffect(state.draggedBookId, gridState) {
        if (state.draggedBookId == null) return@LaunchedEffect
        val edge = with(density) { 72.dp.toPx() }
        val step = with(density) { 18.dp.toPx() }
        while (state.draggedBookId != null) {
            val viewport = state.gridBoundsInRoot
            val y = state.pointerInRoot.y
            val delta = when {
                viewport != Rect.Zero && y < viewport.top + edge -> -step
                viewport != Rect.Zero && y > viewport.bottom - edge -> step
                else -> 0f
            }
            if (delta != 0f) gridState.scrollBy(delta)
            withFrameNanos { }
        }
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除《${book.title}》？") },
            text = { Text("将移除书库记录、阅读进度和书签，原文件不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    latestDelete(book)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

/** 卡片对模块内开放，便于 UI 回归测试直接验证控件交互。 */
@Composable
internal fun BookCard(
    book: BookEntity,
    groups: List<BookGroupEntity>,
    onOpenBook: (Long) -> Unit,
    onToggleFavorite: (BookEntity) -> Unit,
    onDeleteBook: (BookEntity) -> Unit,
    onMoveToGroup: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    dragState: BookGridDragState? = null,
    onRename: (String) -> Unit = {},
    onRefreshCover: () -> Unit = {},
    onPickCustomCover: () -> Unit = {},
    onClearHistory: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var confirmClearHistory by remember { mutableStateOf(false) }
    val corner = RoundedCornerShape(20.dp)
    val isDragging = dragState?.draggedBookId == book.id

    DisposableEffect(book.id, dragState) {
        onDispose {
            dragState?.bookBoundsInRoot?.remove(book.id)
        }
    }

            Column(
                modifier = modifier.onGloballyPositioned { layout ->
                    dragState?.bookBoundsInRoot?.set(book.id, layout.boundsInRoot())
                }.graphicsLayer { if (isDragging) alpha = 0.28f },
            ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(0.72f)
                .shadow(elevation = 2.dp, shape = corner)
                .clip(corner)
                .background(SolidColor(MaterialTheme.colorScheme.surfaceVariant))
                .clickable(onClick = { onOpenBook(book.id) }),
        ) {
            // 默认封面（无封面书）：新主题的渐变艺术封面；有真实封面时仍走 AsyncImage
            if (book.coverPath == null) {
                DefaultBookCover(
                    book = book,
                    modifier = Modifier.fillMaxSize(),
                    // 左下格式角标由卡片统一叠加（真实封面也显示），此处不再重复
                    showFormatBadge = false,
                )
            }
            AsyncImage(
                model = book.coverPath?.let(::File),
                contentDescription = book.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )

            // 格式角标：封面左下，始终显示（沿用既有 FormatTag 样式）
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(48.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.42f)))),
                contentAlignment = Alignment.BottomStart,
            ) { FormatTag(book.format, Modifier.padding(start = 10.dp, bottom = 10.dp)) }

            // 爱心移到封面右上角（圆形半透明底；保留「收藏/取消收藏」contentDescription）
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                shape = CircleShape,
                color = ScrimColor,
            ) {
                IconButton(onClick = { onToggleFavorite(book) }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (book.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (book.isFavorite) "取消收藏" else "收藏",
                        modifier = Modifier.size(18.dp),
                        tint = if (book.isFavorite) MaterialTheme.colorScheme.tertiary else Color.White,
                    )
                }
            }
            BookReadingProgressBadge(
                book = book,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp),
            )
        }

        Spacer(Modifier.height(6.dp))
        // 标题行：书名 + ⋮（保留「更多：书名」contentDescription，测试依赖）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                book.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多：${book.title}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名…") },
                        leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; renameText = book.title; showRename = true },
                    )
                    DropdownMenuItem(
                        text = { Text("刷新封面") },
                        leadingIcon = { Icon(Icons.Outlined.Autorenew, null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; onRefreshCover() },
                    )
                    DropdownMenuItem(
                        text = { Text("自定义封面…") },
                        leadingIcon = { Icon(Icons.Outlined.Image, null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; onPickCustomCover() },
                    )
                    DropdownMenuItem(
                        text = { Text("转移书架…") },
                        leadingIcon = { Icon(Icons.Outlined.DriveFileMove, null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; showGroupDialog = true },
                    )
                    DropdownMenuItem(
                        text = { Text("删除阅读记录") },
                        leadingIcon = { Icon(Icons.Outlined.History, null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; confirmClearHistory = true },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Outlined.DeleteOutline, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menuOpen = false; confirmDelete = true },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除《${book.title}》？") },
            text = { Text("将移除书库记录、阅读进度和书签，原文件不会被删除。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteBook(book) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }

    if (showGroupDialog) {
        var selected by remember { mutableStateOf(book.groupId) }
        AlertDialog(
            onDismissRequest = { showGroupDialog = false },
            title = { Text("转移《${book.title}》到书架") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    GroupPickRow("未分组", selected == null) { selected = null }
                    groups.forEach { group -> GroupPickRow(group.name, selected == group.id) { selected = group.id } }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGroupDialog = false; onMoveToGroup(selected) }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showGroupDialog = false }) { Text("取消") } },
        )
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    placeholder = { Text("书名") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = { showRename = false; onRename(renameText.trim()) },
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } },
        )
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("删除阅读记录？") },
            text = { Text("《${book.title}》的阅读进度将被清零回到未读，书籍与书签保留。") },
            confirmButton = {
                TextButton(onClick = { confirmClearHistory = false; onClearHistory() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("取消") } },
        )
    }
}

@Composable
internal fun BookDropTargetChip(target: BookDropTarget, state: BookGridDragState) {
    DisposableEffect(state, target.key) {
        onDispose { state.unregisterTarget(target.key) }
    }
    Surface(
        modifier = Modifier.onGloballyPositioned { state.registerTarget(target.key, it.boundsInRoot()) },
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            "移入 ${target.label}",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DragGhost(book: BookEntity, state: BookGridDragState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val ghostWidth = 92.dp
    val ghostHeight = 124.dp
    val xOffset = with(density) { (state.pointerInRoot.x - state.containerBoundsInRoot.left - ghostWidth.toPx() / 2).roundToInt() }
    val yOffset = with(density) { (state.pointerInRoot.y - state.containerBoundsInRoot.top - ghostHeight.toPx() - 10.dp.toPx()).roundToInt() }
    Surface(
        modifier = modifier.offset { IntOffset(xOffset, yOffset) }.size(ghostWidth, ghostHeight),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 12.dp,
    ) {
        Box {
            AsyncImage(
                model = book.coverPath?.let(::File),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            Text(
                book.title,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.68f)).padding(5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun isAfterDrop(pointer: Offset, target: Rect): Boolean {
    val dx = abs(pointer.x - target.center.x)
    val dy = abs(pointer.y - target.center.y)
    return if (dx > dy) pointer.x >= target.center.x else pointer.y >= target.center.y
}

private fun reorderBookList(books: List<BookEntity>, draggedId: Long, targetId: Long, after: Boolean): List<BookEntity> {
    if (draggedId == targetId) return books
    val dragged = books.firstOrNull { it.id == draggedId } ?: return books
    val result = books.filterNot { it.id == draggedId }.toMutableList()
    val targetIndex = result.indexOfFirst { it.id == targetId }
    if (targetIndex < 0) return books
    result.add(targetIndex + if (after) 1 else 0, dragged)
    return result
}

/** 阅读进度百分比（无总页数或未读时按 0 处理） */
private fun bookProgress(book: BookEntity): Int =
    if (book.totalPages > 0 && book.currentPage > 0) {
        (book.currentPage * 100f / book.totalPages).roundToInt().coerceIn(0, 100)
    } else 0

/** 封面右下角显示整数阅读进度；没有有效页数时显示 0%。 */
@Composable
internal fun BookReadingProgressBadge(book: BookEntity, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = ScrimColor,
        contentColor = Color.White,
    ) {
        Text(
            "${bookProgress(book)}%",
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun GroupPickRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FormatTag(format: String, modifier: Modifier = Modifier) {
    val label = when (runCatching { BookFormat.valueOf(format) }.getOrDefault(BookFormat.UNKNOWN)) {
        BookFormat.CBZ -> "CBZ"
        BookFormat.CBR -> "CBR"
        BookFormat.CB7 -> "7Z"
        BookFormat.CBT -> "TAR"
        BookFormat.DIRECTORY -> "目录"
        BookFormat.PDF_FOLDER -> "PDF合集"
        BookFormat.UNKNOWN -> "未知"
        else -> BookFormat.valueOf(format).name
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            maxLines = 1,
        )
    }
}

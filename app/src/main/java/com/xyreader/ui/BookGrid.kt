package com.xyreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
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
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
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

    /**
     * 长按之后手指是否已移出点按容差（粘滞：一旦为 true，到本次手势结束前不再回退）。
     * 为 false 时松手视为「长按进入多选」，为 true 才算真正拖动（排序 / 移组 / 删除）。
     */
    var dragMoved by mutableStateOf(false)

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

/**
 * 书库多选状态：是否处于多选模式 + 已选书籍 id（保持勾选顺序）。
 * 由 [BookGrid] 内部持有；宿主也可传入同一个实例，以便多选时隐藏悬浮按钮等。
 */
@Stable
internal class BookSelectionState {
    /** 是否处于多选模式 */
    var active by mutableStateOf(false)
        private set

    /** 已选书籍 id；对外只读，改动一律经由下面的方法 */
    var selectedIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    /** 进入多选；[bookId] 非空时顺带选中这一本（已在多选中则追加） */
    fun enter(bookId: Long? = null) {
        active = true
        if (bookId != null) selectedIds = selectedIds + bookId
    }

    /** 切换一本书的勾选；不改变是否处于多选模式 */
    fun toggle(bookId: Long) {
        selectedIds = if (bookId in selectedIds) selectedIds - bookId else selectedIds + bookId
    }

    /** 全选：用 [ids] 替换当前勾选 */
    fun selectAll(ids: Collection<Long>) {
        selectedIds = ids.toSet()
    }

    /** 清空勾选，仍停留在多选模式 */
    fun clearSelection() {
        selectedIds = emptySet()
    }

    /** 退出多选并清空勾选 */
    fun exit() {
        active = false
        selectedIds = emptySet()
    }

    /** 只保留仍在列表里的 id（书被删除 / 搜索过滤后调用） */
    fun retain(ids: Set<Long>) {
        if (selectedIds.all { it in ids }) return
        selectedIds = selectedIds.filter { it in ids }.toSet()
    }
}

/** 批量操作需要二次选择 / 确认的对话框 */
private enum class BatchDialog { MOVE, CLEAR_HISTORY, DELETE }

/**
 * 首页、分组页和书架列表共用的封面网格。
 *
 * - 长按封面：不动直接松手 = 进入多选并选中这一本；手指移出点按容差后才算拖动
 *   （排序 / 移组 / 删除，需宿主提供 [onReorderBooks] 或 [dropTargets]）。
 * - 多选：网格上方出现选择条，底部浮起批量操作栏（转移书架 / 收藏 / 清除记录 / 删除）；
 *   三个宿主页无需各自实现。[selectionState] 供宿主观察多选状态（如隐藏悬浮按钮），
 *   onBatch* 仅供宿主 / 测试覆盖默认实现（默认直接调用仓库批量接口并经 [onMessage] 提示）。
 */
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
    selectionState: BookSelectionState? = null,
    onBatchMove: ((List<Long>, Long?) -> Unit)? = null,
    onBatchFavorite: ((List<Long>, Boolean) -> Unit)? = null,
    onBatchClearHistory: ((List<Long>) -> Unit)? = null,
    onBatchDelete: ((List<Long>) -> Unit)? = null,
) {
    val repo = rememberLibraryRepository()
    val groups by repo.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    val state = dragState ?: remember { BookGridDragState() }
    val selection = selectionState ?: remember { BookSelectionState() }
    val selectionActive = selection.active
    val selectedIds = selection.selectedIds
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val dragEnabled = onReorderBooks != null || dropTargets.isNotEmpty()
    // 拖动影子 / 底部浮层 / 边缘滚动只在真正拖动（手指已移出点按容差）时出现，长按未移动时不闪烁
    val dragVisible = dragEnabled && state.draggedBookId != null && state.dragMoved
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var pendingDelete by remember { mutableStateOf<BookEntity?>(null) }
    var batchDialog by remember { mutableStateOf<BatchDialog?>(null) }
    // 以当前列表为准的已选书：勾选 id 在列表变化后可能短暂过期，retain 会随后清掉
    val selectedBooks = remember(books, selectedIds) { books.filter { it.id in selectedIds } }

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

    // —— 批量操作（多选）：宿主可覆盖；默认直接走仓库批量接口并经 onMessage 提示 ——
    val doBatchMove: (List<Long>, Long?) -> Unit = onBatchMove ?: { ids, groupId ->
        scope.launch {
            repo.moveBooksToGroup(ids, groupId)
            latestMessage?.invoke("已移动 ${ids.size} 本")
        }
    }
    val doBatchFavorite: (List<Long>, Boolean) -> Unit = onBatchFavorite ?: { ids, favorite ->
        scope.launch {
            repo.setFavorite(ids, favorite)
            latestMessage?.invoke(if (favorite) "已收藏 ${ids.size} 本" else "已取消收藏 ${ids.size} 本")
        }
    }
    val doBatchClearHistory: (List<Long>) -> Unit = onBatchClearHistory ?: { ids ->
        scope.launch {
            repo.clearReadingHistory(ids)
            latestMessage?.invoke("已删除 ${ids.size} 本的阅读记录")
        }
    }
    val doBatchDelete: (List<Long>) -> Unit = onBatchDelete ?: { ids ->
        scope.launch {
            repo.deleteBooks(ids)
            latestMessage?.invoke("已删除 ${ids.size} 本")
        }
    }
    // 任何批量操作执行后（或在多选里按返回键）都收起对话框并退出多选
    val finishBatch: () -> Unit = {
        batchDialog = null
        selection.exit()
    }

    // 返回键先退出多选
    BackHandler(enabled = selectionActive) { finishBatch() }
    // 列表变化（删除 / 搜索过滤）后剔除已不存在的勾选
    LaunchedEffect(books) { selection.retain(books.mapTo(HashSet<Long>()) { it.id }) }
    // 网格离开组合（列表被清空、页面退出）时一并退出多选，避免宿主持有的状态残留
    DisposableEffect(selection) { onDispose { selection.exit() } }

    // 手势监听放在稳定容器上；滚动时原书卡可能离开组合，ghost 和拖动状态仍会保留。
    val columns = if (screenWidthDp > 1120) GridCells.Fixed(4) else GridCells.Fixed(3)
    // 多选时底部要给悬浮操作栏留位，免得最后一行被挡住；其余边距沿用调用方传入的
    val gridPadding = if (selectionActive) {
        PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            top = contentPadding.calculateTopPadding(),
            end = contentPadding.calculateEndPadding(layoutDirection),
            bottom = maxOf(contentPadding.calculateBottomPadding(), 100.dp),
        )
    } else {
        contentPadding
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned {
                rootCoordinates = it
                state.containerBoundsInRoot = it.boundsInRoot()
            }
            .pointerInput(dragEnabled, state, selectionActive) {
                // 多选模式下不再监听长按：点封面直接切换勾选，也免得与拖动手势抢事件。
                // 其余时候总是安装：长按不动松手进入多选，移出点按容差后才是拖动（需 dragEnabled）。
                if (!selectionActive) {
                    val touchSlop = viewConfiguration.touchSlop
                    detectDragGesturesAfterLongPress(
                        onDragStart = { local ->
                            val point = rootCoordinates?.localToRoot(local) ?: local
                            val pressedBook = state.bookBoundsInRoot.entries
                                .firstOrNull { (_, bounds) -> bounds.contains(point) }?.key
                            if (pressedBook != null) {
                                state.draggedBookId = pressedBook
                                state.dragStartInRoot = point
                                state.pointerInRoot = point
                                state.dragMoved = false
                            }
                        },
                        onDrag = { change, _ ->
                            if (state.draggedBookId != null) {
                                change.consume()
                                val point = rootCoordinates?.localToRoot(change.position) ?: change.position
                                state.pointerInRoot = point
                                if (!state.dragMoved && (point - state.dragStartInRoot).getDistance() > touchSlop) {
                                    state.dragMoved = true
                                    // 不支持拖动的网格（列表页）：一移动就放弃这次长按，松手后什么都不做
                                    if (!dragEnabled) state.draggedBookId = null
                                }
                            }
                        },
                        onDragEnd = {
                            val draggedId = state.draggedBookId
                            val currentBooks = latestBooks
                            val book = currentBooks.firstOrNull { it.id == draggedId }
                            val point = state.pointerInRoot
                            if (book != null) {
                                if (!state.dragMoved) {
                                    // 长按后没有拖动就松手：进入多选并选中这一本
                                    selection.enter(book.id)
                                } else if (dragEnabled) {
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
                            }
                            state.draggedBookId = null
                            state.dragMoved = false
                        },
                        // 长按后手指没动就抬起时，抬起事件可能先被封面的点击消费，库函数会按「取消」回调；
                        // 这种情况由封面点击自己把「长按松手」转成进入多选（见 BookCard），这里只复位。
                        onDragCancel = {
                            state.draggedBookId = null
                            state.dragMoved = false
                        },
                    )
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            if (selectionActive) {
                BookSelectionBar(
                    selectedCount = selectedBooks.size,
                    allSelected = books.isNotEmpty() && selectedBooks.size == books.size,
                    onExit = finishBatch,
                    onToggleAll = {
                        if (books.isNotEmpty() && selectedBooks.size == books.size) {
                            selection.clearSelection()
                        } else {
                            selection.selectAll(books.map { it.id })
                        }
                    },
                )
            }
            LazyVerticalGrid(
                columns = columns,
                state = gridState,
                modifier = Modifier.fillMaxWidth().weight(1f).onGloballyPositioned {
                    state.gridBoundsInRoot = it.boundsInRoot()
                },
                contentPadding = gridPadding,
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
                        // 总是传入拖动状态：卡片靠它登记封面坐标，网格的长按手势据此判断按到了哪本书
                        dragState = state,
                        onRename = { title -> doRename(book, title) },
                        onRefreshCover = { doRefreshCover(book) },
                        onPickCustomCover = { doPickCustomCover(book) },
                        onClearHistory = { doClearHistory(book) },
                        onStartSelection = { selection.enter(book.id) },
                        selectionMode = selectionActive,
                        selected = book.id in selectedIds,
                        onToggleSelect = { selection.toggle(book.id) },
                    )
                }
            }
        }

        if (selectionActive) {
            BookBatchActionBar(
                enabled = selectedBooks.isNotEmpty(),
                allFavorite = selectedBooks.isNotEmpty() && selectedBooks.all { it.isFavorite },
                onMove = { batchDialog = BatchDialog.MOVE },
                onFavorite = {
                    val ids = selectedBooks.map { it.id }
                    // 选中的书已全部收藏 → 取消收藏；否则全部收藏
                    val favorite = !selectedBooks.all { it.isFavorite }
                    finishBatch()
                    if (ids.isNotEmpty()) doBatchFavorite(ids, favorite)
                },
                onClearHistory = { batchDialog = BatchDialog.CLEAR_HISTORY },
                onDelete = { batchDialog = BatchDialog.DELETE },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (dragVisible) {
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
    LaunchedEffect(dragVisible, gridState) {
        if (!dragVisible) return@LaunchedEffect
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

    // —— 批量操作的对话框：先收集 id、再退出多选，最后才执行，避免执行期间勾选被清空 ——
    when (batchDialog) {
        BatchDialog.MOVE -> BookBatchMoveDialog(
            count = selectedBooks.size,
            groups = groups,
            initialChoice = commonGroupChoice(selectedBooks),
            onConfirm = { groupId ->
                val ids = selectedBooks.map { it.id }
                finishBatch()
                if (ids.isNotEmpty()) doBatchMove(ids, groupId)
            },
            onDismiss = { batchDialog = null },
        )
        BatchDialog.CLEAR_HISTORY -> BookBatchConfirmDialog(
            title = "删除阅读记录？",
            message = "选中的 ${selectedBooks.size} 本书阅读进度将被清零回到未读，书籍与书签保留。",
            onConfirm = {
                val ids = selectedBooks.map { it.id }
                finishBatch()
                if (ids.isNotEmpty()) doBatchClearHistory(ids)
            },
            onDismiss = { batchDialog = null },
        )
        BatchDialog.DELETE -> BookBatchConfirmDialog(
            title = "删除选中的 ${selectedBooks.size} 本书？",
            message = "将移除书库记录、阅读进度和书签，原文件不会被删除。",
            onConfirm = {
                val ids = selectedBooks.map { it.id }
                finishBatch()
                if (ids.isNotEmpty()) doBatchDelete(ids)
            },
            onDismiss = { batchDialog = null },
        )
        null -> Unit
    }
}

/**
 * 卡片对模块内开放，便于 UI 回归测试直接验证控件交互。
 *
 * 多选相关参数都在末尾且带默认值，不影响按位置传参的旧调用：
 * [onStartSelection] 非空时三点菜单才有「多选」项，也是「长按松手」进入多选的出口；
 * [selectionMode] 为 true 时封面点击改为 [onToggleSelect]，并隐藏爱心与三点按钮；
 * [selected] 决定封面描边与左上角勾选标。
 */
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
    onStartSelection: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var confirmClearHistory by remember { mutableStateOf(false) }
    val corner = RoundedCornerShape(20.dp)
    // 长按后手指还没移出点按容差时不算拖动（松手是进入多选），卡片不要先变淡
    val isDragging = dragState != null && dragState.draggedBookId == book.id && dragState.dragMoved

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
                // 选中封面加主色描边（border 画在内容之上，同圆角）
                .then(
                    if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, corner) else Modifier,
                )
                .clickable(onClick = {
                    if (selectionMode) {
                        // 多选模式：点封面切换勾选，不打开书
                        onToggleSelect()
                    } else if (dragState != null && dragState.draggedBookId == book.id) {
                        // 网格已识别出长按、正等松手，这一下抬起不是点击：手指没动过就进入多选，
                        // 否则是拖动的收尾（拖动态由网格手势处理）
                        if (!dragState.dragMoved) onStartSelection?.invoke()
                    } else {
                        onOpenBook(book.id)
                    }
                }),
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

            // 爱心移到封面右上角（圆形半透明底；保留「收藏/取消收藏」contentDescription）；
            // 多选态隐藏，改在左上角显示勾选标
            if (selectionMode) {
                SelectionBadge(
                    selected = selected,
                    title = book.title,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                )
            } else {
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
            }
            BookReadingProgressBadge(
                book = book,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp),
            )
        }

        Spacer(Modifier.height(6.dp))
        // 标题行：书名 + ⋮（保留「更多：书名」contentDescription，测试依赖）；
        // 多选态隐藏 ⋮，行高仍保持 36dp，进出多选时网格不会跳动
        Row(modifier = Modifier.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                book.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!selectionMode) Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多：${book.title}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (onStartSelection != null) {
                        DropdownMenuItem(
                            text = { Text("多选") },
                            leadingIcon = { Icon(Icons.Outlined.CheckBox, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menuOpen = false; onStartSelection() },
                        )
                    }
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
        // 不叫 selected：避免遮蔽上面「是否被多选勾选」的同名参数
        var pickedGroupId by remember { mutableStateOf(book.groupId) }
        AlertDialog(
            onDismissRequest = { showGroupDialog = false },
            title = { Text("转移《${book.title}》到书架") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    GroupPickRow("未分组", pickedGroupId == null) { pickedGroupId = null }
                    groups.forEach { group ->
                        GroupPickRow(group.name, pickedGroupId == group.id) { pickedGroupId = group.id }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGroupDialog = false; onMoveToGroup(pickedGroupId) }) { Text("确定") }
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

/**
 * 多选时封面左上角的勾选标：选中 = 主色实心对勾圆（白底圆衬出对勾），未选 = 暗底白色空心圆。
 * 自身不拦截点击，随封面一起切换勾选；contentDescription 带上书名，便于无障碍与测试定位。
 */
@Composable
private fun SelectionBadge(selected: Boolean, title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(26.dp).background(if (selected) Color.White else ScrimColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (selected) "已选：$title" else "未选：$title",
            modifier = Modifier.size(24.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
        )
    }
}

/** 多选时网格上方的选择条：退出 + 「已选 N 本」+ 全选 / 全不选。 */
@Composable
private fun BookSelectionBar(
    selectedCount: Int,
    allSelected: Boolean,
    onExit: () -> Unit,
    onToggleAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.Outlined.Close, contentDescription = "退出多选")
            }
            Text(
                "已选 $selectedCount 本",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = onToggleAll) { Text(if (allSelected) "全不选" else "全选") }
        }
    }
}

/**
 * 多选时底部浮起的批量操作栏：转移书架 / 收藏（全部已收藏时变「取消收藏」）/ 清除记录 / 删除。
 * 四项等宽；[enabled] 为 false（一本都没选）时全部禁用。
 */
@Composable
private fun BookBatchActionBar(
    enabled: Boolean,
    allFavorite: Boolean,
    onMove: () -> Unit,
    onFavorite: () -> Unit,
    onClearHistory: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 14.dp).widthIn(max = 480.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BatchActionItem(
                icon = Icons.Outlined.DriveFileMove,
                label = "转移书架",
                enabled = enabled,
                onClick = onMove,
                modifier = Modifier.weight(1f).testTag("batch-move"),
            )
            BatchActionItem(
                icon = if (allFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                label = if (allFavorite) "取消收藏" else "收藏",
                enabled = enabled,
                onClick = onFavorite,
                modifier = Modifier.weight(1f).testTag("batch-favorite"),
            )
            BatchActionItem(
                icon = Icons.Outlined.History,
                label = "清除记录",
                enabled = enabled,
                onClick = onClearHistory,
                modifier = Modifier.weight(1f).testTag("batch-clear-history"),
            )
            BatchActionItem(
                icon = Icons.Outlined.DeleteOutline,
                label = "删除",
                enabled = enabled,
                onClick = onDelete,
                modifier = Modifier.weight(1f).testTag("batch-delete"),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 批量操作栏里的单个操作：图标在上、文字在下；禁用时整体变淡且不响应点击。 */
@Composable
private fun BatchActionItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val color = if (enabled) tint else tint.copy(alpha = 0.38f)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = color)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/**
 * 批量转移对话框的选择：包一层以区分「还没选」（整体为 null）与「选了未分组」（groupId 为 null）。
 */
private data class BatchGroupChoice(val groupId: Long?)

/** 选中的书都在同一分组（含都未分组）时返回该分组作预选项；分属不同分组则不预选。 */
private fun commonGroupChoice(books: List<BookEntity>): BatchGroupChoice? {
    val groupIds = books.map { it.groupId }.distinct()
    return if (groupIds.size == 1) BatchGroupChoice(groupIds.first()) else null
}

/** 批量转移书架：列表为「未分组」+ 各分组；未选择目标时「确定」不可用。 */
@Composable
private fun BookBatchMoveDialog(
    count: Int,
    groups: List<BookGroupEntity>,
    initialChoice: BatchGroupChoice?,
    onConfirm: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var choice by remember { mutableStateOf(initialChoice) }
    val current = choice
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("转移 $count 本书到书架") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                GroupPickRow("未分组", current != null && current.groupId == null) {
                    choice = BatchGroupChoice(null)
                }
                groups.forEach { group ->
                    GroupPickRow(group.name, current?.groupId == group.id) {
                        choice = BatchGroupChoice(group.id)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (current != null) onConfirm(current.groupId) },
                modifier = Modifier.testTag("batch-confirm"),
                enabled = current != null,
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 批量删除书籍 / 批量清除阅读记录共用的二次确认框（确认钮「删除」用错误色）。 */
@Composable
private fun BookBatchConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("batch-confirm")) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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

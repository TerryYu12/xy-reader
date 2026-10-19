package com.xyreader.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xyreader.core.BookEntity
import com.xyreader.ui.bookshelf.ShelfBook
import com.xyreader.ui.bookshelf.ShelfGroup
import com.xyreader.ui.bookshelf.WoodenBookshelf
import com.xyreader.core.BookFormat
import com.xyreader.core.BookGroupEntity
import java.io.File

// —— 暖木色板（照参考图书架）：面板 #D99A5D、层板 #A9682E，前缘高光 + 向下投影 ——
private val CabinetOuter = Color(0xFFD99A5D)      // 木质面板主色
private val CabinetInner = Color(0xFFC98A4D)      // 面板内层（略深）
private val CabinetDrawerTop = Color(0xFFE9B77F)  // 抽屉亮面
private val CabinetDrawerBottom = Color(0xFFCE9152)
private val CabinetBrass = Color(0xFFB8803A)      // 黄铜牌 / 层板深色
private val CabinetCream = Color(0xFF3E2612)      // 木面上的主文字（深木色）
private val CabinetMutedCream = Color(0xFF6B4A2A) // 木面上的次文字
private val CabinetShelfTop = Color(0xFFC98A4A)   // 层板前缘高光
private val CabinetShelfFace = Color(0xFFA9682E)  // 层板本色
private val CabinetShelfShadow = Color(0xFF77421A)

/** 每组前几本以封面朝外，其余侧放成书脊 */
private const val COVER_FIRST_COUNT = 2

/**
 * 书架木柜视图。书籍与分组由调用方提供，组件只负责展示和请求真实操作，
 * 不创建演示条目，也不直接修改仓库数据。
 */
@Composable
internal fun BookshelfCabinet(
    books: List<BookEntity>,
    groups: List<BookGroupEntity>,
    onOpenBook: (Long) -> Unit,
    onCreateGroup: (String) -> Unit,
    onRenameGroup: (BookGroupEntity, String) -> Unit,
    onOpenGroupManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val booksByGroup = remember(books) { books.groupBy { it.groupId } }

    // 数据 → 书架模型：每组前 2 本封面朝外，其余书脊朝外（与参考图混排一致）
    val shelfGroups = remember(groups, books) {
        val byGroup = books.groupBy { it.groupId }
        val palette = listOf(
            Color(0xFF6A6679), Color(0xFF7A5C4A), Color(0xFF4E6B63),
            Color(0xFF6B4E5F), Color(0xFF4A5C7A), Color(0xFF7A6A4A),
        )
        fun toShelf(book: BookEntity, index: Int) = ShelfBook(
            id = book.id.toString(),
            title = book.title,
            coverPath = book.coverPath,
            spineColor = palette[(book.title.hashCode() and Int.MAX_VALUE) % palette.size],
            showAsSpine = index >= COVER_FIRST_COUNT,
        )
        buildList {
            groups.forEach { group ->
                val list = byGroup[group.id].orEmpty()
                add(ShelfGroup(group.id.toString(), group.name, list.mapIndexed { i, b -> toShelf(b, i) }))
            }
            val loose = byGroup[null].orEmpty()
            if (loose.isNotEmpty()) {
                add(ShelfGroup("loose", "散放区", loose.mapIndexed { i, b -> toShelf(b, i) }))
            }
        }
    }
    // 任一时刻最多一本「翻开成封面」：全局只留一个 id，跨层切换自动把上一本收回书脊
    var expandedBookId by remember { mutableStateOf<Long?>(null) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var renameGroup by remember { mutableStateOf<BookGroupEntity?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "木质书柜",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "分组各占一层，未分组书籍在散放区。点书脊翻开封面，再点打开详情。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenGroupManage) {
                    Text("分组管理")
                }
        }

        // 木质书柜：陈列交给 WoodenBookshelf（简约扁平木质、每层一个分组、封面/书脊混排、无格式与进度角标）
        WoodenBookshelf(
            groups = shelfGroups,
            onBookClick = { shelfBook ->
                shelfBook.id.toLongOrNull()?.let(onOpenBook)
            },
            onGroupMenuClick = { onOpenGroupManage() },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (showCreateGroup) {
        GroupNameDialog(
            title = "新建分组",
            initialValue = "",
            groups = groups,
            editingGroupId = null,
            onDismiss = { showCreateGroup = false },
            onSubmit = { name ->
                showCreateGroup = false
                onCreateGroup(name)
            },
        )
    }
    renameGroup?.let { group ->
        GroupNameDialog(
            title = "重命名分组",
            initialValue = group.name,
            groups = groups,
            editingGroupId = group.id,
            onDismiss = { renameGroup = null },
            onSubmit = { name ->
                renameGroup = null
                onRenameGroup(group, name)
            },
        )
    }
}

// —— 陈列密度基准（规范 §4.1）：空间够就封面朝外；不够就书脊朝外竖立；超出宽度在同一层横滑 ——
private val CabBookGap = 5.dp
private val CabCoverMaxWidth = 88.dp   // 单本封面的舒适上限
private val CabCoverMinWidth = 56.dp   // 低于这个宽度就不算「看得清的封面」，改用书脊
private val CabSpineWidth = 28.dp      // 书脊厚度（命中区即书脊本身，不做互相覆盖的扩大热区）

/**
 * 一层书架 = 一个分组（或散放区）。分组标题、数量、三点操作固定在这一层上方；
 * 书名层独立横向滑动，不把同一分组拆成多层。
 */
@Composable
private fun CabinetShelf(
    title: String,
    count: Int,
    coverPath: String?,
    onRename: (() -> Unit)?,
    onManage: (() -> Unit)?,
    emptyHint: String,
    shelfTag: String,
    books: List<BookEntity>,
    expandedBookId: Long?,
    onExpand: (Long) -> Unit,
    onOpenBook: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 每层记住自己的水平位置（LazyListState 自带 saver），纵向滚动只负责浏览分组
    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxWidth()) {
        CabinetShelfHeader(
            title = title,
            count = count,
            coverPath = coverPath,
            onRename = onRename,
            onManage = onManage,
        )
        Spacer(Modifier.height(7.dp))

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val bookCount = books.size
            // 均分可视宽度得到「单本可得宽度」，据此决定这一层用封面还是书脊
            val slot = if (bookCount <= 0) {
                CabCoverMaxWidth
            } else {
                ((maxWidth - CabBookGap * (bookCount - 1)) / bookCount)
            }
            val fitsCovers = bookCount > 0 && slot >= CabCoverMinWidth
            val coverWidth = if (fitsCovers) minOf(CabCoverMaxWidth, slot) else CabCoverMaxWidth
            val coverHeight = coverWidth * 1.5f
            val rowHeight = coverHeight + 8.dp
            val expandedIndex = remember(books, expandedBookId) {
                books.indexOfFirst { it.id == expandedBookId }
            }

            // 展开的那本滑进视野（只在用户点书脊触发，不干扰用户自己的横滑）
            LaunchedEffect(expandedBookId, fitsCovers, expandedIndex) {
                if (!fitsCovers && expandedIndex >= 0) {
                    listState.animateScrollToItem(expandedIndex)
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0x26140A05))
                    .border(1.dp, Color(0x2EE6CCA9), RoundedCornerShape(9.dp)),
            ) {
                if (bookCount == 0) {
                    Text(
                        text = emptyHint,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = CabinetMutedCream,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    LazyRow(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .testTag("cabinet-shelf-$shelfTag"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(CabBookGap),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        itemsIndexed(
                            items = books,
                            key = { _, book -> book.id },
                            contentType = { _, _ -> "cabinet-book" },
                        ) { _, book ->
                            CabinetBookStand(
                                book = book,
                                // 空间够 → 全部封面朝外；不够 → 书脊竖立，仅被点开的那一本翻开
                                showCover = fitsCovers || book.id == expandedBookId,
                                coverWidth = coverWidth,
                                coverHeight = coverHeight,
                                onOpen = { onOpenBook(book.id) },
                                onExpand = { onExpand(book.id) },
                            )
                        }
                    }
                }
            }
        }
        CabinetShelfBoard()
    }
}

@Composable
private fun CabinetShelfHeader(
    title: String,
    count: Int,
    coverPath: String?,
    onRename: (() -> Unit)?,
    onManage: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (coverPath == null) {
            Icon(
                Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = Color(0xFF7C4A1E),
                modifier = Modifier.size(18.dp),
            )
        } else {
            val coverFile = remember(coverPath) { File(coverPath) }
            AsyncImage(
                model = coverFile,
                contentDescription = null,
                modifier = Modifier
                    .width(17.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .border(1.dp, Color(0x66E6CCA9), RoundedCornerShape(3.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        Surface(
            shape = RoundedCornerShape(5.dp),
            color = CabinetBrass,
            contentColor = Color(0xFF392317),
            border = BorderStroke(1.dp, Color(0xFFE0BF78)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 132.dp),
                )
                Box(Modifier.width(1.dp).height(14.dp).background(Color(0x66392317)))
                Text("$count 本", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
        if (onRename != null) {
            IconButton(
                onClick = onRename,
                modifier = Modifier.size(34.dp).semantics { contentDescription = "重命名分组：$title" },
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = null, tint = CabinetMutedCream, modifier = Modifier.size(16.dp))
            }
        }
        if (onManage != null) {
            IconButton(
                onClick = onManage,
                modifier = Modifier.size(34.dp).semantics { contentDescription = "管理分组：$title" },
            ) {
                Icon(Icons.Outlined.MoreVert, contentDescription = null, tint = CabinetMutedCream, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * 单本书的两种姿态：书脊竖立（侧放）与封面朝外（正常放）。
 * 点击语义：书脊 → 翻开成封面；已是封面 → 打开详情。
 */
@Composable
private fun CabinetBookStand(
    book: BookEntity,
    showCover: Boolean,
    coverWidth: Dp,
    coverHeight: Dp,
    onOpen: () -> Unit,
    onExpand: () -> Unit,
) {
    // 一个进度驱动「宽度展开 + 封面淡入」，避免两套动画互相打架
    val progress by animateFloatAsState(
        targetValue = if (showCover) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "cabinet-book-pose",
    )
    val width = CabSpineWidth + (coverWidth - CabSpineWidth) * progress

    Box(
        modifier = Modifier
            .width(width)
            .height(coverHeight)
            .clipToBounds()
            .testTag("cabinet-book-${book.id}")
            .semantics {
                contentDescription = if (showCover) "打开《${book.title}》的详情" else "展开《${book.title}》的封面"
            }
            .clickable { if (showCover) onOpen() else onExpand() },
        contentAlignment = Alignment.BottomStart,
    ) {
        // 书脊始终在底层：封面翻开时从书脊位置向右长出，收起时缩回书脊
        CabinetSpine(
            book = book,
            modifier = Modifier.requiredWidth(CabSpineWidth).height(coverHeight - 2.dp),
        )
        if (progress > 0f) {
            CabinetBookCover(
                book = book,
                modifier = Modifier
                    .requiredWidth(coverWidth)
                    .height(coverHeight)
                    .graphicsLayer { alpha = progress },
            )
        }
    }
}

@Composable
private fun CabinetBookCover(book: BookEntity, modifier: Modifier = Modifier) {
    // 木柜封面圆角收敛到 5dp（规范第 7 节 4–6dp）
    val shape = RoundedCornerShape(5.dp)
    val coverFrame = LocalCoverFrame.current
    val coverFile = remember(book.coverPath) { book.coverPath?.let(::File) }
    Box(
        modifier = modifier
            .shadow(6.dp, shape)
            .clip(shape)
            .background(Color(0xFF33251F))
            .border(1.dp, Color(0x66FFFFFF), shape)
            // 连续打卡封面边框：封面较小，用较细的 2dp（书脊视图不加）
            .coverFrameBorder(coverFrame, shape, width = 2.dp),
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
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().width(3.dp)
                .background(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.2f), Color.Transparent))),
        )
        Box(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(3.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.28f)))),
        )
        CabinetFormatBadge(book = book, modifier = Modifier.align(Alignment.BottomStart).padding(5.dp))
        BookReadingProgressBadge(book = book, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
    }
}

@Composable
private fun CabinetSpine(book: BookEntity, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(topStart = 5.dp, topEnd = 3.dp, bottomStart = 5.dp, bottomEnd = 3.dp)
    val theme = remember(book.title) { CoverTheme.forTitle(book.title) }
    Box(
        modifier = modifier
            .shadow(6.dp, shape)
            .clip(shape)
            .background(Brush.horizontalGradient(theme.stops.map { it.second }))
            .border(1.dp, Color(0x99FFFFFF), shape),
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().width(5.dp)
                .background(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.23f), Color.Transparent))),
        )
        Box(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(4.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.32f)))),
        )
        Text(
            text = book.title,
            modifier = Modifier.align(Alignment.Center).rotate(270f).width(112.dp),
            fontFamily = FontFamily.Serif,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.7.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 7.dp).size(5.dp)
                .border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(50)),
        )
    }
}

@Composable
private fun CabinetFormatBadge(book: BookEntity, modifier: Modifier = Modifier) {
    val label = runCatching { BookFormat.valueOf(book.format).displayName }.getOrDefault("书籍")
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(5.dp),
        color = Color(0xA60C0F16),
        contentColor = Color.White,
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), fontSize = 6.sp, maxLines = 1)
    }
}

@Composable
private fun CabinetShelfBoard() {
    // 层板：上缘高光 + 本色 + 下缘加深，再叠一层向下投影，形成「有厚度的木板」
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 3.dp)
            .height(18.dp)
            .shadow(10.dp, RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))
            .clip(RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))
            .background(
                Brush.verticalGradient(
                    listOf(CabinetShelfTop, CabinetShelfFace, CabinetShelfShadow),
                ),
            )
            .border(1.dp, Color(0xFF7C4A1E), RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp)),
    ) {
        Column {
            // 前缘高光：顶部 3dp 的亮线
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x99FFE3BC)),
            )
            Spacer(Modifier.weight(1f))
            // 板底厚度暗带：让板"有厚度"
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color(0x66000000)),
            )
        }
    }
}

/**
 * 拼板缝：每 72dp 一道竖向明暗过渡，让整块面板读起来是「几块木板拼的柜体」，
 * 而不是一张纯色卡片。同样是平铺渐变，零每帧开销。
 */
@Composable
private fun Modifier.plankSeams(): Modifier {
    val density = LocalDensity.current
    val step = with(density) { 72.dp.toPx() }
    return background(
        Brush.linearGradient(
            colors = listOf(Color(0x26120A04), Color(0x00120A04)),
            start = Offset.Zero,
            end = Offset(step, 0f),
            tileMode = TileMode.Repeated,
        ),
    )
}

/**
 * 木纹叠加层：两条平铺线性渐变（斜向亮纹 + 稀疏暗纹）。
 *
 * 之所以不用 Canvas 逐帧画线：那个实现在滚动和页面切换时每帧要画几十条线 +
 * 200 多个圆点，是实测到的卡顿来源；平铺渐变由 GPU 一次生成，开销接近 0。
 */
@Composable
private fun Modifier.woodGrain(): Modifier {
    val density = LocalDensity.current
    val diag = with(density) { 12.dp.toPx() }
    val row = with(density) { 26.dp.toPx() }
    return this
        .background(
            Brush.linearGradient(
                colors = listOf(Color(0x1AFFFFFF), Color(0x00FFFFFF)),
                start = Offset.Zero,
                end = Offset(diag, diag),
                tileMode = TileMode.Repeated,
            ),
        )
        .background(
            Brush.linearGradient(
                colors = listOf(Color(0x0D000000), Color(0x00000000)),
                start = Offset.Zero,
                end = Offset(0f, row),
                tileMode = TileMode.Repeated,
            ),
        )
}

/**
 * 程序化木纹（Canvas/Brush 自绘，不引图片资源）：
 * 斜向低透明亮纹 + 稀疏深色纹 + 噪点，铺在木质面板上。
 */
private fun DrawScope.drawWoodGrain() {
    if (size.width <= 0f || size.height <= 0f) return
    // 1) 斜向亮纹
    val step = 11.dp.toPx()
    var x = -size.height
    while (x < size.width + size.height) {
        drawLine(
            color = Color(0x14FFFFFF),
            start = Offset(x, size.height),
            end = Offset(x + size.height, 0f),
            strokeWidth = 1.2f,
        )
        x += step
    }
    // 2) 稀疏深色纹（微微倾斜，模拟木料走向）
    var y = 7.dp.toPx()
    val rowStep = 26.dp.toPx()
    while (y < size.height) {
        drawLine(
            color = Color(0x10000000),
            start = Offset(0f, y),
            end = Offset(size.width, y + size.width * 0.05f),
            strokeWidth = 1f,
        )
        y += rowStep
    }
    // 3) 噪点（确定性伪随机，避免每次重组都在跳）
    var seed = 20261019
    fun next(): Int {
        seed = seed * 1103515245 + 12345
        return (seed ushr 16) and 0xFFFF
    }
    val dots = ((size.width * size.height) / 2600f).toInt().coerceIn(0, 220)
    repeat(dots) { i ->
        val px = next() / 65535f * size.width
        val py = next() / 65535f * size.height
        drawCircle(
            color = if (i % 3 == 0) Color(0x12FFFFFF) else Color(0x0E000000),
            radius = if (i % 4 == 0) 1.3f else 0.8f,
            center = Offset(px, py),
        )
    }
}

@Composable
private fun CabinetEmpty(message: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .border(1.dp, Color(0x55E5CCAC), RoundedCornerShape(11.dp))
            .padding(horizontal = 14.dp, vertical = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = CabinetCream, textAlign = TextAlign.Center)
        TextButton(onClick = onAction) { Text(action, color = Color(0xFFF2DBB6)) }
    }
}

@Composable
private fun GroupNameDialog(
    title: String,
    initialValue: String,
    groups: List<BookGroupEntity>,
    editingGroupId: Long?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var value by remember(editingGroupId, initialValue) { mutableStateOf(initialValue) }
    val trimmed = value.trim()
    val duplicate = trimmed.isNotEmpty() && groups.any {
        it.id != editingGroupId && it.name.equals(trimmed, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (editingGroupId == null) "给书柜添一个自己的分类。" else "修改后，组内书籍会保留在这个抽屉里。")
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(24) },
                    singleLine = true,
                    isError = duplicate,
                    label = { Text("分组名称") },
                    supportingText = {
                        if (duplicate) Text("已有同名分组")
                        else Text("最多 24 个字符")
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty() && !duplicate,
                onClick = { onSubmit(trimmed) },
            ) { Text(if (editingGroupId == null) "创建" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

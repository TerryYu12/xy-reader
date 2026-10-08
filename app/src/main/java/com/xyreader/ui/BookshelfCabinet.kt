package com.xyreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Bookmarks
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xyreader.core.BookEntity
import com.xyreader.core.BookFormat
import com.xyreader.core.BookGroupEntity
import java.io.File

private val CabinetOuter = Color(0xFF4A3028)
private val CabinetInner = Color(0xFF3E2723)
private val CabinetDrawerTop = Color(0xFF72503F)
private val CabinetDrawerBottom = Color(0xFF49312A)
private val CabinetBrass = Color(0xFFC5A059)
private val CabinetCream = Color(0xFFF1E3CF)
private val CabinetMutedCream = Color(0xFFD2B99B)

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
    var expandedGroupId by remember { mutableStateOf<Long?>(null) }
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
                    "分组收在抽屉里，未分组书籍放在散放区。点书打开详情。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { showCreateGroup = true }) {
                Text("新建分组")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(12.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF68483B), CabinetOuter, CabinetInner),
                    ),
                )
                .border(1.dp, Color(0xFF8A6650), RoundedCornerShape(20.dp))
                .padding(12.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, tint = CabinetBrass, modifier = Modifier.size(18.dp))
                    Text(
                        "XY · LIBRARY",
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        color = CabinetCream,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${books.size} 本藏书",
                        style = MaterialTheme.typography.labelSmall,
                        color = CabinetMutedCream,
                    )
                }

                if (groups.isEmpty()) {
                    CabinetEmpty(
                        message = "还没有自定义分组。新建一个木抽屉开始整理。",
                        action = "新建分组",
                        onAction = { showCreateGroup = true },
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                } else {
                    groups.forEach { group ->
                        val groupBooks = booksByGroup[group.id].orEmpty()
                        CabinetDrawer(
                            group = group,
                            books = groupBooks,
                            expanded = expandedGroupId == group.id,
                            onToggle = {
                                expandedGroupId = if (expandedGroupId == group.id) null else group.id
                            },
                            onOpenBook = onOpenBook,
                            onRename = { renameGroup = group },
                            onManage = onOpenGroupManage,
                            modifier = Modifier.testTag("cabinet-drawer-${group.id}"),
                        )
                        CabinetShelfBoard()
                    }
                }

                CabinetLooseZone(
                    books = booksByGroup[null].orEmpty(),
                    onOpenBook = onOpenBook,
                )
            }
            Box(Modifier.matchParentSize().border(1.dp, Color(0x38E0B888), RoundedCornerShape(15.dp)))
        }
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

@Composable
private fun CabinetDrawer(
    group: BookGroupEntity,
    books: List<BookEntity>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onRename: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(5.dp, RoundedCornerShape(13.dp))
            .clip(RoundedCornerShape(13.dp))
            .background(
                Brush.verticalGradient(listOf(CabinetDrawerTop, Color(0xFF5D4037), CabinetDrawerBottom)),
            )
            .border(1.dp, Color(0xFF947052), RoundedCornerShape(13.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .clickable(onClick = onToggle)
                .semantics { contentDescription = if (expanded) "收起分组：${group.name}" else "展开分组：${group.name}" }
                .testTag("cabinet-toggle-${group.id}")
                .padding(start = 10.dp, end = 5.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = Color(0xFFD9B783), modifier = Modifier.size(19.dp))
            Surface(
                shape = RoundedCornerShape(5.dp),
                color = CabinetBrass,
                contentColor = Color(0xFF392317),
                border = BorderStroke(1.dp, Color(0xFFE0BF78)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(Modifier.width(1.dp).height(16.dp).background(Color(0x66392317)))
                    Text("${books.size} 本", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onManage,
                modifier = Modifier.size(38.dp).semantics { contentDescription = "管理分组：${group.name}" },
            ) {
                Icon(Icons.Outlined.MoreVert, contentDescription = null, tint = CabinetMutedCream)
            }
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = CabinetCream,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (expanded) 180f else 0f),
            )
        }

        // 展开时在抽屉内显示该组全部书籍；下方散放区只显示 groupId 为空的书。
        if (expanded) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x55E1BE90)))
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${group.name} · 全部分组书籍",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = CabinetCream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onRename, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("改名", color = Color(0xFFF2DBB6), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (books.isEmpty()) {
                Text(
                    "这里还没有书。新书可以从书籍详情转入此分组。",
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 5.dp, bottom = 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = CabinetMutedCream,
                )
            } else {
                CabinetBookRows(
                    books = books,
                    onOpenBook = onOpenBook,
                    tag = "group-${group.id}",
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 8.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp, top = 1.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                group.coverPath?.let { coverPath ->
                    CabinetGroupCover(coverPath, group.name)
                }
                if (books.isEmpty()) {
                    Text(
                        "这个分组暂时是空的。",
                        modifier = Modifier.weight(1f).height(104.dp).wrapContentHeight(Alignment.CenterVertically),
                        style = MaterialTheme.typography.bodySmall,
                        color = CabinetMutedCream,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    CabinetBookRows(
                        books = books.take(3),
                        onOpenBook = onOpenBook,
                        tag = "preview-${group.id}",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CabinetGroupCover(path: String, groupName: String) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = Modifier
            .padding(bottom = 5.dp)
            .width(54.dp)
            .height(91.dp)
            .shadow(5.dp, shape)
            .clip(shape)
            .border(1.dp, Color(0x77E6CCA9), shape)
            .background(Color(0xFF72503F))
            .semantics { contentDescription = "$groupName 分组封面" },
    ) {
        AsyncImage(
            model = File(path),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun CabinetLooseZone(books: List<BookEntity>, onOpenBook: (Long) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x44200F0A))
            .border(1.dp, Color(0x44DCB887), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Outlined.Bookmarks, contentDescription = null, tint = Color(0xFFD2AB74), modifier = Modifier.size(15.dp))
            Text(
                "散放区",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                color = CabinetCream,
            )
            Spacer(Modifier.weight(1f))
            Text("${books.size} 本 · 未分组", style = MaterialTheme.typography.labelSmall, color = CabinetMutedCream)
        }
        Spacer(Modifier.height(10.dp))
        if (books.isEmpty()) {
            Text(
                "散放区暂时没有书。",
                modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                style = MaterialTheme.typography.bodySmall,
                color = CabinetMutedCream,
                textAlign = TextAlign.Center,
            )
        } else {
            CabinetBookRows(books = books, onOpenBook = onOpenBook, tag = "loose")
        }
    }
}

@Composable
private fun CabinetBookRows(
    books: List<BookEntity>,
    onOpenBook: (Long) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        books.chunked(3).forEachIndexed { rowIndex, rowBooks ->
            Row(
                modifier = Modifier.fillMaxWidth().testTag("cabinet-row-$tag-$rowIndex"),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (rowBooks.size == 1) Spacer(Modifier.weight(1f))
                rowBooks.forEachIndexed { itemIndex, book ->
                    CabinetBookObject(
                        book = book,
                        spine = ((rowIndex * 3 + itemIndex) % 2 == 1),
                        onClick = { onOpenBook(book.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowBooks.size == 1) {
                    Spacer(Modifier.weight(1f))
                } else {
                    repeat(3 - rowBooks.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun CabinetBookObject(
    book: BookEntity,
    spine: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = "打开《${book.title}》的详情"
    Box(
        modifier = modifier
            .height(139.dp)
            .semantics { contentDescription = label }
            .testTag("cabinet-book-${book.id}")
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (spine) {
            CabinetSpine(book = book)
        } else {
            CabinetBookCover(book = book)
        }
    }
}

@Composable
private fun CabinetBookCover(book: BookEntity) {
    val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 5.dp, bottomStart = 8.dp, bottomEnd = 5.dp)
    val coverFrame = LocalCoverFrame.current
    Box(
        modifier = Modifier
            .fillMaxWidth(0.84f)
            .widthIn(max = 86.dp)
            .aspectRatio(0.72f)
            .heightIn(max = 119.dp)
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
            model = book.coverPath?.let(::File),
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
private fun CabinetSpine(book: BookEntity) {
    val shape = RoundedCornerShape(topStart = 5.dp, topEnd = 3.dp, bottomStart = 5.dp, bottomEnd = 3.dp)
    val theme = CoverTheme.forTitle(book.title)
    Box(
        modifier = Modifier
            .width(45.dp)
            .height(127.dp)
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
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 3.dp)
            .height(8.dp)
            .shadow(4.dp, RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))
            .clip(RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF9A704E), Color(0xFF6A4835), Color(0xFF402A22))))
            .border(1.dp, Color(0xFF8C6547), RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp)),
    )
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

package com.xyreader.ui.bookshelf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.io.File

/**
 * 简约扁平化木质书架（UI 组件，无业务依赖）。
 *
 * 设计约束（按产品要求）：
 * - 每个分组 = 一层；层顶是「分组名（数量）+ 菜单」。
 * - 书籍封面与书脊混排，同层横向滚动。
 * - **不显示文件格式、阅读进度和百分比**——书柜只负责陈列，进度信息留在首页/详情。
 * - 木色是固定暖棕（不跟随强调色），文字用浅米色，对比度足够。
 */

/** 一本书在书架上的呈现数据。封面用本地文件路径，交给 Coil 加载（复用现有图片加载方案）。 */
data class ShelfBook(
    val id: String,
    val title: String,
    val coverPath: String? = null,
    /** 书脊底色：由书名稳定推导，保证同一本书每次颜色一致 */
    val spineColor: Color = Color(0xFF6A6679),
    /** 是否以书脊（侧放）形态出现；为 true 时点一下才展开成封面 */
    val showAsSpine: Boolean = false,
)

/** 一层书架 = 一个分组 */
data class ShelfGroup(val id: String, val name: String, val books: List<ShelfBook>)

private val Wood = Color(0xFFC88B55)
private val WoodEdge = Color(0xFFA66C3E)
private val WoodBack = Color(0xFF966039)
private val WoodShelf = Color(0xFFDCA56C)
private val Ink = Color(0xFFFFF6EA)

@Composable
fun WoodenBookshelf(
    groups: List<ShelfGroup>,
    modifier: Modifier = Modifier,
    onBookClick: (ShelfBook) -> Unit = {},
    onGroupMenuClick: (ShelfGroup) -> Unit = {},
) {
    // 任一时刻最多一本处于「展开成封面」状态：点另一本书脊时，上一本自动收回书脊
    var expandedBookId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Wood)
            .border(2.dp, WoodEdge, RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        groups.forEach { group ->
            ShelfLevel(
                group = group,
                expandedBookId = expandedBookId,
                onSpineClick = { expandedBookId = it },
                onBookClick = onBookClick,
                onGroupMenuClick = onGroupMenuClick,
            )
        }
    }
}

@Composable
private fun ShelfLevel(
    group: ShelfGroup,
    expandedBookId: String?,
    onSpineClick: (String) -> Unit,
    onBookClick: (ShelfBook) -> Unit,
    onGroupMenuClick: (ShelfGroup) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(WoodBack),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${group.name}（${group.books.size}）",
                modifier = Modifier.weight(1f),
                color = Ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = { onGroupMenuClick(group) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多分组操作：${group.name}", tint = Ink)
            }
        }
        if (group.books.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().height(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无书籍", color = Ink.copy(alpha = 0.65f), fontSize = 13.sp)
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                items(group.books, key = { it.id }) { book ->
                    val asSpine = book.showAsSpine && book.id != expandedBookId
                    ShelfBookItem(
                        book = book.copy(showAsSpine = asSpine),
                        onClick = {
                            // 书脊 → 先展开成封面；已是封面 → 打开书籍
                            if (asSpine) onSpineClick(book.id) else onBookClick(book)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Box(Modifier.fillMaxWidth().height(13.dp).background(WoodShelf))
        Box(Modifier.fillMaxWidth().height(4.dp).background(WoodEdge))
    }
}

@Composable
private fun ShelfBookItem(book: ShelfBook, onClick: () -> Unit) {
    if (book.showAsSpine) {
        Box(
            Modifier
                .width(29.dp)
                .height(136.dp)
                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                .background(book.spineColor)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                book.title,
                color = Color.White,
                fontSize = 11.sp,
                maxLines = 5,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(3.dp),
            )
        }
    } else {
        Box(
            Modifier
                .width(95.dp)
                .height(136.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(book.spineColor)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val path = book.coverPath
            if (path != null) {
                AsyncImage(
                    model = remember(path) { File(path) },
                    contentDescription = "打开《${book.title}》的详情",
                    contentScale = ContentScale.Crop,
                    // 解码期间铺一层与书脊同色的底色，避免白/透明帧闪一下
                    placeholder = remember(book.spineColor) { ColorPainter(book.spineColor) },
                    error = remember(book.spineColor) { ColorPainter(book.spineColor) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    book.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 4,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

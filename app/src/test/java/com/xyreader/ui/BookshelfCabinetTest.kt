package com.xyreader.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.xyreader.core.BookEntity
import com.xyreader.core.BookGroupEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 书柜 = 新组件 WoodenBookshelf：每层一个分组、封面与书脊混排、不显示格式与进度。
 *
 * 断言按**当前**设计写（旧实现里的 `cabinet-book-N` 标签与「展开《…》的封面」语义已随重写移除）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookshelfCabinetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun showsEveryGroupWithItsBookCount() {
        val reading = BookGroupEntity(id = 11, name = "待读", createdAt = 1)
        val classic = BookGroupEntity(id = 12, name = "经典", createdAt = 2)

        compose.setContent {
            ArkTheme {
                BookshelfCabinet(
                    books = listOf(
                        book(1, "分组书一", reading.id),
                        book(2, "分组书二", reading.id),
                        book(3, "经典书", classic.id),
                    ),
                    groups = listOf(reading, classic),
                    onOpenBook = {},
                    onCreateGroup = {},
                    onRenameGroup = { _, _ -> },
                    onOpenGroupManage = {},
                )
            }
        }

        compose.onNodeWithText("待读（2）").assertIsDisplayed()
        compose.onNodeWithText("经典（1）").assertIsDisplayed()
        compose.onNodeWithText("分组书一").assertIsDisplayed()
        compose.onNodeWithText("经典书").assertIsDisplayed()
    }

    @Test
    fun coverOpensBookDirectly() {
        val group = BookGroupEntity(id = 11, name = "待读", createdAt = 1)
        val opened = mutableListOf<Long>()

        compose.setContent {
            ArkTheme {
                BookshelfCabinet(
                    books = listOf(book(1, "分组书一", group.id), book(2, "分组书二", group.id)),
                    groups = listOf(group),
                    onOpenBook = opened::add,
                    onCreateGroup = {},
                    onRenameGroup = { _, _ -> },
                    onOpenGroupManage = {},
                )
            }
        }

        compose.onNodeWithText("分组书一").performClick()
        compose.runOnIdle { assertEquals(listOf(1L), opened) }
    }

    @Test
    fun spineExpandsFirstThenOpens() {
        val group = BookGroupEntity(id = 11, name = "待读", createdAt = 1)
        // 四本：前两本封面朝外，后两本侧放成书脊
        val books = (1..4).map { book(it.toLong(), "分组书$it", group.id) }
        val opened = mutableListOf<Long>()

        compose.setContent {
            ArkTheme {
                BookshelfCabinet(
                    books = books,
                    groups = listOf(group),
                    onOpenBook = opened::add,
                    onCreateGroup = {},
                    onRenameGroup = { _, _ -> },
                    onOpenGroupManage = {},
                )
            }
        }

        // 点书脊：只展开，不打开
        compose.onNodeWithText("分组书3").performClick()
        compose.runOnIdle { assertEquals(emptyList<Long>(), opened) }

        // 再点同一本：这次才打开
        compose.onNodeWithText("分组书3").performClick()
        compose.runOnIdle { assertEquals(listOf(3L), opened) }
    }

    /** 书柜不显示文件格式与阅读进度（产品要求）。 */
    @Test
    fun shelfShowsNoFormatOrProgressBadges() {
        val group = BookGroupEntity(id = 11, name = "待读", createdAt = 1)

        compose.setContent {
            ArkTheme {
                BookshelfCabinet(
                    books = (1..3).map { book(it.toLong(), "分组书$it", group.id) },
                    groups = listOf(group),
                    onOpenBook = {},
                    onCreateGroup = {},
                    onRenameGroup = { _, _ -> },
                    onOpenGroupManage = {},
                )
            }
        }

        compose.onAllNodesWithText("TXT").assertCountEquals(0)
        compose.onAllNodesWithText("PDF").assertCountEquals(0)
        compose.onAllNodesWithText("0%").assertCountEquals(0)
    }

    private fun book(id: Long, title: String, groupId: Long?) = BookEntity(
        id = id,
        title = title,
        uri = "file:///$id.txt",
        format = "TXT",
        addedAt = id,
        groupId = groupId,
    )
}

package com.xyreader.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.xyreader.core.BookEntity
import com.xyreader.core.BookGroupEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookshelfCabinetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun drawerShowsThreeBooksThenExpandsToCenteredThreePlusOneAndOpensRealBook() {
        val group = BookGroupEntity(id = 11, name = "待读", createdAt = 1)
        val books = listOf(
            book(1, "分组书一", group.id),
            book(2, "分组书二", group.id),
            book(3, "分组书三", group.id),
            book(4, "分组书四", group.id),
            book(5, "散放书", null),
        )
        val openedBookIds = mutableListOf<Long>()

        compose.setContent {
            ArkTheme {
                BookshelfCabinet(
                    books = books,
                    groups = listOf(group),
                    onOpenBook = openedBookIds::add,
                    onCreateGroup = {},
                    onRenameGroup = { _, _ -> },
                    onOpenGroupManage = {},
                )
            }
        }

        compose.onNodeWithContentDescription("打开《分组书一》的详情").assertIsDisplayed()
        compose.onNodeWithContentDescription("打开《分组书二》的详情").assertIsDisplayed()
        compose.onNodeWithContentDescription("打开《分组书三》的详情").assertIsDisplayed()
        compose.onNodeWithContentDescription("打开《分组书四》的详情").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("打开《散放书》的详情").assertCountEquals(1)

        compose.onNodeWithTag("cabinet-toggle-${group.id}").performClick()

        compose.onNodeWithContentDescription("打开《分组书四》的详情").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("打开《散放书》的详情").assertCountEquals(1)
        compose.onNodeWithTag("cabinet-row-group-${group.id}-0").assertExists()
        compose.onNodeWithTag("cabinet-row-group-${group.id}-1").assertExists()

        val first = compose.onNodeWithTag("cabinet-book-1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("cabinet-book-2").fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithTag("cabinet-book-3").fetchSemanticsNode().boundsInRoot
        val fourth = compose.onNodeWithTag("cabinet-book-4").fetchSemanticsNode().boundsInRoot
        val secondRow = compose.onNodeWithTag("cabinet-row-group-${group.id}-1").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertEquals(first.top, third.top, 1f)
        assertTrue("Fourth book should wrap to row two", fourth.top > first.top)
        assertEquals("Single item in the last row should be centered", secondRow.center.x, fourth.center.x, 1f)

        compose.onNodeWithTag("cabinet-book-4").performClick()
        compose.runOnIdle { assertEquals(listOf(4L), openedBookIds) }
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

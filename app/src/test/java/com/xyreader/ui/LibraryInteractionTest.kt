package com.xyreader.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.xyreader.core.BookEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val book = BookEntity(id = 7, title = "测试小说", uri = "file:///sample.txt", format = "TXT", addedAt = 0)

    @Test fun shelfLabelsFitNarrowCardWithLargeSystemFont() {
        compose.setContent {
            ArkTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    ShelfEntryCard(
                        modifier = Modifier.width(136.dp).testTag("entry"),
                        icon = Icons.Outlined.FolderOpen,
                        title = "全部",
                        count = 123,
                        onClick = {},
                    )
                }
            }
        }
        // 截图仅作审查附件；Robolectric 下 captureToImage 偶发超时，不作为断言依据
        try {
            val screenshot = File("build/reports/review/shelf-large-font.png")
            screenshot.parentFile?.mkdirs()
            screenshot.outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } catch (e: Throwable) {
            println("审查截图跳过：${e.message}")
        }
        val card = compose.onNodeWithTag("entry").fetchSemanticsNode().boundsInRoot
        for (text in listOf("全部", "123 本")) {
            val label = compose.onNodeWithText(text, useUnmergedTree = true)
            label.assertIsDisplayed()
            val bounds = label.fetchSemanticsNode().boundsInRoot
            assertTrue("$text 超出卡片底部", bounds.bottom <= card.bottom)
            assertTrue("$text 超出卡片右侧", bounds.right <= card.right)
            val layouts = mutableListOf<TextLayoutResult>()
            label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            // hasVisualOverflow 在大字号下会被亚像素尾差误报：didOverflowWidth 是
            // “整数节点宽 < 浮点段宽”的比较，字宽带小数时恒真，故改用带容差的
            // 约束比较判断是否真的放不下
            val fits = layouts.isNotEmpty() && layouts.all { layout ->
                layout.multiParagraph.width <= layout.layoutInput.constraints.maxWidth + 0.5f &&
                    layout.multiParagraph.height <= layout.layoutInput.constraints.maxHeight + 0.5f &&
                    !layout.multiParagraph.didExceedMaxLines
            }
            assertTrue(
                "$text 应完整排版，布局=${layouts.map { "size=${it.size}, para=${it.multiParagraph.width}x${it.multiParagraph.height}, lines=${it.lineCount}, constraints=${it.layoutInput.constraints}, card=$card" }}",
                fits,
            )
        }
    }

    @Test fun favoriteActionDoesNotOpenBook() {
        var favorites = 0
        var opens = 0
        compose.setContent {
            ArkTheme {
                Box(Modifier.width(150.dp)) {
                    BookCard(book, emptyList(), { opens++ }, { favorites++ }, {}, {})
                }
            }
        }
        compose.onNodeWithContentDescription("收藏").performClick()
        compose.runOnIdle {
            assertEquals(1, favorites)
            assertEquals(0, opens)
        }
    }

    @Test fun deletionRequiresConfirmationAndCancelKeepsBook() {
        var deletions = 0
        compose.setContent {
            ArkTheme {
                Box(Modifier.width(150.dp)) {
                    BookCard(book, emptyList(), {}, {}, { deletions++ }, {})
                }
            }
        }
        compose.onNodeWithContentDescription("更多：测试小说").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, deletions) }
        compose.onNodeWithContentDescription("更多：测试小说").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.runOnIdle { assertEquals(1, deletions) }
    }

    @Test fun moreMenuListsAllBookActions() {
        compose.setContent {
            ArkTheme {
                Box(Modifier.width(150.dp)) {
                    BookCard(book, emptyList(), { }, { }, { }, { })
                }
            }
        }
        compose.onNodeWithContentDescription("更多：测试小说").performClick()
        for (label in listOf("重命名…", "刷新封面", "自定义封面…", "转移书架…", "删除阅读记录", "删除")) {
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test fun renameDialogSubmitsTrimmedTitle() {
        var renamed: String? = null
        compose.setContent {
            ArkTheme {
                Box(Modifier.width(150.dp)) {
                    BookCard(book, emptyList(), { }, { }, { }, { }, onRename = { renamed = it })
                }
            }
        }
        compose.onNodeWithContentDescription("更多：测试小说").performClick()
        compose.onNodeWithText("重命名…").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("  新书名  ")
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { assertEquals("新书名", renamed) }
    }

    @Test fun clearReadingHistoryRequiresConfirmation() {
        var cleared = 0
        compose.setContent {
            ArkTheme {
                Box(Modifier.width(150.dp)) {
                    BookCard(book, emptyList(), { }, { }, { }, { }, onClearHistory = { cleared++ })
                }
            }
        }
        compose.onNodeWithContentDescription("更多：测试小说").performClick()
        compose.onNodeWithText("删除阅读记录").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, cleared) }
        compose.onNodeWithContentDescription("更多：测试小说").performClick()
        compose.onNodeWithText("删除阅读记录").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.runOnIdle { assertEquals(1, cleared) }
    }

    @Test fun longPressDragReordersBooksWhenDroppedOnAnotherCover() {
        val second = book.copy(id = 8, title = "第二本")
        var result = emptyList<Long>()
        compose.setContent {
            ArkTheme {
                BookGrid(
                    books = listOf(book, second),
                    onOpenBook = {}, onToggleFavorite = {}, onDeleteBook = {},
                    modifier = Modifier.width(320.dp).height(600.dp).testTag("grid"),
                    onReorderBooks = { result = it.map { item -> item.id } },
                )
            }
        }
        val grid = compose.onNodeWithTag("grid").fetchSemanticsNode().boundsInRoot
        val start = compose.onNodeWithContentDescription("测试小说").fetchSemanticsNode().boundsInRoot.center - grid.topLeft
        val target = compose.onNodeWithContentDescription("第二本").fetchSemanticsNode().boundsInRoot
        val end = Offset(target.center.x, target.bottom - 18f) - grid.topLeft
        compose.onNodeWithTag("grid").performTouchInput {
            down(start)
            advanceEventTime(700)
            moveTo(start + Offset(1f, 1f), delayMillis = 16)
            moveTo(end, delayMillis = 400)
            up()
        }
        compose.runOnIdle { assertEquals(listOf(8L, 7L), result) }
    }
}

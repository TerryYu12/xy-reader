package com.xyreader.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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

    @Test fun bookCardsShowIntegerProgressOnTheCoverIncludingZeroForUnreadBooks() {
        val readBook = book.copy(id = 8, title = "已读书", totalPages = 100, currentPage = 61)
        val unreadBook = book.copy(id = 9, title = "未读书", totalPages = 0, currentPage = 0)
        compose.setContent {
            ArkTheme {
                androidx.compose.foundation.layout.Column {
                    Box(Modifier.width(150.dp)) {
                        BookCard(readBook, emptyList(), {}, {}, {}, {})
                    }
                    Box(Modifier.width(150.dp)) {
                        BookCard(unreadBook, emptyList(), {}, {}, {}, {})
                    }
                }
            }
        }

        compose.onNodeWithText("61%", substring = false, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("0%", substring = false, useUnmergedTree = true).assertIsDisplayed()
        val cover = compose.onNodeWithContentDescription("已读书").fetchSemanticsNode().boundsInRoot
        val badge = compose.onNodeWithText("61%", substring = false, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "百分比徽标应位于封面右下",
            badge.center.x > cover.center.x && badge.bottom <= cover.bottom && badge.right <= cover.right,
        )
        compose.onNodeWithText("61% 已读").assertDoesNotExist()
        compose.onNodeWithText("未分组").assertDoesNotExist()
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

    @Test fun longPressDragMovesBookToUngroupedTarget() {
        val dragState = BookGridDragState()
        var moved: Pair<Long, Long?>? = null
        compose.setContent {
            ArkTheme {
                Box(Modifier.width(320.dp).height(600.dp).testTag("ungrouped-grid")) {
                    BookGrid(
                        books = listOf(book),
                        onOpenBook = {},
                        onToggleFavorite = {},
                        onDeleteBook = {},
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        onMoveToGroup = { dragged, groupId -> moved = dragged.id to groupId },
                        dropTargets = listOf(BookDropTarget("test-ungrouped", null, "未分组")),
                        dragState = dragState,
                    )
                    // Use the production drop chip in a stable slot to measure its target before dragging.
                    Box(Modifier.align(Alignment.TopCenter)) {
                        BookDropTargetChip(
                            BookDropTarget("test-ungrouped", null, "未分组"),
                            dragState,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        val grid = compose.onNodeWithTag("ungrouped-grid").fetchSemanticsNode().boundsInRoot
        val bookBounds = compose.onNodeWithContentDescription(book.title).fetchSemanticsNode().boundsInRoot
        val target = requireNotNull(dragState.targetBoundsInRoot["test-ungrouped"])
        val start = bookBounds.center - grid.topLeft
        val end = target.center - grid.topLeft
        compose.onNodeWithTag("ungrouped-grid").performTouchInput {
            down(start)
            advanceEventTime(700)
            moveTo(start + Offset(1f, 1f), delayMillis = 16)
            moveTo(end, delayMillis = 400)
            up()
        }

        compose.runOnIdle { assertEquals(book.id to null, moved) }
    }

    @Test fun phoneBookGridUsesThreeColumns() {
        val books = (1L..4L).map { id ->
            book.copy(id = id, title = "书$id")
        }
        compose.setContent {
            ArkTheme {
                BookGrid(
                    books = books,
                    onOpenBook = {},
                    onToggleFavorite = {},
                    onDeleteBook = {},
                    modifier = Modifier.width(320.dp).height(720.dp).testTag("phone-grid"),
                    contentPadding = PaddingValues(16.dp),
                )
            }
        }
        compose.waitForIdle()

        val firstRow = books.take(3).map { item ->
            compose.onNodeWithContentDescription(item.title).fetchSemanticsNode().boundsInRoot
        }
        val fourth = compose.onNodeWithContentDescription(books[3].title).fetchSemanticsNode().boundsInRoot
        assertEquals(3, firstRow.map { it.center.x }.distinct().size)
        assertTrue("第四本应落在下一行", fourth.top > firstRow.first().top)
    }

    @Test
    @Config(qualifiers = "w1200dp-h800dp-mdpi")
    fun wideBookGridUsesFourColumns() {
        val books = (1L..5L).map { id ->
            book.copy(id = id, title = "宽屏书$id")
        }
        compose.setContent {
            ArkTheme {
                BookGrid(
                    books = books,
                    onOpenBook = {},
                    onToggleFavorite = {},
                    onDeleteBook = {},
                    modifier = Modifier.fillMaxSize().testTag("wide-grid"),
                    contentPadding = PaddingValues(16.dp),
                )
            }
        }
        compose.waitForIdle()

        val firstRow = books.take(4).map { item ->
            compose.onNodeWithContentDescription(item.title).fetchSemanticsNode().boundsInRoot
        }
        val fifth = compose.onNodeWithContentDescription(books[4].title).fetchSemanticsNode().boundsInRoot
        assertEquals(4, firstRow.map { it.center.x }.distinct().size)
        assertTrue("第五本应落在下一行", fifth.top > firstRow.first().top)
    }

    // ---- 多选 ----

    @Test fun longPressWithoutMovingEntersSelectionAndTapsToggleInsteadOfOpening() {
        val second = book.copy(id = 8, title = "第二本")
        var opened = 0
        compose.setContent {
            ArkTheme {
                // 不传拖动参数：与列表页一致，长按不动松手就是进入多选
                BookGrid(
                    books = listOf(book, second),
                    onOpenBook = { opened++ },
                    onToggleFavorite = {},
                    onDeleteBook = {},
                    modifier = Modifier.width(320.dp).height(600.dp),
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("测试小说").performTouchInput {
            down(center)
            advanceEventTime(700)
            up()
        }
        compose.waitForIdle()

        compose.onNodeWithText("已选 1 本").assertIsDisplayed()
        compose.onNodeWithContentDescription("已选：测试小说").assertExists()
        compose.onNodeWithContentDescription("未选：第二本").assertExists()
        compose.runOnIdle { assertEquals("长按松手不应打开书", 0, opened) }

        // 多选态下点封面是切换勾选，不是打开
        compose.onNodeWithContentDescription("第二本").performClick()
        compose.onNodeWithText("已选 2 本").assertIsDisplayed()
        compose.onNodeWithContentDescription("测试小说").performClick()
        compose.onNodeWithText("已选 1 本").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, opened) }
    }

    @Test fun longPressWithTinyFingerJitterAlsoEntersSelection() {
        var opened = 0
        compose.setContent {
            ArkTheme {
                BookGrid(
                    books = listOf(book),
                    onOpenBook = { opened++ },
                    onToggleFavorite = {},
                    onDeleteBook = {},
                    modifier = Modifier.width(320.dp).height(600.dp),
                )
            }
        }
        compose.waitForIdle()

        // 真实手指总会抖动：1px 的移动在点按容差之内，仍然算「没有拖动」
        compose.onNodeWithContentDescription("测试小说").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(center + Offset(1f, 1f), delayMillis = 16)
            up()
        }
        compose.waitForIdle()

        compose.onNodeWithText("已选 1 本").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, opened) }
    }

    @Test fun longPressThenDraggingInAListGridDoesNotEnterSelection() {
        val second = book.copy(id = 8, title = "第二本")
        var opened = 0
        compose.setContent {
            ArkTheme {
                BookGrid(
                    books = listOf(book, second),
                    onOpenBook = { opened++ },
                    onToggleFavorite = {},
                    onDeleteBook = {},
                    modifier = Modifier.width(320.dp).height(600.dp),
                )
            }
        }
        compose.waitForIdle()

        // 手指移出点按容差：列表页没有拖动功能，这次长按作废，既不多选也不打开
        compose.onNodeWithContentDescription("测试小说").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(center + Offset(1f, 1f), delayMillis = 16)
            moveTo(center + Offset(60f, 0f), delayMillis = 100)
            up()
        }
        compose.waitForIdle()

        compose.onAllNodesWithText("已选", substring = true).assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, opened) }
    }

    @Test fun moreMenuStartsSelectionAndHidesPerCardActions() {
        compose.setContent {
            ArkTheme {
                BookGrid(
                    books = listOf(book),
                    onOpenBook = {},
                    onToggleFavorite = {},
                    onDeleteBook = {},
                    modifier = Modifier.width(320.dp).height(600.dp),
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("更多：测试小说").performClick()
        compose.onNodeWithText("多选").performClick()
        compose.onNodeWithText("已选 1 本").assertIsDisplayed()
        // 多选态隐藏封面爱心和标题行三点按钮，改为勾选标
        compose.onNodeWithContentDescription("收藏").assertDoesNotExist()
        compose.onNodeWithContentDescription("更多：测试小说").assertDoesNotExist()
        compose.onNodeWithContentDescription("已选：测试小说").assertExists()

        compose.onNodeWithContentDescription("退出多选").performClick()
        compose.onAllNodesWithText("已选", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("收藏").assertExists()
        compose.onNodeWithContentDescription("更多：测试小说").assertExists()
    }

    @Test fun selectAllThenBatchDeleteNeedsConfirmationAndExitsSelection() {
        val second = book.copy(id = 8, title = "第二本")
        val selection = BookSelectionState()
        val deleted = mutableListOf<List<Long>>()
        compose.setContent {
            ArkTheme {
                BookGrid(
                    books = listOf(book, second),
                    onOpenBook = {},
                    onToggleFavorite = {},
                    onDeleteBook = {},
                    modifier = Modifier.width(320.dp).height(600.dp),
                    selectionState = selection,
                    onBatchDelete = { ids -> deleted.add(ids) },
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { selection.enter() }
        compose.waitForIdle()

        // 还没勾选任何书：批量操作全部禁用
        compose.onNodeWithTag("batch-delete").assertIsNotEnabled()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("已选 2 本").assertIsDisplayed()
        compose.onNodeWithText("全不选").assertIsDisplayed()
        compose.onNodeWithTag("batch-delete").assertIsEnabled()

        // 先取消：回调不触发，仍在多选里
        compose.onNodeWithTag("batch-delete").performClick()
        compose.onNodeWithText("删除选中的 2 本书？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
        compose.onNodeWithText("已选 2 本").assertIsDisplayed()

        // 再来一次并确认：收到两本书的 id，并退出多选
        compose.onNodeWithTag("batch-delete").performClick()
        compose.onNodeWithTag("batch-confirm").performClick()
        compose.runOnIdle {
            assertEquals(1, deleted.size)
            assertEquals(setOf(7L, 8L), deleted.single().toSet())
        }
        compose.onAllNodesWithText("已选", substring = true).assertCountEquals(0)
    }

    @Test fun batchMoveWithMixedGroupsNeedsAnExplicitTargetBeforeConfirm() {
        val first = book.copy(groupId = 1L)
        val second = book.copy(id = 8, title = "第二本", groupId = 2L)
        val selection = BookSelectionState()
        var moved: Pair<Set<Long>, Long?>? = null
        compose.setContent {
            ArkTheme {
                BookGrid(
                    books = listOf(first, second),
                    onOpenBook = {},
                    onToggleFavorite = {},
                    onDeleteBook = {},
                    modifier = Modifier.width(320.dp).height(600.dp),
                    selectionState = selection,
                    onBatchMove = { ids, groupId -> moved = ids.toSet() to groupId },
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { selection.enter() }
        compose.waitForIdle()

        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithTag("batch-move").performClick()
        compose.onNodeWithText("转移 2 本书到书架").assertIsDisplayed()
        // 两本书分属不同分组：不预选，没选目标前「确定」不可用
        compose.onNodeWithText("确定").assertIsNotEnabled()
        compose.onNodeWithText("未分组").performClick()
        compose.onNodeWithText("确定").assertIsEnabled()
        compose.onNodeWithText("确定").performClick()

        compose.runOnIdle { assertEquals(setOf(7L, 8L) to null, moved) }
        compose.onAllNodesWithText("已选", substring = true).assertCountEquals(0)
    }

    @Test fun batchMoveDialogPreselectsTheSharedGroupAndDistinguishesUngrouped() {
        // 两本都未分组：预选「未分组」，「确定」直接可用，结果是 null 而不是「没选」
        val second = book.copy(id = 8, title = "第二本")
        val selection = BookSelectionState()
        var moved: Pair<Set<Long>, Long?>? = null
        compose.setContent {
            ArkTheme {
                BookGrid(
                    books = listOf(book, second),
                    onOpenBook = {},
                    onToggleFavorite = {},
                    onDeleteBook = {},
                    modifier = Modifier.width(320.dp).height(600.dp),
                    selectionState = selection,
                    onBatchMove = { ids, groupId -> moved = ids.toSet() to groupId },
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { selection.enter(7L); selection.toggle(8L) }
        compose.waitForIdle()

        compose.onNodeWithTag("batch-move").performClick()
        compose.onNodeWithText("确定").assertIsEnabled()
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { assertEquals(setOf(7L, 8L) to null, moved) }
    }

    @Test fun batchFavoriteFlipsToUnfavoriteOnlyWhenEverySelectedBookIsFavorite() {
        val liked = book.copy(isFavorite = true)
        val second = book.copy(id = 8, title = "第二本")
        val selection = BookSelectionState()
        val calls = mutableListOf<Pair<Set<Long>, Boolean>>()
        compose.setContent {
            ArkTheme {
                BookGrid(
                    books = listOf(liked, second),
                    onOpenBook = {},
                    onToggleFavorite = {},
                    onDeleteBook = {},
                    modifier = Modifier.width(320.dp).height(600.dp),
                    selectionState = selection,
                    onBatchFavorite = { ids, favorite -> calls.add(ids.toSet() to favorite) },
                )
            }
        }
        compose.waitForIdle()

        // 只选已收藏的那本：按钮是「取消收藏」，点击后取消收藏并退出多选
        compose.runOnIdle { selection.enter(7L) }
        compose.waitForIdle()
        compose.onNodeWithText("取消收藏").assertIsDisplayed()
        compose.onNodeWithTag("batch-favorite").performClick()
        compose.runOnIdle { assertEquals(listOf(setOf(7L) to false), calls) }
        compose.onAllNodesWithText("已选", substring = true).assertCountEquals(0)

        // 再把没收藏的也选上：不是全部已收藏，按钮是「收藏」，点击后全部收藏
        calls.clear()
        compose.runOnIdle { selection.enter(7L); selection.toggle(8L) }
        compose.waitForIdle()
        compose.onNodeWithText("收藏").assertIsDisplayed()
        compose.onNodeWithTag("batch-favorite").performClick()
        compose.runOnIdle { assertEquals(listOf(setOf(7L, 8L) to true), calls) }
        compose.onAllNodesWithText("已选", substring = true).assertCountEquals(0)
    }

    @Test fun batchClearHistoryRequiresConfirmation() {
        val selection = BookSelectionState()
        val cleared = mutableListOf<Set<Long>>()
        compose.setContent {
            ArkTheme {
                BookGrid(
                    books = listOf(book),
                    onOpenBook = {},
                    onToggleFavorite = {},
                    onDeleteBook = {},
                    modifier = Modifier.width(320.dp).height(600.dp),
                    selectionState = selection,
                    onBatchClearHistory = { ids -> cleared.add(ids.toSet()) },
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { selection.enter(7L) }
        compose.waitForIdle()

        compose.onNodeWithTag("batch-clear-history").performClick()
        compose.onNodeWithText("删除阅读记录？").assertIsDisplayed()
        compose.onNodeWithText("选中的 1 本书阅读进度将被清零回到未读，书籍与书签保留。").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(cleared.isEmpty()) }

        compose.onNodeWithTag("batch-clear-history").performClick()
        compose.onNodeWithTag("batch-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(setOf(7L)), cleared) }
        compose.onAllNodesWithText("已选", substring = true).assertCountEquals(0)
    }
}

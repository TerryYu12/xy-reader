package com.xyreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.xyreader.core.BookEntity
import com.xyreader.core.BookGroupEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeGroupFoldersTest {
    @get:Rule
    val compose = createComposeRule()

    private val group = BookGroupEntity(id = 23, name = "待读", createdAt = 1)

    private fun drawWindowRoots(stage: String, baselineWindowRoots: List<View>) {
        repeat(60) { frame ->
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeByFrame()
            val dialogRootsReady = compose.runOnUiThread {
                var foundDecorView = false
                var allDecorViewsReady = true
                var hasNewPopupLayout = false
                WindowInspector.getGlobalWindowViews().forEachIndexed { index, view ->
                    val attached = view.isAttachedToWindow
                    val width = view.width
                    val height = view.height
                    val isNewDialogDecorView =
                        view.javaClass.simpleName == "DecorView" && baselineWindowRoots.none { it === view }
                    val isNewPopupLayout =
                        view.javaClass.simpleName == "PopupLayout" && baselineWindowRoots.none { it === view }
                    if (isNewPopupLayout) hasNewPopupLayout = true
                    println(
                        "window[$stage/$frame/$index] class=${view.javaClass.name} " +
                            "size=${width}x$height visibility=${view.visibility} " +
                            "attached=$attached layoutRequested=${view.isLayoutRequested} " +
                            "newPopupLayout=$isNewPopupLayout",
                    )
                    if (isNewDialogDecorView) {
                        foundDecorView = true
                    }
                    if (attached && width > 0 && height > 0) {
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            view.draw(Canvas(bitmap))
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    if (isNewDialogDecorView) {
                        val ready = attached && width > 0 && height > 0 && !view.isLayoutRequested
                        println("window[$stage/$frame/$index] newDialogDecorReady=$ready")
                        if (!ready) allDecorViewsReady = false
                    }
                }
                foundDecorView && allDecorViewsReady && !hasNewPopupLayout
            }
            if (dialogRootsReady) {
                println("window[$stage] DecorView roots ready after ${frame + 1} frames")
                return
            }
        }
        println("window[$stage] DecorView roots were not ready after 60 frames")
    }

    @Test
    fun folderRegistersAnIndependentDropTargetAndOpensTheGroup() {
        val dragState = BookGridDragState()
        var openedGroupId: Long? = null
        compose.setContent {
            ArkTheme {
                HomeGroupFolderSection(
                    groups = listOf(group),
                    bookCounts = mapOf(group.id to 3),
                    dragState = dragState,
                    onOpenGroup = { openedGroupId = it },
                    onRenameGroup = { _, _ -> },
                    onPickCover = {},
                    onClearCover = {},
                )
            }
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(dragState.targetBoundsInRoot.containsKey("home-folder-23"))
            assertFalse(dragState.targetBoundsInRoot.containsKey("home-group-23"))
        }
        compose.onNodeWithText("待读").performClick()
        compose.runOnIdle { assertEquals(23L, openedGroupId) }
    }

    @Test
    fun longPressDragDropsBookOnFolderAndMovesItToThatGroup() {
        val dragState = BookGridDragState()
        val book = BookEntity(id = 7, title = "可拖动的书", uri = "file:///drag.txt", format = "TXT", addedAt = 1)
        var moved: Pair<Long, Long?>? = null
        compose.setContent {
            ArkTheme {
                Column(Modifier.fillMaxSize()) {
                    HomeGroupFolderSection(
                        groups = listOf(group),
                        bookCounts = emptyMap(),
                        dragState = dragState,
                        onOpenGroup = {},
                        onRenameGroup = { _, _ -> },
                        onPickCover = {},
                        onClearCover = {},
                    )
                    BookGrid(
                        books = listOf(book),
                        onOpenBook = {},
                        onToggleFavorite = {},
                        onDeleteBook = {},
                        modifier = Modifier.weight(1f).testTag("drag-grid"),
                        contentPadding = PaddingValues(16.dp),
                        onMoveToGroup = { movedBook, groupId -> moved = movedBook.id to groupId },
                        dropTargets = listOf(BookDropTarget("home-folder-23", 23L, "待读")),
                        dragState = dragState,
                    )
                }
            }
        }
        compose.waitForIdle()

        val gridBounds = compose.onNodeWithTag("drag-grid").fetchSemanticsNode().boundsInRoot
        val bookBounds = compose.onNodeWithContentDescription("可拖动的书").fetchSemanticsNode().boundsInRoot
        val folderBounds = requireNotNull(dragState.targetBoundsInRoot["home-folder-23"])
        val start = bookBounds.center - gridBounds.topLeft
        val end = folderBounds.center - gridBounds.topLeft
        compose.onNodeWithTag("drag-grid").performTouchInput {
            down(start)
            advanceEventTime(700)
            moveTo(start + Offset(1f, 1f), delayMillis = 16)
            moveTo(end, delayMillis = 400)
            up()
        }

        compose.runOnIdle { assertEquals(7L to 23L, moved) }
    }

    @Test
    fun folderMenuIsIndependentAndContainsOnlyRenameAndDelete() {
        val dragState = BookGridDragState()
        var openedGroupId: Long? = null
        compose.setContent {
            ArkTheme {
                Column {
                    HomeGroupFolderSection(
                        groups = listOf(group),
                        bookCounts = emptyMap(),
                        dragState = dragState,
                        onOpenGroup = { openedGroupId = it },
                        onRenameGroup = { _, _ -> },
                        onPickCover = {},
                        onClearCover = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("更多分组操作：待读").performClick()
        compose.runOnIdle { assertEquals(null, openedGroupId) }
        compose.onNodeWithText("重命名").assertIsDisplayed()
        compose.onNodeWithText("删除分组").assertIsDisplayed()
        compose.onNodeWithText("更换封面").assertDoesNotExist()
    }

    @Test
    fun renameActionSubmitsTheTrimmedGroupName() {
        val dragState = BookGridDragState()
        var renamed: Pair<Long, String>? = null
        compose.setContent {
            ArkTheme {
                Column(Modifier.fillMaxSize()) {
                    HomeGroupFolderSection(
                        groups = listOf(group),
                        bookCounts = emptyMap(),
                        dragState = dragState,
                        onOpenGroup = {},
                        onRenameGroup = { id, name -> renamed = id to name },
                        onPickCover = {},
                        onClearCover = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val baselineWindowRoots = compose.runOnUiThread {
            WindowInspector.getGlobalWindowViews().toList()
        }
        compose.onNodeWithContentDescription("更多分组操作：待读").performClick()
        compose.onNodeWithText("重命名").performClick()
        drawWindowRoots("rename-dialog", baselineWindowRoots)
        compose.onNode(hasSetTextAction()).performTextReplacement("  正在读  ")
        drawWindowRoots("renamed-text", baselineWindowRoots)
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(23L to "正在读", renamed) }
    }

    @Test
    fun deletingGroupRequiresConfirmation() {
        val dragState = BookGridDragState()
        val deleted = mutableListOf<Long>()
        compose.setContent {
            ArkTheme {
                HomeGroupFolderSection(
                    groups = listOf(group),
                    bookCounts = mapOf(group.id to 2),
                    dragState = dragState,
                    onOpenGroup = {},
                    onRenameGroup = { _, _ -> },
                    onPickCover = {},
                    onClearCover = {},
                    onDeleteGroup = { deleted += it },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("更多分组操作：待读").performClick()
        compose.onNodeWithText("删除分组").performClick()
        compose.onNodeWithText("组内 2 本书将移至未分组，书籍文件不会删除。").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }

        compose.onNodeWithContentDescription("更多分组操作：待读").performClick()
        compose.onNodeWithText("删除分组").performClick()
        compose.onNodeWithText("删除", substring = false).performClick()
        compose.runOnIdle { assertEquals(listOf(23L), deleted) }
    }

    @Test
    fun emptySectionStillOffersCreateGroup() {
        val dragState = BookGridDragState()
        var createRequests = 0
        compose.setContent {
            ArkTheme {
                HomeGroupFolderSection(
                    groups = emptyList(),
                    bookCounts = emptyMap(),
                    dragState = dragState,
                    onOpenGroup = {},
                    onRenameGroup = { _, _ -> },
                    onPickCover = {},
                    onClearCover = {},
                    onCreateGroup = { createRequests++ },
                )
            }
        }
        compose.onNodeWithText("还没有分组").assertDoesNotExist()
        compose.onNodeWithText("暂无分组").assertIsDisplayed()
        compose.onNodeWithText("新建").performClick()
        compose.runOnIdle { assertEquals(1, createRequests) }
    }

    @Test
    fun disposedFolderUnregistersItsDropTarget() {
        val dragState = BookGridDragState()
        compose.setContent {
            ArkTheme {
                var visible by remember { mutableStateOf(true) }
                Column {
                    if (visible) {
                        HomeGroupFolderSection(
                            groups = listOf(group),
                            bookCounts = emptyMap(),
                            dragState = dragState,
                            onOpenGroup = {},
                            onRenameGroup = { _, _ -> },
                            onPickCover = {},
                            onClearCover = {},
                        )
                    }
                    TextButton(onClick = { visible = false }) { Text("隐藏分组") }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(dragState.targetBoundsInRoot.containsKey("home-folder-23")) }

        compose.onNodeWithText("隐藏分组").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(dragState.targetBoundsInRoot.containsKey("home-folder-23")) }
    }
}

package com.xyreader.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.xyreader.core.BookEntity
import com.xyreader.core.Chapter
import com.xyreader.data.ArkDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 书籍详情页：目录读取（本地开包 / 远程不发请求 / 坏包报错）、当前章节下标、
 * 以及"点击章节把该章起始页交给阅读器"的端到端接线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookDetailTest {
    @get:Rule val compose = createComposeRule()

    private fun makeCbz(name: String, vararg entryNames: String): File {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for (entry in entryNames) {
                // 目录读取只走条目名（章节分组），页面渲染不在本测试范围，假字节足够
                zip.putNextEntry(ZipEntry(entry))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
        return file
    }

    @Test fun localZipChaptersExposedInOrder() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = makeCbz("toc.cbz", "第一话/01.png", "第一话/02.png", "第二话/01.png")
        val book = BookEntity(
            title = "目录测试",
            uri = Uri.fromFile(file).toString(),
            format = "CBZ",
            addedAt = 1,
        )
        val toc = loadToc(context, book)
        assertTrue("本地包应读到目录：$toc", toc is BookDetailViewModel.Toc.Loaded)
        val loaded = toc as BookDetailViewModel.Toc.Loaded
        assertEquals(listOf("第一话", "第二话"), loaded.chapters.map { it.title })
        assertEquals(0, loaded.chapters[0].startPage)
        assertEquals(1, loaded.chapters[0].endPageInclusive)
        assertEquals(2, loaded.chapters[1].startPage)
        assertEquals(3, loaded.pageCount)
    }

    @Test fun remoteBookTocIsHiddenWithoutNetwork() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        for (uri in listOf("webdav://1/novel.cbz", "gdrive://2/abcDEF123")) {
            val book = BookEntity(title = "远端", uri = uri, format = "CBZ", addedAt = 1)
            assertTrue(
                "远程书 $uri 不应在详情页读目录",
                loadToc(context, book) is BookDetailViewModel.Toc.Hidden,
            )
        }
    }

    @Test fun brokenLocalBookTocFails() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val book = BookEntity(
            title = "坏包",
            uri = "file:///nonexistent/void.cbz",
            format = "CBZ",
            addedAt = 1,
        )
        assertTrue(loadToc(context, book) is BookDetailViewModel.Toc.Failed)
    }

    @Test fun currentChapterIndexFollowsReadingPage() {
        val chapters = listOf(
            Chapter("一", startPage = 0, endPageInclusive = 3),
            Chapter("二", startPage = 4, endPageInclusive = 6),
            Chapter("三", startPage = 7, endPageInclusive = 9),
        )
        assertEquals(0, currentChapterIndex(chapters, 0))
        assertEquals(0, currentChapterIndex(chapters, 3))
        assertEquals(1, currentChapterIndex(chapters, 4))
        assertEquals(1, currentChapterIndex(chapters, 6))
        assertEquals(2, currentChapterIndex(chapters, 9))
        assertEquals(2, currentChapterIndex(chapters, 99))
        val shifted = listOf(Chapter("正文", startPage = 1, endPageInclusive = 3))
        assertEquals(-1, currentChapterIndex(shifted, 0))
        assertEquals(-1, currentChapterIndex(emptyList(), 3))
    }

    @Test fun chapterClickOpensReaderAtChapterStartPage() {
        val context = RuntimeEnvironment.getApplication()
        val file = makeCbz(
            "click.cbz",
            "第一话/01.png", "第一话/02.png",
            "第二话/01.png", "第二话/02.png",
        )
        val bookId = runBlocking {
            ArkDatabase.getInstance(context).bookDao().insertBook(
                BookEntity(
                    title = "点击测试",
                    uri = Uri.fromFile(file).toString(),
                    format = "CBZ",
                    addedAt = 1,
                    totalPages = 4,
                    currentPage = 2,
                ),
            )
        }
        val opened = mutableListOf<Int>()
        compose.setContent {
            ArkTheme {
                BookDetailScreen(
                    bookId = bookId,
                    onBack = {},
                    onContinue = {},
                    onStartFromBeginning = {},
                    onOpenChapter = { page -> opened.add(page) },
                    onDeleted = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag("chapter_1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("chapter_1").performClick()
        assertEquals("点击第二话应把其起始页交给阅读器", listOf(2), opened)
    }
}

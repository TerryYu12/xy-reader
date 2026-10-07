package com.xyreader.data

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.withTransaction
import com.xyreader.core.BookEntity
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 封面三点菜单的数据层动作：
 * - 重命名（去首尾空白、空标题忽略）；
 * - 删除阅读记录（进度清零 + lastReadAt 置空，书与页数保留）；
 * - 自定义封面（写入 covers/ 并落库，旧封面被替换）；
 * - 刷新封面失败时保留旧封面不破坏。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookActionsRepoTest {

    @Test
    fun renameTrimsAndIgnoresBlank() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppGraph.libraryRepository(context)
        val dao = ArkDatabase.getInstance(context).bookDao()
        val id = dao.insertBook(BookEntity(title = "旧名", uri = "file:///b1.txt", format = "TXT", addedAt = 1))

        repo.renameBook(id, "  新名字  ")
        assertEquals("新名字", dao.getById(id)!!.title)

        repo.renameBook(id, "   ")
        assertEquals("新名字", dao.getById(id)!!.title)
    }

    @Test
    fun clearReadingHistoryResetsProgressAndTimestamp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppGraph.libraryRepository(context)
        val dao = ArkDatabase.getInstance(context).bookDao()
        val id = dao.insertBook(
            BookEntity(
                title = "有进度",
                uri = "file:///b2.txt",
                format = "TXT",
                addedAt = 2,
                currentPage = 5,
                totalPages = 120,
                lastReadAt = 12_345L,
            ),
        )

        repo.clearReadingHistory(id)
        val book = dao.getById(id)!!
        assertEquals(0, book.currentPage)
        assertNull(book.lastReadAt)
        assertEquals(120, book.totalPages)
    }

    @Test
    fun setCustomCoverWritesFileAndRefreshFailureKeepsIt() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppGraph.libraryRepository(context)
        val dao = ArkDatabase.getInstance(context).bookDao()
        val id = dao.insertBook(BookEntity(title = "封面", uri = "file:///missing-book.txt", format = "TXT", addedAt = 3))

        // 造一张真实 PNG 作为「用户选择的图片」
        val src = File(context.filesDir, "picked.png")
        val bitmap = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(0xFF336699.toInt()) }
        FileOutputStream(src).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertTrue(repo.setCustomCover(id, Uri.fromFile(src)))
        val cover = dao.getById(id)!!.coverPath
        assertNotNull(cover)
        assertTrue(cover!!.contains("-custom-"))
        assertTrue(File(cover).exists())

        // 刷新封面：书源文件不存在 → 返回 false，且旧自定义封面不被破坏
        assertFalse(repo.refreshCover(id))
        assertEquals(cover, dao.getById(id)!!.coverPath)
        assertTrue(File(cover).exists())
    }

    // ---- 批量（多选）----

    @Test
    fun moveBooksToGroupMovesOnlyTheGivenBooks() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppGraph.libraryRepository(context)
        val dao = ArkDatabase.getInstance(context).bookDao()
        val target = repo.addGroup("批量转移-目标")
        val other = repo.addGroup("批量转移-其他")
        val a = dao.insertBook(BookEntity(title = "转移甲", uri = "file:///batch-move-a.txt", format = "TXT", addedAt = 11))
        val b = dao.insertBook(BookEntity(title = "转移乙", uri = "file:///batch-move-b.txt", format = "TXT", addedAt = 12))
        val untouched = dao.insertBook(
            BookEntity(title = "转移丙", uri = "file:///batch-move-c.txt", format = "TXT", addedAt = 13, groupId = other),
        )

        repo.moveBooksToGroup(listOf(a, b), target)
        assertEquals(target, dao.getById(a)!!.groupId)
        assertEquals(target, dao.getById(b)!!.groupId)
        assertEquals("未选中的书分组不变", other, dao.getById(untouched)!!.groupId)

        // groupId 传 null = 整体移出分组；空集合与重复 id 都安全
        repo.moveBooksToGroup(emptyList(), target)
        repo.moveBooksToGroup(listOf(a, a, b), null)
        assertNull(dao.getById(a)!!.groupId)
        assertNull(dao.getById(b)!!.groupId)
        assertEquals(other, dao.getById(untouched)!!.groupId)

        // 清理：分组与书都留在共享的单例数据库里，不要影响其他用例
        repo.removeGroup(target)
        repo.removeGroup(other)
        repo.deleteBooks(listOf(a, b, untouched))
        assertNull(dao.getById(untouched))
    }

    @Test
    fun setFavoriteForManyBooksSetsAndClearsOnlyTheGivenBooks() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppGraph.libraryRepository(context)
        val dao = ArkDatabase.getInstance(context).bookDao()
        val a = dao.insertBook(BookEntity(title = "收藏甲", uri = "file:///batch-fav-a.txt", format = "TXT", addedAt = 21))
        val b = dao.insertBook(BookEntity(title = "收藏乙", uri = "file:///batch-fav-b.txt", format = "TXT", addedAt = 22))
        val untouched = dao.insertBook(BookEntity(title = "收藏丙", uri = "file:///batch-fav-c.txt", format = "TXT", addedAt = 23))

        repo.setFavorite(listOf(a, b), true)
        assertTrue(dao.getById(a)!!.isFavorite)
        assertTrue(dao.getById(b)!!.isFavorite)
        assertFalse("未选中的书不受影响", dao.getById(untouched)!!.isFavorite)

        // 显式指定目标状态：对已收藏的书再次 true 仍是收藏，false 才取消
        repo.setFavorite(listOf(a), false)
        assertFalse(dao.getById(a)!!.isFavorite)
        assertTrue(dao.getById(b)!!.isFavorite)
        repo.setFavorite(emptyList(), true)
        assertFalse(dao.getById(a)!!.isFavorite)

        repo.deleteBooks(listOf(a, b, untouched))
        assertNull(dao.getById(a))
    }

    @Test
    fun clearReadingHistoryForManyBooksResetsProgressButKeepsPagesAndOthers() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppGraph.libraryRepository(context)
        val dao = ArkDatabase.getInstance(context).bookDao()
        fun readBook(title: String, uri: String, page: Int, readAt: Long) = BookEntity(
            title = title,
            uri = uri,
            format = "TXT",
            addedAt = 31,
            currentPage = page,
            totalPages = 200,
            lastReadAt = readAt,
        )
        val a = dao.insertBook(readBook("清除甲", "file:///batch-clear-a.txt", page = 5, readAt = 1_000L))
        val b = dao.insertBook(readBook("清除乙", "file:///batch-clear-b.txt", page = 9, readAt = 2_000L))
        val untouched = dao.insertBook(readBook("清除丙", "file:///batch-clear-c.txt", page = 7, readAt = 3_000L))

        repo.clearReadingHistory(listOf(a, b))
        for (id in listOf(a, b)) {
            val book = dao.getById(id)!!
            assertEquals(0, book.currentPage)
            assertNull(book.lastReadAt)
            assertEquals("总页数保留", 200, book.totalPages)
        }
        val kept = dao.getById(untouched)!!
        assertEquals(7, kept.currentPage)
        assertEquals(3_000L, kept.lastReadAt)

        // 单本重载仍然可用，互不干扰
        repo.clearReadingHistory(untouched)
        assertEquals(0, dao.getById(untouched)!!.currentPage)
        repo.clearReadingHistory(emptyList())

        repo.deleteBooks(listOf(a, b, untouched))
        assertNull(dao.getById(a))
    }

    @Test
    fun deleteBooksRemovesRecordsBookmarksAndCoversButKeepsOthers() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppGraph.libraryRepository(context)
        val dao = ArkDatabase.getInstance(context).bookDao()
        val original = File(context.filesDir, "batch-del-original.txt").apply { writeText("原文件必须保留") }
        val coverA = File(context.filesDir, "batch-del-a.jpg").apply { writeText("cover-a") }
        val coverB = File(context.filesDir, "batch-del-b.jpg").apply { writeText("cover-b") }
        val coverKept = File(context.filesDir, "batch-del-kept.jpg").apply { writeText("cover-kept") }
        val a = dao.insertBook(
            BookEntity(
                title = "删除甲",
                uri = original.toURI().toString(),
                format = "TXT",
                addedAt = 41,
                coverPath = coverA.absolutePath,
            ),
        )
        val b = dao.insertBook(
            BookEntity(
                title = "删除乙",
                uri = "file:///batch-del-b.txt",
                format = "TXT",
                addedAt = 42,
                coverPath = coverB.absolutePath,
            ),
        )
        val kept = dao.insertBook(
            BookEntity(
                title = "删除丙（保留）",
                uri = "file:///batch-del-kept.txt",
                format = "TXT",
                addedAt = 43,
                coverPath = coverKept.absolutePath,
            ),
        )
        repo.addBookmark(a, 1)
        repo.addBookmark(b, 2)
        repo.addBookmark(kept, 3)

        // 不存在的 id 直接忽略
        repo.deleteBooks(listOf(a, b, Long.MAX_VALUE))

        assertNull(dao.getById(a))
        assertNull(dao.getById(b))
        assertNotNull("未选中的书保留", dao.getById(kept))
        assertEquals(
            "被删书的书签一并清理，未选中的书签保留",
            listOf(kept),
            repo.bookmarks.first().map { it.bookId }.filter { it == a || it == b || it == kept },
        )
        assertFalse("封面缓存被清理", coverA.exists())
        assertFalse(coverB.exists())
        assertTrue("未选中的书封面保留", coverKept.exists())
        assertEquals("原文件不动", "原文件必须保留", original.readText())

        // 清理：留下的书与书签会污染其他用例共享的单例数据库
        repo.deleteBook(kept)
        assertFalse(coverKept.exists())
    }

    @Test
    fun batchOperationsCoverEveryChunkOfALargeSelection() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppGraph.libraryRepository(context)
        val db = ArkDatabase.getInstance(context)
        val dao = db.bookDao()
        // 1001 个 id：跨过每批 500 个的两个边界，最后一批只剩 1 个
        val ids = db.withTransaction {
            (1..1001).map { index ->
                dao.insertBook(
                    BookEntity(
                        title = "大批量-$index",
                        uri = "file:///batch-large-$index.txt",
                        format = "TXT",
                        addedAt = index.toLong(),
                        currentPage = 3,
                        totalPages = 10,
                        lastReadAt = 5L,
                    ),
                )
            }
        }

        repo.setFavorite(ids, true)
        assertEquals(1001, ids.chunked(500).flatMap { dao.getByIds(it) }.count { it.isFavorite })

        repo.clearReadingHistory(ids)
        assertEquals(1001, ids.chunked(500).flatMap { dao.getByIds(it) }.count { it.currentPage == 0 && it.lastReadAt == null })

        repo.deleteBooks(ids)
        assertEquals(0, ids.chunked(500).flatMap { dao.getByIds(it) }.size)
    }
}

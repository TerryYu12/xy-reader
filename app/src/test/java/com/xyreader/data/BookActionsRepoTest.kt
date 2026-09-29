package com.xyreader.data

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import com.xyreader.core.BookEntity
import java.io.File
import java.io.FileOutputStream
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
}

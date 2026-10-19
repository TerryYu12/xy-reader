package com.xyreader.data

import android.app.Application
import android.net.Uri
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 下载入库链路（真 Room + 真文件系统）：
 * 先落盘后入库、按「来源派生名」避免同名覆盖、同身份重复入库幂等、不支持扩展名拒绝。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadedImportTest {

    private fun staged(context: Application, name: String, text: String): File =
        File(context.cacheDir, name).apply { writeText(text) }

    private fun imported(context: Application, name: String) =
        File(File(context.filesDir, "imported"), name)

    @Test fun downloadedFileIsCopiedIntoPrivateDirWithStoredNameAndDisplayTitle() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = LibraryRepositoryImpl(context)
        val dao = ArkDatabase.getInstance(context).bookDao()
        val storedName = "opds-home_book-42_1a2b3c4d.txt"
        val src = staged(context, storedName, "下载来的正文")

        val id = repo.importDownloadedFile(src, storedName, "测试书 第一卷")
        val book = dao.getById(id)!!

        assertEquals("测试书 第一卷", book.title)
        assertEquals("TXT", book.format)
        val copied = imported(context, storedName)
        assertTrue("应复制到私有导入目录: ${book.uri}", copied.exists())
        assertTrue(copied.readText().contains("下载来的正文"))
        assertTrue(Uri.decode(book.uri).endsWith(storedName))
        assertTrue(book.size > 0)
        // 不留半成品
        assertFalse(imported(context, "$storedName.part").exists())
    }

    @Test fun sameIdentityReimportIsIdempotentAndRefreshesTitleAndSize() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = LibraryRepositoryImpl(context)
        val dao = ArkDatabase.getInstance(context).bookDao()
        val storedName = "opds-home_book-7_aabbccdd.txt"
        val id1 = repo.importDownloadedFile(staged(context, "first.txt", "旧内容"), storedName, "旧标题")

        val id2 = repo.importDownloadedFile(
            staged(context, "second.txt", "新内容，更长一些"),
            storedName,
            "新标题",
        )

        assertEquals("同一身份重复入库应复用记录", id1, id2)
        val book = dao.getById(id1)!!
        assertEquals("新标题", book.title)
        assertEquals(imported(context, storedName).length(), book.size)
        assertTrue(imported(context, storedName).readText().contains("新内容"))
        assertEquals("不应产生重复记录", 1, dao.getAllUris().count { it == book.uri })
    }

    @Test fun sameTitleFromDifferentSourcesProducesTwoBooks() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = LibraryRepositoryImpl(context)
        val dao = ArkDatabase.getInstance(context).bookDao()
        val nameA = "opds-home_book-1_11111111.txt"
        val nameB = "calibre-home_book-1_22222222.txt"

        val idA = repo.importDownloadedFile(staged(context, nameA, "来源 A 的正文"), nameA, "同名书")
        val idB = repo.importDownloadedFile(staged(context, nameB, "来源 B 的正文，完全不同"), nameB, "同名书")

        assertNotEquals("同名不同来源必须是两本书", idA, idB)
        val bookA = dao.getById(idA)!!
        val bookB = dao.getById(idB)!!
        assertNotEquals(bookA.uri, bookB.uri)
        assertTrue(imported(context, nameA).readText().contains("来源 A"))
        assertTrue(imported(context, nameB).readText().contains("来源 B"))
    }

    @Test fun unsupportedStoredNameIsRejectedWithoutLeavingFiles() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = LibraryRepositoryImpl(context)
        val name = "opds-home_book-9_deadbeef.bin"

        val result = runCatching {
            repo.importDownloadedFile(staged(context, name, "bytes"), name, "书")
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertFalse(imported(context, name).exists())
    }
}

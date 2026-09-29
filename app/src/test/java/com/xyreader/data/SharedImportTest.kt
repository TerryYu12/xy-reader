package com.xyreader.data

import android.app.Application
import android.net.Uri
import com.xyreader.core.BookFormat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 外部「用其他应用打开 / 分享」单文件导入链路（真 Room + 真文件系统）：
 * 拷贝进私有目录、建记录、无扩展名时按 MIME 补扩展、重复导入去重、不支持类型拒绝。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedImportTest {

    @Test
    fun importsTxtIntoPrivateDirAndCreatesBook() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = LibraryRepositoryImpl(context)
        val dao = ArkDatabase.getInstance(context).bookDao()

        val src = File(context.cacheDir, "来源小说.txt")
        src.writeText("第一段落，测试导入。\n\n第二段落。")

        val id = repo.importSharedFile(Uri.fromFile(src).toString(), null, "text/plain")
        val book = dao.getById(id)!!

        assertEquals("来源小说", book.title)
        assertEquals("TXT", book.format)
        // 文件复制进私有 imported/ 目录且内容一致（路径断言不依赖分隔符，兼容 Windows 测试环境）
        val copied = File(File(context.filesDir, "imported"), "来源小说.txt")
        assertTrue("文件应复制到私有目录: ${book.uri}", copied.exists())
        assertTrue(copied.readText().contains("第二段落"))
        assertTrue(Uri.decode(book.uri).contains("来源小说.txt"))
        assertTrue(book.size > 0)
    }

    @Test
    fun reimportSameFileReusesRecord() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = LibraryRepositoryImpl(context)
        val dao = ArkDatabase.getInstance(context).bookDao()

        val src = File(context.cacheDir, "重复导入.txt")
        src.writeText("第一次")
        val id1 = repo.importSharedFile(Uri.fromFile(src).toString(), null, null)

        src.writeText("第二次，内容已更新")
        val id2 = repo.importSharedFile(Uri.fromFile(src).toString(), null, null)

        assertEquals("重复导入应复用同一记录", id1, id2)
        val book = dao.getById(id1)!!
        val uris = dao.getAllUris().filter { it == book.uri }
        assertEquals("不应产生重复记录", 1, uris.size)
    }

    @Test
    fun derivesExtensionFromMimeWhenNameHasNone() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = LibraryRepositoryImpl(context)
        val dao = ArkDatabase.getInstance(context).bookDao()

        val src = File(context.cacheDir, "没扩展名的文件")
        src.writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46)) // %PDF 魔数头，仅作字节

        val id = repo.importSharedFile(Uri.fromFile(src).toString(), "没扩展名的文件", "application/pdf")
        val book = dao.getById(id)!!
        assertEquals("PDF", book.format)
        assertEquals("没扩展名的文件", book.title)
    }

    @Test
    fun rejectsUnsupportedBinary() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = LibraryRepositoryImpl(context)

        // 二进制内容 + 未知扩展名 + 无 MIME：连魔数嗅探都认不出 → 拒绝
        val src = File(context.cacheDir, "不支持的格式.xyz")
        src.writeBytes(byteArrayOf(0x01, 0x02, 0x00, 0x03, 0x1A, 0x7F, 0x00, 0x55))

        val result = runCatching {
            repo.importSharedFile(Uri.fromFile(src).toString(), null, null)
        }
        assertTrue("无法识别的二进制应被拒绝", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun sniffsPdfMagicWhenNameAndMimeUnknown() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = LibraryRepositoryImpl(context)
        val dao = ArkDatabase.getInstance(context).bookDao()

        val src = File(context.cacheDir, "无名文件")
        src.writeBytes("%PDF-1.7\nfake pdf body".toByteArray())

        val id = repo.importSharedFile(
            Uri.fromFile(src).toString(),
            nameHint = null,
            mimeType = "application/octet-stream",
        )
        val book = dao.getById(id)!!
        assertEquals("PDF", book.format)
        assertTrue(
            "应按魔数重命名为 .pdf",
            File(File(context.filesDir, "imported"), "无名文件.pdf").exists(),
        )
    }

    @Test
    fun sniffsEpubFromZipMimetype() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = LibraryRepositoryImpl(context)
        val dao = ArkDatabase.getInstance(context).bookDao()

        val src = File(context.cacheDir, "无扩展书")
        java.util.zip.ZipOutputStream(src.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("content.opf"))
            zos.write("<package/>".toByteArray())
            zos.closeEntry()
        }

        val id = repo.importSharedFile(Uri.fromFile(src).toString(), null, null)
        val book = dao.getById(id)!!
        assertEquals("EPUB", book.format)
    }

    @Test
    fun sniffsPlainTextWhenEverythingUnknown() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = LibraryRepositoryImpl(context)
        val dao = ArkDatabase.getInstance(context).bookDao()

        val src = File(context.cacheDir, "纯文本文件")
        src.writeText("hello 世界\n第二行文字")

        val id = repo.importSharedFile(Uri.fromFile(src).toString(), null, null)
        val book = dao.getById(id)!!
        assertEquals("TXT", book.format)
    }
}

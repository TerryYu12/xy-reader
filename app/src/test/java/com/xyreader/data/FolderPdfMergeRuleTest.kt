package com.xyreader.data

import com.xyreader.core.BookFormat
import com.xyreader.core.LocalRepoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同文件夹 PDF 合并规则（纯函数 [shouldMergeFolderPdfs]）：
 * 只在「非仓库根目录 + 仓库开关打开 + 直接子项 PDF ≥ 2 个」时合并成一本 PDF 合集。
 */
class FolderPdfMergeRuleTest {

    @Test
    fun `仓库根目录即使有多个 PDF 也不合并`() {
        assertFalse(shouldMergeFolderPdfs(depth = 0, enabled = true, pdfCount = 2))
        assertFalse(shouldMergeFolderPdfs(depth = 0, enabled = true, pdfCount = 10))
    }

    @Test
    fun `子目录只有 1 个 PDF 不合并`() {
        assertFalse(shouldMergeFolderPdfs(depth = 1, enabled = true, pdfCount = 1))
    }

    @Test
    fun `子目录没有 PDF 不合并`() {
        assertFalse(shouldMergeFolderPdfs(depth = 1, enabled = true, pdfCount = 0))
    }

    @Test
    fun `子目录有 2 个 PDF 合并`() {
        assertTrue(shouldMergeFolderPdfs(depth = 1, enabled = true, pdfCount = 2))
    }

    @Test
    fun `更深层的子目录同样合并`() {
        assertTrue(shouldMergeFolderPdfs(depth = 3, enabled = true, pdfCount = 5))
        // 扫描最深进入第 8 层子目录
        assertTrue(shouldMergeFolderPdfs(depth = 8, enabled = true, pdfCount = 2))
    }

    @Test
    fun `仓库开关关闭时任何目录都不合并`() {
        assertFalse(shouldMergeFolderPdfs(depth = 1, enabled = false, pdfCount = 5))
        assertFalse(shouldMergeFolderPdfs(depth = 0, enabled = false, pdfCount = 5))
    }

    @Test
    fun `新建仓库默认开启合并`() {
        val repo = LocalRepoEntity(name = "漫画库", uri = "content://tree/comics", createdAt = 0L)
        assertTrue(repo.mergeFolderPdfs)
    }

    @Test
    fun `PDF 合集格式名会持久化进书库且不由扩展名识别`() {
        // BookEntity.format 存的是枚举名，改名会让已入库的合集失效
        assertEquals(BookFormat.PDF_FOLDER, BookFormat.valueOf("PDF_FOLDER"))
        assertEquals("PDF 合集", BookFormat.PDF_FOLDER.displayName)
        // 合集只由扫描器按文件夹生成：单个 pdf 依旧是 PDF，任何扩展名都不会识别成合集
        assertEquals(BookFormat.PDF, BookFormat.fromFileName("卷1.pdf"))
        listOf(
            "cbz", "zip", "cbr", "rar", "cb7", "7z", "cbt", "tar", "epub", "pdf",
            "mobi", "prc", "azw3", "azw", "txt", "pdf_folder",
        ).forEach { ext ->
            assertNotEquals(BookFormat.PDF_FOLDER, BookFormat.fromFileName("x.$ext"))
        }
    }
}

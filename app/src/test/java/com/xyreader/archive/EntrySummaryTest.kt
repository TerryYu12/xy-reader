package com.xyreader.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「没有图片页面」的诊断摘要：只含数量与扩展名，不泄露文件名 */
class EntrySummaryTest {

    private fun describe(vararg names: String) =
        EntrySummary.describe(names.asSequence()) { PageSources.isExcludedEntry(it) }

    @Test fun emptyArchiveIsCalledOut() {
        assertEquals("压缩包是空的", describe())
    }

    @Test fun countsDirectoriesExcludedAndExtensions() {
        val text = describe(
            "第一话/",
            "第一话/a.ZIP",
            "第一话/b.zip",
            "__MACOSX/第一话/._a.zip",
            "readme.txt",
            "NOEXT",
        )
        assertEquals("条目 6 个（目录 1，被排除 1）；扩展名：.zip×2 .txt×1 (无扩展名)×1", text)
    }

    @Test fun neverEchoesFileNamesOrNonAsciiExtensions() {
        val text = describe("我的秘密漫画/第1话.壹贰", "SecretTitle.verylongextensionname")
        assertFalse(text.contains("秘密"))
        assertFalse(text.contains("SecretTitle"))
        assertFalse(text.contains("壹"))
        // 超长扩展名被截断，非 ASCII 字符变成 ?
        assertTrue(text, text.contains(".??"))
        assertTrue(text, text.contains(".verylong"))
        assertFalse(text, text.contains("verylongext"))
    }

    @Test fun capsExtensionKindsAndReportsTheRest() {
        val names = (1..9).map { "f.e$it" }.toTypedArray()
        val text = describe(*names)
        assertTrue(text, text.endsWith("…另 3 种"))
        assertEquals(6, Regex("×1").findAll(text).count())
    }

    @Test fun windowsSeparatorsAreTreatedAsDirectories() {
        assertEquals("条目 2 个（目录 1，被排除 0）；扩展名：.png×1", describe("ch1\\", "ch1\\p.png"))
    }
}

package com.xyreader

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PatternMatcher
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「用其他应用打开 / 分享」意图过滤器验证。
 *
 * 判案者 = Android 框架真实匹配代码（Robolectric 加载应用合并清单里的
 * intent-filter，queryIntentActivities 走 IntentFilter/PatternMatcher 真实实现），
 * 不是自写模拟器——本机上一个自写模拟器曾给出全部通过的假象。
 *
 * 场景矩阵覆盖：MT 管理器（file:// 无 MIME）、文件管理器、QQ/微信（content://
 * 常无扩展名，靠 MIME）、octet-stream 有路径、分享（SEND），以及反例防误列。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OpenWithIntentTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * MT 管理器视角（反编译实证：MT 用 `Intent(VIEW)` + `setDataAndType(uri, mime)`、
     * **不加任何 category**、`queryIntentActivities(intent, MATCH_DEFAULT_ONLY)` 自建列表）——
     * 默认按 MT 的真实调用方式判定。
     */
    private fun listed(
        uriString: String,
        mimeType: String?,
        action: String = Intent.ACTION_VIEW,
    ): Boolean = listedWithCategories(uriString, mimeType, action, categories = emptyList())

    /** 部分文件管理器会给 intent 附加 category 变体（DEFAULT/BROWSABLE/OPENABLE） */
    private fun listedWithCategories(
        uriString: String,
        mimeType: String?,
        action: String = Intent.ACTION_VIEW,
        categories: List<String> = emptyList(),
    ): Boolean {
        val intent = Intent(action).apply {
            categories.forEach { addCategory(it) }
            setDataAndType(Uri.parse(uriString), mimeType)
        }
        return context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo?.packageName == context.packageName }
    }

    /** 直接探测：哪种写法的运行态字符串能在真实 PatternMatcher 下命中 .txt 路径 */
    @Test
    fun pathPatternRuntimeProbe() {
        fun probe(pattern: String): Boolean {
            val filter = android.content.IntentFilter(Intent.ACTION_VIEW)
            filter.addCategory(Intent.CATEGORY_DEFAULT)
            filter.addDataScheme("file")
            filter.addDataAuthority("*", null)
            filter.addDataPath(pattern, PatternMatcher.PATTERN_SIMPLE_GLOB)
            return filter.match(
                Intent.ACTION_VIEW, null, "file",
                Uri.parse("file:///sdcard/novel/测试.txt"),
                setOf(Intent.CATEGORY_DEFAULT), "probe",
            ) >= 0
        }

        val one = probe(".*\\.txt")     // 运行态字符串：.*\.txt（单反斜杠）
        val two = probe(".*\\\\.txt")   // 运行态字符串：.*\\.txt（双反斜杠）
        val raw = probe(".*.txt")
        println("PROBE single=$one double=$two raw=$raw")
        // 至少要有一个形式能命中，否则说明注册写法整体失效
        assertTrue("no pathPattern form matches", one || two || raw)
    }

    // ---------- MT 管理器 / 文件管理器：file:// 路径、通常不带 MIME ----------

    @Test
    fun mtManagerFileUrisWithoutMimeAreListed() {
        val cases = listOf(
            "file:///storage/emulated/0/Download/小说.txt",
            "file:///storage/emulated/0/Download/漫画.pdf",
            "file:///storage/emulated/0/Download/book.epub",
            "file:///storage/emulated/0/Download/book.MOBI",
            "file:///storage/emulated/0/Download/档案.CBZ",
            "file:///storage/emulated/0/Download/old.cbr",
            "file:///storage/emulated/0/Download/doc.azw3",
        )
        for (uri in cases) {
            assertTrue("MT 场景未命中: $uri", listed(uri, null))
        }
    }

    @Test
    fun mtManagerFileUrisWithMimeAreListed() {
        assertTrue(listed("file:///storage/emulated/0/Download/漫画.pdf", "application/pdf"))
        assertTrue(listed("file:///storage/emulated/0/Download/小说.txt", "text/plain"))
    }

    // ---------- QQ / 微信：content:// 常无扩展名，靠 MIME ----------

    @Test
    fun contentUrisWithMimeAreListed() {
        val cases = listOf(
            "content://com.tencent.mm.external.fileprovider/file/123456" to "application/pdf",
            "content://com.tencent.mm.external.fileprovider/file/234567" to "text/plain",
            "content://com.tencent.mobileqq.fileprovider/qqfile/345678" to "application/epub+zip",
            "content://com.tencent.mobileqq.fileprovider/qqfile/456789" to "application/x-mobipocket-ebook",
        )
        for ((uri, mime) in cases) {
            assertTrue("content+mime 未命中: $uri ($mime)", listed(uri, mime))
        }
    }

    @Test
    fun contentUriWithExtensionAndMimeIsListed() {
        assertTrue(
            listed(
                "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fbook.pdf",
                "application/pdf",
            ),
        )
    }

    // ---------- 杂牌管理器：octet-stream / */* 但路径有扩展名 ----------

    @Test
    fun octetStreamWithKnownExtensionIsListed() {
        assertTrue(
            listed(
                "content://com.mt.provider/external/%2Fstorage%2FDownload%2F存档.cbz",
                "application/octet-stream",
            ),
        )
        assertTrue(listed("file:///sdcard/Download/文件.epub", "application/octet-stream"))
        assertTrue(listed("file:///sdcard/Download/文件.pdf", "*/*"))
    }

    // ---------- 分享（ACTION_SEND） ----------

    @Test
    fun shareIntentsAreListed() {
        assertTrue(
            listed(
                "content://com.tencent.mm.external.fileprovider/file/999",
                "application/epub+zip",
                Intent.ACTION_SEND,
            ),
        )
        assertTrue(listed("file:///sdcard/小说.txt", "text/plain", Intent.ACTION_SEND))
        assertTrue(
            listed(
                "content://com.mt.provider/external/%2Fstorage%2FDownload%2Fbook.epub",
                "application/octet-stream",
                Intent.ACTION_SEND,
            ),
        )
    }

    // ---------- 真机真实形态缺口（调研三源交叉确认的主因） ----------

    @Test
    fun noExtensionContentUrisWithUnknownMimeAreListed() {
        // 系统文件管理器：content://media/external/file/85139 —— 路径无扩展名、MIME=octet-stream
        assertTrue(
            "系统文件管理器 octet-stream",
            listed("content://media/external/file/85139", "application/octet-stream"),
        )
        // 微信：content://com.tencent.mm.external.fileprovider/.../111（无扩展名）
        assertTrue(
            "微信 octet-stream",
            listed(
                "content://com.tencent.mm.external.fileprovider/external/tencent/MicroMsg/Download/111",
                "application/octet-stream",
            ),
        )
        assertTrue(
            "无扩展名 + application/pdf",
            listed("content://media/external/file/85140", "application/pdf"),
        )
        assertTrue(
            "file:// 无扩展名 + octet-stream",
            listed("file:///sdcard/Download/noext", "application/octet-stream"),
        )
        assertTrue(
            "SEND + octet-stream",
            listed("content://media/external/file/85141", "application/octet-stream", Intent.ACTION_SEND),
        )
    }

    // ---------- MT 管理器精确调用方式（反编译实证：无 category + MATCH_DEFAULT_ONLY） ----------

    @Test
    fun mtManagerExactQueryFindsUs() {
        // MT 用 setDataAndType(uri, mime) 且不带任何 category，content:// 与 file:// 各查一次
        assertTrue(
            "content:// 无扩展名 + octet-stream（MT 未知类型默认值）",
            listed("content://media/external/file/85139", "application/octet-stream"),
        )
        assertTrue(
            "file:// pdf + application/pdf",
            listed("file:///storage/emulated/0/Download/漫画.pdf", "application/pdf"),
        )
        assertTrue(
            "file:// txt + text/plain",
            listed("file:///storage/emulated/0/Download/小说.txt", "text/plain"),
        )
        assertTrue(
            "file:// epub + epub mime",
            listed("file:///storage/emulated/0/Download/book.epub", "application/epub+zip"),
        )
        assertTrue(
            "content:// 带扩展名 + pdf",
            listed("content://media/external/file/85142", "application/pdf"),
        )
    }

    // ---------- 调用方 categories 变体（MT管理器/各文件管理器可能带额外分类） ----------

    @Test
    fun categoryVariantsStillMatch() {
        fun listedWith(categories: List<String>, uri: String, mime: String?): Boolean {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                categories.forEach { addCategory(it) }
                setDataAndType(Uri.parse(uri), mime)
            }
            return context.packageManager.queryIntentActivities(intent, 0)
                .any { it.activityInfo?.packageName == context.packageName }
        }

        val fileUri = "file:///storage/emulated/0/Download/漫画.pdf"
        val contentUri = "content://com.tencent.mm.external.fileprovider/file/123456"

        assertTrue("VIEW+DEFAULT", listedWith(listOf(Intent.CATEGORY_DEFAULT), fileUri, null))
        assertTrue(
            "VIEW+DEFAULT+BROWSABLE",
            listedWith(listOf(Intent.CATEGORY_DEFAULT, Intent.CATEGORY_BROWSABLE), fileUri, null),
        )
        assertTrue(
            "VIEW+DEFAULT+OPENABLE",
            listedWith(listOf(Intent.CATEGORY_DEFAULT, Intent.CATEGORY_OPENABLE), fileUri, null),
        )
        assertTrue(
            "VIEW+DEFAULT+BROWSABLE+OPENABLE",
            listedWith(
                listOf(Intent.CATEGORY_DEFAULT, Intent.CATEGORY_BROWSABLE, Intent.CATEGORY_OPENABLE),
                fileUri,
                null,
            ),
        )
        assertTrue(
            "content+DEFAULT+BROWSABLE+text",
            listedWith(
                listOf(Intent.CATEGORY_DEFAULT, Intent.CATEGORY_BROWSABLE),
                contentUri,
                "text/plain",
            ),
        )
    }

    // ---------- 反例：不能误列 ----------

    @Test
    fun unrelatedFilesAreNotListed() {
        val negatives = listOf(
            "file:///sdcard/Movies/video.mp4" to null,
            "content://media/external/video/media/42" to "video/mp4",
            "file:///sdcard/Pictures/photo.jpg" to "image/jpeg",
            "file:///sdcard/Documents/report.docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "content://com.tencent.mm.external.fileprovider/file/888" to "video/mp4",
        )
        for ((uri, mime) in negatives) {
            assertTrue("不应命中: $uri ($mime)", !listed(uri, mime))
        }

        // 网页链接（浏览器场景，带 BROWSABLE）不应列出：只处理本地文件
        val web = Intent(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            setDataAndType(Uri.parse("https://example.com/doc.pdf"), "application/pdf")
        }
        assertTrue(
            "网页链接不应列出",
            context.packageManager.queryIntentActivities(web, 0)
                .none { it.activityInfo?.packageName == context.packageName },
        )
    }
}

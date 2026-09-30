package com.xyreader.archive

import android.app.Application
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * EPUB 封面提取 + 空章节跳过测试。
 *
 * 样本形态：EPUB2 meta cover（content=manifest id，calibre 常见）/ EPUB3 cover-image 属性 /
 * 文件名兜底 / 无封面；另含"SVG 包裹封面页与插图页 → 不生成空章节"回归项。
 * 真实样本（D:/Download/1.epub）存在时附跑（Assume，CI 无此文件自动跳过）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubCoverExtractionTest {

    /** 带 JPEG 魔数的假封面字节（提取层只搬运字节流，不解码） */
    private val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
        ByteArray(256) { (it % 251).toByte() }

    // ---------- 样本构造 ----------

    private fun zipOf(entries: List<Pair<String, ByteArray>>): ZipFile {
        val bos = ByteArrayOutputStream()
        ZipArchiveOutputStream(bos).use { zos ->
            for ((name, data) in entries) {
                zos.putArchiveEntry(ZipArchiveEntry(name))
                zos.write(data)
                zos.closeArchiveEntry()
            }
        }
        return ZipFile(SeekableInMemoryByteChannel(bos.toByteArray()))
    }

    private fun txt(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun containerXml() = txt(
        """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""",
    )

    private fun opf(metadata: String, manifest: String, spine: String) = txt(
        """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>测试书</dc:title>
    $metadata
  </metadata>
  <manifest>
    <item href="Text/Cover.xhtml" id="Cover_xhtml" media-type="application/xhtml+xml"/>
    <item href="Text/chapter1.xhtml" id="chapter1_xhtml" media-type="application/xhtml+xml"/>
    $manifest
  </manifest>
  <spine><itemref idref="Cover_xhtml"/><itemref idref="chapter1_xhtml"/>$spine</spine>
</package>""",
    )

    /** SVG 包裹的封面页（无文本）与带文字的章节 —— 与真实样本同构 */
    private fun coverDoc() = txt(
        """<html><head><title>封面</title></head><body>
<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 1225 1800">
<image width="1225" height="1800" xlink:href="../Images/cover.jpg"/></svg></body></html>""",
    )

    private fun chapterDoc() = txt(
        """<html><head><title>序章</title></head><body><p>这是正文的第一段。</p><p>这是正文的第二段。</p></body></html>""",
    )

    // ---------- 场景 1：EPUB2 meta cover content = manifest id ----------

    @Test
    fun metaCoverByIdSkipsEmptySvgPages() {
        val zip = zipOf(
            listOf(
                "META-INF/container.xml" to containerXml(),
                "OEBPS/content.opf" to opf(
                    metadata = """<meta name="cover" content="img_cover.jpg"/>""",
                    manifest = """<item href="Images/cover.jpg" id="img_cover.jpg" media-type="image/jpeg"/>""",
                    spine = "",
                ),
                "OEBPS/Text/Cover.xhtml" to coverDoc(),
                "OEBPS/Text/chapter1.xhtml" to chapterDoc(),
                "OEBPS/Images/cover.jpg" to fakeJpeg,
            ),
        )
        val data = NovelTextExtractor.parseEpub(zip)
        zip.close()

        assertNotNull("应提取到封面字节", data.coverBytes)
        assertTrue("封面字节应与样本一致", data.coverBytes!!.contentEquals(fakeJpeg))
        // 封面页（SVG 包裹）的图被收集为整页图片组：阅读时首页显示封面图
        assertEquals(1, data.imageGroups.size)
        assertEquals(0, data.imageGroups[0].insertAtIndex)
        assertTrue(
            "图片组字节应与样本一致",
            data.imageGroups[0].images[0].contentEquals(fakeJpeg),
        )
        // 空 SVG 封面页不生成章节：只剩"序章"
        assertEquals(listOf("序章"), data.marks.map { it.title })
        assertTrue("正文应含两段", data.paragraphs.size >= 2)
    }

    // ---------- 场景 2：EPUB3 properties="cover-image" ----------

    @Test
    fun epub3CoverImageProperty() {
        val zip = zipOf(
            listOf(
                "META-INF/container.xml" to containerXml(),
                "OEBPS/content.opf" to txt(
                    """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>测试书</dc:title></metadata>
  <manifest>
    <item href="Text/chapter1.xhtml" id="chapter1_xhtml" media-type="application/xhtml+xml"/>
    <item href="Images/cover-2.png" id="cover2" media-type="image/png" properties="cover-image"/>
  </manifest>
  <spine><itemref idref="chapter1_xhtml"/></spine>
</package>""",
                ),
                "OEBPS/Text/chapter1.xhtml" to chapterDoc(),
                "OEBPS/Images/cover-2.png" to (byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(128) { 7 }),
            ),
        )
        val data = NovelTextExtractor.parseEpub(zip)
        zip.close()

        assertNotNull("EPUB3 cover-image 应被识别", data.coverBytes)
        assertEquals(0x89.toByte(), data.coverBytes!![0])
    }

    // ---------- 场景 3：文件名兜底 href 含 cover ----------

    @Test
    fun fallbackHrefContainsCover() {
        val zip = zipOf(
            listOf(
                "META-INF/container.xml" to containerXml(),
                "OEBPS/content.opf" to opf(
                    metadata = "",
                    manifest = """<item href="Images/COVER.png" id="z1" media-type="image/png"/>""",
                    spine = "",
                ),
                "OEBPS/Text/Cover.xhtml" to coverDoc(),
                "OEBPS/Text/chapter1.xhtml" to chapterDoc(),
                "OEBPS/Images/COVER.png" to (byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(64) { 1 }),
            ),
        )
        val data = NovelTextExtractor.parseEpub(zip)
        zip.close()

        assertNotNull("文件名兜底应命中", data.coverBytes)
        assertEquals(0x89.toByte(), data.coverBytes!![0])
    }

    // ---------- 场景 4：无封面 → null ----------

    @Test
    fun noCoverReturnsNull() {
        val zip = zipOf(
            listOf(
                "META-INF/container.xml" to containerXml(),
                "OEBPS/content.opf" to opf(metadata = "", manifest = "", spine = ""),
                "OEBPS/Text/Cover.xhtml" to coverDoc(),
                "OEBPS/Text/chapter1.xhtml" to chapterDoc(),
            ),
        )
        val data = NovelTextExtractor.parseEpub(zip)
        zip.close()

        assertNull("无封面应返回 null", data.coverBytes)
        assertEquals(listOf("序章"), data.marks.map { it.title })
    }

    // ---------- 场景 5：图文混排 —— 段间插图按位置插入 ----------

    @Test
    fun inlineImageBetweenParagraphs() {
        val zip = zipOf(
            listOf(
                "META-INF/container.xml" to containerXml(),
                "OEBPS/content.opf" to opf(metadata = "", manifest = "", spine = ""),
                "OEBPS/Text/Cover.xhtml" to txt("""<html><head><title>Cover</title></head><body></body></html>"""),
                "OEBPS/Text/chapter1.xhtml" to txt(
                    """<html><head><title>序章</title></head><body>""" +
                        """<p>第一段文字。</p><img src="../Images/inline.jpg"/><p>第二段文字。</p>""" +
                        """</body></html>""",
                ),
                "OEBPS/Images/inline.jpg" to fakeJpeg,
            ),
        )
        val data = NovelTextExtractor.parseEpub(zip)
        zip.close()

        // 插图应在"第一段"与"第二段"之间插入（插入点 == 1），而不是整章丢失
        assertEquals(1, data.imageGroups.size)
        assertEquals("插图插入点应在首段之后", 1, data.imageGroups[0].insertAtIndex)
        assertTrue(data.imageGroups[0].images[0].contentEquals(fakeJpeg))
        assertTrue("首段仍完整提取", data.paragraphs.map { it.text }.contains("第一段文字。"))
        assertTrue("次段仍完整提取", data.paragraphs.map { it.text }.contains("第二段文字。"))
    }

    // ---------- 预览导出（本地真实样本；手动检查渲染效果用） ----------

    @Test
    fun exportSamplePagesPreview() {
        val f = java.io.File("D:/Download/1.epub")
        org.junit.Assume.assumeTrue("本地样本不存在，跳过预览导出", f.exists())
        val zip = ZipFile(f)
        val data = NovelTextExtractor.parseEpub(zip)
        zip.close()

        val context: android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()
        val metrics = android.util.DisplayMetrics().apply {
            widthPixels = 540
            heightPixels = 960
            density = 1f
            scaledDensity = 1f
            densityDpi = 160
        }
        val source = NovelPageSource(
            data.paragraphs, data.marks,
            NovelStyle(0xffcccccc.toInt(), 19f, 540, 960, chapterNewPage = true),
            metrics,
            data.coverBytes, data.imageGroups,
        )
        val outDir = java.io.File(System.getProperty("java.io.tmpdir"), "xy-epub-preview")
        outDir.mkdirs()
        val count = minOf(3, source.pageCount)
        for (p in 0 until count) {
            val bmp = kotlinx.coroutines.runBlocking { source.renderPage(p) }
                .asAndroidBitmap()
            java.io.File(outDir, "page$p.png").outputStream().use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        println("PREVIEW EXPORTED TO: ${outDir.absolutePath} pages=$count/${source.pageCount}")
    }

    // ---------- 真实样本（本地存在才跑） ----------

    @Test
    fun realSampleIfPresent() {
        val f = java.io.File("D:/Download/1.epub")
        org.junit.Assume.assumeTrue("本地样本 D:/Download/1.epub 不存在，跳过", f.exists())

        val zip = ZipFile(f)
        val data = NovelTextExtractor.parseEpub(zip)
        zip.close()

        println("REAL SAMPLE: cover=${data.coverBytes?.size} bytes; marks=${data.marks.map { it.title }}")
        println(
            "REAL SAMPLE IMAGES: groups=${data.imageGroups.map { it.images.size }} " +
                "insertAt=${data.imageGroups.map { it.insertAtIndex }}",
        )
        assertNotNull("真实样本应提取到封面", data.coverBytes)
        // JPEG 魔数
        assertEquals(0xFF.toByte(), data.coverBytes!![0])
        assertEquals(0xD8.toByte(), data.coverBytes!![1])
        // 整页图片（封面页 + 彩页 + 章内插画）：样本共 14 张
        val totalImages = data.imageGroups.sumOf { it.images.size }
        assertEquals("整页图应含全部 14 张（封面1+彩页5+章内8）", 14, totalImages)
        assertEquals("首组在段首（封面页）", 0, data.imageGroups.first().insertAtIndex)
        assertTrue("应有正文内插图（插入点 > 0）", data.imageGroups.any { it.insertAtIndex > 0 })
        // "封面"/"插图"空章应被跳过
        assertTrue(
            "不应再有空章节: ${data.marks.map { it.title }}",
            data.marks.none { it.title == "封面" || it.title == "插图" },
        )
        assertTrue("正文段落应存在", data.paragraphs.isNotEmpty())
    }
}

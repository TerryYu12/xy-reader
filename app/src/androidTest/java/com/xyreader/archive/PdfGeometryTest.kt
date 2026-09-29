package com.xyreader.archive

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import com.xyreader.core.BookEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfGeometryTest {
    @Test fun dimensionsAreAvailableBeforeRenderingForDifferentPageHeights() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "mixed.pdf")
        val document = PdfDocument()
        try {
            for ((i, height) in listOf(400, 800, 1200).withIndex()) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, height, i + 1).create())
                page.canvas.drawColor(Color.BLUE)
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        val book = BookEntity(title = "不同页高", uri = android.net.Uri.fromFile(file).toString(), format = "PDF", addedAt = 0)
        PdfPageSource.open(context, book).use { source ->
            assertEquals(3, source.pageCount)
            for ((i, ratio) in listOf(1.5f, 0.75f, 0.5f).withIndex()) {
                assertEquals(ratio, source.pageAspectRatio(i)!!, 0.0001f)
                val bitmap = source.renderPage(i)
                assertEquals(ratio, bitmap.width.toFloat() / bitmap.height, 0.0001f)
            }
        }
    }
}

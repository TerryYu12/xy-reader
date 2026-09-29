package com.xyreader.archive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.xyreader.core.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * PDF 页面源：android.graphics.pdf.PdfRenderer。
 *
 * PdfRenderer 是单线程 API，openPage/render/closePage 必须串行执行，
 * 用类内 [renderLock]（Mutex）把整页渲染流程保护起来。
 */
class PdfPageSource private constructor(
    private val renderer: PdfRenderer,
    private val pfd: ParcelFileDescriptor,
) : AbstractPageSource() {

    private val renderLock = Mutex()

    // PdfRenderer 构造成功后页数即固定
    override val cachedPageCount: Int get() = renderer.pageCount

    override suspend fun renderPage(index: Int): ImageBitmap {
        checkPage(index)
        return renderLock.withLock {
            withContext(Dispatchers.IO) {
                val page = renderer.openPage(index)
                try {
                    val bitmap =
                        Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    // PDF 透明区域渲染结果为透明像素，垫白色避免阅读时露黑底
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.asImageBitmap()
                } finally {
                    runCatching { page.close() }
                        .onFailure { Log.w(PAGE_SOURCE_TAG, "关闭 PDF 页异常", it) }
                }
            }
        }
    }

    /** PdfRenderer 可在解码位图前直接给出页面尺寸，避免连续列表先用占位高度再跳动。 */
    override suspend fun pageAspectRatio(index: Int): Float? {
        checkPage(index)
        return renderLock.withLock {
            withContext(Dispatchers.IO) {
                val page = renderer.openPage(index)
                try {
                    page.width.toFloat() / page.height.coerceAtLeast(1)
                } finally {
                    runCatching { page.close() }
                        .onFailure { Log.w(PAGE_SOURCE_TAG, "读取 PDF 页尺寸后关闭页面异常", it) }
                }
            }
        }
    }

    override fun close() = onFirstClose {
        // renderer.close() 会关闭它接管的 fd，pfd.close() 幂等兜底
        runCatching { renderer.close() }
            .onFailure { Log.w(PAGE_SOURCE_TAG, "关闭 PdfRenderer 异常", it) }
        runCatching { pfd.close() }
            .onFailure { Log.w(PAGE_SOURCE_TAG, "关闭 PDF 描述符异常", it) }
    }

    companion object {
        /** 打开 PDF：PdfRenderer 直接消费 ParcelFileDescriptor */
        fun open(context: Context, book: BookEntity): PdfPageSource {
            val pfd = PageSources.openPfd(context, book)
            try {
                val renderer = PdfRenderer(pfd)
                if (renderer.pageCount == 0) {
                    runCatching { renderer.close() }
                        .onFailure { Log.w(PAGE_SOURCE_TAG, "关闭空 PDF 异常", it) }
                    runCatching { pfd.close() }
                        .onFailure { Log.w(PAGE_SOURCE_TAG, "回收 pfd 异常", it) }
                    throw IllegalStateException("PDF 没有可渲染页面: ${book.uri}")
                }
                return PdfPageSource(renderer, pfd)
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                    .onFailure { Log.w(PAGE_SOURCE_TAG, "打开失败回收 pfd 异常", it) }
                throw t
            }
        }
    }
}

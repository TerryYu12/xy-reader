package com.xyreader.core

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.Closeable

/** 章节：标题 + 页区间（闭区间）。由包内子文件夹等结构推导，无章节结构的格式返回空列表 */
data class Chapter(
    val title: String,
    val startPage: Int,
    val endPageInclusive: Int,
)

/**
 * 一本书的页面数据源契约。所有格式（CBZ/CBR/CB7/CBT/PDF/EPUB/图片目录/MOBI）都实现此接口。
 *
 * 实现要求：
 * - [pageCount] 在打开后不可变；
 * - [renderPage] 支持随机访问任意页（0 起），实现内部自行保证线程安全
 *   （如 PdfRenderer 这类单线程库要在实现里加锁，并在 IO 线程执行阻塞工作）；
 * - [close] 之后不可再调用 renderPage；
 * - 页面图片按文件名"自然顺序"排列（第2页排在第10页前面），
 *   过滤掉 __MACOSX、. 开头的隐藏文件与非图片文件。
 */
interface PageSource : Closeable {
    val pageCount: Int

    /**
     * 章节列表（阅读器目录抽屉用）：压缩包按条目路径第一层子文件夹分组；
     * 根级直接放图片时归入"正文"。无章节结构的格式（PDF/MOBI/扁平包）返回空列表。
     * 必须按 startPage 升序排列。
     */
    val chapters: List<Chapter>
        get() = emptyList()

    /** 渲染第 index 页（0 起） */
    suspend fun renderPage(index: Int): ImageBitmap

    /** 页面宽高比（宽 / 高）；无预先元数据的格式可返回 null，由渲染后的位图推导。 */
    suspend fun pageAspectRatio(index: Int): Float? = null

    /** 封面默认取第一页；格式自带封面元数据时可覆盖 */
    suspend fun renderCover(): ImageBitmap = renderPage(0)

    override fun close()
}

/** 便捷：字节数组解码为 ImageBitmap，失败返回 null */
fun ByteArray.decodeToImageBitmap(): ImageBitmap? =
    BitmapFactory.decodeByteArray(this, 0, size)?.asImageBitmap()

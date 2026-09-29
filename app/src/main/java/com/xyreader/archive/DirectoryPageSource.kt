package com.xyreader.archive

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.documentfile.provider.DocumentFile
import com.xyreader.core.BookEntity
import com.xyreader.core.decodeToImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 图片目录页面源：SAF tree URI 下的直接图片文件。
 *
 * 页面列表在打开时固化为快照（满足 pageCount 不可变契约），
 * 按文件名自然排序；每次渲染独立 openInputStream，无共享底层资源。
 */
class DirectoryPageSource private constructor(
    private val context: Context,
    files: List<DocumentFile>,
) : AbstractPageSource() {

    private val files = files.toList()

    override val cachedPageCount: Int get() = files.size

    override suspend fun renderPage(index: Int): ImageBitmap {
        checkPage(index)
        return withContext(Dispatchers.IO) {
            val doc = files[index]
            val bytes = context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
                ?: throw IOException("无法打开页面文件: ${doc.uri}")
            bytes.decodeToImageBitmap()
                ?: throw IllegalStateException("第 ${index + 1} 页解码失败: ${doc.name}")
        }
    }

    override fun close() = onFirstClose {
        // 无底层长期资源（每次读取都是独立流），仅标记关闭
    }

    companion object {
        /** 打开图片目录：DocumentFile.fromTreeUri 列出目录内图片文件 */
        fun open(context: Context, book: BookEntity): DirectoryPageSource {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(book.uri))
                ?: throw IllegalArgumentException("无效的目录 URI: ${book.uri}")
            val files = tree.listFiles().filter { doc ->
                val name = doc.name
                !doc.isDirectory && name != null &&
                    !PageSources.isExcludedEntry(name) &&
                    PageSources.isImageName(name)
            }.sortedWith { a, b ->
                PageSources.naturalCompare(a.name ?: "", b.name ?: "")
            }
            if (files.isEmpty()) {
                throw IllegalStateException("目录中没有图片页面: ${book.uri}")
            }
            return DirectoryPageSource(context, files)
        }
    }
}

package com.xyreader.archive

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import com.xyreader.core.BookEntity
import com.xyreader.core.Chapter
import com.xyreader.core.PageSource
import com.xyreader.core.decodeToImageBitmap
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** archive 模块统一的日志 TAG */
const val PAGE_SOURCE_TAG = "ArkPageSource"

/**
 * archive 模块公共工具：URI 解析、条目过滤、自然排序、条目解码等。
 */
object PageSources {

    /** 认可的图片扩展名（不含点） */
    val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")

    /** 是否图片文件：按扩展名判断 */
    fun isImageName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.US) in IMAGE_EXTENSIONS

    /**
     * 是否应排除的条目路径：
     * - 含 __MACOSX 段（macOS 压缩垃圾，忽略大小写）；
     * - 任一路径段以 "." 开头（隐藏文件/目录，含 .DS_Store 等；
     *   "." 与 ".." 属于路径噪音，跳过不判）；
     * - 以 / 结尾的目录条目兜底。
     */
    fun isExcludedEntry(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        if (normalized.endsWith("/")) return true
        return normalized.split('/').any { seg ->
            seg.equals("__MACOSX", ignoreCase = true) ||
                (seg.startsWith(".") && seg != "." && seg != "..")
        }
    }

    /**
     * 自然排序比较："p2" < "p10"。
     * 数字段按数值比较，非数字字符按忽略大小写的字符序；纯前缀时短者在前。
     */
    fun naturalCompare(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        while (ia < a.length && ib < b.length) {
            val ca = a[ia]
            val cb = b[ib]
            if (ca.isDigit() && cb.isDigit()) {
                // 两侧都是数字：取完整数字段按数值比较
                var ea = ia
                while (ea < a.length && a[ea].isDigit()) ea++
                var eb = ib
                while (eb < b.length && b[eb].isDigit()) eb++
                val cmp = compareNumericTokens(a.substring(ia, ea), b.substring(ib, eb))
                if (cmp != 0) return cmp
                ia = ea
                ib = eb
            } else {
                // 非数字：逐字符比较（忽略大小写）
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la.compareTo(lb)
                ia++
                ib++
            }
        }
        // 一方先耗尽：剩余字符少者在前
        return (a.length - ia) - (b.length - ib)
    }

    /** 数字段数值比较：去前导零后先比长度，等长时字典序即数值序 */
    private fun compareNumericTokens(x: String, y: String): Int {
        val sx = x.trimStart('0')
        val sy = y.trimStart('0')
        return if (sx.length != sy.length) sx.length - sy.length else sx.compareTo(sy)
    }

    /**
     * 通用条目收集：排除目录/隐藏/__MACOSX，只留图片条目，按完整路径自然排序。
     * 适用于 zip / 7z / tar / rar 的条目列表（每本书只枚举一次，无需 inline）。
     */
    fun <T> collectImageEntries(
        entries: Sequence<T>,
        isDirectory: (T) -> Boolean,
        nameOf: (T) -> String,
    ): List<T> = entries
        .filter { !isDirectory(it) }
        .filter { !isExcludedEntry(nameOf(it)) }
        .filter { isImageName(nameOf(it)) }
        .sortedWith { x, y -> naturalCompare(nameOf(x), nameOf(y)) }
        .toList()

    /** 根级图片分组（与子文件夹混排时）的章节标题 */
    private const val ROOT_CHAPTER_TITLE = "正文"

    /**
     * 从已按自然序排好的页表构建章节：按条目路径第一层目录分组。
     *
     * - 路径先把 '\' 归一化为 '/'（RAR 常见 Windows 分隔符），再取第一个 '/' 前的
     *   第一段作为目录名；不含 '/'、或首段去首尾空白后为空（以 '/' 开头的绝对路径
     *   条目，罕见）都视为"根级"；
     * - 分组保持页序（LinkedHashMap 按首次出现顺序），startPage / endPageInclusive
     *   取组内最小 / 最大页序（连续页表里即组的第一个 / 最后一个出现序）；
     * - 根级组也是一章，标题用"正文"，按其页序位置插入（如包内既有根级图片又有
     *   子文件夹：章节 = [正文(根级区间), 第01话(...), ...]）；
     * - 只有存在至少一个子文件夹组才算有章节：扁平包（全根级）返回空列表，
     *   目录抽屉会兜底显示书签。章节数量不截断。
     */
    internal fun <T> buildChapters(entries: List<T>, nameOf: (T) -> String): List<Chapter> {
        /** 单个分组的页序聚合：key = 目录名，null 代表根级 */
        class Group(val key: String?, var start: Int, var end: Int)

        val groups = LinkedHashMap<String?, Group>()
        entries.forEachIndexed { index, entry ->
            val path = nameOf(entry).replace('\\', '/')
            val firstSeg = path.substringBefore('/', "").trim()
            val key = firstSeg.takeIf { it.isNotEmpty() }
            val existing = groups[key]
            if (existing == null) {
                groups[key] = Group(key, index, index)
            } else {
                if (index < existing.start) existing.start = index
                if (index > existing.end) existing.end = index
            }
        }
        // 没有任何子文件夹组 = 扁平包，无章节结构
        if (groups.keys.all { it == null }) return emptyList()
        return groups.map { (key, group) ->
            Chapter(
                title = key ?: ROOT_CHAPTER_TITLE,
                startPage = group.start,
                endPageInclusive = group.end,
            )
        }
    }

    /**
     * 统一打开只读文件描述符：
     * - content:// SAF 文档 → ContentResolver.openFileDescriptor；
     * - file:// 或以 / 开头的本地路径 → ParcelFileDescriptor.open。
     */
    fun openPfd(context: Context, book: BookEntity): ParcelFileDescriptor {
        val uri = book.uri
        return when {
            uri.startsWith("content://") ->
                context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
                    ?: throw IOException("无法打开 SAF 文档: $uri")

            uri.startsWith("file://") -> {
                val path = Uri.parse(uri).path
                    ?: throw IllegalArgumentException("无效的 file URI: $uri")
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            }

            uri.startsWith("/") ->
                ParcelFileDescriptor.open(File(uri), ParcelFileDescriptor.MODE_READ_ONLY)

            else -> throw IllegalArgumentException("无法识别的 URI 形态: $uri")
        }
    }

    /** file:// 或 / 开头时解析为本地文件；content:// 返回 null（由调用方决定后续策略） */
    fun resolveLocalFile(book: BookEntity): File? = when {
        book.uri.startsWith("file://") -> Uri.parse(book.uri).path?.let(::File)
        book.uri.startsWith("/") -> File(book.uri)
        else -> null
    }

    /**
     * 为 RAR 准备本地文件：content URI 整包复制到 cacheDir/rar_cache/{uriHash}.{ext}。
     * 已存在（且非空）则复用；先写 .part 再 rename，保证不会留下半截的正式缓存文件。
     */
    fun ensureRarCacheFile(context: Context, book: BookEntity): File {
        val dir = File(context.cacheDir, "rar_cache").apply { mkdirs() }
        val ext = (Uri.parse(book.uri).lastPathSegment ?: "")
            .substringAfterLast('.', "").lowercase(Locale.US)
            .takeIf { it == "rar" || it == "cbr" } ?: "rar"
        val target = File(dir, "${book.uri.hashCode()}.$ext")
        if (target.exists() && target.length() > 0) return target

        val tmp = File(dir, "${target.name}.part")
        context.contentResolver.openInputStream(Uri.parse(book.uri))?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("无法打开 RAR 文档: ${book.uri}")

        if (!tmp.renameTo(target)) {
            // rename 失败（极端并发下目标可能已被创建）：目标有效则用目标，否则直接用 tmp
            if (target.exists() && target.length() > 0) {
                tmp.delete()
            } else {
                tmp.renameTo(target)
            }
        }
        return target
    }

    /** 打开条目流 → 读全字节 → 解码为 ImageBitmap；失败抛出带页码信息的异常 */
    fun decodeEntryStream(index: Int, open: () -> InputStream): ImageBitmap =
        open().use { it.readBytes() }
            .decodeToImageBitmap()
            ?: throw IllegalStateException("第 ${index + 1} 页无法解码为图片")
}

/**
 * PageSource 实现的公共骨架：
 * - 页数在打开时固化（[cachedPageCount]），[pageCount] 不可变；
 * - [checkPage] 统一做关闭状态与页码校验；
 * - [onFirstClose] 保证 close() 幂等。
 */
abstract class AbstractPageSource : PageSource {

    /** 打开时固化的总页数 */
    protected abstract val cachedPageCount: Int

    private val closed = AtomicBoolean(false)

    final override val pageCount: Int
        get() = cachedPageCount

    /** 渲染前校验：未关闭且页码在范围内 */
    protected fun checkPage(index: Int) {
        check(!closed.get()) { "页面源已关闭" }
        if (index < 0 || index >= cachedPageCount) {
            throw IndexOutOfBoundsException("页码越界: $index / $cachedPageCount")
        }
    }

    /** 仅首次 close 执行动作，保证幂等 */
    protected fun onFirstClose(action: () -> Unit) {
        if (closed.compareAndSet(false, true)) action()
    }
}

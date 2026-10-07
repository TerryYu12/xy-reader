package com.xyreader.archive

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import com.xyreader.core.BookEntity
import com.xyreader.core.PageSource
import com.xyreader.core.decodeToImageBitmap
import com.xyreader.data.AppGraph
import com.xyreader.data.GoogleDriveClient
import com.xyreader.feedback.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * MOBI 文件内的一条图片记录（即一页）。
 *
 * @property recordIndex PDB 记录序号；页序 = 记录序号升序
 * @property offset 记录数据在文件内的起始偏移
 * @property length 记录字节长度（下一条记录 offset - 本条 offset；最后一条到文件尾）
 */
private class MobiImageRecord(
    val recordIndex: Int,
    val offset: Long,
    val length: Int,
)

/**
 * MOBI/AZW3（Kindle 漫画）页面源：容器级解析，只抽 PDB 记录流里的图片记录，
 * 不渲染 MOBI 的 HTML/CSS——对漫画场景完全够用，且天然兼容 HTTP Range 流式随机读
 * （记录按偏移存放，可随机 seek 抽取单页）。
 *
 * == PDB/MOBI 字段偏移自查对照表（全部大端 BE） ==
 *
 * PDB header（文件起始）：
 * - 76..78      numRecords（2B）：记录总数
 * - 78..78+8n   记录偏移表，每条 8B：offset(4B) + attributes(1B) + uniqueID(3B)
 * - 偏移表第一条的 offset 即 record0 在文件内的位置（PDB 头之后）
 *
 * record0（PalmDOC header 从 record0+0 起）：
 * - +0..+2      compression：1=无压缩 / 2=PalmDOC 压缩 / 17480=HUFF+CDIC（Kindle DRM）→ 拒绝
 * - +12..+14    encryption type：0=无加密；非 0 = DRM → 拒绝
 * - +16..+20    魔数 "MOBI"（0x4D4F4249）；缺失 = 纯 PalmDOC 文本（不是漫画）→ 拒绝
 *
 * MOBI header（坐标以魔数后偏移给出，括号内为等价的 record0 坐标，
 * 两者换算：record0 坐标 = 魔数后坐标 + 16）：
 * - +4  (record0+20)   headerLength（4B）：整个 MOBI header 长度（含魔数），典型 232/248
 * - +92 (record0+108)  firstImageIndex（4B）：第一个资源（图片）记录号；
 *   0xFFFFFFFF 或越界或字段缺失 = 无图像 → 兜底从记录 0 全扫
 *
 * 图片记录：从 firstImageIndex 起连续存放（AZW3/KF8 容器结构相同，
 * firstResourceRecord 同一字段位置），按记录开头 4 字节的文件魔数识别：
 * - JPEG：FF D8 FF
 * - PNG：89 50 4E 47
 * - GIF："GIF8"（47 49 46 38）
 * 非图片记录（FLIS/FCIS/RESC/SRCM/CMET 等 ASCII 魔数或 KF8 辅助记录）
 * 跳过但不终止收集（图像区可能有间隙）；收集到的记录按序号升序即页序。
 *
 * 线程安全：channel 的 position 是共享状态，renderPage 用 [Mutex] 串行化，
 * 阻塞读与解码切到 Dispatchers.IO。channel 生命周期由本类 close 统一关闭
 * （本地 FileChannel 与远程 HttpRangeChannel 的 close 均幂等）。
 */
class MobiPageSource private constructor(
    /** 底层随机读通道：本地 FileChannel 或远程 HttpRangeChannel */
    private val channel: SeekableByteChannel,
    imageRecords: List<MobiImageRecord>,
) : AbstractPageSource() {

    /** 页表：打开时固化的图片记录列表（记录序号升序即页序），pageCount 不可变 */
    private val records = imageRecords.toList()

    /** 串行化 channel 的 seek+read（position 状态共享） */
    private val ioLock = Mutex()

    override val cachedPageCount: Int get() = records.size

    override suspend fun renderPage(index: Int): ImageBitmap {
        checkPage(index)
        // 记录可达数 MB，与 zip 条目同内存量级：一次读完整条记录再解码
        return ioLock.withLock {
            withContext(Dispatchers.IO) {
                val record = records[index]
                val dst = ByteBuffer.allocate(record.length)
                channel.position(record.offset)
                readFully(channel, dst)
                dst.array().decodeToImageBitmap()
                    ?: throw IllegalStateException("第 ${index + 1} 页无法解码为图片")
            }
        }
    }

    override fun close() = onFirstClose {
        // 通道由本类管理生命周期（构造传入：本地 FileChannel / 远程 HttpRangeChannel）
        runCatching { channel.close() }
            .onFailure { AppLog.w(PAGE_SOURCE_TAG, "关闭 MOBI 通道异常", it) }
    }

    companion object {

        /** PDB 头固定部分长度（记录数字段 76..78 结束） */
        private const val PDB_HEADER_SIZE = 78

        /** 记录偏移表条目长度：offset(4B) + attributes(1B) + uniqueID(3B) */
        private const val PDB_ENTRY_SIZE = 8

        /** MOBI 魔数在 record0 内的偏移（PalmDOC header 长 16 字节） */
        private const val MOBI_MAGIC_AT = 16

        /** "MOBI" 的 32 位大端魔数值 */
        private const val MOBI_MAGIC = 0x4D4F4249L

        /** firstImageIndex 字段在 record0 内的偏移（= MOBI 魔数后 +92 字节） */
        private const val FIRST_IMAGE_INDEX_AT = 108

        /** PalmDOC compression = HUFF/CDIC（17480）：Amazon DRM 专用 */
        private const val COMPRESSION_HUFF_CDIC = 17480

        /** firstImageIndex 的"无图像"标记值 */
        private const val NO_IMAGE_INDEX = 0xFFFFFFFFL

        /** record0 解析需要读到的字段末尾（firstImageIndex 占 108..112） */
        private const val RECORD0_REQUIRED = FIRST_IMAGE_INDEX_AT + 4

        /** record0 读取上限：覆盖全部所需字段并留余量 */
        private const val RECORD0_READ_SIZE = 256

        // ---------- 打开入口（三来源统一构造 SeekableByteChannel） ----------

        /**
         * 打开本地 MOBI/AZW3（book.uri = file:// 或 / 开头路径）。
         * webdav:// 与 gdrive:// 由 ArchiveFactory 路由到下面的远程工厂。
         */
        fun open(context: Context, book: BookEntity): MobiPageSource {
            val file = PageSources.resolveLocalFile(book)
                ?: throw IllegalArgumentException("无法识别的本地文件 URI: ${book.uri}")
            val channel = FileInputStream(file).channel
            return open(channel)
        }

        /** 打开 WebDAV 远程 MOBI/AZW3：HttpRangeChannel 走 HTTP Range 随机读，不下载整包 */
        fun openRemoteWebDav(
            book: BookEntity,
            url: String,
            username: String?,
            password: String?,
        ): PageSource {
            val channel = HttpRangeChannel(url, username, password)
            return open(channel)
        }

        /**
         * 打开 Google Drive 远程 MOBI/AZW3：动态 Bearer 鉴权（OAuth token 会过期）。
         * Drive v3 alt=media 下载端点原生支持 Range（206 + Content-Range），
         * [HttpRangeChannel.size] 的 0-0 探测直接可用。
         */
        fun openRemoteGdrive(
            context: Context,
            book: BookEntity,
            fileId: String,
            accountId: Long,
        ): PageSource {
            val channel = HttpRangeChannel(
                GoogleDriveClient.downloadUrl(fileId), null, null,
                authHeaderProvider = bearerAuthHeaderProvider(context, accountId),
            )
            return open(channel)
        }

        /**
         * 统一打开：解析页表 → 构造页面源；任何失败回收通道（close 幂等）后原样抛出。
         * 本地 FileChannel 与远程 HttpRangeChannel 走同一入口（SeekableByteChannel），
         * 与 [RemoteArchiveSources] 各打开函数的 try-catch 回收模式一致。
         */
        fun open(channel: SeekableByteChannel): MobiPageSource {
            try {
                val pages = parsePageTable(channel)
                if (pages.isEmpty()) {
                    throw IllegalStateException("MOBI 内未找到图片记录")
                }
                return MobiPageSource(channel, pages)
            } catch (t: Throwable) {
                // 打开中途失败：回收通道，绝不泄漏连接/文件句柄
                runCatching { channel.close() }
                    .onFailure { AppLog.w(PAGE_SOURCE_TAG, "回收 MOBI 通道异常", it) }
                throw t
            }
        }

        /**
         * 构造动态 Bearer 鉴权头提供者（参照 RemoteGdriveSources 的既有模式）：
         * 每次 HTTP 请求时取当前有效 access token；refreshGdriveAccessToken 内部
         * 有缓存与互斥，token 未过期时几乎零开销。调用点全部在网络线程
         * （Dispatchers.IO / OkHttp 工作线程），runBlocking 桥接安全；
         * clientSecret / refreshToken / token 只进请求头，绝不落日志。
         */
        private fun bearerAuthHeaderProvider(context: Context, accountId: Long): () -> String? = {
            val token = runBlocking {
                AppGraph.libraryRepository(context).refreshGdriveAccessToken(accountId)
            }
            "Bearer $token"
        }

        // ---------- 页表解析 ----------

        /**
         * 解析 PDB/MOBI 结构，收集全部图片记录（即页表）。
         * 返回空列表表示没有图片（调用方抛"MOBI 内未找到图片记录"）。
         */
        private fun parsePageTable(channel: SeekableByteChannel): List<MobiImageRecord> {
            val fileSize = channel.size()

            // 1. PDB 头：拿记录数（76..78）
            channel.position(0)
            val head = readUpTo(channel, PDB_HEADER_SIZE)
            if (head.size < PDB_HEADER_SIZE) throw IOException("文件过小或损坏")
            val numRecords = readUShortBE(head, 76)
            if (numRecords == 0) throw IOException("文件过小或损坏")

            // 2. 记录偏移表：78 + 8*n 字节必须完整落盘（大文件记录数上千，按需读够）
            val tableEnd = PDB_HEADER_SIZE + PDB_ENTRY_SIZE * numRecords
            if (tableEnd > fileSize) throw IOException("文件过小或损坏")
            channel.position(PDB_HEADER_SIZE.toLong())
            val table = readUpTo(channel, PDB_ENTRY_SIZE * numRecords)
            if (table.size < tableEnd - PDB_HEADER_SIZE) throw IOException("文件过小或损坏")
            val offsets = LongArray(numRecords)
            for (i in 0 until numRecords) {
                offsets[i] = readUIntBE(table, i * PDB_ENTRY_SIZE)
            }

            // 3. record0：解析 MOBI 头（压缩/加密/魔数/首图索引）
            val rec0Offset = offsets[0]
            if (rec0Offset < tableEnd || rec0Offset >= fileSize) {
                // record0 必须在偏移表之后、文件之内，否则表已损坏
                throw IOException("文件过小或损坏")
            }
            val rec0Length = (if (numRecords > 1) offsets[1] else fileSize) - rec0Offset
            if (rec0Length <= 0) throw IOException("文件过小或损坏")
            channel.position(rec0Offset)
            val rec0 = readUpTo(channel, minOf(RECORD0_READ_SIZE.toLong(), rec0Length).toInt())
            if (rec0.size < MOBI_MAGIC_AT + 4) throw IOException("不是有效的 MOBI/AZW3 文件")
            if (readUIntBE(rec0, MOBI_MAGIC_AT) != MOBI_MAGIC) {
                // 无 MOBI 魔数 = 纯 PalmDOC 文本（不是漫画）
                throw IOException("不是有效的 MOBI/AZW3 文件")
            }
            // DRM 检测：HUFF/CDIC 压缩（Amazon DRM 专用）或加密类型非 0 → 直接拒绝，不做绕过
            if (readUShortBE(rec0, 0) == COMPRESSION_HUFF_CDIC || readUShortBE(rec0, 12) != 0) {
                throw IOException("该文件有 Kindle DRM 保护，无法读取")
            }

            // 4. 首图索引：MOBI header 需足够长（headerLength 覆盖到该字段）且 record0
            //    读到了该字段才可信；无效（0xFFFFFFFF/越界/字段缺失）→ 兜底从记录 0 全扫
            val headerLength = if (rec0.size >= 24) readUIntBE(rec0, 20) else 0L
            var startFrom = 0
            if (rec0.size >= RECORD0_REQUIRED && headerLength >= 96L) {
                val first = readUIntBE(rec0, FIRST_IMAGE_INDEX_AT)
                if (first != NO_IMAGE_INDEX && first < numRecords) {
                    startFrom = first.toInt()
                }
            }

            // 5. 遍历记录判图片魔数：命中记下（序号, 偏移, 长度）；非图片跳过但不终止
            val pages = mutableListOf<MobiImageRecord>()
            val probe = ByteBuffer.allocate(4)
            for (i in startFrom until numRecords) {
                val offset = offsets[i]
                if (offset >= fileSize) break // 偏移越界：表已损坏到尾部，停止收集
                val nextOffset = if (i + 1 < numRecords) offsets[i + 1] else fileSize
                val length = nextOffset - offset
                if (length <= 0L || length > Int.MAX_VALUE.toLong()) continue // 乱序/异常记录跳过
                probe.clear()
                channel.position(offset)
                var got = 0
                while (probe.hasRemaining()) {
                    val n = channel.read(probe)
                    if (n <= 0) break
                    got += n
                }
                if (got == 0) break // 已到文件尾
                if (isImageMagic(probe.array(), got)) {
                    pages += MobiImageRecord(i, offset, length.toInt())
                }
            }
            return pages
        }

        // ---------- 小工具 ----------

        /** 记录开头是否已知图片魔数：JPEG(FF D8 FF) / PNG(89 50 4E 47) / GIF("GIF8") */
        private fun isImageMagic(bytes: ByteArray, size: Int): Boolean =
            (
                size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
                    bytes[2] == 0xFF.toByte()
                ) ||
                (
                    size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
                    ) ||
                (
                    size >= 4 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
                        bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()
                    )

        /** 大端读 4 字节为无符号长整型（避免 Int 符号位坑：0xFFFFFFFF 需与 Long 比较） */
        private fun readUIntBE(bytes: ByteArray, offset: Int): Long =
            ((bytes[offset].toLong() and 0xFF) shl 24) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
                (bytes[offset + 3].toLong() and 0xFF)

        /** 大端读 2 字节为无符号整型 */
        private fun readUShortBE(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

        /** 从通道当前 position 读至多 max 字节，返回实际读到的数组（提前 EOF 截断） */
        private fun readUpTo(channel: SeekableByteChannel, max: Int): ByteArray {
            val dst = ByteBuffer.allocate(max)
            while (dst.hasRemaining()) {
                if (channel.read(dst) <= 0) break
            }
            return if (dst.hasRemaining()) dst.array().copyOf(dst.position()) else dst.array()
        }

        /** 从通道当前 position 读满 dst；提前 EOF（文件截断/网络中断）抛 IOException */
        private fun readFully(channel: SeekableByteChannel, dst: ByteBuffer) {
            while (dst.hasRemaining()) {
                val n = channel.read(dst)
                if (n < 0) throw IOException("读取记录数据失败：文件被截断或已损坏")
                if (n == 0) throw IOException("读取记录数据失败：通道未返回数据")
            }
        }
    }
}

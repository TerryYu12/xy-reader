package com.xyreader.archive

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

/**
 * WebDAV 远程文件的只读随机访问通道：把 HTTP Range 请求伪装成 [SeekableByteChannel]，
 * 供 commons-compress 的 ZipFile / SevenZFile / TarFile 直接消费，
 * 实现"HTTP Range 随机读"的真流式打开（不下载整包）。
 *
 * 读取策略：按 256KB 块对齐取覆盖 [position, position+len) 的数据（通常 1-2 个 Range GET），
 * LRU 缓存最近 16 块（4MB）——重复读中央目录 / 相邻页命中缓存不发请求。
 * 所有 HTTP 异常统一包装为 [IOException]（commons-compress 期望 IOException）。
 * 只读实现：write/truncate 一律抛 [NonWritableChannelException]。
 */
class HttpRangeChannel(
    /** 完整请求 URL（WebDAV 文件的最终地址，路径段已 URL 编码） */
    private val url: String,
    /** Basic 认证用户名；null/空 视为匿名访问 */
    username: String?,
    /** Basic 认证密码（只进请求头，绝不落日志） */
    password: String?,
    /** HTTP 客户端；默认取进程级共享单例（复用连接池） */
    client: OkHttpClient = defaultHttpClient(),
    /**
     * 动态鉴权头提供者：每次请求时调用，用于会过期的凭据（如 Google Drive OAuth Bearer token）。
     * 提供此参数时忽略 username/password。调用发生在网络线程，提供者内部可阻塞（如同步刷新 token）。
     */
    private val authHeaderProvider: (() -> String?)? = null,
) : SeekableByteChannel {

    companion object {
        /** Range 请求与块缓存的对齐粒度：256KB */
        private const val BLOCK_SIZE = 256L * 1024L

        /** LRU 块缓存容量：16 块 = 4MB */
        private const val MAX_CACHED_BLOCKS = 16

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val READ_TIMEOUT_MS = 60_000L

        @Volatile
        private var sharedClient: OkHttpClient? = null

        /** 进程级共享 OkHttpClient：15s 连接 / 60s 读超时，全局复用连接池 */
        fun defaultHttpClient(): OkHttpClient =
            sharedClient ?: synchronized(this) {
                sharedClient ?: OkHttpClient.Builder()
                    .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build()
                    .also { sharedClient = it }
            }
    }

    private val httpClient: OkHttpClient = client

    /** Basic 认证头（静态）；提供动态 provider 时恒为 null */
    private val basicAuthHeader: String? =
        if (authHeaderProvider != null || username.isNullOrEmpty()) null
        else Credentials.basic(username, password ?: "")

    /** 每次请求时解析实际鉴权头：动态 provider 优先（如 OAuth Bearer），否则用静态 Basic 头 */
    private fun currentAuthHeader(): String? = authHeaderProvider?.invoke() ?: basicAuthHeader

    /** 读位置；与块缓存一起由 synchronized(this) 保护 */
    private var positionValue: Long = 0L

    /** 关闭标志；volatile 保证跨线程可见，close 幂等 */
    @Volatile
    private var closed = false

    /** 惰性获取并缓存的文件总长；-1 表示尚未获取 */
    private var cachedSize: Long = -1L

    /**
     * LRU 块缓存：key = 块序号，value = 块数据。
     * 最后一块可短于 [BLOCK_SIZE]；空数组表示"块起点越过文件尾"的 EOF 块（也缓存，避免反复 416）。
     */
    private val blockCache = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>): Boolean =
            size > MAX_CACHED_BLOCKS
    }

    /** 一次 Range 取回的数据；cacheable=false 表示来自不支持 Range 的全量响应，不得写入块缓存 */
    private class RangeFetch(val data: ByteArray, val cacheable: Boolean)

    // ---- SeekableByteChannel：读路径 ----

    override fun read(dst: ByteBuffer): Int {
        synchronized(this) {
            ensureOpen()
            if (!dst.hasRemaining()) return 0
            var total = 0
            // 逐块填充：请求区间 [position, position+len) 通常落在 1-2 个 256KB 块内
            while (dst.hasRemaining()) {
                val blockIndex = positionValue / BLOCK_SIZE
                val offsetInBlock = (positionValue % BLOCK_SIZE).toInt()
                val block = fetchBlock(blockIndex)
                val available = block.size - offsetInBlock
                if (available <= 0) {
                    // 块起点已越过文件尾（EOF）：已读到部分数据则返回已读量，否则按流末尾返回 -1
                    if (total == 0) return -1
                    break
                }
                val n = minOf(available, dst.remaining())
                dst.put(block, offsetInBlock, n)
                positionValue += n
                total += n
            }
            return total
        }
    }

    /**
     * 文件总长：惰性获取——发 "Range: bytes=0-0" 读 206 响应的 Content-Range 总长并缓存；
     * 服务器忽略 Range 返回 200 时退化为 Content-Length。
     */
    override fun size(): Long {
        synchronized(this) {
            ensureOpen()
            if (cachedSize >= 0) return cachedSize
            // HEAD 在部分 WebDAV 服务器上不可用且无 Content-Range，用 0-0 探测最稳
            val response = execute("bytes=0-0")
            response.use { resp ->
                when (resp.code) {
                    206 -> {
                        val total = parseTotalLength(resp.header("Content-Range"))
                            ?: throw IOException("HTTP 206 响应缺少有效的 Content-Range，无法确定文件大小")
                        cachedSize = total
                    }
                    200 -> {
                        val len = resp.header("Content-Length")?.toLongOrNull()
                            ?: throw IOException("HTTP 200 响应缺少 Content-Length，无法确定文件大小")
                        cachedSize = len
                    }
                    401 -> throw httpError(401, "获取文件大小失败")
                    else -> throw httpError(resp.code, "获取文件大小失败")
                }
            }
            return cachedSize
        }
    }

    override fun position(): Long {
        synchronized(this) {
            ensureOpen()
            return positionValue
        }
    }

    override fun position(newPosition: Long): SeekableByteChannel {
        synchronized(this) {
            ensureOpen()
            if (newPosition < 0) {
                throw IllegalArgumentException("偏移不能为负: $newPosition")
            }
            // 越界（超过文件总长）抛 IllegalArgumentException；size() 为 synchronized 可重入调用
            if (newPosition > size()) {
                throw IllegalArgumentException("偏移越界: $newPosition > 文件大小 $cachedSize")
            }
            positionValue = newPosition
            return this
        }
    }

    override fun isOpen(): Boolean = !closed

    // ---- SeekableByteChannel：只读约定 ----

    /** 只读通道：不支持写入 */
    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()

    /** 只读通道：不支持截断 */
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()

    /** 幂等关闭：置位关闭标志并清空块缓存（底层 HTTP 连接由 OkHttp 连接池管理，无需额外释放） */
    override fun close() {
        synchronized(this) {
            closed = true
            blockCache.clear()
        }
    }

    // ---- 内部实现 ----

    private fun ensureOpen() {
        if (closed) throw ClosedChannelException()
    }

    /** 构造含 HTTP 状态码的 IOException；401/403 附加认证失败提示 */
    private fun httpError(code: Int, detail: String): IOException =
        if (code == 401 || code == 403) {
            IOException("HTTP $code: $detail（认证失败，请检查 WebDAV 用户名/应用密码）")
        } else {
            IOException("HTTP $code: $detail")
        }

    /** 组装带 Range 与鉴权头的请求并发送；网络层异常统一包装为 IOException */
    private fun execute(rangeSpec: String?): Response {
        val builder = Request.Builder().url(url)
        if (rangeSpec != null) builder.header("Range", rangeSpec)
        currentAuthHeader()?.let { builder.header("Authorization", it) }
        return try {
            httpClient.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw IOException("WebDAV 请求失败（网络错误）: ${e.message}", e)
        }
    }

    /**
     * 取块数据：先查 LRU，未命中发 Range GET（块起点越过已知文件尾时直接给 EOF 空块）。
     * 调用方必须持有 synchronized(this)。
     */
    private fun fetchBlock(blockIndex: Long): ByteArray {
        blockCache[blockIndex]?.let { return it }
        val start = blockIndex * BLOCK_SIZE
        val known = cachedSize
        if (known >= 0 && start >= known) {
            // 起点越过文件尾：EOF 空块也进缓存，避免重复 416
            return ByteArray(0).also { blockCache[blockIndex] = it }
        }
        // 请求终点：已知总长则钳制到文件尾，服务器对越界终点会自行截断
        val endInclusive =
            if (known >= 0) minOf(start + BLOCK_SIZE, known) - 1 else start + BLOCK_SIZE - 1
        val fetched = httpGetRange(start, endInclusive)
        if (fetched.cacheable) blockCache[blockIndex] = fetched.data
        return fetched.data
    }

    /** 发一次 Range GET 并取出块数据；服务器忽略 Range（200 全量）时流式跳读兜底（不写缓存，避免整包进内存） */
    private fun httpGetRange(start: Long, endInclusive: Long): RangeFetch {
        val response = execute("bytes=$start-$endInclusive")
        response.use { resp ->
            val body = resp.body ?: throw httpError(resp.code, "响应无内容")
            // use 是 inline 函数，这里显式 return（非局部返回）以保证所有路径都有返回值
            return when (resp.code) {
                206 -> RangeFetch(body.byteStream().use { it.readBytes() }, cacheable = true)
                200 -> {
                    // 服务器不支持 Range：响应体是整个文件。流式跳到 start 取所需长度即返回
                    val need = (endInclusive - start + 1).toInt()
                    val data = body.byteStream().use { input ->
                        input.skipExactly(start)
                        input.readUpTo(need)
                    }
                    RangeFetch(data, cacheable = false)
                }
                401 -> throw httpError(401, "Range 读取失败（$start-$endInclusive）")
                else -> throw httpError(resp.code, "Range 读取失败（$start-$endInclusive）")
            }
        }
        // 不可达兜底：上面 use 内必然 return 或 throw
        throw IOException("HTTP Range 响应处理异常")
    }

    /** Content-Range 总长解析："bytes 0-0/12345" 或 "bytes 星号/12345" → 12345；无 "/" 或星号视为未知 */
    private fun parseTotalLength(contentRange: String?): Long? {
        val total = contentRange?.trim()?.substringAfterLast('/', "")?.trim() ?: return null
        if (total.isEmpty() || total == "*") return null
        return total.toLongOrNull()
    }

    /** 精确跳过 n 字节（InputStream.skip 不保证一次跳满，返回 0 时改用真实读取消耗） */
    private fun InputStream.skipExactly(n: Long) {
        var remaining = n
        val sink = ByteArray(64 * 1024)
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val r = read(sink, 0, minOf(sink.size.toLong(), remaining).toInt())
                if (r < 0) return
                remaining -= r
            }
        }
    }

    /** 读至多 max 字节（提前 EOF 则返回实际读到的长度） */
    private fun InputStream.readUpTo(max: Int): ByteArray {
        val out = ByteArray(max)
        var off = 0
        while (off < max) {
            val r = read(out, off, max - off)
            if (r < 0) break
            off += r
        }
        return if (off == max) out else out.copyOf(off)
    }
}

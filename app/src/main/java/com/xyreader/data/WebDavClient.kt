package com.xyreader.data

import android.net.Uri
import android.util.Xml
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * 仅解码路径里的 %XX 百分号转义。
 * 不用 java.net.URLDecoder：它会把 '+' 解成空格，而路径里的 '+' 是字面量。
 */
internal fun percentDecodePath(raw: String): String {
    if ('%' !in raw) return raw
    val out = ByteArrayOutputStream(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '%' && i + 2 < raw.length) {
            val hi = raw[i + 1].digitToIntOrNull(16)
            val lo = raw[i + 2].digitToIntOrNull(16)
            if (hi != null && lo != null) {
                out.write((hi shl 4) or lo)
                i += 3
                continue
            }
        }
        out.write(c.code)
        i++
    }
    return out.toString("UTF-8")
}

/** PROPFIND 解析出的单个目录条目（[href] 为解码后的服务器绝对路径，以 / 开头） */
data class WebDavEntry(
    val displayName: String,
    val isDir: Boolean,
    val size: Long,
    val href: String,
)

/**
 * WebDAV 客户端：PROPFIND 列目录 / Range 流式读 / 整包下载 / 连通性测试。
 * OkHttp 为同步调用，调用方须在 IO 线程使用。
 * 认证走 Authorization 头（Basic），凭据不出现在 URL 里。
 */
class WebDavClient {

    private companion object {
        /** 进程内共享客户端，复用连接池 */
        val CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /** PROPFIND 请求体：仅声明检索动作，服务器默认返回全部属性即可 */
        const val PROPFIND_BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:propfind xmlns:D=\"DAV:\"/>"
    }

    /** 连通性测试（PROPFIND Depth:0）。返回 null 表示成功，否则为用户可读的错误描述 */
    fun test(url: String, user: String, pass: String): String? {
        // baseUrl 末尾无 / 则补
        val normalized = url.trim().let { if (it.endsWith("/")) it else "$it/" }
        return try {
            CLIENT.newCall(propfindRequest(normalized, user, pass, depth = 0)).execute().use { resp ->
                when {
                    resp.isSuccessful -> null
                    resp.code == 401 -> "认证失败：检查用户名与密码（坚果云需使用应用密码）"
                    resp.code == 403 -> "无权限访问"
                    resp.code == 404 -> "路径不存在"
                    resp.code in 500..599 -> "服务器错误 ${resp.code}"
                    else -> "HTTP ${resp.code}"
                }
            }
        } catch (e: IOException) {
            "网络错误：${e.message}"
        } catch (e: IllegalArgumentException) {
            // OkHttp 对非法 URL 抛 IllegalArgumentException
            "地址无效：${e.message}"
        }
    }

    /** 列目录：PROPFIND Depth:1，解析 207 multistatus。失败抛 IOException，由调用方决定是否继续 */
    fun listDir(url: String, user: String, pass: String): List<WebDavEntry> {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IOException("无效的 URL: $url")
        CLIENT.newCall(propfindRequest(url, user, pass, depth = 1)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("PROPFIND 失败 HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("PROPFIND 无响应体")
            return parseMultistatus(body, httpUrl)
        }
    }

    /** 读 [start, end] 闭区间字节。206 直接返回；200 视为全量返回后自行截断；其余抛 IOException */
    fun getRange(url: String, user: String, pass: String, start: Long, end: Long): ByteArray {
        val request = baseRequest(url, user, pass)
            .header("Range", "bytes=$start-$end")
            .build()
        CLIENT.newCall(request).execute().use { resp ->
            val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
            // use 是 inline 函数，显式 return 保证所有路径都有返回值
            return when {
                resp.code == 206 -> bodyBytes
                resp.code == 200 -> {
                    // 服务器不支持 Range 时返回全量内容，自行截断
                    val from = start.coerceIn(0, bodyBytes.size.toLong()).toInt()
                    val toExclusive = (end + 1).coerceIn(0, bodyBytes.size.toLong()).toInt()
                    if (from >= toExclusive) ByteArray(0) else bodyBytes.copyOfRange(from, toExclusive)
                }
                else -> throw IOException("Range 请求失败 HTTP ${resp.code}")
            }
        }
        // 不可达兜底：上面 use 内必然 return 或 throw
        throw IOException("HTTP Range 响应处理异常")
    }

    /** 流式下载到 [dest]：先写 dest.part 再改名成 dest；目标已存在直接返回 */
    fun downloadToFile(url: String, user: String, pass: String, dest: File) {
        if (dest.exists()) return
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, "${dest.name}.part")
        if (part.exists()) part.delete()
        try {
            CLIENT.newCall(baseRequest(url, user, pass).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("下载失败 HTTP ${resp.code}")
                val input = resp.body?.byteStream() ?: throw IOException("下载无响应体")
                part.outputStream().use { out ->
                    input.copyTo(out)
                    out.flush()
                }
            }
            if (!part.renameTo(dest)) {
                // 极端情况（跨分区等）兜底：复制后再删临时文件
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
        } catch (e: Throwable) {
            // 下载中断不留半截 .part 文件
            part.delete()
            throw e
        }
    }

    // ---------- 请求构造 ----------

    /** 带 Basic 认证的请求骨架（凭据按 UTF-8 编码，兼容非 ASCII 用户名） */
    private fun baseRequest(url: String, user: String, pass: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(user, pass, Charsets.UTF_8))

    /** PROPFIND 请求：OkHttp 要求非 GET 方法必须带 body */
    private fun propfindRequest(url: String, user: String, pass: String, depth: Int): Request =
        baseRequest(url, user, pass)
            .method(
                "PROPFIND",
                PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()),
            )
            .header("Depth", depth.toString())
            .build()

    // ---------- multistatus 解析 ----------

    /**
     * 解析 207 multistatus：用 XmlPullParser 逐事件按 localName 匹配（忽略命名空间前缀），
     * 在 <response> 内取 href / displayname / getcontentlength / getlastmodified，
     * resourcetype 内出现 collection 即目录。跳过自身（href 与请求路径相同）。
     */
    private fun parseMultistatus(body: ResponseBody, requestUrl: HttpUrl): List<WebDavEntry> {
        val selfPath = percentDecodePath(requestUrl.encodedPath).trimEnd('/')
        val entries = mutableListOf<WebDavEntry>()

        val parser = Xml.newPullParser()
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(body.charStream())
        } catch (e: Exception) {
            throw IOException("初始化 XML 解析失败", e)
        }

        // 当前 <response> 条目的临时状态
        var inResponse = false
        var inResourceType = false
        var capture: String? = null
        val text = StringBuilder()
        var href: String? = null
        var displayName: String? = null
        var size = 0L
        var lastModified: String? = null
        var isDir = false

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name.substringAfterLast(':')
                        when (name) {
                            "response" -> {
                                inResponse = true
                                href = null
                                displayName = null
                                size = 0L
                                lastModified = null
                                isDir = false
                                inResourceType = false
                            }
                            "resourcetype" -> inResourceType = true
                            // <D:collection/> 出现在 resourcetype 内 => 目录
                            "collection" -> if (inResponse && inResourceType) isDir = true
                            "href", "displayname", "getcontentlength", "getlastmodified" ->
                                if (inResponse) {
                                    capture = name
                                    text.setLength(0)
                                }
                        }
                    }
                    // 文本（TEXT 会合并 CDATA；分段 append，END 时再 trim）
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.IGNORABLE_WHITESPACE ->
                        if (capture != null) text.append(parser.text)
                    XmlPullParser.END_TAG -> {
                        val name = parser.name.substringAfterLast(':')
                        when (name) {
                            "href" -> if (capture == "href") { href = text.toString().trim(); capture = null }
                            "displayname" ->
                                if (capture == "displayname") {
                                    displayName = text.toString().trim()
                                    capture = null
                                }
                            "getcontentlength" ->
                                if (capture == "getcontentlength") {
                                    size = text.toString().trim().toLongOrNull() ?: 0L
                                    capture = null
                                }
                            "getlastmodified" ->
                                // 一期条目模型不含时间字段，解析出来仅作预留
                                if (capture == "getlastmodified") {
                                    lastModified = text.toString().trim()
                                    capture = null
                                }
                            "resourcetype" -> inResourceType = false
                            "response" -> {
                                inResponse = false
                                finalizeEntry(entries, requestUrl, selfPath, href, displayName, size, lastModified, isDir)
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            throw IOException("解析 WebDAV 目录响应失败", e)
        }
        return entries
    }

    /** 把一个解析完的 <response> 转成条目；无 href 的残缺条目与自身跳过 */
    private fun finalizeEntry(
        out: MutableList<WebDavEntry>,
        requestUrl: HttpUrl,
        selfPath: String,
        rawHref: String?,
        displayName: String?,
        size: Long,
        @Suppress("UNUSED_PARAMETER") lastModified: String?,
        isDir: Boolean,
    ) {
        val href = rawHref?.trim().orEmpty()
        if (href.isEmpty()) return
        // href 可能是绝对 URL / 绝对路径 / 相对路径，且多为 percent-encoded：
        // 先原样解析；个别服务器返回未编码 href 时补编码再试。统一得到解码后的服务器绝对路径。
        val resolved = runCatching { requestUrl.resolve(href) }.getOrNull()
            ?: runCatching { requestUrl.resolve(Uri.encode(href, "/")) }.getOrNull()
            ?: return
        val decodedPath = percentDecodePath(resolved.encodedPath)
        // 跳过自身：href 与请求路径统一解码后相同
        if (decodedPath.trimEnd('/') == selfPath) return
        // 目录兜底：个别服务器不给 collection 标记，按 href 尾斜杠判断
        val dir = isDir || decodedPath.endsWith("/")
        // displayname 缺省时由 href 推导最后一个路径段
        val name = displayName?.takeIf { it.isNotEmpty() }
            ?: decodedPath.trimEnd('/').substringAfterLast('/')
        if (name.isEmpty()) return
        out += WebDavEntry(displayName = name, isDir = dir, size = size, href = decodedPath)
    }
}

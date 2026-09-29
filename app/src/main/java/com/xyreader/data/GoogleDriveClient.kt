package com.xyreader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/** 带 HTTP 状态码的 Google API 异常。message 含状态码与错误响应体摘要，便于上层识别授权失效/配额等问题 */
class GDriveHttpException(val statusCode: Int, detail: String) : IOException("HTTP $statusCode: $detail")

/** files.list 返回的单个条目（目录条目 size 恒为 0） */
data class GDriveEntry(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long,
)

/** token 接口返回的访问令牌信息（access token 有效期约 1 小时） */
data class TokenInfo(
    val accessToken: String,
    val expiresInSeconds: Long,
)

/** Google OAuth 端点：授权页 */
private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"

/** Google OAuth 端点：授权码/刷新令牌换 access token */
private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

/** 进程内共享客户端（授权流程与 Drive API 共用），复用连接池 */
private val GDRIVE_HTTP_CLIENT: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

/** Google Drive API 的 MIME 类型：文件夹 */
private const val GDRIVE_FOLDER_MIME = "application/vnd.google-apps.folder"

/**
 * OAuth2 浏览器 loopback 授权流程（不引入 Google 官方 SDK）：
 * 1. 在 127.0.0.1 随机端口起一个最小 HTTP 服务（Android 无 com.sun.net.httpserver，手写解析）；
 * 2. 拼授权 URL 并用系统浏览器打开，用户在 Google 页面完成登录与同意；
 * 3. Google 重定向回 http://127.0.0.1:{port}/?code=...，我们解析出授权码并回写提示页；
 * 4. 用授权码 POST token 端点换 access_token / refresh_token，只保留 refresh_token 返回。
 *
 * 安全约定：clientSecret / refreshToken / 授权码只参与请求，绝不写任何日志。
 */
object GoogleDriveAuth {

    /** 只申请只读最小权限 */
    private const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"

    /** 等待浏览器回调的总时长（ServerSocket accept 超时） */
    private const val AUTH_WAIT_TIMEOUT_MS = 5 * 60 * 1000

    /** 单个回调连接的读取超时：防浏览器建立连接后不发数据挂死 */
    private const val CALLBACK_READ_TIMEOUT_MS = 10_000

    /** 授权成功时回写给浏览器的提示页 */
    private val SUCCESS_HTML =
        "<html><head><meta charset=\"utf-8\"></head>" +
            "<body style=\"font-family:sans-serif;text-align:center;padding-top:48px\">" +
            "<h2>授权成功，请返回应用</h2></body></html>"

    /** 授权失败时回写给浏览器的提示页 */
    private fun failureHtml(error: String): String =
        "<html><head><meta charset=\"utf-8\"></head>" +
            "<body style=\"font-family:sans-serif;text-align:center;padding-top:48px\">" +
            "<h2>授权失败，请返回应用重试</h2><p>$error</p></body></html>"

    /**
     * 走完整浏览器授权流程，返回 refresh_token。
     * 全程阻塞（等待用户在浏览器完成操作），须在协程中调用；内部已切 [Dispatchers.IO]。
     *
     * @throws IOException 未找到浏览器 / 用户拒绝授权 / 等待超时 / 换取令牌失败（均含用户可读原因）
     */
    suspend fun authorize(context: Context, clientId: String, clientSecret: String): String =
        withContext(Dispatchers.IO) {
            // 1) 绑定本机回环随机端口（端口 0 由系统分配）；backlog 给余量，浏览器可能并发预取 favicon
            val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            try {
                val redirectUri = "http://127.0.0.1:${server.localPort}"

                // 2) 拼授权 URL：HttpUrl.Builder 统一做百分号编码，防参数注入
                val authUrl = AUTH_ENDPOINT.toHttpUrl().newBuilder()
                    .addQueryParameter("client_id", clientId.trim())
                    .addQueryParameter("redirect_uri", redirectUri)
                    .addQueryParameter("response_type", "code")
                    .addQueryParameter("scope", SCOPE)
                    .addQueryParameter("access_type", "offline")
                    .addQueryParameter("prompt", "consent")
                    .build()

                // 3) 拉起系统浏览器；无可用浏览器时给出明确错误
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl.toString()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
                    .onFailure { throw IOException("未找到浏览器", it) }

                // 4) 阻塞等待 Google 重定向回 loopback，拿授权码
                val code = waitForAuthorizationCode(server)

                // 5) 授权码换令牌：只保留 refresh_token；
                //    access_token / expires_in 已解析但不持久化，运行时统一按需用 refresh_token 换新
                val form = FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("client_id", clientId.trim())
                    .add("client_secret", clientSecret.trim())
                    .add("redirect_uri", redirectUri)
                    .build()
                val json = postTokenRequest(form)
                val refreshToken = json.optString("refresh_token")
                if (refreshToken.isEmpty()) {
                    // prompt=consent 下正常必返回；缺失说明用户未真正重新同意（未勾选/会话异常）
                    throw IOException("未获得 refresh_token，请重新授权并在页面中同意")
                }
                refreshToken
            } finally {
                runCatching { server.close() }
            }
        }

    /**
     * 循环 accept 等待授权回调：拿到授权码返回；用户拒绝（error 参数）抛 [IOException]；
     * favicon 等无关请求丢弃后继续等下一个连接。
     */
    private suspend fun waitForAuthorizationCode(server: ServerSocket): String {
        server.soTimeout = AUTH_WAIT_TIMEOUT_MS
        while (true) {
            // 协程被取消时在连接间隙及时退出（accept 本身不可中断）
            currentCoroutineContext().ensureActive()
            val socket = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                throw IOException("授权等待超时（5 分钟内未收到 Google 回调），请重试", e)
            }
            val callback = try {
                readCallback(socket)
            } catch (e: IOException) {
                // 单个连接异常（浏览器预取中断等）：丢弃后继续等下一个连接
                null
            }
            if (callback != null) {
                callback.error?.let { error ->
                    throw IOException("授权失败：$error（请重试并在页面中同意授权）")
                }
                callback.code?.let { return it }
            }
        }
    }

    /** 一次回调的解析结果（code 与 error 互斥，均为 null 表示无关请求） */
    private class CallbackResult(val code: String?, val error: String?)

    /**
     * 处理一次浏览器连接：读请求行，解析 query 里的 code / error，
     * 回写一个最简 200 HTML 提示页并关闭。非授权回调（/favicon.ico 等）返回 null。
     */
    private fun readCallback(socket: Socket): CallbackResult? = socket.use { s ->
        s.soTimeout = CALLBACK_READ_TIMEOUT_MS
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
        // 请求行形如 "GET /?code=xxx&scope=yyy HTTP/1.1"
        val requestLine = reader.readLine() ?: return null
        // 消费剩余请求头到空行，避免残留数据干扰（响应前必须读完整请求）
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val target = requestLine.split(' ').getOrNull(1)
            ?.takeIf { it.startsWith("/") }
            ?: return null
        // 把请求目标拼到回环主机上解析，复用 OkHttp 的 query 解码（%xx / + 均正确处理）
        val url: HttpUrl = "http://127.0.0.1$target".toHttpUrlOrNull() ?: return null
        val code = url.queryParameter("code")
        val error = url.queryParameter("error")
        // favicon 等无关请求：不回写，直接等下一个连接
        if (code.isNullOrEmpty() && error.isNullOrEmpty()) return null
        writeHtmlResponse(s, if (code.isNullOrEmpty()) failureHtml(error ?: "未知错误") else SUCCESS_HTML)
        CallbackResult(code, error)
    }

    /** 手写最小 HTTP 响应（Android 无 com.sun.net.httpserver）：固定 200 + UTF-8 HTML */
    private fun writeHtmlResponse(socket: Socket, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        socket.getOutputStream().use { out ->
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
        }
    }
}

/**
 * Google Drive 客户端（纯 REST，OkHttp 直调，不引入 Google 官方 SDK）。
 * 同步调用，调用方须在 IO 线程使用。所有失败统一抛 [IOException]
 * （[GDriveHttpException] 携带状态码：401 提示 token 过期重试授权、403 配额/权限）。
 */
object GoogleDriveClient {

    /** Drive v3 files.list 端点 */
    private const val FILES_LIST_ENDPOINT = "https://www.googleapis.com/drive/v3/files"

    /** 单页条数上限 */
    private const val PAGE_SIZE = 200L

    /** expires_in 缺失时的兜底有效期（Google 常规返回 3600） */
    private const val DEFAULT_EXPIRES_IN_SECONDS = 3600L

    /**
     * 用 refresh_token 换新 access token。
     * 失败抛 [GDriveHttpException]：401/invalid_grant/invalid_client 表示授权已失效，需重新走浏览器授权。
     */
    fun refreshToken(clientId: String, clientSecret: String, refreshToken: String): TokenInfo {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId.trim())
            .add("client_secret", clientSecret.trim())
            .build()
        val json = postTokenRequest(form)
        val accessToken = json.optString("access_token")
        if (accessToken.isEmpty()) throw IOException("刷新响应缺少 access_token")
        return TokenInfo(
            accessToken = accessToken,
            expiresInSeconds = json.optLong("expires_in", DEFAULT_EXPIRES_IN_SECONDS),
        )
    }

    /**
     * 列出某文件夹的直接子项（自动按 pageToken 翻页直到取完）。
     * [folderId] 传 "root" 扫 My Drive 根目录（账号实体里空串表示根，由调用方归一化）。
     * q 条件：'{folderId}' in parents and trashed=false（回收站文件不入库）。
     */
    fun listChildren(token: String, folderId: String): List<GDriveEntry> {
        val target = folderId.trim()
        val entries = mutableListOf<GDriveEntry>()
        var pageToken: String? = null
        do {
            val builder = FILES_LIST_ENDPOINT.toHttpUrl().newBuilder()
                .addQueryParameter("q", "'$target' in parents and trashed=false")
                .addQueryParameter("pageSize", PAGE_SIZE.toString())
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size)")
                .addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("includeItemsFromAllDrives", "true")
            pageToken?.let { builder.addQueryParameter("pageToken", it) }
            val request = Request.Builder()
                .url(builder.build())
                .header("Authorization", "Bearer $token")
                .build()
            try {
                GDRIVE_HTTP_CLIENT.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        // 错误响应体含 error 字段（如 rate limit），摘要进异常消息便于排查；
                        // 成功响应体不含任何机密，但也不落日志
                        throw GDriveHttpException(resp.code, "files.list 失败: ${body.take(300)}")
                    }
                    val json = JSONObject(body)
                    val files = json.optJSONArray("files")
                    if (files != null) {
                        for (i in 0 until files.length()) {
                            val file = files.optJSONObject(i) ?: continue
                            val id = file.optString("id")
                            if (id.isEmpty()) continue
                            val name = file.optString("name")
                            val isFolder = file.optString("mimeType") == GDRIVE_FOLDER_MIME
                            entries += GDriveEntry(
                                id = id,
                                name = name,
                                isFolder = isFolder,
                                size = if (isFolder) 0L else file.optLong("size", 0L),
                            )
                        }
                    }
                    pageToken = json.optString("nextPageToken").ifEmpty { null }
                }
            } catch (e: GDriveHttpException) {
                throw withStatusHint(e)
            } catch (e: JSONException) {
                throw IOException("解析 files.list 响应失败: ${e.message}", e)
            } catch (e: IOException) {
                throw IOException("Google Drive 请求失败（网络错误）: ${e.message}", e)
            }
        } while (pageToken != null)
        return entries
    }

    /**
     * 文件内容下载 URL（alt=media 直出内容，原生支持 HTTP Range 请求），
     * 供 archive 层流式阅读与扫描封面使用。fileId 字符集为 [A-Za-z0-9_-]，不做编码。
     */
    fun downloadUrl(fileId: String): String =
        "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true"

    /** 401/403 附加用户可读提示（其余状态码原样透传） */
    private fun withStatusHint(e: GDriveHttpException): IOException {
        val hint = when (e.statusCode) {
            401 -> "（access token 已过期或无效，请重试；若持续出现请重新授权）"
            403 -> "（配额受限或无权限访问）"
            else -> return e
        }
        return IOException("${e.message}$hint", e)
    }
}

/**
 * POST token 端点并解析 JSON 响应（授权码换令牌与刷新令牌共用）。
 * 失败抛 [GDriveHttpException]（含状态码与错误响应摘要）。
 * 注意：成功响应体含 access_token/refresh_token，绝不进日志或异常消息；
 * 错误响应体只含 error 错误码，摘要可安全进入异常消息。
 */
private fun postTokenRequest(form: FormBody): JSONObject {
    val request = Request.Builder().url(TOKEN_ENDPOINT).post(form).build()
    try {
        GDRIVE_HTTP_CLIENT.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw GDriveHttpException(resp.code, "Google token 接口错误: ${body.take(300)}")
            }
            return JSONObject(body)
        }
    } catch (e: GDriveHttpException) {
        throw e
    } catch (e: JSONException) {
        throw IOException("解析 Google token 响应失败: ${e.message}", e)
    } catch (e: IOException) {
        throw IOException("Google token 请求失败（网络错误）: ${e.message}", e)
    }
}

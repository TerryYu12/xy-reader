package com.xyreader.feedback

import android.app.Application
import android.os.Build
import com.xyreader.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 反馈上传器单测：用基于 [ServerSocket] 的极简 HTTP/1.1 本地服务起中转（Android 单测类路径没有 com.sun.net.httpserver），验证请求体与各类响应 / 故障的错误码映射。
 * 需要 org.json 与 Build 的真实实现，所以走 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FeedbackUploaderTest {

    private class Recorded(
        val method: String,
        val path: String,
        val contentType: String?,
        val accept: String?,
        val body: String,
    )

    /** 一次请求的上下文：服务端处理器通过它回写响应，或直接断开连接 */
    private class Exchange(val socket: Socket) {
        fun close() {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    private lateinit var serverSocket: ServerSocket
    private lateinit var serverThreads: ExecutorService
    private val openSockets = CopyOnWriteArrayList<Socket>()
    private val received = CopyOnWriteArrayList<Recorded>()

    /** 让处理器阻塞的闸门：测试结束时放开，免得慢处理器线程挂着 */
    private val gate = CountDownLatch(1)

    @Volatile
    private var respond: (Exchange) -> Unit = { reply(it, 200, """{"ok":true,"id":"20261007-123456-ab12"}""") }

    @Before
    fun startServer() {
        serverThreads = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        serverThreads.execute {
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    break
                }
                openSockets += socket
                serverThreads.execute { handle(socket) }
            }
        }
    }

    @After
    fun stopServer() {
        gate.countDown()
        try {
            serverSocket.close()
        } catch (_: IOException) {
        }
        openSockets.forEach { try { it.close() } catch (_: IOException) { } }
        serverThreads.shutdownNow()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            buf.write(b)
        }
        return buf.toString("ISO-8859-1").trimEnd('\r')
    }

    private fun handle(socket: Socket) {
        val exchange = Exchange(socket)
        try {
            val input = socket.getInputStream().buffered()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(bodyBytes, read, length - read)
                if (n < 0) return
                read += n
            }
            received += Recorded(
                method = parts.getOrElse(0) { "" },
                path = parts.getOrElse(1) { "" }.substringBefore('?'),
                contentType = headers["content-type"],
                accept = headers["accept"],
                body = bodyBytes.toString(Charsets.UTF_8),
            )
            respond(exchange)
        } catch (_: IOException) {
            // 客户端已取消 / 超时断开：忽略
        } catch (_: InterruptedException) {
            // 测试结束，线程池被打断：忽略
        } finally {
            exchange.close()
            openSockets.remove(socket)
        }
    }

    private fun reply(exchange: Exchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status X\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = exchange.socket.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private val endpoint get() = "http://127.0.0.1:${serverSocket.localPort}/upload"

    private fun newUploader(
        endpoint: String = this.endpoint,
        client: OkHttpClient = OkHttpClient(),
        report: FeedbackReport = FeedbackReport("HEADER-LINE\n", "app log line\n", null),
    ) = FeedbackUploader(endpoint = endpoint, client = client, reportSource = { report })

    private fun upload(uploader: FeedbackUploader = newUploader(), note: String = "闪退了") =
        runBlocking { uploader.upload(note) }

    // ---------- 成功 ----------

    @Test fun successReturnsTheIdAndSendsTheExpectedRequest() {
        val result = upload(note = "打开 PDF 闪退")

        assertEquals(FeedbackResult.Success("20261007-123456-ab12"), result)
        val request = received.single()
        assertEquals("POST", request.method)
        assertEquals("/upload", request.path)
        assertEquals("application/json", request.contentType)
        assertEquals("application/json", request.accept)

        val json = JSONObject(request.body)
        assertEquals("请求体恰好 5 个字段", 5, json.length())
        assertEquals("xy-reader", json.getString("app"))
        assertEquals(BuildConfig.VERSION_NAME, json.getString("version"))
        assertEquals("android", json.getString("platform"))
        assertEquals("打开 PDF 闪退", json.getString("note"))
        val log = json.getString("log")
        assertTrue(log.contains("HEADER-LINE"))
        assertTrue(log.contains("app log line"))
    }

    @Test fun successIdIsTrimmed() {
        respond = { reply(it, 200, """{"ok":true,"id":"  20261007-123456-ab12 "}""") }
        assertEquals(FeedbackResult.Success("20261007-123456-ab12"), upload())
    }

    @Test fun logIsRedactedAgainBeforeItLeavesTheDevice() {
        val report = FeedbackReport(
            header = "HEADER\n",
            appLog = "2026-10-07 10:00:00.000 W/X: password=hunter2 https://u:p@h.com/a?token=abc\n",
            logcat = "10-07 10:00:00.000 1 1 D OkHttp: Authorization: Bearer abc.def.ghi\n",
        )
        assertTrue(upload(newUploader(report = report)) is FeedbackResult.Success)
        val log = JSONObject(received.single().body).getString("log")
        for (secret in listOf("hunter2", "u:p@", "token=abc", "abc.def.ghi")) {
            assertFalse("日志里不应出现 $secret", log.contains(secret))
        }
        assertTrue(log.contains("password=***"))
        assertTrue(log.contains("https://h.com/a"))
        assertTrue(log.contains("Authorization: ***"))
    }

    @Test fun oversizedLogIsTrimmedToTheLimitKeepingTheTail() {
        val appLog = (1..400_000).joinToString("\n", postfix = "\n") { "line-$it 日志内容日志内容日志内容" }
        val report = FeedbackReport("HEADER-LINE\n", appLog, "LOGCAT-TAIL\n")
        assertTrue(upload(newUploader(report = report)) is FeedbackResult.Success)
        val log = JSONObject(received.single().body).getString("log")
        assertTrue(log.toByteArray(Charsets.UTF_8).size <= FeedbackReport.MAX_LOG_BYTES)
        assertTrue(log.startsWith("HEADER-LINE"))
        assertTrue(log.contains("日志过长，已丢弃较早的部分"))
        assertTrue(log.trimEnd().endsWith("LOGCAT-TAIL"))
        assertFalse(log.contains("line-1 "))
    }

    @Test fun unicodeNoteSurvivesTheRoundTrip() {
        val note = "漫画「第 1 话」打不开 😀 \"quotes\" \\ back\nslash / new line"
        assertTrue(upload(note = note) is FeedbackResult.Success)
        assertEquals(note, JSONObject(received.single().body).getString("note"))
    }

    // ---------- 服务端错误码透传 ----------

    @Test fun serverErrorCodesArePassedThrough() {
        val cases = listOf(
            "invalid_json" to 400,
            "invalid_app" to 400,
            "invalid_version" to 400,
            "invalid_platform" to 400,
            "invalid_log" to 400,
            "note_too_long" to 400,
            "not_found" to 404,
            "log_too_large" to 413,
            "rate_limited" to 429,
            "daily_quota_exceeded" to 429,
            "github_upload_failed" to 502,
            "internal_error" to 502,
            "some_future_code" to 500,
        )
        for ((code, status) in cases) {
            respond = { reply(it, status, """{"ok":false,"error":"$code"}""") }
            assertEquals(code, FeedbackResult.Failure(code), upload())
        }
        assertEquals(cases.size, received.size)
    }

    // ---------- 响应不是预期 JSON ----------

    @Test fun nonJsonOrIncompleteResponsesAreInvalidResponse() {
        val bodies = listOf(
            200 to "<html><body>Hello</body></html>",
            502 to "<html>Bad gateway</html>",
            200 to "",
            200 to "[1,2,3]",
            200 to "null",
            200 to "\"just a string\"",
            200 to """{"ok":true}""",
            200 to """{"ok":true,"id":null}""",
            200 to """{"ok":true,"id":"   "}""",
            200 to """{"ok":false}""",
            200 to """{"ok":false,"error":null}""",
            200 to """{"foo":1}""",
        )
        for ((status, body) in bodies) {
            respond = { reply(it, status, body) }
            assertEquals("响应体：$body", FeedbackResult.Failure("invalid_response"), upload())
        }
    }

    // ---------- 网络故障 ----------

    @Test fun connectionRefusedIsNetworkError() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val result = upload(newUploader(endpoint = "http://127.0.0.1:$closedPort/upload"))
        assertEquals(FeedbackResult.Failure("network_error"), result)
        assertTrue("没有任何请求到达服务端", received.isEmpty())
    }

    @Test fun serverThatNeverRepliesTimesOutWithTheInjectedShortTimeout() {
        respond = {
            gate.await(10, TimeUnit.SECONDS)
            reply(it, 200, """{"ok":true,"id":"too-late"}""")
        }
        val client = OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).build()
        val start = System.nanoTime()
        val result = upload(newUploader(client = client))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(FeedbackResult.Failure("timeout"), result)
        assertTrue("应在短超时附近返回，实际 ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test fun serverThatDropsTheConnectionIsNetworkError() {
        respond = { it.close() } // 不回任何响应直接断开
        val result = upload()
        assertEquals(FeedbackResult.Failure("network_error"), result)
    }

    // ---------- 端点未配置 ----------

    @Test fun blankOrInvalidEndpointIsNotConfigured() {
        for (bad in listOf("", "   ", "not a url", "xylog.example.com/upload", "ftp://example.com/upload")) {
            assertEquals(bad, FeedbackResult.Failure("not_configured"), upload(newUploader(endpoint = bad)))
        }
        assertTrue("端点无效时不应发出任何请求", received.isEmpty())
    }

    @Test fun defaultEndpointComesFromBuildConfig() {
        // 全工程只有 app/build.gradle.kts 里定义一次端点，客户端经 BuildConfig 读取；这里只确认它存在且是合法 URL
        assertNotNull("FEEDBACK_ENDPOINT 应是合法的 http(s) URL", BuildConfig.FEEDBACK_ENDPOINT.toHttpUrlOrNull())
    }

    // ---------- 取消 ----------

    @Test fun cancellingTheCoroutineAbortsTheInFlightRequestPromptly() {
        val arrived = CountDownLatch(1)
        respond = {
            arrived.countDown()
            gate.await(10, TimeUnit.SECONDS)
            reply(it, 200, """{"ok":true,"id":"too-late"}""")
        }
        runBlocking {
            val job = launch(Dispatchers.Default) { newUploader().upload("x") }
            assertTrue("请求应到达服务端", arrived.await(5, TimeUnit.SECONDS))
            val start = System.nanoTime()
            job.cancelAndJoin()
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(job.isCancelled)
            assertTrue("取消应立刻生效而不是等 HTTP 超时，实际 ${elapsedMs}ms", elapsedMs < 3_000)
        }
    }

    @Test fun cancellationWhileCollectingTheLogSendsNothing() {
        val collecting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uploader = FeedbackUploader(
            endpoint = endpoint,
            client = OkHttpClient(),
            reportSource = {
                collecting.countDown()
                release.await(5, TimeUnit.SECONDS) // 模拟读日志很慢；读日志本身不可取消
                FeedbackReport("H\n", "a\n", null)
            },
        )
        runBlocking {
            val job = launch(Dispatchers.Default) { uploader.upload("x") }
            assertTrue(collecting.await(5, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            job.join()
            assertTrue(job.isCancelled)
        }
        assertTrue("已取消就不该再发请求", received.isEmpty())
    }

    // ---------- 响应解析 ----------

    @Test fun parseUploadResponseHandlesTheDocumentedShapes() {
        assertEquals(
            FeedbackResult.Success("abc"),
            FeedbackUploader.parseUploadResponse("""{"ok":true,"id":"abc"}"""),
        )
        assertEquals(
            FeedbackResult.Failure("rate_limited"),
            FeedbackUploader.parseUploadResponse("""{"ok":false,"error":"rate_limited"}"""),
        )
        assertEquals(
            FeedbackResult.Failure("invalid_response"),
            FeedbackUploader.parseUploadResponse("not json"),
        )
    }

    // ---------- 问题描述长度 ----------

    @Test fun clampNoteKeepsShortTextAndCutsAt2000() {
        assertEquals("abc", FeedbackUploader.clampNote("abc"))
        val exact = "a".repeat(FeedbackUploader.MAX_NOTE_CHARS)
        assertEquals(exact, FeedbackUploader.clampNote(exact))
        assertEquals(exact, FeedbackUploader.clampNote(exact + "overflow"))
    }

    @Test fun clampNoteDoesNotSplitSurrogatePairs() {
        // 第 2000 个字符恰好是 emoji 的前半：整体退一位
        val text = "a".repeat(FeedbackUploader.MAX_NOTE_CHARS - 1) + "😀" + "tail"
        val clamped = FeedbackUploader.clampNote(text)
        assertEquals("a".repeat(FeedbackUploader.MAX_NOTE_CHARS - 1), clamped)
        assertFalse(clamped.any { Character.isSurrogate(it) })
    }

    // ---------- 错误码文案 ----------

    @Test fun everyKnownCodeHasItsOwnChineseMessage() {
        val codes = listOf(
            FeedbackErrorCode.INVALID_JSON,
            FeedbackErrorCode.INVALID_APP,
            FeedbackErrorCode.INVALID_VERSION,
            FeedbackErrorCode.INVALID_PLATFORM,
            FeedbackErrorCode.INVALID_LOG,
            FeedbackErrorCode.NOTE_TOO_LONG,
            FeedbackErrorCode.NOT_FOUND,
            FeedbackErrorCode.LOG_TOO_LARGE,
            FeedbackErrorCode.RATE_LIMITED,
            FeedbackErrorCode.DAILY_QUOTA_EXCEEDED,
            FeedbackErrorCode.GITHUB_UPLOAD_FAILED,
            FeedbackErrorCode.INTERNAL_ERROR,
            FeedbackErrorCode.TIMEOUT,
            FeedbackErrorCode.NETWORK_ERROR,
            FeedbackErrorCode.NOT_CONFIGURED,
            FeedbackErrorCode.INVALID_RESPONSE,
        )
        val messages = codes.map { feedbackErrorMessage(it) }
        assertEquals("每个码一句专属提示", codes.size, messages.toSet().size)
        for ((code, message) in codes.zip(messages)) {
            assertTrue("$code 的提示不应为空", message.isNotBlank())
            assertFalse("$code 不应落入默认提示", message.startsWith("上传失败（错误码："))
            assertFalse("提示里不应暴露错误码本身：$message", message.contains(code))
        }
    }

    @Test fun specificMessages() {
        assertEquals("提交太频繁，请一分钟后再试", feedbackErrorMessage("rate_limited"))
        assertEquals("问题描述过长，请精简到 2000 字以内", feedbackErrorMessage("note_too_long"))
    }

    @Test fun unknownCodeFallsBackToTheDefaultMessage() {
        assertEquals("上传失败（错误码：weird_code）", feedbackErrorMessage("weird_code"))
        assertEquals("上传失败（错误码：client_error）", feedbackErrorMessage(FeedbackErrorCode.CLIENT_ERROR))
        // 异常服务器塞进超长内容时，提示里只展示前 64 个字符
        assertEquals("上传失败（错误码：${"x".repeat(64)}）", feedbackErrorMessage("x".repeat(500)))
    }

    // ---------- 设备信息头（依赖 Build / BuildConfig） ----------

    @Test fun headerCarriesVersionDeviceSystemAndTime() {
        val header = FeedbackReport.buildHeader(nowMillis = 1_791_376_496_789L)
        assertTrue(header.contains("应用：xy-reader ${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）"))
        assertTrue(header.contains("设备：${Build.MANUFACTURER} ${Build.MODEL}"))
        assertTrue(header.contains("系统：Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）"))
        assertTrue(Regex("""时间：\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.789 [+-]\d{2}:\d{2}""").containsMatchIn(header))
    }

    @Test fun collectDoesNotFailWithoutLogcat() {
        // 单测环境没有可用的 logcat：该段落应被安静地跳过，而不是抛异常
        AppLog.i("FeedbackUploaderTest", "collect me")
        val report = FeedbackReport.collect()
        assertNotNull(report.header)
        assertTrue(report.appLog.contains("collect me"))
        assertTrue(report.toUploadLog().isNotEmpty())
    }
}

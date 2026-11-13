package com.xyreader.booksource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

/** 书源可用性状态 */
enum class SourceCheckStatus {
    UNCHECKED,
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
}

/** 单条书源检测结果 */
data class SourceCheckResult(
    val url: String,
    val status: SourceCheckStatus,
    val responseTimeMs: Long = 0,
    val message: String = "",
)

/** 单源可用性检测接口，便于测试注入假实现（测试不联网） */
interface SourceAvailabilityChecker {
    suspend fun check(source: LegadoBookSource, timeoutMs: Long = 3000L): SourceCheckResult
}

/** 基于 OkHttp 的生产实现 */
class OkHttpSourceAvailabilityChecker(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
) : SourceAvailabilityChecker {

    override suspend fun check(source: LegadoBookSource, timeoutMs: Long): SourceCheckResult = withContext(Dispatchers.IO) {
        val rawUrl = source.url.trim()
        if (!rawUrl.startsWith("http://", ignoreCase = true) && !rawUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext SourceCheckResult(
                url = source.url,
                status = SourceCheckStatus.UNAVAILABLE,
                message = "无效链接协议",
            )
        }

        val httpUrl = rawUrl.toHttpUrlOrNull()
        if (httpUrl == null) {
            return@withContext SourceCheckResult(
                url = source.url,
                status = SourceCheckStatus.UNAVAILABLE,
                message = "无效链接格式",
            )
        }

        val start = System.currentTimeMillis()
        try {
            val headers = parseHeaders(source.header)
            val requestBuilder = Request.Builder().url(httpUrl)
            if (!headers.containsKey("User-Agent") && !headers.containsKey("user-agent")) {
                requestBuilder.header("User-Agent", BookSourceImporter.DEFAULT_USER_AGENT)
            }
            headers.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) {
                    requestBuilder.header(k, v)
                }
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                val code = response.code
                if (code in 200..399) {
                    SourceCheckResult(
                        url = source.url,
                        status = SourceCheckStatus.AVAILABLE,
                        responseTimeMs = elapsed,
                        message = "${elapsed}ms",
                    )
                } else {
                    SourceCheckResult(
                        url = source.url,
                        status = SourceCheckStatus.UNAVAILABLE,
                        responseTimeMs = elapsed,
                        message = "HTTP $code",
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            SourceCheckResult(source.url, SourceCheckStatus.UNAVAILABLE, elapsed, "链接格式错误")
        } catch (e: SocketTimeoutException) {
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            SourceCheckResult(source.url, SourceCheckStatus.UNAVAILABLE, elapsed, "连接超时")
        } catch (e: UnknownHostException) {
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            SourceCheckResult(source.url, SourceCheckStatus.UNAVAILABLE, elapsed, "域名无法解析")
        } catch (e: ConnectException) {
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            SourceCheckResult(source.url, SourceCheckStatus.UNAVAILABLE, elapsed, "连接被拒绝")
        } catch (e: SSLException) {
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            SourceCheckResult(source.url, SourceCheckStatus.UNAVAILABLE, elapsed, "SSL证书错误")
        } catch (e: IOException) {
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            SourceCheckResult(source.url, SourceCheckStatus.UNAVAILABLE, elapsed, e.message ?: "网络IO异常")
        } catch (e: Exception) {
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            SourceCheckResult(source.url, SourceCheckStatus.UNAVAILABLE, elapsed, e.message ?: "检测失败")
        }
    }

    private fun parseHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { key -> json.optString(key) }
        }.getOrDefault(emptyMap())
    }
}

/**
 * 书源批量检测服务：支持并发控制、渐进式进度回调与协程取消。
 */
class BookSourceCheckService(
    private val checker: SourceAvailabilityChecker = OkHttpSourceAvailabilityChecker(),
    private val concurrency: Int = 32,
) {
    /**
     * 并发渐进式检测书源可用性：
     * [onSourceStart] 某条源开始检测
     * [onSourceChecked] 某条源完成检测，返回当前单条结局、已完成数与总数
     */
    suspend fun checkSourcesProgressive(
        sources: List<LegadoBookSource>,
        onSourceStart: (suspend (source: LegadoBookSource) -> Unit)? = null,
        onSourceChecked: suspend (source: LegadoBookSource, result: SourceCheckResult, doneCount: Int, totalCount: Int) -> Unit,
    ): Map<String, SourceCheckResult> = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext emptyMap()
        val total = sources.size
        val results = ConcurrentHashMap<String, SourceCheckResult>()
        val doneCounter = AtomicInteger(0)
        val semaphore = Semaphore(concurrency)
        val callbackMutex = Mutex()

        supervisorScope {
            val deferreds = sources.map { source ->
                async {
                    semaphore.withPermit {
                        coroutineContext.ensureActive()
                        callbackMutex.withLock {
                            onSourceStart?.invoke(source)
                        }
                        val result = try {
                            checker.check(source)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            SourceCheckResult(
                                url = source.url,
                                status = SourceCheckStatus.UNAVAILABLE,
                                message = e.message ?: "检测失败",
                            )
                        }
                        results[source.url] = result
                        callbackMutex.withLock {
                            val done = doneCounter.incrementAndGet()
                            onSourceChecked(source, result, done, total)
                        }
                    }
                }
            }
            deferreds.awaitAll()
        }
        results.toMap()
    }
}

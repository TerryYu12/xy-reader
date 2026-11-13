package com.xyreader.booksource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class BookSourceCheckServiceTest {

    private fun dummySource(url: String, name: String = "源-$url"): LegadoBookSource {
        return LegadoBookSource(name = name, url = url)
    }

    private class FakeChecker(
        private val handler: (LegadoBookSource) -> SourceCheckResult,
    ) : SourceAvailabilityChecker {
        override suspend fun check(source: LegadoBookSource, timeoutMs: Long): SourceCheckResult {
            return handler(source)
        }
    }

    @Test
    fun checkSourcesProgressiveReportsProgressAndClassifiesResults() = runBlocking {
        val s1 = dummySource("https://ok1.com", "OK1")
        val s2 = dummySource("https://fail.com", "Fail")
        val s3 = dummySource("https://ok2.com", "OK2")

        val checker = FakeChecker { source ->
            if (source.url.contains("ok")) {
                SourceCheckResult(source.url, SourceCheckStatus.AVAILABLE, 120L, "120ms")
            } else {
                SourceCheckResult(source.url, SourceCheckStatus.UNAVAILABLE, 500L, "HTTP 404")
            }
        }

        val service = BookSourceCheckService(checker = checker, concurrency = 2)
        val progressLog = mutableListOf<String>()

        val results = service.checkSourcesProgressive(
            sources = listOf(s1, s2, s3),
            onSourceChecked = { source, result, done, total ->
                synchronized(progressLog) {
                    progressLog.add("${source.name}:$done/$total:${result.status}")
                }
            },
        )

        assertEquals(3, results.size)
        assertEquals(SourceCheckStatus.AVAILABLE, results["https://ok1.com"]?.status)
        assertEquals(SourceCheckStatus.UNAVAILABLE, results["https://fail.com"]?.status)
        assertEquals(SourceCheckStatus.AVAILABLE, results["https://ok2.com"]?.status)
        assertEquals(3, progressLog.size)
    }

    @Test
    fun checkSourcesProgressiveHandlesEmptyList() = runBlocking {
        val service = BookSourceCheckService()
        val results = service.checkSourcesProgressive(emptyList(), onSourceChecked = { _, _, _, _ -> })
        assertTrue(results.isEmpty())
    }

    @Test
    fun checkSourcesProgressiveSupportsCancellation() = runBlocking {
        val sources = (1..10).map { dummySource("https://site$it.com", "Site$it") }
        val checker = FakeChecker { source ->
            runBlocking { delay(200) }
            SourceCheckResult(source.url, SourceCheckStatus.AVAILABLE, 200L, "200ms")
        }

        val service = BookSourceCheckService(checker = checker, concurrency = 2)
        var doneCount = 0

        val job: Job = launch {
            service.checkSourcesProgressive(
                sources = sources,
                onSourceChecked = { _, _, _, _ -> doneCount++ },
            )
        }

        delay(100)
        job.cancel()
        job.join()

        // 取消后应中断，不会跑完全部 10 个
        assertTrue(doneCount < sources.size)
    }

    @Test
    fun okHttpCheckerHandlesInvalidSchemeSafely() = runBlocking {
        val checker = OkHttpSourceAvailabilityChecker()
        val source = dummySource("ftp://invalid.com/books", "FTP源")
        val result = checker.check(source)
        assertEquals(SourceCheckStatus.UNAVAILABLE, result.status)
        assertEquals("无效链接协议", result.message)
    }

    @Test
    fun okHttpCheckerHandlesInvalidHostWithSpacesSafely() = runBlocking {
        val checker = OkHttpSourceAvailabilityChecker()
        // 来自用户真实崩溃日志中的非法 host：www.630book.cc-By Dark
        val source = dummySource("http://www.630book.cc-By Dark", "问题源")
        val result = checker.check(source)
        assertEquals(SourceCheckStatus.UNAVAILABLE, result.status)
        assertEquals("无效链接格式", result.message)
    }

    @Test
    fun checkSourcesProgressiveIsolatesUnexpectedCheckerExceptions() = runBlocking {
        val s1 = dummySource("https://normal1.com", "Normal1")
        val s2 = dummySource("https://exploding.com", "Boom")
        val s3 = dummySource("https://normal2.com", "Normal2")

        val checker = FakeChecker { source ->
            if (source.url.contains("exploding")) {
                throw RuntimeException("网络库内部突发未捕获异常")
            }
            SourceCheckResult(source.url, SourceCheckStatus.AVAILABLE, 100L, "100ms")
        }

        val service = BookSourceCheckService(checker = checker, concurrency = 2)
        val results = service.checkSourcesProgressive(
            sources = listOf(s1, s2, s3),
            onSourceChecked = { _, _, _, _ -> },
        )

        assertEquals(3, results.size)
        assertEquals(SourceCheckStatus.AVAILABLE, results[s1.url]?.status)
        assertEquals(SourceCheckStatus.UNAVAILABLE, results[s2.url]?.status)
        assertEquals("网络库内部突发未捕获异常", results[s2.url]?.message)
        assertEquals(SourceCheckStatus.AVAILABLE, results[s3.url]?.status)
    }
}

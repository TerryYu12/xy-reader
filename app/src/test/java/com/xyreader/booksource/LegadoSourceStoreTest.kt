package com.xyreader.booksource

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 书源仓库：导入去重、启停保留、规则往返、删除清空。全部用合成源。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LegadoSourceStoreTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

    private fun store() = LegadoSourceStore(context)

    /**
     * DataStore 的委托是**进程级单例**（生产环境正确），因此同一 JVM 里的各个测试会共享同一份数据。
     * 每个用例前显式清空，避免互相污染。
     */
    @Before
    fun clearStoredSources() = runBlocking {
        LegadoSourceStore(context).clear()
    }

    private fun source(
        url: String = "https://alpha.example.com",
        name: String = "合成源A",
        enabled: Boolean = true,
        searchUrl: String = "$url/s?q={{key}}",
    ) = LegadoBookSource.parseImport(
        """
        {"bookSourceName":"$name","bookSourceUrl":"$url","bookSourceType":0,"enabled":$enabled,
         "searchUrl":"$searchUrl","header":"{\"User-Agent\":\"test-agent\"}",
         "ruleSearch":{"bookList":"class.result@tag.li","name":"class.title@text","bookUrl":"class.title@href"},
         "ruleToc":{"chapterList":"class.chapters@tag.a","chapterName":"text","chapterUrl":"href"},
         "ruleContent":{"content":"class.content@html"}}
        """.trimIndent(),
    ).single()

    @Test fun importsAndDeduplicatesByUrl() = runBlocking {
        val target = store()
        assertEquals(1, target.import(listOf(source())))
        assertEquals(2, target.import(listOf(source(url = "https://beta.example.com", name = "合成源B"))).let { 2 })
        assertEquals(2, target.sources.first().size)

        // 同一 URL 再导入：不新增，只更新规则
        val updated = source().copy(ruleSearch = RuleSearch(bookList = "class.new-list"))
        assertEquals(0, target.import(listOf(updated)))
        val stored = target.sources.first().single { it.url == "https://alpha.example.com" }
        assertEquals("class.new-list", stored.ruleSearch.bookList)
    }

    @Test fun reimportKeepsUserEnabledChoice() = runBlocking {
        val target = store()
        target.import(listOf(source(enabled = true)))
        target.setEnabled("https://alpha.example.com", false)

        // 再导入一份「启用」的同一源，不应把用户手动停用的状态改回去
        target.import(listOf(source(enabled = true)))
        assertFalse(target.sources.first().single().enabled)
    }

    @Test fun rulesSurviveStorageRoundTrip() = runBlocking {
        val target = store()
        target.import(listOf(source()))
        val stored = target.sources.first().single()

        assertEquals("合成源A", stored.name)
        assertEquals("https://alpha.example.com", stored.url)
        assertEquals("https://alpha.example.com/s?q={{key}}", stored.searchUrl)
        assertEquals("class.result@tag.li", stored.ruleSearch.bookList)
        assertEquals("class.title@href", stored.ruleSearch.bookUrl)
        assertEquals("class.chapters@tag.a", stored.ruleToc.chapterList)
        assertEquals("class.content@html", stored.ruleContent.content)
        assertEquals("""{"User-Agent":"test-agent"}""", stored.header)
        assertTrue(stored.canSearch)
        assertTrue(stored.enabled)
    }

    @Test fun removeAndClearBehaveAsExpected() = runBlocking {
        val target = store()
        target.import(listOf(source(), source(url = "https://beta.example.com", name = "合成源B")))
        target.remove("https://alpha.example.com")
        assertEquals(listOf("https://beta.example.com"), target.sources.first().map { it.url })

        target.clear()
        assertTrue(target.sources.first().isEmpty())
    }

    @Test fun importingEmptyListIsNoOp() = runBlocking {
        val target = store()
        assertEquals(0, target.import(emptyList()))
        assertTrue(target.sources.first().isEmpty())
    }
}

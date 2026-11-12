package com.xyreader.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.xyreader.booksource.LegadoBookSource
import com.xyreader.booksource.LegadoSearchService
import com.xyreader.booksource.LegadoSourceStore
import com.xyreader.booksource.SourcePageFetcher
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 书源搜索页 UI 测试：
 * 覆盖搜索范围选择（全选/反选/清空/逐源选择）、搜索进度展示、停止搜索（保留已有结果）、刷新搜索。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookSourceSearchScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val store get() = LegadoSourceStore(context)

    @Before
    fun clearSources() = runBlocking {
        store.clear()
    }

    private fun testSource(
        name: String,
        url: String,
        group: String? = null,
        enabled: Boolean = true,
    ): LegadoBookSource {
        val groupPart = if (group != null) """"bookSourceGroup":"$group",""" else ""
        val json = """
            {"bookSourceName":"$name","bookSourceUrl":"$url",$groupPart"bookSourceType":0,"enabled":$enabled,
             "searchUrl":"$url/search?q={{key}}",
             "ruleSearch":{"bookList":"class.result@tag.li","name":"class.title@text","bookUrl":"class.title@href","author":"class.author@text"}}
        """.trimIndent()
        return LegadoBookSource.parseImport(json).single()
    }

    private class FakeFetcher(private val pages: Map<String, String>) : SourcePageFetcher {
        override suspend fun fetch(url: String, headers: Map<String, String>): String {
            pages.entries.firstOrNull { url.contains(it.key) }?.let { return it.value }
            throw IOException("Not found: $url")
        }
    }

    @Test
    fun initialStateWithoutSourcesShowsControls() {
        compose.setContent { BookSourceSearchScreen(onBack = {}) }

        compose.onNodeWithText("搜索书源").assertIsDisplayed()
        compose.onNodeWithText("范围：无可用源").assertIsDisplayed()
        compose.onNodeWithText("搜索").assertIsDisplayed()
        compose.onNodeWithText("停止搜索").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("刷新搜索").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun scopeDialogShowsAvailableSourcesAndAllowsSelection() {
        runBlocking {
            val src1 = testSource("源甲", "https://alpha.example.com", group = "综合")
            val src2 = testSource("源乙", "https://beta.example.com", group = "精品")
            store.import(listOf(src1, src2))
        }

        compose.setContent { BookSourceSearchScreen(onBack = {}) }

        // 默认全选：显示"范围：全部 (2)"
        compose.onNodeWithText("范围：全部 (2)").assertIsDisplayed()

        // 点击打开选择搜索范围弹层
        compose.onNodeWithText("范围：全部 (2)").performClick()
        compose.onNodeWithText("选择搜索范围").assertIsDisplayed()
        compose.onNodeWithText("已选 2 / 2 个可用源").assertIsDisplayed()
        compose.onNodeWithText("全选").assertIsDisplayed()
        compose.onNodeWithText("反选").assertIsDisplayed()
        compose.onNodeWithText("清空").assertIsDisplayed()

        // 测试清空
        compose.onNodeWithText("清空").performClick()
        compose.onNodeWithText("已选 0 / 2 个可用源").assertIsDisplayed()

        // 测试反选
        compose.onNodeWithText("反选").performClick()
        compose.onNodeWithText("已选 2 / 2 个可用源").assertIsDisplayed()

        // 勾选取消源乙 (点击源乙所在行)
        compose.onNodeWithText("源乙").performClick()
        compose.onNodeWithText("已选 1 / 2 个可用源").assertIsDisplayed()

        // 确定保存选择
        compose.onNodeWithText("确定").performClick()

        // 弹层关闭，按钮文案更新为"范围：已选 1/2"
        compose.onNodeWithText("范围：已选 1/2").assertIsDisplayed()
    }

    @Test
    fun searchShowsProgressAndDisplaysResultsBelow() {
        val src1 = testSource("源甲", "https://alpha.example.com")
        runBlocking { store.import(listOf(src1)) }

        val htmlAlpha = """
            <html><body><ul class="result">
              <li class="row"><a class="title" href="/book/101">诛仙修真录</a><span class="author">萧鼎</span></li>
            </ul></body></html>
        """.trimIndent()
        val fetcher = FakeFetcher(mapOf("alpha.example.com" to htmlAlpha))
        val service = LegadoSearchService(fetcher = fetcher)

        compose.setContent { BookSourceSearchScreen(onBack = {}, searchService = service) }

        compose.onNodeWithText("书名 / 作者").performTextInput("诛仙")
        compose.onNodeWithText("搜索").performClick()

        // 等待后台搜索完成
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("搜索完成 · 共搜索 1 个源").fetchSemanticsNodes().isNotEmpty()
        }

        // 搜索完成状态与进度显示
        compose.onNodeWithText("搜索完成 · 共搜索 1 个源").assertIsDisplayed()
        compose.onNodeWithText("共搜出 1 本书").assertIsDisplayed()

        // 结果展示在下方
        compose.onNodeWithText("源甲").assertIsDisplayed()
        compose.onNodeWithText("诛仙修真录").assertIsDisplayed()
        compose.onNodeWithText("萧鼎").assertIsDisplayed()
        compose.onNodeWithText("抓取整本").assertIsDisplayed()
    }

    @Test
    fun stopSearchHaltsProgressAndPreservesFoundResults() {
        val src1 = testSource("源甲", "https://alpha.example.com")
        val src2 = testSource("源乙", "https://beta.example.com")
        runBlocking { store.import(listOf(src1, src2)) }

        val htmlAlpha = """
            <html><body><ul class="result">
              <li class="row"><a class="title" href="/book/1">已搜到的书</a><span class="author">甲作者</span></li>
            </ul></body></html>
        """.trimIndent()

        // 模拟源甲返回，源乙挂起模拟慢请求，以便在源乙搜索时点击「停止搜索」
        val source2Started = CompletableDeferred<Unit>()
        val controlledFetcher = object : SourcePageFetcher {
            override suspend fun fetch(url: String, headers: Map<String, String>): String {
                if (url.contains("alpha.example.com")) {
                    return htmlAlpha
                }
                if (url.contains("beta.example.com")) {
                    source2Started.complete(Unit)
                    delay(30_000)
                    return ""
                }
                throw IOException("Not found")
            }
        }
        val service = LegadoSearchService(fetcher = controlledFetcher)

        compose.setContent { BookSourceSearchScreen(onBack = {}, searchService = service) }

        compose.onNodeWithText("书名 / 作者").performTextInput("测试")
        compose.onNodeWithText("搜索").performClick()

        // 等待源乙开始搜索
        compose.waitUntil(timeoutMillis = 5_000) { source2Started.isCompleted }

        // 点击停止搜索
        compose.onNodeWithText("停止搜索").assertIsEnabled().performClick()

        // 进度更新为「已停止搜索」，且停止搜索按钮恢复禁用
        compose.onNodeWithText("已停止搜索 · 进度 1/2").assertIsDisplayed()
        compose.onNodeWithText("已保留 1 本书").assertIsDisplayed()
        compose.onNodeWithText("停止搜索").assertIsNotEnabled()

        // 关键断言：源甲已搜出的结果没有被清空，依然在下方列表中展示！
        compose.onNodeWithText("已搜到的书").assertIsDisplayed()
        compose.onNodeWithText("甲作者").assertIsDisplayed()
        compose.onNodeWithText("抓取整本").assertIsDisplayed()
    }

    @Test
    fun refreshSearchRestartsSearchAndUpdatesResults() {
        val src1 = testSource("源甲", "https://alpha.example.com")
        runBlocking { store.import(listOf(src1)) }

        var queryCount = 0
        val controlledFetcher = object : SourcePageFetcher {
            override suspend fun fetch(url: String, headers: Map<String, String>): String {
                queryCount++
                return """
                    <html><body><ul class="result">
                      <li class="row"><a class="title" href="/book/$queryCount">结果版本 $queryCount</a><span class="author">作者</span></li>
                    </ul></body></html>
                """.trimIndent()
            }
        }
        val service = LegadoSearchService(fetcher = controlledFetcher)

        compose.setContent { BookSourceSearchScreen(onBack = {}, searchService = service) }

        compose.onNodeWithText("书名 / 作者").performTextInput("关键词")
        compose.onNodeWithText("搜索").performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("结果版本 1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("结果版本 1").assertIsDisplayed()

        // 点击刷新搜索
        compose.onNodeWithText("刷新搜索").assertIsEnabled().performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("结果版本 2").fetchSemanticsNodes().isNotEmpty()
        }
        // 验证重新请求，结果更新为结果版本 2
        compose.onNodeWithText("结果版本 2").assertIsDisplayed()
    }
}

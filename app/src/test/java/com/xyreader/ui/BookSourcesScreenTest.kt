package com.xyreader.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.xyreader.booksource.BookSourceCheckService
import com.xyreader.booksource.LegadoBookSource
import com.xyreader.booksource.LegadoSourceStore
import com.xyreader.booksource.SourceAvailabilityChecker
import com.xyreader.booksource.SourceCheckResult
import com.xyreader.booksource.SourceCheckStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 在线书源两页的界面行为：空态、导入弹层对坏输入的处理、搜索页初始态、可用性检测与一键选中删除。
 *
 * 只在合成数据上跑（不导入任何真实源、不联网）；导入成功后的列表行为由
 * `LegadoSourceStoreTest` 在数据层覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookSourcesScreenTest {

    @get:Rule val compose = createComposeRule()

    /** DataStore 委托是进程级单例，用例间必须显式清空（同 LegadoSourceStoreTest） */
    @Before
    fun clearSources() = runBlocking {
        LegadoSourceStore(RuntimeEnvironment.getApplication()).clear()
    }

    private fun dummySource(url: String, name: String) = LegadoBookSource(
        name = name,
        url = url,
    )

    @Test
    fun emptyStateShowsCountAndEntryPoints() {
        compose.setContent { BookSourcesScreen(onBack = {}, onOpenSearch = {}) }

        compose.onNodeWithText("在线书源").assertIsDisplayed()
        compose.onNodeWithText("导入源").assertIsDisplayed()
        compose.onNodeWithText("检测可用性").assertIsDisplayed()
        compose.onNodeWithText("共 0 条源，启用 0 条").assertIsDisplayed()
    }

    @Test
    fun invalidJsonShowsErrorInsideDialogAndKeepsItOpen() {
        compose.setContent { BookSourcesScreen(onBack = {}, onOpenSearch = {}) }

        compose.onNodeWithText("导入源").performClick()
        compose.onNodeWithText("粘贴 JSON 规则或输入 URL 链接").performTextInput("这不是 JSON")
        compose.onNodeWithText("导入").performClick()

        // 报错留在弹层里，弹层不关（用户可继续改）
        compose.onNodeWithText("没有解析到有效的书源（需要 Legado 格式的 JSON）").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
    }

    @Test
    fun checkAvailabilityAndOneClickSelectAndBatchDeleteUnavailableSources() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = LegadoSourceStore(app)
        val s1 = dummySource("https://avail.com", "可用源A")
        val s2 = dummySource("https://broken.com", "失效源B")
        store.import(listOf(s1, s2))

        val fakeChecker = object : SourceAvailabilityChecker {
            override suspend fun check(source: LegadoBookSource, timeoutMs: Long): SourceCheckResult {
                return if (source.url == "https://avail.com") {
                    SourceCheckResult(source.url, SourceCheckStatus.AVAILABLE, 100L, "100ms")
                } else {
                    SourceCheckResult(source.url, SourceCheckStatus.UNAVAILABLE, 500L, "HTTP 404")
                }
            }
        }
        val checkService = BookSourceCheckService(checker = fakeChecker, concurrency = 2)

        compose.setContent {
            BookSourcesScreen(
                onBack = {},
                onOpenSearch = {},
                checkService = checkService,
            )
        }

        // 等待书源数据从 DataStore 加载完成
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("共 2 条源，启用 2 条").fetchSemanticsNodes().isNotEmpty()
        }

        // 点击检测可用性
        compose.onNodeWithText("检测可用性").performClick()

        // 等待检测完成并展示结果条
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("检测完成：可用 1 · 不可用 1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("检测完成：可用 1 · 不可用 1").assertIsDisplayed()
        compose.onNodeWithText("一键选中不可用 (1)").assertIsDisplayed()

        // 点击「一键选中不可用 (1)」
        compose.onNodeWithText("一键选中不可用 (1)").performClick()

        // 验证进入多选且已选 1 条
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("已选 1 条").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("已选 1 条").assertIsDisplayed()
        compose.onNodeWithText("删除 (1)").assertIsDisplayed()

        // 点击删除 (1)
        compose.onNodeWithText("删除 (1)").performClick()

        // 弹出确认弹窗，确认删除
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("删除选中的 1 条书源？").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("删除选中的 1 条书源？").assertIsDisplayed()
        compose.onNodeWithText("删除").performClick()

        // 等待删除完成
        compose.waitUntil(timeoutMillis = 5_000) {
            runBlocking { store.sources.first().size == 1 }
        }

        // 验证失效源已被移除，只剩下可用源
        val remaining = store.sources.first()
        assertEquals(1, remaining.size)
        assertEquals("https://avail.com", remaining.first().url)
    }

    @Test
    fun searchScreenShowsInitialHintWithoutSources() {
        compose.setContent { BookSourceSearchScreen(onBack = {}) }

        compose.onNodeWithText("搜索书源").assertIsDisplayed()
        compose.onNodeWithText("共 0 个可用源 · 结果来自你导入的源").assertIsDisplayed()
        compose.onNodeWithText("搜索").assertIsDisplayed()
    }
}

package com.xyreader.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.xyreader.booksource.LegadoSourceStore
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
 * 在线书源两页的界面行为：空态、导入弹层对坏输入的处理、搜索页初始态。
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

    @Test
    fun emptyStateShowsCountAndEntryPoints() {
        compose.setContent { BookSourcesScreen(onBack = {}, onOpenSearch = {}) }

        compose.onNodeWithText("在线书源").assertIsDisplayed()
        compose.onNodeWithText("导入源").assertIsDisplayed()
        compose.onNodeWithText("共 0 条源，启用 0 条").assertIsDisplayed()
    }

    @Test
    fun invalidJsonShowsErrorInsideDialogAndKeepsItOpen() {
        compose.setContent { BookSourcesScreen(onBack = {}, onOpenSearch = {}) }

        compose.onNodeWithText("导入源").performClick()
        compose.onNodeWithText("粘贴 Legado 书源 JSON（数组或单条）").performTextInput("这不是 JSON")
        compose.onNodeWithText("导入").performClick()

        // 报错留在弹层里，弹层不关（用户可继续改）
        compose.onNodeWithText("没有解析到有效的书源（需要 Legado 格式的 JSON）").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
    }

    @Test
    fun searchScreenShowsInitialHintWithoutSources() {
        compose.setContent { BookSourceSearchScreen(onBack = {}) }

        compose.onNodeWithText("搜索书源").assertIsDisplayed()
        compose.onNodeWithText("共 0 个可用源 · 结果来自你导入的源").assertIsDisplayed()
        compose.onNodeWithText("搜索").assertIsDisplayed()
    }
}

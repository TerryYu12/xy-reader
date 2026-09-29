package com.xyreader.reader

import android.app.Application
import android.graphics.Color
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.xyreader.core.BookEntity
import com.xyreader.core.PageMode
import com.xyreader.core.ReaderPrefs
import com.xyreader.data.AppGraph
import com.xyreader.data.ArkDatabase
import com.xyreader.ui.ArkTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 从真实漫画源进入共用阅读界面，确认短页不再被撑成一整屏。PDF原生引擎另用设备测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContinuousReaderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun adjacentShortPagesMeetWithoutViewportPadding() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "continuous.cbz")
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(4) { i ->
                val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(if (i % 2 == 0) Color.BLUE else Color.GREEN)
                zip.putNextEntry(ZipEntry("${i + 1}.png"))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
                bitmap.recycle()
            }
        }
        val bookId = runBlocking {
            AppGraph.libraryRepository(context).setReaderPrefs(ReaderPrefs(pageMode = PageMode.UP_DOWN))
            ArkDatabase.getInstance(context).bookDao().insertBook(
                BookEntity(title = "连续阅读测试", uri = Uri.fromFile(file).toString(), format = "CBZ", addedAt = 123),
            )
        }
        compose.setContent { ArkTheme { ReaderScreen(bookId, onBack = {}) } }
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithContentDescription("第 2 页").fetchSemanticsNodes().isNotEmpty()
        }
        val first = compose.onNodeWithContentDescription("第 1 页").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithContentDescription("第 2 页").fetchSemanticsNode().boundsInRoot
        assertEquals("相邻页面不应插入视口补白", first.bottom, second.top, 1f)
        assertEquals("页面高度应遵从600×400比例", first.width * 2f / 3f, first.height, 1f)
    }
}

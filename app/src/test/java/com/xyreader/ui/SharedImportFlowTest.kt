package com.xyreader.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import com.xyreader.SharedIntake
import com.xyreader.data.ArkDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/**
 * 外部文件导入全链路回归测试：真 Compose 运行时 + 真 SharedIntake + 真仓库 + 真导航。
 *
 * 曾复现的缺陷：`LaunchedEffect(shared)` 中消费 shared（置 null）→ key 变化 →
 * Compose 取消正在执行的导入协程（LeftCompositionCancellationException），
 * 且 runCatching 把取消异常当失败 → 弹「无法打开这个文件：The coroutine scope left the composition」。
 * 本测试锁定修复后的行为：导入真实落库、且不出现任何失败提示。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedImportFlowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun externalViewIntentImportsWithoutSelfCancellation() {
        val context = RuntimeEnvironment.getApplication()
        SharedIntake.consume() // 干净起点

        val file = File(context.cacheDir, "外部打开测试.txt")
        file.writeText("第一行正文\n第二行正文")

        SharedIntake.submit(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(file), "text/plain")
            },
        )

        compose.setContent { ArkTheme { ArkNavHost() } }

        // 导入应真实落库（而不是「被取消就了事」）
        val expected = File(File(context.filesDir, "imported"), "外部打开测试.txt")
        val expectedUri = Uri.fromFile(expected).toString()
        val dao = ArkDatabase.getInstance(context).bookDao()
        try {
            // 120s：本地实测该导入链路在 Robolectric 下需 ~45s（24 核机），CI（美区 4 核）更慢；宽限给足
            compose.waitUntil(timeoutMillis = 120_000) {
                runBlocking {
                    runCatching { expected.exists() && dao.getByUri(expectedUri) != null }
                        .getOrDefault(false)
                }
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val dir = File(context.filesDir, "imported")
            val dbProbe = runBlocking { runCatching { dao.getByUri(expectedUri) } }
            println(
                buildString {
                    append("DIAG pending=").append(SharedIntake.pending.value)
                    append(" | fileExists=").append(expected.exists())
                    append(" | filesDir=").append(context.filesDir.absolutePath)
                    append(" | importedDirExists=").append(dir.exists())
                    append(" | importedFiles=")
                    append(dir.listFiles()?.joinToString { "${it.name}(${it.length()}B)" })
                    append(" | db=").append(dbProbe)
                    append(" | toast=").append(ShadowToast.getTextOfLatestToast())
                },
            )
            throw e
        }

        // 且不应出现任何导入失败提示（旧缺陷会弹「无法打开这个文件：…」）
        val latest = ShadowToast.getTextOfLatestToast()
        assertFalse("不应弹出导入失败提示：$latest", latest?.contains("无法打开") == true)
    }
}

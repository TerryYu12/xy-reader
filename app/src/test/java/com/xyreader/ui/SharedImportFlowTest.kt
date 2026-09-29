package com.xyreader.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import com.xyreader.SharedIntake
import com.xyreader.data.AppGraph
import java.io.File
import kotlinx.coroutines.flow.first
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
        val t0 = System.currentTimeMillis()
        println("DIAG-T start")

        val file = File(context.cacheDir, "外部打开测试.txt")
        file.writeText("第一行正文\n第二行正文")

        SharedIntake.submit(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(file), "text/plain")
            },
        )
        println("DIAG-T submit done @${System.currentTimeMillis() - t0}ms pendingSet=${SharedIntake.pending.value != null}")

        compose.setContent { ArkTheme { ArkNavHost() } }
        println("DIAG-T setContent done @${System.currentTimeMillis() - t0}ms")

        // 导入应真实落库（而不是「被取消就了事」）。
        // 断言「语义结果」而非写死路径：Robolectric 的临时数据目录随测试方法变化，而
        // AppGraph / ArkDatabase 是单例（整套测试共享，可能由更早的测试类初始化为旧目录）——
        // 两者不保证一致（CI 上曾因此稳定误报）。改为：库里出现这本书，且它 uri 指向的文件真实存在。
        val repo = AppGraph.libraryRepository(context)
        var importedUri: String? = null
        var polls = 0
        try {
            // 不用 waitUntil 单等：Robolectric/CI 下需要显式泵（compose 帧 + 主 looper）推动导入协程；
            // 本地实测导入本身瞬时，等待成本全在冷启动与调度窗口。宽限 180s，每 100 轮打印一次状态。
            val deadline = System.currentTimeMillis() + 180_000
            var ok = false
            while (!ok && System.currentTimeMillis() < deadline) {
                val landed = runBlocking {
                    runCatching {
                        repo.books.first().firstOrNull { it.title == "外部打开测试" }
                    }.getOrNull()
                }
                importedUri = landed?.uri
                val path = landed?.uri?.let { Uri.parse(it).path }
                ok = path != null && File(path).exists()
                if (ok) break
                polls++
                if (polls % 100 == 0) {
                    println(
                        "DIAG-T pump#$polls @${System.currentTimeMillis() - t0}ms" +
                            " landed=${landed != null} uri=$importedUri" +
                            " pending=${SharedIntake.pending.value != null}",
                    )
                }
                compose.waitForIdle()
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                Thread.sleep(20)
            }
            println("DIAG-T wait done @${System.currentTimeMillis() - t0}ms pumps=$polls ok=$ok uri=$importedUri")
            if (!ok) {
                throw androidx.compose.ui.test.ComposeTimeoutException(
                    "导入条件 180s 内未满足（pumps=$polls uri=$importedUri）",
                )
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            println(
                buildString {
                    append("DIAG pendingSet=").append(SharedIntake.pending.value != null)
                    append(" | importedUri=").append(importedUri)
                    append(" | booksCount=")
                    append(runBlocking { runCatching { repo.books.first().size }.getOrDefault(-1) })
                    append(" | titles=")
                    append(
                        runBlocking {
                            runCatching { repo.books.first().map { it.title } }.getOrDefault(emptyList<String>())
                        },
                    )
                    append(" | filesDir=").append(context.filesDir.absolutePath)
                    append(" | toast=").append(ShadowToast.getTextOfLatestToast())
                    append(" | polls=").append(polls)
                    append(" | elapsed=").append(System.currentTimeMillis() - t0).append("ms")
                },
            )
            throw e
        }

        // 且不应出现任何导入失败提示（旧缺陷会弹「无法打开这个文件：…」）
        val latest = ShadowToast.getTextOfLatestToast()
        assertFalse("不应弹出导入失败提示：$latest", latest?.contains("无法打开") == true)
    }
}

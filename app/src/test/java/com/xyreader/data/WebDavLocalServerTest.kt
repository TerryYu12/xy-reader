package com.xyreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 本地 WebDAV 集成测试（对接 127.0.0.1:8899 的 wsgidav，验证 PROPFIND 解析链路）。
 *
 * 不属于常规测试套件：仅当本地 8899 端口有测试服务器时手动运行。
 * 启动方式（scratch 目录）：
 *   python -m wsgidav.server.server_cli --host=127.0.0.1 --port=8899 \
 *     --root "F:/AI Flies/Hermes/cache/scratch/dav-server" --auth=anonymous
 *
 * 覆盖：浅层列目录、中文/空格路径编码、目录识别、自身条目跳过。
 *
 * 服务器未运行、或 8899 被非 DAV 服务占用时自动跳过（Assume），可在 CI 环境安全存在。
 */
@RunWith(RobolectricTestRunner::class)
class WebDavLocalServerTest {

    private val client = WebDavClient()
    private val base = "http://127.0.0.1:8899/"

    /**
     * 探测本地 8899 是否是一个可用的 WebDAV 服务。
     * 只用 TCP connect 不够：端口可能被非 DAV 服务占用（本机 devserver 对 PROPFIND 返 501），
     * 那样会误判为「服务器可用」而在断言处失败。改用最小 PROPFIND Depth:0 探活，
     * 仅 207 Multistatus 视为可用，否则 Assume 跳过。
     */
    private val serverAvailable: Boolean by lazy { client.isDavServer(base) }

    @Test
    fun listRootDirParsesEntries() {
        org.junit.Assume.assumeTrue("本地 WebDAV 测试服务器未运行（127.0.0.1:8899），跳过", serverAvailable)
        val entries = client.listDir(base, "", "")
        val names = entries.map { it.displayName }
        println("ROOT ENTRIES: " + entries.joinToString { "${it.displayName}(dir=${it.isDir})" })

        // 三个目录都在，且被识别为目录
        assertTrue("漫画 应存在", "漫画" in names)
        assertTrue("novels 应存在", "novels" in names)
        assertTrue("空目录 应存在", "空目录" in names)
        assertTrue("漫画 应识别为目录", entries.first { it.displayName == "漫画" }.isDir)
        // 根自身被跳过（href 与请求路径相同）
        assertTrue("根自身不应出现", entries.none { it.displayName == "dav-server" })
        assertEquals(0, entries.count { !it.isDir }) // 根下没有文件
    }

    @Test
    fun listChineseDirParsesFiles() {
        org.junit.Assume.assumeTrue("本地 WebDAV 测试服务器未运行（127.0.0.1:8899），跳过", serverAvailable)
        // 中文目录：手动 percent-encode（与 scanner 的 dirUrl 同策略）
        val entries = client.listDir("${base}%E6%BC%AB%E7%94%BB/", "", "")
        val names = entries.map { it.displayName }
        println("漫画 DIR ENTRIES: " + entries.joinToString { "${it.displayName}(dir=${it.isDir},size=${it.size})" })

        assertTrue("vol1.cbz 应存在", "vol1.cbz" in names)
        assertTrue("带空格 和中文.cbz 应存在", "带空格 和中文.cbz" in names)
        assertTrue("正常名称.pdf 应存在", "正常名称.pdf" in names)
        // 三个都是文件
        assertTrue("应为文件", entries.filter { !it.isDir }.size == 3)
        // href 是解码后的服务器绝对路径
        val vol1 = entries.first { it.displayName == "vol1.cbz" }
        assertEquals("/漫画/vol1.cbz", vol1.href)
    }
}

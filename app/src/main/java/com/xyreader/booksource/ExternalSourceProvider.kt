package com.xyreader.booksource

import java.util.ServiceLoader

/**
 * 可选外部源的 SPI（主仓只认这个接口）。
 *
 * 约定：
 * - **主仓不含任何具体来源的实现或站点名**；具体来源作为独立的 Gradle 模块编入（`settings.gradle.kts`
 *   里的 `optionalSourcesDir`，路径写在 local.properties，不入库）。
 * - 模块通过 `META-INF/services/com.xyreader.booksource.ExternalSourceProvider` 注册自身，
 *   应用启动时用 [ExternalSourceRegistry] 发现；没有模块时列表为空，一切照常。
 * - 这里只暴露「搜索」这一件事：详情/下载由来源对象自己给出可打开的地址，应用不代存凭据。
 */
interface ExternalSourceProvider {
    /** 稳定标识（用于日志/去重，不参与 UI 文案） */
    val id: String

    /** 卡片标题 */
    val displayName: String

    /** 卡片副标题（能力说明） */
    val description: String

    /** 附加提示（例如"搜索免登录；下载需你在浏览器登录"），可为空 */
    val note: String

    /** 新建一次搜索会话；实现方自行决定是否复用 HTTP 客户端 */
    fun createSession(): ExternalSourceSession
}

/** 一次搜索会话（无状态或自成一体，UI 不持有凭据） */
interface ExternalSourceSession {
    /**
     * 按关键词搜索。
     *
     * @throws Exception 网络/协议错误，UI 转成可读提示
     */
    suspend fun search(query: String, limit: Int): List<ExternalSearchItem>
}

/** 一条外部来源的搜索结果（足以展示 + 让用户用浏览器打开） */
data class ExternalSearchItem(
    val title: String,
    val externalUrl: String,
    val author: String? = null,
    val format: String? = null,
    val sizeBytes: Long? = null,
    val language: String? = null,
)

/**
 * 发现可用的外部来源。
 *
 * 用 `ServiceLoader` 读取模块里注册的实现；任何加载失败都降级为空列表（应用不该因为一个可选模块而崩）。
 */
object ExternalSourceRegistry {

    fun providers(): List<ExternalSourceProvider> = runCatching {
        ServiceLoader.load(ExternalSourceProvider::class.java, ExternalSourceProvider::class.java.classLoader)
            .toList()
    }.getOrDefault(emptyList())
}

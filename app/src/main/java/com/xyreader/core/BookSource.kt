package com.xyreader.core

/**
 * 在线来源的一条书目条目（来源无关）。
 *
 * [sourceId] + [itemId]（+ [version]）决定下载身份与落盘命名；[title] 只用于展示。
 * 不能让标题参与命名：两个来源出现同名书时，按文件名落盘会互相覆盖
 * （现有 `importSharedFile` 正是「同名即覆盖」语义，下载路径必须绕开它）。
 */
data class DownloadEntry(
    val sourceId: String,
    val itemId: String,
    val title: String,
    /** 期望格式（[BookFormat.name]）。未知传 null，落盘后按文件头嗅探。 */
    val format: String? = null,
    /** 来源内的版本/卷次标识（可为空）；参与命名，避免同条目不同版本互相覆盖。 */
    val version: String? = null,
) {
    /** 下载身份：与标题无关，用于任务去重与落盘命名 */
    fun identityKey(): String = "$sourceId\u0000$itemId\u0000${version ?: ""}"
}

/** 来源解析出的获取方式。 */
sealed interface Acquisition {
    /** 可直接下载的文件：HTTPS，或局域网/回环的明文 HTTP（自建目录的常见形态） */
    data class DirectFile(
        val url: String,
        val mimeType: String? = null,
        /** 来源声明的字节数；未知传 null（跳过大小校验，仅用系统报告值） */
        val sizeBytes: Long? = null,
    ) : Acquisition

    /** 借阅 / 付费 / DRM / 无权限：下载层不处理，由来源层给原站入口 */
    data class Unsupported(val reason: String) : Acquisition
}

/**
 * 在线书库来源适配接口。
 *
 * 本阶段（下载基础设施）只落地 [DownloadEntry] → [Acquisition] 这一环所需的契约；
 * 目录浏览 / 搜索 / 分页等方法等第一个真实来源接入时再补，避免先造一套没人用的接口。
 *
 * 下载、校验、命名、入库、离线阅读全部与来源无关：新增来源不需要改动这些链路。
 */
interface BookSource {
    /** 稳定标识，写进落盘命名；改了它等于换了来源身份 */
    val id: String

    /** 界面展示名 */
    val displayName: String

    /** 解析某条目的获取方式；失败返回 Result.failure */
    suspend fun acquire(entry: DownloadEntry): Result<Acquisition>
}

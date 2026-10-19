package com.xyreader.booksource

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 整本抓取的生命周期阶段 */
enum class BookFetchPhase {
    /** 目录/正文抓取中（已进入前台服务） */
    RUNNING,
    /** 抓取完成且已入库 */
    DONE,
    /** 抓取失败（任何一章失败都不入库） */
    FAILED,
    /** 用户取消（或服务被系统回收），不入库、不留文件 */
    CANCELLED,
}

/** 一本书的抓取状态；键为 [bookUrl]（搜索结果里一本书唯一） */
data class BookFetchStatus(
    val bookUrl: String,
    val title: String,
    val phase: BookFetchPhase,
    val done: Int = 0,
    val total: Int = 0,
    /** 入库成功后的书籍 id，可直接用于打开阅读器 */
    val bookId: Long? = null,
    val message: String? = null,
) {
    val running: Boolean get() = phase == BookFetchPhase.RUNNING
}

/**
 * 整本抓取进度/结局的进程内广播点（StateFlow，键 `bookUrl`）。
 *
 * 为什么是单例：抓取现在跑在 [BookFetchService] 里，生命周期与页面/Activity 解耦。
 * 页面重建（旋转、从最近任务回来）后仍要能读到「正在抓取」的状态，
 * 所以状态不能放在 Compose 的 `remember` 里，只能放进程级单例。
 *
 * 约定：服务写，页面读；页面消费掉终态（DONE/FAILED/CANCELLED）后应调用 [clear]，
 * 行才能回到「抓取整本」。
 */
object BookFetchState {

    private val _statuses = MutableStateFlow<Map<String, BookFetchStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, BookFetchStatus>> = _statuses.asStateFlow()

    /** 写入/覆盖一本书的状态（服务侧调用） */
    fun update(status: BookFetchStatus) {
        _statuses.update { it + (status.bookUrl to status) }
    }

    /** 清除一本书的状态（页面消费终态后调用，或用户重新发起抓取前） */
    fun clear(bookUrl: String) {
        _statuses.update { it - bookUrl }
    }

    /** 仅测试用：清空全部状态，避免用例互相污染 */
    internal fun reset() {
        _statuses.value = emptyMap()
    }
}

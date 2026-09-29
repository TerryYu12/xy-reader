package com.xyreader.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xyreader.core.ArchiveFactory
import com.xyreader.core.BookEntity
import com.xyreader.core.Chapter
import com.xyreader.data.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 书籍详情页 ViewModel：书籍元数据来自仓库流；目录（章节）按需打开数据源读取。
 *
 * 仅本地书在详情页读目录；远程书（webdav:// / gdrive://）不在此发起网络 IO，
 * 目录留待阅读器内的目录抽屉查看。打开读完即关源，不缓存位图。
 */
class BookDetailViewModel(
    private val bookId: Long,
    app: Application,
) : ViewModel() {

    private val appContext = app.applicationContext
    private val repository = AppGraph.libraryRepository(appContext)

    /** 详情书流：书被删除后变为 null（UI 转入"找不到这本书"空态） */
    val book: StateFlow<BookEntity?> = repository.books
        .map { list -> list.firstOrNull { it.id == bookId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 目录加载状态 */
    sealed interface Toc {
        data object Loading : Toc
        data class Loaded(val chapters: List<Chapter>, val pageCount: Int) : Toc
        data object Failed : Toc
        data object Hidden : Toc
    }

    private val _toc = MutableStateFlow<Toc>(Toc.Loading)
    val toc: StateFlow<Toc> = _toc.asStateFlow()

    /** 一次性提示（删除失败等） */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            // 与阅读器一致：等目标书出现（8 秒超时），不依赖尚未定型的 DAO 签名
            val target = try {
                withTimeoutOrNull(8_000) { book.filterNotNull().first() }
            } catch (e: Exception) {
                null
            }
            if (target == null) {
                _toc.value = Toc.Failed
                return@launch
            }
            _toc.value = withContext(Dispatchers.IO) { loadToc(appContext, target) }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch { runCatching { repository.toggleFavorite(bookId) } }
    }

    /** 删除书库记录（原文件保留）；成功后通知 UI 退出 */
    fun deleteBook(onDone: () -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { repository.deleteBook(bookId) }.isSuccess
            if (ok) onDone() else _events.tryEmit("删除失败")
        }
    }
}

/** 远程书 URI（WebDAV / Google Drive）：详情页不做网络 IO */
internal fun isRemoteUri(uri: String): Boolean =
    uri.startsWith("webdav://") || uri.startsWith("gdrive://") || uri.startsWith("http")

/**
 * 读取目录：仅本地书打开数据源（章节在 open 时已建好索引，非 suspend），读完即关；
 * 远程书直接 Hidden；打开失败转 Failed。
 */
internal suspend fun loadToc(context: Context, book: BookEntity): BookDetailViewModel.Toc =
    if (isRemoteUri(book.uri)) {
        BookDetailViewModel.Toc.Hidden
    } else {
        runCatching {
            ArchiveFactory.open(context, book, null).use { source ->
                BookDetailViewModel.Toc.Loaded(source.chapters, source.pageCount)
            }
        }.getOrElse { BookDetailViewModel.Toc.Failed }
    }

/** 当前章节下标：startPage ≤ currentPage 的最后一章；无匹配返回 -1 */
internal fun currentChapterIndex(chapters: List<Chapter>, currentPage: Int): Int =
    chapters.indexOfLast { it.startPage <= currentPage }

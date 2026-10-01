package com.xyreader.reader

import android.app.Application
import android.util.LruCache
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xyreader.archive.NovelPageSource
import com.xyreader.archive.NovelStyle
import com.xyreader.archive.PdfPageSource
import com.xyreader.core.ArchiveFactory
import com.xyreader.core.BookEntity
import com.xyreader.core.BookmarkEntity
import com.xyreader.core.Chapter
import com.xyreader.core.ImageQuality
import com.xyreader.core.NovelFonts
import com.xyreader.core.NovelFontWeight
import com.xyreader.core.PageSource
import com.xyreader.core.ReadBackground
import com.xyreader.core.ReaderPrefs
import com.xyreader.data.AppGraph
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 单页渲染状态：未渲染 / 已就绪 / 失败 */
sealed interface PageUi {
    data object Loading : PageUi
    data class Ready(val bitmap: ImageBitmap) : PageUi
    data object Failed : PageUi
}

/** 阅读器整体阶段 */
enum class ReaderPhase { Loading, Ready, Error }

/** 阅读器整体 UI 状态（不含逐页位图，位图在 [pages] 里） */
data class ReaderState(
    val phase: ReaderPhase = ReaderPhase.Loading,
    /** Pager 首次组合时的起始页（书签跳转优先，其次续读页） */
    val initialPage: Int = 0,
    val pageCount: Int = 0,
    /** 章节结构（目录抽屉用）；无章节结构的格式（PDF/MOBI/扁平包）为空列表 */
    val chapters: List<Chapter> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * 页位图缓存：按字节预算的 LRU。预算取最大堆的 1/6（48–128MB）：
 * 1080p 一页 ARGB 约 10MB，固定 48MB 只装得下 4~5 页，与 ±2 预载互相挤兑，
 * 翻回上一页就要重新渲染，是翻页卡顿的来源之一。
 */
private class PageBitmapCache(
    byteBudget: Int = ((Runtime.getRuntime().maxMemory() / 6).toInt())
        .coerceIn(48 * 1024 * 1024, 128 * 1024 * 1024),
) {
    val lru = object : LruCache<Int, ImageBitmap>(byteBudget) {
        override fun sizeOf(key: Int, value: ImageBitmap): Int =
            (value.width.toLong() * value.height * 4L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
    }
}

/**
 * 阅读器 ViewModel：
 * 打开书（IO）→ 暴露 pageCount/初始页 → 逐页渲染（去重 + LRU 缓存 + 前后各 2 页预载）
 * → 翻页防抖保存进度 → 收藏 / 书签 → 销毁时兜底保存并关闭数据源。
 * 阅读背景色 / 小说字号任一变化（styleKey 变化）→ 防抖 300ms 后关闭旧源重建（保留当前页）。
 */
class ReaderViewModel(
    private val bookId: Long,
    private val initialPage: Int,
    private val startFromBeginning: Boolean = false,
    app: Application,
) : ViewModel() {

    private val appContext = app.applicationContext
    private val repository = AppGraph.libraryRepository(appContext)

    /** 页面数据源，打开后持有，onCleared 关闭 */
    @Volatile
    private var source: PageSource? = null

    /** 每次换源递增，旧渲染任务只可回收自己的任务键，不可发布到新一代状态。 */
    @Volatile
    private var activeGeneration = 0L
    private val generationCounter = AtomicLong()
    private val sourceLifecycleMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前页（即时值，供预载与兜底保存使用） */
    @Volatile
    private var currentPage = 0

    private data class RenderKey(val generation: Long, val page: Int)

    /** 页渲染单队列优先当前页，避免串行图源把目标页排在多个预载之后。 */
    private val queueLock = Any()
    private val queuedPages = mutableListOf<RenderKey>()
    private val inFlight = mutableSetOf<RenderKey>()
    private var renderWorker: Job? = null
    @Volatile
    private var visiblePageIndices: Set<Int> = emptySet()
    private val pageCache = PageBitmapCache()
    private val aspectRatioCache = mutableMapOf<Int, Float>()

    /** 防抖保存进度的任务 */
    private var saveJob: Job? = null

    /** 当前已打开数据源所用的样式键；与最新配置不一致时触发重建 */
    private var openedStyleKey: String? = null

    /** 样式重建防抖任务：背景/字号连续变化时取消重设，只执行最后一次 */
    private var restyleJob: Job? = null

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private val _book = MutableStateFlow<BookEntity?>(null)
    val book: StateFlow<BookEntity?> = _book.asStateFlow()

    /** 阅读配置（翻页模式 / 漫画方向 / 屏幕方向 / 背景色 / 亮度），来自设置页-阅读配置管理 */
    val readerPrefs: StateFlow<ReaderPrefs> = repository.readerPrefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReaderPrefs())

    /** 全部书签（UI 层按当前书 bookId 过滤；仓库保证按创建时间倒序），目录抽屉书签段用 */
    val bookmarks: Flow<List<BookmarkEntity>> = repository.bookmarks

    /** 当前书是否为文字小说（TXT / 文字版 EPUB / MOBI）：决定「复制文字」入口是否出现 */
    private val _isTextNovel = MutableStateFlow(false)
    val isTextNovel: StateFlow<Boolean> = _isTextNovel.asStateFlow()

    // 逐页状态用 SnapshotStateMap 而非整表 StateFlow：组合中对 pages[page] 的读取
    // 按键级订阅，单页渲染只重组对应 item；整表替换会让所有可见页跟着重组，
    // 是快速翻页卡顿的主因。
    private val _pages = mutableStateMapOf<Int, PageUi>()
    val pages: Map<Int, PageUi> = _pages

    private val _pageAspectRatios = mutableStateMapOf<Int, Float>()
    val pageAspectRatios: Map<Int, Float> = _pageAspectRatios

    /** 一次性提示（如“已添加书签”），UI 收集后弹 Snackbar */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** 样式键：小说文字样式与版面参数变化都要重建排版。 */
    private fun styleKey(prefs: ReaderPrefs): String =
        listOf(
            prefs.readBackground.name,
            prefs.novelFontSizeSp.toString(),
            prefs.novelFontFamily.name,
            prefs.novelCustomFont.orEmpty(),
            prefs.novelFontWeight.name,
            prefs.novelLineSpacingMultiplier.toString(),
            prefs.novelMarginTopPx.toString(),
            prefs.novelMarginBottomPx.toString(),
            prefs.novelMarginLeftPx.toString(),
            prefs.novelMarginRightPx.toString(),
            prefs.novelLetterSpacingPx.toString(),
            prefs.novelFirstLineIndent.toString(),
            prefs.novelChapterNewPage.toString(),
        ).joinToString("|")

    /** 背景色 → 文字小说正文字色：深背景配浅灰字，浅背景配深字 */
    private fun ReadBackground.novelTextColorArgb(): Int = when (this) {
        ReadBackground.BLACK -> 0xFFC9CDD6.toInt()
        ReadBackground.DARK_GRAY -> 0xFFC9CDD6.toInt()
        ReadBackground.SEPIA -> 0xFF4A3B2A.toInt()
        ReadBackground.WHITE -> 0xFF333333.toInt()
    }

    /** 按当前配置构造小说排版样式（漫画格式的 PageSource 会忽略该样式） */
    private fun buildNovelStyle(prefs: ReaderPrefs): NovelStyle {
        val metrics = appContext.resources.displayMetrics
        return NovelStyle(
            textColorArgb = prefs.readBackground.novelTextColorArgb(),
            fontSizeSp = prefs.novelFontSizeSp.coerceIn(12f, 36f),
            pageWidthPx = metrics.widthPixels,
            pageHeightPx = metrics.heightPixels,
            fontFamily = prefs.novelFontFamily,
            fontWeight = prefs.novelFontWeight,
            lineSpacingMultiplier = prefs.novelLineSpacingMultiplier.coerceIn(0.8f, 2.5f),
            marginTopPx = prefs.novelMarginTopPx.coerceIn(0f, 96f),
            marginBottomPx = prefs.novelMarginBottomPx.coerceIn(0f, 96f),
            marginLeftPx = prefs.novelMarginLeftPx.coerceIn(0f, 96f),
            marginRightPx = prefs.novelMarginRightPx.coerceIn(0f, 96f),
            letterSpacingPx = prefs.novelLetterSpacingPx.coerceIn(-4f, 12f),
            // 字体解析（内置/导入字体文件的加载）在此一次完成；调用链已在线程
            // IO 上（openSource 契约）；失败由 NovelFonts.resolve 内部回退系统族。
            typeface = NovelFonts.resolve(
                context = appContext,
                family = prefs.novelFontFamily,
                customFont = prefs.novelCustomFont,
                bold = prefs.novelFontWeight == NovelFontWeight.BOLD,
            ),
            firstLineIndent = prefs.novelFirstLineIndent,
            chapterNewPage = prefs.novelChapterNewPage,
        )
    }

    init {
        // 首次等待目标书出现：用 repository.books Flow 过滤 id，
        // 不依赖尚未定型的 ArkDatabase DAO 签名。书不存在时 8 秒超时转错误态。
        viewModelScope.launch {
            val initial = try {
                withTimeoutOrNull(8_000) {
                    repository.books
                        .first { list -> list.any { it.id == bookId } }
                        .firstOrNull { it.id == bookId }
                }
            } catch (e: Exception) {
                null
            }
            if (initial == null) {
                _state.value = ReaderState(
                    phase = ReaderPhase.Error,
                    errorMessage = "找不到这本书或读取失败",
                )
            } else {
                _book.value = initial
                // ArchiveFactory.open 含解压/索引等阻塞操作，放 IO
                sourceLifecycleMutex.withLock {
                    withContext(Dispatchers.IO) {
                        activeGeneration = generationCounter.incrementAndGet()
                        openSource(initial)
                    }
                }
            }
        }
        // 样式重建：背景色 / 字号 / 字体族 / 字重任一变化（styleKey 变化）→ 防抖后重建
        // 重新 openSource（保留当前页）。连续变化（如快速连点字号）由 restyleJob
        // 取消重设实现 latest-win，只重建最后一次。
        viewModelScope.launch {
            var lastImageQuality: ImageQuality? = null
            readerPrefs.collect { prefs ->
                // 图片渲染质量切换（标准 ↔ 高清）：位图规格变化，无需重开数据源——
                // 失效在途结果、清缓存并重渲染当前页即可。
                if (lastImageQuality == null) {
                    lastImageQuality = prefs.imageQuality
                } else if (lastImageQuality != prefs.imageQuality) {
                    lastImageQuality = prefs.imageQuality
                    if (source != null && _state.value.phase == ReaderPhase.Ready) {
                        refreshPagesForImageQuality()
                    }
                }
                val key = styleKey(prefs)
                if (key == openedStyleKey) return@collect
                // 初次打开或样式重排时 source 会暂时为空；openSource 完成后会比较最新偏好。
                if (source == null) return@collect
                // 图片页的背景由 UI 绘制；字体配置不应导致 PDF/漫画重新解包或下载。
                if (source !is NovelPageSource) {
                    if (_state.value.phase == ReaderPhase.Ready) openedStyleKey = key
                    return@collect
                }
                scheduleLatestStyleSync()
            }
        }
        // 持续同步书籍元数据（标题、收藏状态），收藏点击后由 Room Flow 自动刷新
        viewModelScope.launch {
            try {
                repository.books
                    .map { list -> list.firstOrNull { it.id == bookId } }
                    .distinctUntilChanged()
                    .collect { _book.value = it }
            } catch (_: Exception) {
                // 元数据流失败不影响阅读
            }
        }
    }

    /**
     * 打开页面数据源并发布就绪状态；失败转错误态（archive 模块未实现时也会走到这里）。
     * [reopen] = true 表示样式变化触发的重建：起始页沿用上一状态的 [currentPage]，
     * 不再走书签/续读逻辑，阅读进度原地保留。
     */
    private suspend fun replaceSource(book: BookEntity) {
        val oldSource = source
        val oldGeneration = activeGeneration
        val oldPage = currentPage
        val novelAnchor = (oldSource as? NovelPageSource)
            ?.takeIf { it.pageCount > 0 }
            ?.let { runCatching { it.pageStartCharOffset(oldPage.coerceIn(0, it.pageCount - 1)) }.getOrNull() }

        // 先使旧结果失效，再取消并等待旧渲染退出，最后才关闭其数据源。
        activeGeneration = generationCounter.incrementAndGet()
        source = null
        val oldWorker = synchronized(queueLock) {
            queuedPages.removeAll { it.generation == oldGeneration }
            inFlight.removeAll { it.generation == oldGeneration }
            renderWorker.also { renderWorker = null }
        }
        oldWorker?.cancelAndJoin()
        runCatching { oldSource?.close() }
        pageCache.lru.evictAll()
        aspectRatioCache.clear()
        visiblePageIndices = emptySet()
        _pages.clear()
        _pageAspectRatios.clear()
        openSource(book, reopen = true, novelAnchor = novelAnchor, oldPage = oldPage)
    }

    /**
     * 图片渲染质量切换：缓存与在页位图全部按旧规格，需整体失效重渲染。
     * 不重开数据源（打开成本高且无必要）；递增 generation 让在途渲染结果作废。
     */
    private fun refreshPagesForImageQuality() {
        if (source == null) return
        activeGeneration = generationCounter.incrementAndGet()
        pageCache.lru.evictAll()
        synchronized(queueLock) {
            queuedPages.removeAll { it.generation != activeGeneration }
            inFlight.removeAll { it.generation != activeGeneration }
        }
        _pages.clear()
        requestPage(currentPage)
        preloadAround(currentPage)
    }

    private fun openSource(
        book: BookEntity,
        reopen: Boolean = false,
        novelAnchor: Long? = null,
        oldPage: Int = currentPage,
    ) {
        try {
            val prefs = readerPrefs.value
            val src = ArchiveFactory.open(appContext, book, buildNovelStyle(prefs))
            source = src
            _isTextNovel.value = src is NovelPageSource
            val count = src.pageCount
            // 章节结构：打开成功后普通属性读取（实现已在 open 时建好索引，非 suspend）；
            // 兜底异常转空列表，避免个别格式章节解析失败拖垮整本书的打开
            val chapters = runCatching { src.chapters }.getOrDefault(emptyList())
            // 起始页：重建模式保留当前页；首次打开则"从头开始"优先，其次书签跳转 > 0，最后续读 book.currentPage
            val start = (
                if (reopen) {
                    (src as? NovelPageSource)
                        ?.let { novelAnchor?.let(it::pageForCharOffset) }
                        ?: oldPage
                } else {
                    firstOpenStartPage(
                        startFromBeginning = startFromBeginning,
                        initialPage = initialPage,
                        bookCurrentPage = book.currentPage,
                    )
                }
                ).coerceIn(0, (count - 1).coerceAtLeast(0))
            currentPage = start
            // 记录本次打开所用样式键，样式监听据此判断是否需要再次重建
            openedStyleKey = styleKey(prefs)
            // 缓存按页码作键且属于当前 ViewModel：重建时清空，避免跨样式复用旧位图。
            pageCache.lru.evictAll()
            aspectRatioCache.clear()
            visiblePageIndices = emptySet()
            synchronized(queueLock) {
                queuedPages.clear()
                inFlight.clear()
            }
            _pages.clear()
            _pageAspectRatios.clear()
            _state.value = ReaderState(
                phase = ReaderPhase.Ready,
                initialPage = start,
                pageCount = count,
                chapters = chapters,
            )
            preloadAround(start)
            scheduleLatestStyleSync()
        } catch (e: Exception) {
            _isTextNovel.value = false
            _state.value = ReaderState(
                phase = ReaderPhase.Error,
                errorMessage = "打开书籍失败：${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    /** 打开期间若偏好再次变化，完成后按最新样式补一次防抖重排。 */
    private fun scheduleLatestStyleSync() {
        if (source !is NovelPageSource || _state.value.phase != ReaderPhase.Ready) return
        if (styleKey(readerPrefs.value) == openedStyleKey) return
        restyleJob?.cancel()
        restyleJob = viewModelScope.launch {
            delay(300)
            val book = _book.value ?: return@launch
            if (source !is NovelPageSource || styleKey(readerPrefs.value) == openedStyleKey) return@launch
            sourceLifecycleMutex.withLock {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (source is NovelPageSource && styleKey(readerPrefs.value) != openedStyleKey) {
                        replaceSource(book)
                    }
                }
            }
        }
    }

    /** UI 请求渲染一页；请求进入有界优先队列，避免滚动过快时积压整段预载。 */
    fun requestPage(page: Int) {
        val src = source ?: return
        val count = _state.value.pageCount
        if (count <= 0 || page !in 0 until count || !isNearCurrent(page)) return
        val generation = activeGeneration
        pageCache.lru.get(page)?.let { bitmap ->
            publishReady(page, bitmap, generation)
            return
        }
        synchronized(queueLock) {
            val key = RenderKey(generation, page)
            if (!inFlight.add(key)) return
            queuedPages.add(key)
            sortQueuedPagesLocked(currentPage)
            publishLoading(page, generation)
            ensureRenderWorkerLocked()
        }
    }

    private fun sortQueuedPagesLocked(center: Int) {
        queuedPages.sortBy { key ->
            val distance = key.page - center
            when {
                distance == 0 -> 0
                distance > 0 -> distance * 2 - 1 // +1, +2, ...
                else -> -distance * 2 // -1, -2, ...
            }
        }
    }

    /** Caller holds queueLock. The single worker preserves center, +1, -1, +2, -2 priority. */
    private fun ensureRenderWorkerLocked() {
        if (renderWorker?.isActive == true) return
        lateinit var worker: Job
        worker = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    val key = synchronized(queueLock) {
                        queuedPages.firstOrNull()?.also { queuedPages.removeAt(0) }
                    } ?: break
                    try {
                        if (key.generation == activeGeneration) renderIfNeeded(key)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 单页失败由 renderIfNeeded 发布；其它异常不应终止后续页。
                    } finally {
                        synchronized(queueLock) { inFlight.remove(key) }
                    }
                }
            } finally {
                synchronized(queueLock) {
                    // 只允许当前 worker 清理自己，避免空队列窗口覆盖刚启动的新 worker。
                    if (renderWorker === worker) {
                        renderWorker = null
                        if (queuedPages.isNotEmpty()) ensureRenderWorkerLocked()
                    }
                }
            }
        }
        renderWorker = worker
        worker.start()
    }

    /** 单 worker 串行渲染；每个结果先校验 generation，旧源无法污染新页状态。 */
    private suspend fun renderIfNeeded(key: RenderKey) {
        val src = source ?: return
        val page = key.page
        if (key.generation != activeGeneration || page !in 0 until src.pageCount) return
        pageCache.lru.get(page)?.let { bitmap ->
            publishReady(page, bitmap, key.generation)
            return
        }
        try {
            val knownAspect = aspectRatioCache[page]
                ?: try {
                    src.pageAspectRatio(page)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            knownAspect?.takeIf { it.isFinite() && it > 0f }
                ?.let { publishAspectRatio(page, it, key.generation) }

            val bitmap = withContext(Dispatchers.IO) { applyImageQuality(src, src.renderPage(page)) }
            if (src !== source || key.generation != activeGeneration) return
            pageCache.lru.put(page, bitmap)
            val renderedAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
            publishAspectRatio(page, renderedAspect, key.generation)
            publishReady(page, bitmap, key.generation)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (src === source && key.generation == activeGeneration && isNearCurrent(page)) {
                _pages[page] = PageUi.Failed
            }
        }
    }

    private fun isNearCurrent(page: Int): Boolean =
        page in visiblePageIndices || kotlin.math.abs(page - currentPage) <= PAGE_UI_RADIUS

    /** 高清档：位图页在缩小显示前先做多级高质量预缩（见 ImageDownscale）；失败回退原图。 */
    private fun applyImageQuality(src: PageSource, bitmap: ImageBitmap): ImageBitmap {
        // PDF 渲染尺寸已按屏幕适配（PdfRenderMath）；文字页（NovelPageSource）天然贴合屏幕——
        // 这两类再走预缩只会无谓损失分辨率 / 浪费 CPU，直接跳过。
        if (src is PdfPageSource || src is NovelPageSource) return bitmap
        if (readerPrefs.value.imageQuality != ImageQuality.HIGH) return bitmap
        val android = bitmap.asAndroidBitmap()
        val target = ImageDownscale.targetSize(android.width, android.height, screenShortSidePx())
            ?: return bitmap
        val scaled = ImageDownscale.downscale(android, target) ?: return bitmap
        // 原图仅此处持有（尚未发布/入缓存），显式回收避免大位图堆积等 GC
        if (scaled !== android) android.recycle()
        return scaled.asImageBitmap()
    }

    /** 屏幕短边（取宽高较小者：竖屏=屏宽、横屏=屏高；旋转/窗口变化时动态读取） */
    private fun screenShortSidePx(): Int {
        val dm = appContext.resources.displayMetrics
        return minOf(dm.widthPixels, dm.heightPixels)
    }

    private fun publishLoading(page: Int, generation: Long) {
        if (generation != activeGeneration || !isNearCurrent(page)) return
        if (page !in _pages) _pages[page] = PageUi.Loading
    }

    private fun publishReady(page: Int, bitmap: ImageBitmap, generation: Long) {
        if (generation != activeGeneration || !isNearCurrent(page)) return
        prunePages()
        _pages[page] = PageUi.Ready(bitmap)
    }

    private fun publishAspectRatio(page: Int, ratio: Float, generation: Long) {
        if (generation != activeGeneration || ratio !in 0.01f..100f) return
        if (aspectRatioCache.putIfAbsent(page, ratio) == null) {
            _pageAspectRatios[page] = ratio
        }
    }

    /** 清理视口外的逐页状态（按当前页 ± PAGE_UI_RADIUS 与可见页保留）。 */
    private fun prunePages() {
        val iterator = _pages.entries.iterator()
        while (iterator.hasNext()) {
            if (!isNearCurrent(iterator.next().key)) iterator.remove()
        }
    }

    /** 以 [center] 为中心的窗口清理，用于翻页/预载路径。 */
    private fun pruneAround(center: Int) {
        val iterator = _pages.entries.iterator()
        while (iterator.hasNext()) {
            if (kotlin.math.abs(iterator.next().key - center) > PAGE_UI_RADIUS) iterator.remove()
        }
    }

    /** 预载顺序按可见目标优先；快速滚动会丢弃尚未开始且已远离视口的旧请求。 */
    private fun preloadAround(center: Int) {
        val count = _state.value.pageCount
        val generation = activeGeneration
        if (count <= 0 || source == null) return
        val ordered = listOf(center, center + 1, center - 1, center + 2, center - 2)
            .filter { it in 0 until count }
        val keep = ordered.toSet()
        synchronized(queueLock) {
            val removed = queuedPages.filter { it.generation == generation && it.page !in keep }
            queuedPages.removeAll(removed.toSet())
            inFlight.removeAll(removed.toSet())
            pruneAround(center)
            ordered.forEach { page ->
                if (pageCache.lru.get(page) != null) return@forEach
                val key = RenderKey(generation, page)
                if (inFlight.add(key)) {
                    queuedPages.add(key)
                    publishLoading(page, generation)
                }
            }
            sortQueuedPagesLocked(center)
            ensureRenderWorkerLocked()
        }
    }

    /** 连续列表上报真实可见项；短页一次可见很多页，全部列入渲染范围并保留 Ready 状态。 */
    fun onVisiblePagesChanged(pages: List<Int>) {
        val valid = pages.filter { it in 0 until _state.value.pageCount }.toSet()
        visiblePageIndices = valid
        prunePages()
        valid.forEach(::requestPage)
    }

    /**
     * 当前页变化（由 Screen 的 pager snapshotFlow 回报）：
     * 立即预载前后各 2 页；进度防抖 500ms 后保存，快速连翻只落库最后一页。
     */
    fun onPageChanged(page: Int) {
        currentPage = page
        pruneAround(page)
        preloadAround(page)
        val count = _state.value.pageCount
        if (count <= 0) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            runCatching { repository.saveProgress(bookId, page, count) }
        }
    }

    /** 收藏 / 取消收藏（结果经 books Flow 刷新回 UI） */
    fun toggleFavorite() {
        viewModelScope.launch {
            runCatching { repository.toggleFavorite(bookId) }
        }
    }

    /** 添加书签并发一次性提示 */
    fun addBookmark(page: Int) {
        viewModelScope.launch {
            val result = runCatching { repository.addBookmark(bookId, page) }
            _events.emit(if (result.isSuccess) "已添加书签" else "添加书签失败")
        }
    }

    /** 删除书签（目录抽屉书签段行尾删除按钮用） */
    fun removeBookmark(id: Long) {
        viewModelScope.launch {
            runCatching { repository.removeBookmark(id) }
        }
    }

    /**
     * 取某页文字（仅文字小说）：供「复制文字」弹层使用；
     * 非文字源 / 越界 / 失败一律返回 null，由 UI 降级提示。
     */
    suspend fun pageText(page: Int): String? {
        val src = source as? NovelPageSource ?: return null
        if (page !in 0 until src.pageCount) return null
        return runCatching { src.pageText(page) }.getOrNull()
    }

    /** 更新阅读配置（背景色 / 亮度等）：基于当前值做变换后落库，DataStore 变更自动回流到 UI */
    fun updatePrefs(transform: (ReaderPrefs) -> ReaderPrefs) {
        viewModelScope.launch {
            runCatching { repository.setReaderPrefs(transform(readerPrefs.value)) }
        }
    }

    override fun onCleared() {
        // ViewModel 销毁可能发生在主线程；进度落库与关源转到独立 IO scope，并等待渲染退出。
        cleanupScope.launch {
            sourceLifecycleMutex.withLock {
                val oldSource = source
                val oldGeneration = activeGeneration
                source = null
                activeGeneration = generationCounter.incrementAndGet()
                val oldWorker = synchronized(queueLock) {
                    queuedPages.clear()
                    inFlight.removeAll { it.generation == oldGeneration }
                    renderWorker.also { renderWorker = null }
                }
                oldWorker?.cancelAndJoin()
                runCatching { oldSource?.close() }
            }
            val count = _state.value.pageCount
            if (count > 0) runCatching { repository.saveProgress(bookId, currentPage, count) }
            cleanupScope.cancel()
        }
    }

    private companion object {
        const val PAGE_UI_RADIUS = 3
    }
}

/**
 * 首次打开时的起始页选择：
 * - "从头开始"（详情页按钮 / restart 路由参数）优先，无视已存进度；
 * - 显式跳转页（书签）次之；
 * - 否则续读已存进度。
 * 越界收敛仍由调用侧统一 coerceIn 处理。
 */
internal fun firstOpenStartPage(
    startFromBeginning: Boolean,
    initialPage: Int,
    bookCurrentPage: Int,
): Int = when {
    startFromBeginning -> 0
    initialPage > 0 -> initialPage
    else -> bookCurrentPage
}

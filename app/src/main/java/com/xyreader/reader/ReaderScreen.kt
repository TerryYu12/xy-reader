package com.xyreader.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List as ListIcon
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xyreader.core.BookmarkEntity
import com.xyreader.core.Chapter
import com.xyreader.core.ImageScale
import com.xyreader.core.MangaDirection
import com.xyreader.core.NovelFontFamily
import com.xyreader.core.NovelFontSize
import com.xyreader.core.NovelFontWeight
import com.xyreader.core.PageMode
import com.xyreader.core.ReadBackground
import com.xyreader.core.ReaderPrefs
import com.xyreader.core.ScreenOrientation
import com.xyreader.ui.LocalAutoRotate
import com.xyreader.ui.LocalSetAutoRotate
import com.xyreader.ui.formatDate
import com.xyreader.ui.CapsuleTab
import com.xyreader.ui.NovelSpacingControls
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

private const val BAR_ANIM_MS = 250

/** 栏分割线颜色取色板 --line（outlineVariant）；栏底色取 --card（surfaceContainer），浅色主题自动切换 */

/** 顶栏底部分割线：仅画一条 1dp 底边（可左右内缩，避免圆角外溢出线） */
private fun Modifier.bottomHairline(color: Color, inset: Dp = 0.dp): Modifier = drawWithContent {
    drawContent()
    val h = 1.dp.toPx()
    val x = inset.toPx()
    drawRect(color, topLeft = Offset(x, size.height - h), size = Size(size.width - 2 * x, h))
}

/** 底栏顶部分割线：仅画一条 1dp 顶边 */
private fun Modifier.topHairline(color: Color, inset: Dp = 0.dp): Modifier = drawWithContent {
    drawContent()
    val h = 1.dp.toPx()
    val x = inset.toPx()
    drawRect(color, topLeft = Offset(x, 0f), size = Size(size.width - 2 * x, h))
}

/** 页内双击放大的目标倍数 */
private const val PAGE_ZOOM = 2.5f

/**
 * 直接定位到目标页，不播放滚动动画（动画会快速滑过中间所有页，触发无谓的渲染与预载）。
 *
 * 跳转一律走这里：进度条松手、目录/书签、上一章/下一章。仅「点击两侧翻一页（±1）」才用带动画的
 * `turnTo`，以保留相邻翻页的视觉反馈。
 * 目标页收敛到 `[0, pageCount - 1]`；上下模式滚动 [listState]，其余模式切换 [pagerState]。
 */
internal suspend fun jumpToPage(
    upDown: Boolean,
    pagerState: PagerState,
    listState: LazyListState,
    pageCount: Int,
    target: Int,
) {
    val safeTarget = target.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    if (upDown) listState.scrollToItem(safeTarget) else pagerState.scrollToPage(safeTarget)
}

/** 沿 ContextWrapper 链找宿主 Activity；拿不到时返回 null（调用方判空降级） */
private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** ReadBackground.argb(Long) 转 Compose Color：0xFFxxxxxx 为负数 Int，Color(Int) 按 ARGB 解析 */
private fun ReadBackground.toComposeColor(): Color = Color(argb.toInt())

/**
 * 阅读器界面：黑底全屏 Pager + 三分点击（左翻上页 / 中工具栏 / 右翻下页，可配置关闭点击翻页）
 * + 双击页内放大（可配置）+ 顶部（返回 / 目录 / 书名 / 书签 / 收藏 / 锁定手势）与底部
 * （滑条跳页 + 亮度行 + 六按钮：上一章 / 亮度 / 设置 / 添加书签 / 目录 / 下一章）圆角浮层工具栏
 * + 手势锁（防误触：锁定只拦点击，滑动照常）——锁定入口在顶部工具栏、随菜单一起出现；
 *   锁定后点屏幕中央呼出解锁钮，点解锁钮解锁并呼出菜单
 * + 全屏目录弹层（目录 / 书签双 tab，当前章高亮）+ 阅读设置快捷面板（翻页 / 背景 / 缩放 / 小说字号 / 常亮）
 * + 阅读背景色与亮度即时生效 + 屏幕常亮。
 * 进入隐藏系统状态栏、应用亮度配置，离开全部恢复。
 */
@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    initialPage: Int = 0,
    startFromBeginning: Boolean = false,
) {
    val viewModel: ReaderViewModel = viewModel(key = "reader_$bookId") {
        // CreationExtras 里取 Application；非标准宿主（如预览）拿不到，会直接抛错——阅读器不支持预览
        ReaderViewModel(
            bookId = bookId,
            initialPage = initialPage,
            startFromBeginning = startFromBeginning,
            app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!,
        )
    }
    val state by viewModel.state.collectAsState()
    val book by viewModel.book.collectAsState()
    // 逐页状态是 SnapshotStateMap：直接读取按键订阅，不能用 collect 整表收集
    val pages = viewModel.pages
    val pageAspectRatios = viewModel.pageAspectRatios
    // 书签全量流（UI 按 bookId 过滤本书）；阅读配置提升到顶层供亮度作用与各分支共用
    val bookmarks by viewModel.bookmarks.collectAsState(initial = emptyList())
    val prefs by viewModel.readerPrefs.collectAsState()
    // 全局「自动旋屏」（设置 → 显示）：关闭时本页也不跟随重力，锁定竖屏
    val autoRotate = LocalAutoRotate.current

    // —— 状态栏：进入隐藏，离开恢复；部分环境拿不到 Activity，整体 try-catch 静默降级 ——
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(context, view) {
        val controller: WindowInsetsControllerCompat? = try {
            context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
            }
        } catch (_: Exception) {
            null
        }
        try {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.statusBars())
        } catch (_: Exception) {
        }
        onDispose {
            try {
                controller?.show(WindowInsetsCompat.Type.statusBars())
            } catch (_: Exception) {
            }
        }
    }

    // —— 亮度：配置值作用于本 App 窗口（0.01-1.0）；null（跟随系统）设 -1 即系统默认。
    //    离开阅读器或值变化时先恢复系统亮度再按新值应用，避免亮度"泄漏"到其他页面 ——
    DisposableEffect(prefs.brightness) {
        try {
            context.findActivity()?.window?.let { window ->
                window.attributes = window.attributes.apply {
                    screenBrightness = prefs.brightness?.coerceIn(0.01f, 1f)
                        ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        } catch (_: Exception) {
        }
        onDispose {
            try {
                context.findActivity()?.window?.let { window ->
                    window.attributes = window.attributes.apply {
                        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    // —— 屏幕常亮：按配置给宿主窗口加/清 FLAG_KEEP_SCREEN_ON；离开阅读器或关闭开关时清掉，
    //    拿不到 Activity 的环境 try-catch 静默降级 ——
    DisposableEffect(prefs.keepScreenOn) {
        try {
            if (prefs.keepScreenOn) {
                context.findActivity()?.window?.addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                )
            }
        } catch (_: Exception) {
        }
        onDispose {
            try {
                context.findActivity()?.window?.clearFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                )
            } catch (_: Exception) {
            }
        }
    }

    // —— 一次性提示（书签等）：收集 ViewModel 事件弹 Snackbar ——
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (state.phase) {
            ReaderPhase.Loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            ReaderPhase.Error -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = state.errorMessage ?: "打开书籍失败",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onBack) {
                    Text("返回", color = MaterialTheme.colorScheme.secondary)
                }
            }

            ReaderPhase.Ready -> {
                // 屏幕方向：进阅读器应用配置，离开恢复「自动旋屏」全局策略。
                // 「跟随系统」这一档服从全局自动旋屏开关（开 = 重力感应自由旋转，关 = 锁定竖屏）；
                // 显式「锁定竖屏 / 锁定横屏」优先级更高，不受全局开关影响。
                DisposableEffect(prefs.screenOrientation, autoRotate) {
                    val activity = context.findActivity()
                    try {
                        activity?.requestedOrientation = when (prefs.screenOrientation) {
                            ScreenOrientation.SYSTEM -> if (autoRotate) {
                                ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                            ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    } catch (_: Exception) {
                    }
                    onDispose {
                        try {
                            // 离开阅读器：回到全局策略（而非一律 UNSPECIFIED，否则会冲掉「锁定竖屏」）
                            activity?.requestedOrientation = if (autoRotate) {
                                ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                val upDown = prefs.pageMode == PageMode.UP_DOWN
                val pagerState = rememberPagerState(
                    initialPage = state.initialPage,
                    pageCount = { state.pageCount },
                )
                val verticalListState = rememberLazyListState(
                    initialFirstVisibleItemIndex = state.initialPage.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0)),
                )
                // 旋转/重建后仍可恢复：三个状态跨 Activity 重建存活，
                // 使“源未变”判断成立，避免滚动位置回退并污染落库进度
                var currentReadingPage by rememberSaveable { mutableIntStateOf(state.initialPage) }
                var lastAppliedInitialPage by rememberSaveable { mutableIntStateOf(-1) }
                var lastAppliedPageCount by rememberSaveable { mutableIntStateOf(-1) }
                LaunchedEffect(state.initialPage, state.pageCount, upDown) {
                    // 分页重建后用 ViewModel 的字符锚点结果重定位；仅切模式时沿用当前页。
                    val sourcePositionChanged =
                        state.initialPage != lastAppliedInitialPage || state.pageCount != lastAppliedPageCount
                    val target = (if (sourcePositionChanged) state.initialPage else currentReadingPage)
                        .coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
                    currentReadingPage = target
                    if (upDown) verticalListState.scrollToItem(target) else pagerState.scrollToPage(target)
                    lastAppliedInitialPage = state.initialPage
                    lastAppliedPageCount = state.pageCount
                }
                LaunchedEffect(pagerState, verticalListState, upDown) {
                    snapshotFlow {
                        if (upDown) verticalListState.firstVisibleItemIndex else pagerState.currentPage
                    }.distinctUntilChanged().collect { page ->
                        val safePage = page.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
                        currentReadingPage = safePage
                        viewModel.onPageChanged(safePage)
                    }
                }
                LaunchedEffect(verticalListState, pagerState, upDown, state.pageCount) {
                    if (upDown) {
                        snapshotFlow { verticalListState.layoutInfo.visibleItemsInfo.map { it.index } }
                            .distinctUntilChanged()
                            .collect(viewModel::onVisiblePagesChanged)
                    } else {
                        snapshotFlow { listOf(pagerState.currentPage) }
                            .distinctUntilChanged()
                            .collect(viewModel::onVisiblePagesChanged)
                    }
                }
                val scope = rememberCoroutineScope()
                var showDirectory by remember { mutableStateOf(false) }
                // 弹层 tab 记忆：关闭再打开仍停留在上次的 tab
                var directoryTab by remember { mutableIntStateOf(0) }
                // 目录/书签跳页：立即收起弹层，同时直接定位到目标页（不播动画；越界收敛防脏数据）
                val jumpTo: (Int) -> Unit = { target ->
                    showDirectory = false
                    scope.launch {
                        jumpToPage(upDown, pagerState, verticalListState, state.pageCount, target)
                    }
                }
                ReaderPagerArea(
                    pagerState = pagerState,
                    verticalListState = verticalListState,
                    pageCount = state.pageCount,
                    title = book?.title.orEmpty(),
                    favorite = book?.isFavorite == true,
                    pages = pages,
                    pageAspectRatios = pageAspectRatios,
                    prefs = prefs,
                    chapters = state.chapters,
                    viewModel = viewModel,
                    onOpenDirectory = { showDirectory = true },
                    onBack = onBack,
                )
                // 全屏目录弹层（替换早期版本的抽屉）
                if (showDirectory) {
                    DirectoryDialog(
                        chapters = state.chapters,
                        bookmarks = bookmarks.filter { it.bookId == bookId },
                        currentPage = currentReadingPage,
                        tab = directoryTab,
                        onTabChange = { directoryTab = it },
                        onJumpTo = jumpTo,
                        onRemoveBookmark = viewModel::removeBookmark,
                        onDismiss = { showDirectory = false },
                    )
                }
            }
        }

        // Snackbar 置于底部，避开导航栏与底部工具栏
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(WindowInsets.navigationBars.asPaddingValues())
                .padding(bottom = 88.dp),
        )
    }
}

/** 就绪后的阅读主体：Pager（左右 / 上下 / 日漫右开本）+ 上下工具栏浮层 + 手势锁 + 阅读背景色 */
@Composable
private fun ReaderPagerArea(
    pagerState: PagerState,
    verticalListState: LazyListState,
    pageCount: Int,
    title: String,
    favorite: Boolean,
    pages: Map<Int, PageUi>,
    pageAspectRatios: Map<Int, Float>,
    prefs: ReaderPrefs,
    chapters: List<Chapter>,
    viewModel: ReaderViewModel,
    onOpenDirectory: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val upDown = prefs.pageMode == PageMode.UP_DOWN
    val currentPage = if (upDown) verticalListState.firstVisibleItemIndex else pagerState.currentPage
    // 页面与页边距（ContentScale.Fit 留白）统一使用配置的阅读背景色
    val bgColor = prefs.readBackground.toComposeColor()

    var toolbarVisible by remember { mutableStateOf(false) }
    var sliderActive by remember { mutableStateOf(false) }
    var sliderPage by remember { mutableIntStateOf(0) }
    // 手势锁（会话内记忆）：防误触——锁定期间只拦点击（不弹菜单、不点按翻页、不双击缩放），
    // 滑动翻页照常；无遮罩不变暗。锁定入口在顶部工具栏（随菜单一起出现）；
    // 解锁钮常驻隐藏，锁定后点击屏幕中央呼出。
    var locked by remember { mutableStateOf(false) }
    var lockBadgeVisible by remember { mutableStateOf(false) }
    // 阅读设置快捷面板开关
    var showSettings by remember { mutableStateOf(false) }
    // 亮度独立浮动弹层开关（点底栏「亮度」按钮切换；底部居中的浮动小卡片，无遮罩变暗）
    var showBrightness by remember { mutableStateOf(false) }
    // 页内缩放状态：双指捏合与双击放大共用。图片书（漫画）横向模式整本共用这一份
    // scale/offset——翻页不清零，放大原点整本一致；文字小说由下面的 effect 翻页复位
    val pageZoom = remember { PageZoomState() }
    // 连续列表（上下模式）整列同步缩放状态：与页内缩放（pageZoom）独立，
    // 缩放时整列等比放大、页与页仍首尾相接；纵向滚动继续由列表承担
    val columnZoomState = remember { PageZoomState() }
    // 「复制文字」弹层开关（仅文字小说）
    var showCopyDialog by remember { mutableStateOf(false) }
    val isTextNovel by viewModel.isTextNovel.collectAsState()

    /** 相邻翻页（点击两侧 ±1 页）：保留滚动动画。跨多页的跳转请用 [jumpTo]。 */
    fun turnTo(target: Int) {
        if (target in 0 until pageCount) {
            scope.launch {
                if (upDown) verticalListState.animateScrollToItem(target)
                else pagerState.animateScrollToPage(target)
            }
        }
    }

    /** 进度条松手、上一章/下一章：直接定位，不播动画、不滑过中间页 */
    fun jumpTo(target: Int) {
        scope.launch { jumpToPage(upDown, pagerState, verticalListState, pageCount, target) }
    }

    /** 双击页内放大：1x ↔ 2.5x 平滑动画；缩回 1x 时清零平移 */
    fun togglePageZoom() {
        scope.launch {
            val from = pageZoom.scale
            val to = if (from > 1f) 1f else PAGE_ZOOM
            animate(from, to, animationSpec = tween(200)) { value, _ -> pageZoom.set(value) }
            if (to == 1f) pageZoom.reset()
        }
    }

    /** 顶部工具栏锁定入口：收起菜单、收起解锁钮并进入锁定态 */
    fun lockGesture() {
        toolbarVisible = false
        lockBadgeVisible = false
        locked = true
    }

    // 锁定期间返回键不退出（防误触退出）；解锁后返回键恢复常规行为
    BackHandler(enabled = locked) {
    }

    // 翻页时是否复位页内缩放：仅文字小说复位；图片书（漫画）整本统一缩放，翻页保持。
    // 该 effect 不随 isTextNovel 重启，靠 rememberUpdatedState 读最新值（否则捕获的是
    // 建 effect 那一次的旧值）；进度和预载由上层按当前阅读模式同步。
    val textNovelNow = rememberUpdatedState(isTextNovel)
    LaunchedEffect(pagerState, verticalListState, upDown) {
        snapshotFlow { if (upDown) verticalListState.firstVisibleItemIndex else pagerState.currentPage }
            .distinctUntilChanged().collect {
            if (textNovelNow.value) pageZoom.reset()
        }
    }

    val rtl = !upDown && prefs.mangaDirection == MangaDirection.RTL

    Box(Modifier.fillMaxSize()) {
        if (upDown) {
            BoxWithConstraints(Modifier.fillMaxSize().background(bgColor)) {
                val fallbackPageHeight = maxHeight
                val viewportWidthPx = constraints.maxWidth
                val fallbackPageHeightPx = with(LocalDensity.current) { fallbackPageHeight.toPx() }
                // 以组合值读取缩放：高度随其重算（页页无缝衔接的根）
                val listScale = columnZoomState.scale

                // 未缩放时每一项的高度（px）：页面按宽高比撑满宽度，缺比例用整屏高兜底
                fun pageBaseHeight(page: Int): Float {
                    val aspect = pageAspectRatios[page]
                        ?: (pages[page] as? PageUi.Ready)?.bitmap
                            ?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }
                    return if (aspect != null && aspect.isFinite() && aspect > 0f) {
                        viewportWidthPx.toFloat() / aspect
                    } else {
                        fallbackPageHeightPx
                    }
                }

                fun baseHeights(): List<Float> = List(pageCount) { pageBaseHeight(it) }

                /** 整列缩放到 [to]，并保持视口 [focalY] 处内容不动（锚定滚动、页页相接） */
                fun zoomAnchored(to: Float, focalY: Float) {
                    val from = columnZoomState.scale
                    if (to == from) return
                    val heights = baseHeights()
                    val pixel = ContinuousZoomMath.topPixel(
                        heights, verticalListState.firstVisibleItemIndex, from,
                    ) + verticalListState.firstVisibleItemScrollOffset
                    if (to <= 1f) {
                        columnZoomState.reset()
                    } else {
                        columnZoomState.setScalePreservePan(to)
                        columnZoomState.clampPanToViewport(viewportWidthPx)
                    }
                    // 与双击动画共用同一套锚定数学：焦点处文档点缩放前后停在原处
                    val (index, offsetInItem) =
                        ContinuousZoomMath.anchorForScale(heights, pixel, focalY, from, to)
                    verticalListState.requestScrollToItem(index, offsetInItem.toInt())
                }

                LazyColumn(
                    state = verticalListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .pointerInput(pageCount, prefs.doubleTapZoom, locked) {
                            detectTapGestures(
                                onDoubleTap = { offset ->
                                    if (!locked && prefs.doubleTapZoom) {
                                        val from = columnZoomState.scale
                                        val to = if (from > 1f) 1f else PAGE_ZOOM
                                        scope.launch {
                                            val heights = baseHeights()
                                            // 动画起点：双击那一刻视口顶部的绝对像素（scale = from）
                                            val pixel = ContinuousZoomMath.topPixel(
                                                heights, verticalListState.firstVisibleItemIndex, from,
                                            ) + verticalListState.firstVisibleItemScrollOffset
                                            animate(from, to, animationSpec = tween(200)) { value, _ ->
                                                columnZoomState.setScalePreservePan(value)
                                                // 逐帧锚定：每帧先写缩放，再按当前 scale 重算落点并滚动，
                                                // 焦点处文档点全程停在双击位置（连续页不漂移、结尾不瞬跳）
                                                val (index, offsetInItem) =
                                                    ContinuousZoomMath.anchorForScale(
                                                        heights, pixel, offset.y, from, value,
                                                    )
                                                verticalListState.requestScrollToItem(
                                                    index, offsetInItem.toInt(),
                                                )
                                            }
                                            if (to <= 1f) {
                                                columnZoomState.reset()
                                            } else {
                                                columnZoomState.clampPanToViewport(viewportWidthPx)
                                            }
                                        }
                                    }
                                },
                                onTap = {
                                    // 锁定中：竖排无分区，点击任意处呼出/收起解锁钮；未锁定时呼出工具栏
                                    if (locked) lockBadgeVisible = !lockBadgeVisible
                                    else toolbarVisible = !toolbarVisible
                                },
                            )
                        }
                        // 连续列表同步缩放：双指捏合整列等比放大（页与页始终衔接）；
                        // 放大后水平拖动平移内容、纵向拖动仍交给列表滚动
                        .columnZoom(
                            onZoom = { zoomChange, centroid ->
                                zoomAnchored(
                                    (columnZoomState.scale * zoomChange).coerceIn(1f, PAGE_MAX_ZOOM),
                                    centroid.y,
                                )
                            },
                            onPanX = { dx -> columnZoomState.panByX(dx, viewportWidthPx) },
                            isZoomed = { columnZoomState.scale > 1f },
                        ),
                ) {
                    items(count = pageCount, key = { it }) { page ->
                        val bitmap = (pages[page] as? PageUi.Ready)?.bitmap
                        val aspect = pageAspectRatios[page]
                            ?: bitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }
                        val pageModifier = Modifier.fillMaxWidth().then(
                            if (aspect != null && aspect.isFinite() && aspect > 0f) {
                                // 高度 = 未缩放高度 × 缩放倍数：整列等比放大且页页相接、不重叠
                                Modifier.aspectRatio(aspect / listScale)
                            } else {
                                Modifier.height(fallbackPageHeight * listScale)
                            },
                        )
                        ReaderPage(
                            page = page,
                            ui = pages[page],
                            bgColor = bgColor,
                            zoomState = null,
                            imageScale = prefs.imageScale,
                            onNeedRender = viewModel::requestPage,
                            modifier = pageModifier,
                            panX = { columnZoomState.offset.x },
                            renderScale = { columnZoomState.scale },
                        )
                    }
                }
            }
        } else {
            // 左右翻页：RTL（日漫右开本）时镜像布局方向，pager 自动从右往左翻
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bgColor)
                        // 三分点击：左 1/3 上一页，右 1/3 下一页，中间切换工具栏；
                        // RTL 时镜像（左 1/3 下一页、右 1/3 上一页）。
                        // tapTurnPage 关闭时左右分区与中间一致，只呼出/隐藏工具栏。
                        // detectTapGestures 不消费移动事件，与 Pager 的拖拽翻页互不干扰。
                        .pointerInput(pageCount, rtl, prefs.tapTurnPage, prefs.doubleTapZoom, locked) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (!locked && prefs.doubleTapZoom) togglePageZoom()
                                },
                                onTap = { offset ->
                                    val w = size.width
                                    when {
                                        // 锁定中：只认屏幕中央的点击（呼出/收起解锁钮），
                                        // 左右分区静默，防误触翻页
                                        locked ->
                                            if (offset.x >= w / 3f && offset.x <= w * 2f / 3f) {
                                                lockBadgeVisible = !lockBadgeVisible
                                            }
                                        offset.x < w / 3f ->
                                            if (prefs.tapTurnPage) {
                                                turnTo(pagerState.targetPage + if (rtl) 1 else -1)
                                            } else {
                                                toolbarVisible = !toolbarVisible
                                            }
                                        offset.x > w * 2f / 3f ->
                                            if (prefs.tapTurnPage) {
                                                turnTo(pagerState.targetPage + if (rtl) -1 else 1)
                                            } else {
                                                toolbarVisible = !toolbarVisible
                                            }
                                        else -> toolbarVisible = !toolbarVisible
                                    }
                                },
                            )
                        },
                    pageSpacing = 0.dp, // 页与页零间距
                    // 锁定不影响翻页（防误触只拦点击）；缩放中（scale > 1）把拖动让给图片平移
                    userScrollEnabled = pageZoom.scale <= 1f,
                ) { page ->
                    ReaderPage(
                        page = page,
                        ui = pages[page],
                        bgColor = bgColor,
                        zoomState = pageZoom,
                        imageScale = prefs.imageScale,
                        onNeedRender = viewModel::requestPage,
                    )
                }
            }
        }

        // —— 顶部工具栏：返回 / 目录 / 书名 / 书签 / 收藏 / 锁定（全宽贴顶，底边 1dp 分割线）——
        AnimatedVisibility(
            visible = toolbarVisible && !locked,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(BAR_ANIM_MS)) + slideInVertically(tween(BAR_ANIM_MS)) { -it },
            exit = fadeOut(tween(BAR_ANIM_MS)) + slideOutVertically(tween(BAR_ANIM_MS)) { -it },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .bottomHairline(MaterialTheme.colorScheme.outlineVariant)
                    .heightIn(min = 54.dp)
                    .padding(WindowInsets.statusBars.asPaddingValues())
                    .padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenDirectory) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ListIcon,
                        contentDescription = "目录",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { viewModel.addBookmark(currentPage) }) {
                    Icon(
                        imageVector = Icons.Outlined.BookmarkAdd,
                        contentDescription = "添加书签",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = viewModel::toggleFavorite) {
                    if (favorite) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = "取消收藏",
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.FavoriteBorder,
                            contentDescription = "加入收藏",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 手势锁入口（防误触）：随菜单一起出现/消失，点击即锁定并收起菜单
                IconButton(onClick = { lockGesture() }) {
                    Icon(
                        imageVector = Icons.Outlined.LockOpen,
                        contentDescription = "锁定手势（防误触）",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // —— 底部工具栏：滑条跳页 + 亮度行 + 六个等宽功能按钮 ——
        AnimatedVisibility(
            visible = toolbarVisible && !locked,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(BAR_ANIM_MS)) + slideInVertically(tween(BAR_ANIM_MS)) { it },
            exit = fadeOut(tween(BAR_ANIM_MS)) + slideOutVertically(tween(BAR_ANIM_MS)) { it },
        ) {
            val last = (pageCount - 1).coerceAtLeast(0)
            val shownPage = (if (sliderActive) sliderPage else currentPage)
                .coerceIn(0, last)

            // —— 章节跳转目标推导：基于 chapters 与当前页；chapters 为空时两钮禁用 ——
            val current = currentPage
            val currentIdx = chapters.indexOfFirst { current in it.startPage..it.endPageInclusive }
            val atChapterStart = currentIdx >= 0 && current == chapters[currentIdx].startPage
            // 上一章：当前页恰为某章 startPage → 取前一章 startPage，否则取当前章 startPage
            val prevTarget: Int? = when {
                atChapterStart && currentIdx > 0 -> chapters[currentIdx - 1].startPage
                !atChapterStart && currentIdx >= 0 -> chapters[currentIdx].startPage
                else -> null
            }
            // 存在且 >0 才可点（=0 时跳转无意义）
            val prevEnabled = prevTarget != null && prevTarget > 0
            // 下一章：第一个 startPage 在当前页之后的章节
            val nextTarget: Int? = chapters.firstOrNull { it.startPage > current }?.startPage

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainer,
                        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                    )
                    .topHairline(MaterialTheme.colorScheme.outlineVariant, inset = 18.dp)
                    .padding(WindowInsets.navigationBars.asPaddingValues())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                // —— 第一行：页码滑条（第 N 页 … 进度%）——
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "第 ${shownPage + 1} 页",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = shownPage.toFloat(),
                        onValueChange = { value ->
                            sliderActive = true
                            sliderPage = value.roundToInt().coerceIn(0, last)
                        },
                        onValueChangeFinished = {
                            sliderActive = false
                            jumpTo(sliderPage)
                        },
                        valueRange = 0f..last.toFloat().coerceAtLeast(0f),
                        modifier = Modifier
                            .padding(horizontal = 10.dp)
                            .weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                        ),
                    )
                    Text(
                        text = "${((shownPage + 1).toFloat() / pageCount.coerceAtLeast(1) * 100).roundToInt()}%",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                // —— 等宽功能按钮：上一章 / 亮度 / 设置 / 书签 /（复制文字）/ 目录 / 下一章 ——
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReaderBarIconButton(
                        icon = Icons.Filled.SkipPrevious,
                        desc = "上一章",
                        label = "上一章",
                        enabled = prevEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = { prevTarget?.let { jumpTo(it) } },
                    )
                    ReaderBarIconButton(
                        icon = Icons.Outlined.Brightness6,
                        desc = "亮度",
                        label = "亮度",
                        selected = showBrightness,
                        modifier = Modifier.weight(1f),
                        onClick = { showBrightness = !showBrightness },
                    )
                    ReaderBarIconButton(
                        icon = Icons.Outlined.Settings,
                        desc = "阅读设置",
                        label = "设置",
                        modifier = Modifier.weight(1f),
                        onClick = { showSettings = true },
                    )
                    ReaderBarIconButton(
                        icon = Icons.Outlined.BookmarkAdd,
                        desc = "添加书签",
                        label = "书签",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.addBookmark(currentPage) },
                    )
                    if (isTextNovel) {
                        ReaderBarIconButton(
                            icon = Icons.Outlined.ContentCopy,
                            desc = "复制文字",
                            label = "复制文字",
                            modifier = Modifier.weight(1f),
                            onClick = { showCopyDialog = true },
                        )
                    }
                    ReaderBarIconButton(
                        icon = Icons.AutoMirrored.Outlined.ListIcon,
                        desc = "目录",
                        label = "目录",
                        modifier = Modifier.weight(1f),
                        onClick = onOpenDirectory,
                    )
                    ReaderBarIconButton(
                        icon = Icons.Filled.SkipNext,
                        desc = "下一章",
                        label = "下一章",
                        enabled = nextTarget != null,
                        modifier = Modifier.weight(1f),
                        onClick = { nextTarget?.let { jumpTo(it) } },
                    )
                }
            }
        }

        // —— 解锁钮：顶部菜单锁定后本钮常驻隐藏（无遮罩不变暗、滑动翻页照常）；
        //    点击屏幕中央呼出，点击解锁并呼出菜单；返回键由 BackHandler 拦截 ——
        AnimatedVisibility(
            visible = locked && lockBadgeVisible,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = fadeIn(tween(BAR_ANIM_MS)) +
                slideInHorizontally(tween(BAR_ANIM_MS)) { it },
            exit = fadeOut(tween(BAR_ANIM_MS)),
        ) {
            Surface(
                onClick = {
                    locked = false
                    lockBadgeVisible = false
                    toolbarVisible = true
                },
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "解锁手势",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // —— 亮度独立浮动弹层：底部居中、导航栏上方的圆角小卡片；背景透明无变暗，点外部关闭 ——
        if (showBrightness) {
            // 透明遮罩：铺满全屏，只接「点外部关闭」，不改变背景明暗
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { showBrightness = false } },
            )
            BrightnessPopoverCard(
                brightness = prefs.brightness,
                onBrightness = { value -> viewModel.updatePrefs { it.copy(brightness = value) } },
                onFollowSystem = { viewModel.updatePrefs { it.copy(brightness = null) } },
                onDismiss = { showBrightness = false },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(WindowInsets.navigationBars.asPaddingValues())
                    .padding(start = 14.dp, end = 14.dp, bottom = 112.dp),
            )
        }

        // —— 阅读设置快捷面板（底部弹层）——
        if (showSettings) {
            ReaderSettingsSheet(
                prefs = prefs,
                onUpdate = viewModel::updatePrefs,
                onDismiss = { showSettings = false },
            )
        }

        // —— 复制文字弹层（仅文字小说，从底部工具栏「复制文字」进入）——
        if (showCopyDialog) {
            PageTextCopyDialog(
                viewModel = viewModel,
                page = currentPage,
                onDismiss = { showCopyDialog = false },
            )
        }
    }
}

/**
 * 底部工具栏功能按钮：等宽布局，图标 + 小字标签（对齐设计稿 .reader-tool）。
 * 颜色取色板中性色（onSurfaceVariant）；禁用降透明度；选中用主色高亮。
 */
@Composable
private fun ReaderBarIconButton(
    icon: ImageVector,
    desc: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .heightIn(min = 45.dp)
            .clip(RoundedCornerShape(11.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/**
 * 亮度浮动弹层卡片（对齐设计稿 .brightness-popover）：
 * 底部居中的圆角小卡片；头部「亮度」+ 百分比 + 跟随系统 + 关闭，下方滑条。
 * 拖动即写 prefs（顶层 DisposableEffect 实时作用于窗口亮度）；「跟随系统」置 null。
 * 卡片本身吞掉点按，避免落到下方透明遮罩被误关闭。
 */
@Composable
private fun BrightnessPopoverCard(
    brightness: Float?,
    onBrightness: (Float) -> Unit,
    onFollowSystem: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    // 跟随系统（null）时滑条显示近似值 50%
    var sliderValue by remember { mutableFloatStateOf(brightness ?: 0.5f) }
    // 外部变化（如「跟随系统」重置）同步滑条显示；拖动中不回写，避免与手势竞态回跳
    LaunchedEffect(brightness) {
        if (!dragging) sliderValue = brightness ?: 0.5f
    }
    Surface(
        modifier = modifier
            .widthIn(max = 340.dp)
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures { } },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 10.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 15.dp, end = 15.dp, top = 13.dp, bottom = 15.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "亮度",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${(sliderValue * 100).roundToInt()}%",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(
                    onClick = onFollowSystem,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("跟随系统", style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "关闭亮度",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Slider(
                value = sliderValue,
                onValueChange = { value ->
                    dragging = true
                    sliderValue = value
                    // 拖动即写配置：DataStore 回流后顶层 DisposableEffect 实时作用于窗口
                    onBrightness(value)
                },
                onValueChangeFinished = { dragging = false },
                valueRange = 0.01f..1f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                ),
            )
        }
    }
}

/**
 * 复制文字弹层（仅文字小说）：展示当前页文字（可根据行布局还原），
 * 长按即可选择片段；「复制本页」一键把整页文本写入剪贴板。
 * 非文字源 / 排版未就绪时给出空态提示。
 */
@Composable
private fun PageTextCopyDialog(viewModel: ReaderViewModel, page: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(page) {
        text = viewModel.pageText(page)
        loading = false
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                Text(
                    text = "复制文字 · 第 ${page + 1} 页",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                val content = text
                when {
                    loading -> Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    content.isNullOrBlank() -> Text(
                        text = "本页暂无可复制的文字",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> SelectionContainer {
                        Text(
                            text = content,
                            modifier = Modifier
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    if (!loading && !content.isNullOrBlank()) {
                        TextButton(onClick = {
                            val manager =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            manager?.setPrimaryClip(ClipData.newPlainText("XY reader", content))
                            Toast.makeText(context, "已复制本页文字", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }) { Text("复制本页") }
                    }
                }
            }
        }
    }
}

/**
 * 阅读设置快捷面板（ModalBottomSheet）：顶部胶囊分组 + 横向分页（取代旧的整段上下滚动列表）。
 * 分组：翻页模式 / 页面（阅读背景、图片缩放、屏幕常亮）/ 字体（字体、字重、字号、首行缩进）。
 * 改动经 [onUpdate] 落库即时生效；面板底部 insets（导航栏）由 ModalBottomSheet 默认处理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    prefs: ReaderPrefs,
    onUpdate: ((ReaderPrefs) -> ReaderPrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 面板整体高度约半屏（对齐设计稿 .reader-sheet 的 50dvh，上下限呼应 min 280 / max 520）
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val sheetHeight = (screenHeight * 0.5f).coerceIn(280.dp, 520.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { SheetGrabber() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight),
        ) {
            ReaderSettingsSheetContent(prefs = prefs, onUpdate = onUpdate, onDismiss = onDismiss)
        }
    }
}

/** 面板拖动条（对齐设计稿 .sheet-grabber：36×4 圆角条，色 surfaceContainerHighest） */
@Composable
private fun SheetGrabber() {
    Box(
        Modifier
            .padding(top = 10.dp, bottom = 6.dp)
            .size(width = 36.dp, height = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
    )
}

/** 弹层分组（与阅读配置管理页同款三组，胶囊即分组标题） */
private enum class ReaderSheetTab(val label: String) {
    MODE("翻页模式"),
    PAGE("页面"),
    FONT("字体"),
}

/**
 * 设置面板内容（拆出独立于 ModalBottomSheet 的容器以便 UI 测试直挂）：
 * 顶部胶囊 ↔ 横滑分页双向同步；每组内保留原有控件与即时生效行为。
 */
@Composable
internal fun ReaderSettingsSheetContent(
    prefs: ReaderPrefs,
    onUpdate: ((ReaderPrefs) -> ReaderPrefs) -> Unit,
    onDismiss: () -> Unit = {},
) {
    val pagerState = rememberPagerState { ReaderSheetTab.entries.size }
    val scope = rememberCoroutineScope()
    // 用 fillMaxHeight + 分页区 weight(1f)：面板高度由容器决定（弹层里是半屏）。
    // 分页/滚动状态挂在本组合位的 remember 上，改设置项触发重组不会重置位置。
    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        // —— 面板头：标题「阅读设置」+ 右侧关闭（对齐设计稿 .sheet-head）——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "阅读设置",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // —— 顶部胶囊分组：点胶囊换组，与横滑分页双向同步 ——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ReaderSheetTab.entries.forEachIndexed { index, tab ->
                CapsuleTab(
                    label = tab.label,
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            SheetGroupPage {
                when (ReaderSheetTab.entries[page]) {
                    ReaderSheetTab.MODE -> SheetModeGroup(prefs = prefs, onUpdate = onUpdate)
                    ReaderSheetTab.PAGE -> SheetDisplayGroup(prefs = prefs, onUpdate = onUpdate)
                    ReaderSheetTab.FONT -> SheetFontGroup(prefs = prefs, onUpdate = onUpdate)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "更多设置：设置-阅读配置管理",
            modifier = Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
    }
}

/** 分组页容器：统一内边距（左右 20dp）；内容超高时页内滚动，常规屏幕无需滚动 */
@Composable
private fun SheetGroupPage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        content()
        Spacer(Modifier.height(12.dp))
    }
}

// ---------- 组 1：翻页模式 ----------

/** 左右翻页 / 上下滚动 快捷切换 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetModeGroup(prefs: ReaderPrefs, onUpdate: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PageMode.entries.forEach { mode ->
            FilterChip(
                selected = prefs.pageMode == mode,
                onClick = { onUpdate { it.copy(pageMode = mode) } },
                label = { Text(mode.label) },
            )
        }
    }
}

// ---------- 组 2：页面 ----------

/** 阅读背景 4 档圆色块（选中描边高亮）+ 图片缩放 2 档 + 屏幕常亮 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetDisplayGroup(prefs: ReaderPrefs, onUpdate: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    Text(
        text = "阅读背景",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ReadBackground.entries.forEach { bg ->
            val selected = prefs.readBackground == bg
            Surface(
                onClick = { onUpdate { it.copy(readBackground = bg) } },
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = bg.toComposeColor(),
                border = BorderStroke(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
            ) {}
        }
    }
    Text(
        text = "图片缩放",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ImageScale.entries.forEach { scale ->
            FilterChip(
                selected = prefs.imageScale == scale,
                onClick = { onUpdate { it.copy(imageScale = scale) } },
                label = { Text(scale.label) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "自动旋屏",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "按重力感应自动旋转屏幕；关闭后锁定竖屏",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = LocalAutoRotate.current,
            onCheckedChange = LocalSetAutoRotate.current,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "屏幕常亮",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(
            checked = prefs.keepScreenOn,
            onCheckedChange = { value -> onUpdate { it.copy(keepScreenOn = value) } },
        )
    }
}

// ---------- 组 3：字体 ----------

/** 小说字体（系统族 / 内置 / 导入 chip）+ 字重 + 字号 + 首行缩进（仅对文字小说生效） */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SheetFontGroup(prefs: ReaderPrefs, onUpdate: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        prefs.novelCustomFont?.let { name ->
            FilterChip(
                selected = true,
                onClick = { },
                label = { Text(name.substringBeforeLast('.')) },
            )
        }
        NovelFontFamily.entries.forEach { family ->
            FilterChip(
                selected = prefs.novelCustomFont == null && prefs.novelFontFamily == family,
                onClick = {
                    onUpdate { it.copy(novelFontFamily = family, novelCustomFont = null) }
                },
                label = { Text(family.label) },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NovelFontWeight.entries.forEach { weight ->
            FilterChip(
                selected = prefs.novelFontWeight == weight,
                onClick = { onUpdate { it.copy(novelFontWeight = weight) } },
                label = { Text(weight.label) },
            )
        }
    }
    val fontSizeSlider = remember(prefs.novelFontSizeSp) {
        mutableFloatStateOf(prefs.novelFontSizeSp.coerceIn(12f, 36f))
    }
    Column {
        Text(
            text = "字号 ${fontSizeSlider.floatValue.toInt()} sp",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value = fontSizeSlider.floatValue,
            onValueChange = { fontSizeSlider.floatValue = it },
            onValueChangeFinished = {
                val value = fontSizeSlider.floatValue
                val nearestLegacy = NovelFontSize.entries.minBy { kotlin.math.abs(it.sp - value) }
                onUpdate { it.copy(novelFontSize = nearestLegacy, novelFontSizeSp = value) }
            },
            valueRange = 12f..36f,
            steps = 23,
        )
    }
    NovelSpacingControls(prefs = prefs, onUpdate = onUpdate, compact = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "首行缩进",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(
            checked = prefs.novelFirstLineIndent,
            onCheckedChange = { value ->
                onUpdate { it.copy(novelFirstLineIndent = value) }
            },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "章首另起一页",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(
            checked = prefs.novelChapterNewPage,
            onCheckedChange = { value ->
                onUpdate { it.copy(novelChapterNewPage = value) }
            },
        )
    }
    Text(
        text = "仅文字小说使用这些排版设置",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 全屏目录弹层（Dialog usePlatformDefaultWidth=false）：顶部关闭 + "共 N 章"（无章节时"目录"）
 * + 目录/书签双 tab 胶囊。目录 tab 高亮当前章（[currentPage] 所在章），点击章节跳章节首页并关闭；
 * 书签 tab 沿用书签列表（点击跳页 / 行尾删除）。
 */
@Composable
private fun DirectoryDialog(
    chapters: List<Chapter>,
    bookmarks: List<BookmarkEntity>,
    currentPage: Int,
    tab: Int,
    onTabChange: (Int) -> Unit,
    onJumpTo: (Int) -> Unit,
    onRemoveBookmark: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(WindowInsets.statusBars.asPaddingValues()),
            ) {
                // —— 顶部：✕ 关闭 + 标题 ——
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = if (chapters.isEmpty()) "目录" else "共 ${chapters.size} 章",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                // —— tab 切换：选中深底白字胶囊，未选描边 ——
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TabChip(text = "目录", selected = tab == 0, onClick = { onTabChange(0) })
                    TabChip(text = "书签", selected = tab == 1, onClick = { onTabChange(1) })
                }
                when (tab) {
                    1 -> BookmarkList(
                        bookmarks = bookmarks,
                        onJumpTo = onJumpTo,
                        onRemoveBookmark = onRemoveBookmark,
                    )
                    else -> ChapterList(
                        chapters = chapters,
                        currentPage = currentPage,
                        onJumpTo = onJumpTo,
                    )
                }
            }
        }
    }
}

/** 目录 tab 内容：章节列表（标题 + X-Y 页码小字），当前章行背景与文字用 primary 高亮 */
@Composable
private fun ChapterList(
    chapters: List<Chapter>,
    currentPage: Int,
    onJumpTo: (Int) -> Unit,
) {
    if (chapters.isEmpty()) {
        Text(
            text = "本书没有章节结构",
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(chapters) { chapter ->
            // 当前章 = 当前页落在该章闭区间内：背景 primary 微透明 + 文字 primary
            val isCurrent = currentPage in chapter.startPage..chapter.endPageInclusive
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isCurrent) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .clickable { onJumpTo(chapter.startPage) }
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // 页码按 1 起展示："起始页-结束页"
                    text = "${chapter.startPage + 1}-${chapter.endPageInclusive + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** 书签 tab 内容：本书书签列表（点击跳页 / 行尾删除），空列表给添加指引 */
@Composable
private fun BookmarkList(
    bookmarks: List<BookmarkEntity>,
    onJumpTo: (Int) -> Unit,
    onRemoveBookmark: (Long) -> Unit,
) {
    if (bookmarks.isEmpty()) {
        Text(
            text = "暂无书签，阅读时点工具栏书签图标添加",
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // 仓库书签流已按创建时间倒序，行尾删除按钮单独处理不触发跳页
        items(bookmarks, key = { it.id }) { bookmark ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onJumpTo(bookmark.pageIndex) }
                    .padding(start = 20.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "第 ${bookmark.pageIndex + 1} 页 · ${formatDate(bookmark.createdAt)}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { onRemoveBookmark(bookmark.id) }) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "删除书签",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** 弹层 tab 胶囊：选中 primary 深底白字，未选描边透明底 */
@Composable
private fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        border = if (selected) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/**
 * 单页：就绪画图，未就绪转圈，失败提示；进入组合时请求 ViewModel 渲染。
 * [zoomState] 非空 = 页内缩放（双指捏合 + 双击放大 + 平移，左右翻页用）；
 * 为空 = 连续列表模式：页框由列表层按整列缩放撑高，这里把同一倍数 [renderScale] 作用到
 * 图像本体（否则只撑高框、画面尺寸不变），再按 [panX] 做水平平移。
 * [imageScale] 决定图片适配方式（适合宽度 / 适合屏幕）。
 */
@Composable
private fun ReaderPage(
    page: Int,
    ui: PageUi?,
    bgColor: Color,
    zoomState: PageZoomState?,
    imageScale: ImageScale,
    onNeedRender: (Int) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    panX: () -> Float = { 0f },
    renderScale: () -> Float = { 1f },
) {
    Box(
        modifier.background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        when (ui) {
            is PageUi.Ready -> Image(
                bitmap = ui.bitmap,
                contentDescription = "第 ${page + 1} 页",
                // 适合宽度 = FillWidth；其余（含缺省）= Fit，留白由背景色填充
                contentScale = if (imageScale == ImageScale.FILL_WIDTH) {
                    ContentScale.FillWidth
                } else {
                    ContentScale.Fit
                },
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (zoomState != null) {
                            Modifier
                                // 双指捏合 / 双击缩放 / 缩放态平移；未放大时事件透传给 Pager 翻页
                                .pageZoom(zoomState)
                                .graphicsLayer {
                                    scaleX = zoomState.scale
                                    scaleY = zoomState.scale
                                    translationX = zoomState.offset.x
                                    translationY = zoomState.offset.y
                                }
                        } else {
                            // 连续列表：页框已按整列缩放撑高，图像本体必须同步等比放大
                            // （Fit/FillWidth 都把图居中绘在框内，故框中心 = 图中心；绕中心
                            //   放大 s 倍后图恰好填满 baseH×s 的框，页页首尾相接不重叠）。
                            //
                            // 用 canvas 变换而不是 graphicsLayer：两者绘制结果等价，但图层会
                            // 把本节点的 boundsInRoot 一并放大 s 倍（语义/几何口径失真，
                            // ContinuousZoomAnchorTest 这类按页面矩形反推缩放的测试会失效），
                            // canvas 变换只影响绘制、不动布局与语义坐标。
                            Modifier.drawWithContent {
                                withTransform({
                                    translate(panX(), 0f)
                                    scale(renderScale(), renderScale(), pivot = center)
                                }) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                        },
                    ),
            )

            is PageUi.Failed -> Text(
                text = "第 ${page + 1} 页加载失败",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
    LaunchedEffect(page) {
        if (ui !is PageUi.Ready) onNeedRender(page)
    }
}

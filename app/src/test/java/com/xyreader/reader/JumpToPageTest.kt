package com.xyreader.reader

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 跳转直接定位：进度条松手、目录/书签、上一章/下一章都走 [jumpToPage]，
 * 当前页序列只能是「起点 -> 目标」，不能出现被快速滑过的中间页。
 * 整屏「滑条松手」未单独覆盖：M3 Slider 的语义 SetProgress 是否触发 onValueChangeFinished
 * 在单测环境不可靠，这里直接验证三处共用的 [jumpToPage]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JumpToPageTest {
    @get:Rule val compose = createComposeRule()

    private val pageCount = 50

    /** 记录 Pager 当前页的变化序列 */
    private val pagerSeen = mutableListOf<Int>()

    /** 记录列表首个可见项的变化序列 */
    private val listSeen = mutableListOf<Int>()

    private lateinit var pagerState: PagerState
    private lateinit var listState: LazyListState
    private lateinit var scope: CoroutineScope

    private fun setUpPager() {
        compose.setContent {
            pagerState = rememberPagerState { pageCount }
            listState = rememberLazyListState()
            scope = rememberCoroutineScope()
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { pagerSeen += it }
            }
            LaunchedEffect(listState) {
                snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { listSeen += it }
            }
            Box(Modifier.height(400.dp).fillMaxWidth()) {
                HorizontalPager(state = pagerState) { Box(Modifier.fillMaxWidth().height(400.dp)) }
            }
        }
    }

    private fun setUpList() {
        compose.setContent {
            pagerState = rememberPagerState { pageCount }
            listState = rememberLazyListState()
            scope = rememberCoroutineScope()
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { pagerSeen += it }
            }
            LaunchedEffect(listState) {
                snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { listSeen += it }
            }
            LazyColumn(Modifier.height(400.dp).fillMaxWidth(), state = listState) {
                items(pageCount) { Box(Modifier.fillMaxWidth().height(100.dp)) }
            }
        }
    }

    private fun jump(upDown: Boolean, target: Int) {
        compose.runOnIdle {
            scope.launch { jumpToPage(upDown, pagerState, listState, pageCount, target) }
        }
        compose.waitForIdle()
    }

    @Test fun pagerJumpGoesStraightToTargetWithoutIntermediatePages() {
        setUpPager()
        jump(upDown = false, target = 40)
        assertEquals(40, pagerState.currentPage)
        assertEquals(listOf(0, 40), pagerSeen)
    }

    @Test fun listJumpGoesStraightToTargetWithoutIntermediateItems() {
        setUpList()
        jump(upDown = true, target = 40)
        assertEquals(40, listState.firstVisibleItemIndex)
        assertEquals(listOf(0, 40), listSeen)
    }

    @Test fun pagerTargetIsClampedIntoRange() {
        setUpPager()
        jump(upDown = false, target = 999)
        assertEquals(pageCount - 1, pagerState.currentPage)
        jump(upDown = false, target = -5)
        assertEquals(0, pagerState.currentPage)
        // 越界目标同样只落到边界页，不经过中间页
        assertEquals(listOf(0, pageCount - 1, 0), pagerSeen)
    }

    @Test fun listTargetIsClampedIntoRange() {
        setUpList()
        jump(upDown = true, target = 999)
        // 末项总在可见范围内（列表滚到底时首个可见项可能小于 49）
        assertEquals(pageCount - 1, listState.layoutInfo.visibleItemsInfo.last().index)
        jump(upDown = true, target = -5)
        assertEquals(0, listState.firstVisibleItemIndex)
    }
}

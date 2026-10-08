package com.xyreader.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.xyreader.core.BookEntity
import com.xyreader.stats.ReadingStats
import com.xyreader.stats.StreakTier
import com.xyreader.stats.unlockedTiers
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 封面边框：BookCard 在各档边框 / 选中描边下都能渲染；统计页的边框选择只允许点已解锁的档位。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverFrameBorderTest {
    @get:Rule val compose = createComposeRule()

    private val book = BookEntity(id = 7, title = "测试小说", uri = "file:///sample.txt", format = "TXT", addedAt = 0)

    @Test
    fun bookCardRendersWithEveryFrameTier() {
        // setContent 每个测试只能调用一次：五档边框各放一张卡片，书名区分
        compose.setContent {
            ArkTheme {
                Column {
                    for (tier in StreakTier.entries) {
                        CompositionLocalProvider(LocalCoverFrame provides tier) {
                            Box(Modifier.width(150.dp)) {
                                BookCard(
                                    book.copy(id = 10L + tier.ordinal, title = "书-${tier.name}"),
                                    emptyList(),
                                    {},
                                    {},
                                    {},
                                    {},
                                )
                            }
                        }
                    }
                }
            }
        }
        for (tier in StreakTier.entries) {
            compose.onNodeWithContentDescription("书-${tier.name}").assertIsDisplayed()
        }
    }

    @Test
    fun selectedBookCardKeepsRenderingWhenAFrameIsAlsoSet() {
        compose.setContent {
            ArkTheme {
                CompositionLocalProvider(LocalCoverFrame provides StreakTier.GOLD) {
                    Box(Modifier.width(150.dp)) {
                        BookCard(
                            book,
                            emptyList(),
                            {},
                            {},
                            {},
                            {},
                            selectionMode = true,
                            selected = true,
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("已选：测试小说", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun statsWithLongest(days: Int) = ReadingStats.EMPTY.copy(
        longestStreak = days,
        currentStreak = days,
        unlockedTiers = unlockedTiers(days),
    )

    @Test
    fun statsPageOnlyAppliesUnlockedFrames() {
        val picked = mutableListOf<StreakTier?>()
        compose.setContent {
            ArkTheme {
                ReadingStatsContent(
                    stats = statsWithLongest(7),
                    cells = emptyList(),
                    today = LocalDate.of(2026, 10, 8),
                    selectedFrame = null,
                    onSelectFrame = { picked += it },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("银边框").performClick()
        // 连续 30 天才解锁金边框：点击无效
        compose.onNodeWithText("金边框").performClick()
        compose.onNodeWithText("无边框").performClick()
        compose.runOnIdle {
            assertEquals(listOf<StreakTier?>(StreakTier.SILVER, null), picked)
        }
    }
}

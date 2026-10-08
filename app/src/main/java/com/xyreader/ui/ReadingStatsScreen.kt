package com.xyreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xyreader.data.ArkDatabase
import com.xyreader.stats.CHECK_IN_THRESHOLD_MS
import com.xyreader.stats.DayCell
import com.xyreader.stats.ReadingStats
import com.xyreader.stats.StreakTier
import com.xyreader.stats.computeReadingStats
import com.xyreader.stats.daysToUnlock
import com.xyreader.stats.formatChars
import com.xyreader.stats.formatDuration
import com.xyreader.stats.formatPages
import com.xyreader.stats.recentDays
import java.time.LocalDate

/** 最近多少天的打卡格子（3 行 × 10 列） */
private const val RECENT_DAYS = 30
private const val RECENT_COLUMNS = 10

/**
 * 阅读统计页（路由 stats）：累计数据、连续打卡与今日进度、最近 30 天格子、
 * 五档连续打卡徽章，以及封面边框选择（选中后应用到书库封面，见 [LocalCoverFrame]）。
 * 数据全部来自本机 daily_reading 表，不联网。
 */
@Composable
fun ReadingStatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dailyFlow = remember(context) { ArkDatabase.getInstance(context).readingStatsDao().observeAll() }
    val records by dailyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val today = remember { LocalDate.now() }
    val stats = remember(records, today) { computeReadingStats(records, today) }
    val cells = remember(records, today) { recentDays(records, today, RECENT_DAYS) }

    ReadingStatsContent(
        stats = stats,
        cells = cells,
        today = today,
        selectedFrame = LocalCoverFrame.current,
        onSelectFrame = LocalSetCoverFrame.current,
        onBack = onBack,
    )
}

/** 无状态内容，便于预览与测试 */
@Composable
internal fun ReadingStatsContent(
    stats: ReadingStats,
    cells: List<DayCell>,
    today: LocalDate,
    selectedFrame: StreakTier?,
    onSelectFrame: (StreakTier?) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShelfSubpageHead(
                title = "阅读统计",
                subtitle = "数据仅保存在本机，不会上传",
                onBack = onBack,
            )
            StatsSummaryCard(stats)
            CheckInCard(stats)
            RecentDaysCard(cells, today)
            BadgesCard(stats)
            FramePickerCard(stats, selectedFrame, onSelectFrame)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 统计页通用卡片：圆角 + 描边 + 标题（与书架页卡片同一语言） */
@Composable
private fun StatsCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(19.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    modifier = Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun StatsSummaryCard(stats: ReadingStats) {
    val tiles = listOf(
        "累计阅读时长" to formatDuration(stats.totalDurationMs),
        "累计阅读字数" to formatChars(stats.totalChars),
        "累计阅读页数" to formatPages(stats.totalPages),
        "阅读天数" to "${stats.readingDays} 天",
        "日均阅读时长" to formatDuration(stats.averageDailyMs),
        "打卡天数" to "${stats.checkInDays} 天",
        "最长连续打卡" to "${stats.longestStreak} 天",
    )
    StatsCard(title = "累计数据") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) }
                    // 奇数个时补一个空位，保持最后一格宽度与其他格一致
                    if (row.size < 2) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun CheckInCard(stats: ReadingStats) {
    val thresholdMinutes = (CHECK_IN_THRESHOLD_MS / 60_000L).toInt()
    val fraction = (stats.todayDurationMs.toFloat() / CHECK_IN_THRESHOLD_MS).coerceIn(0f, 1f)
    // 还差多少分钟：向上取整，避免差几秒时显示「还差 0 分钟」
    val remainingMinutes = ((CHECK_IN_THRESHOLD_MS - stats.todayDurationMs + 59_999L) / 60_000L)
        .coerceAtLeast(1L)
    StatsCard(title = "连续打卡", subtitle = "每天阅读满 $thresholdMinutes 分钟自动打卡") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${stats.currentStreak}",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "天",
                modifier = Modifier.padding(bottom = 5.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "最长 ${stats.longestStreak} 天",
                modifier = Modifier.padding(bottom = 5.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (stats.todayCheckedIn) {
                "今日已打卡 · 已读 ${formatDuration(stats.todayDurationMs)}"
            } else {
                "今日 ${stats.todayDurationMs / 60_000L} / $thresholdMinutes 分钟 · 还差 $remainingMinutes 分钟打卡"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentDaysCard(cells: List<DayCell>, today: LocalDate) {
    StatsCard(title = "最近 ${cells.size} 天", subtitle = "实心为已打卡，浅色为有阅读，外框为今天") {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            cells.chunked(RECENT_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { cell -> DayCellBox(cell, cell.date == today, Modifier.weight(1f)) }
                    // 不足一行时补空位，格子大小保持一致
                    repeat(RECENT_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun DayCellBox(cell: DayCell, isToday: Boolean, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val fill = when {
        cell.checkedIn -> primary
        cell.durationMs > 0 -> primary.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(fill)
            .then(
                if (isToday) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurface, shape)
                } else {
                    Modifier
                },
            ),
    )
}

@Composable
private fun BadgesCard(stats: ReadingStats) {
    StatsCard(title = "连续打卡徽章", subtitle = "按历史最长连续天数解锁，断签后不会收回") {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StreakTier.entries.forEach { tier ->
                BadgeItem(
                    tier = tier,
                    unlocked = tier in stats.unlockedTiers,
                    remainingDays = daysToUnlock(tier, stats.longestStreak),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BadgeItem(
    tier: StreakTier,
    unlocked: Boolean,
    remainingDays: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    if (unlocked) tier.badgeColor() else MaterialTheme.colorScheme.surfaceVariant,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (unlocked) Icons.Filled.Star else Icons.Filled.Lock,
                contentDescription = if (unlocked) "已解锁：${tier.badgeTitle}" else "未解锁：${tier.badgeTitle}",
                modifier = Modifier.size(if (unlocked) 26.dp else 20.dp),
                tint = if (unlocked) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            tier.badgeTitle,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (unlocked) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            if (unlocked) "连续 ${tier.days} 天" else "还差 $remainingDays 天",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun FramePickerCard(
    stats: ReadingStats,
    selected: StreakTier?,
    onSelect: (StreakTier?) -> Unit,
) {
    // 「无」+ 五款边框，每行三个
    val options: List<StreakTier?> = listOf(null) + StreakTier.entries
    StatsCard(title = "封面边框", subtitle = "解锁后可选，选中后应用到书库里所有书的封面") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            options.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { tier ->
                        FrameOption(
                            tier = tier,
                            unlocked = tier == null || tier in stats.unlockedTiers,
                            selected = tier == selected,
                            onClick = { onSelect(tier) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun FrameOption(
    tier: StreakTier?,
    unlocked: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewShape = RoundedCornerShape(8.dp)
    val label = tier?.frameTitle ?: "无边框"
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .selectable(selected = selected, enabled = unlocked, role = Role.RadioButton, onClick = onClick)
            .alpha(if (unlocked) 1f else 0.45f)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(width = 46.dp, height = 62.dp)
                    .clip(previewShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(
                        if (tier == null) {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, previewShape)
                        } else {
                            Modifier.coverFrameBorder(tier, previewShape, width = 3.dp)
                        },
                    ),
            )
            if (!unlocked) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "已选中",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            when {
                tier == null -> "默认封面"
                unlocked -> "连续 ${tier.days} 天"
                else -> "连续 ${tier.days} 天解锁"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

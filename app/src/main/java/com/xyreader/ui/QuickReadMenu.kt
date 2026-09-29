package com.xyreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 「开始阅读」菜单的四个动作（顺序即菜单展示顺序） */
internal enum class QuickReadKind(val label: String) {
    SHELF_LAST("当前书架上次阅读"),
    LAST("上次阅读"),
    SHELF_RANDOM("当前书架随机"),
    RANDOM("随机"),
}

/**
 * 主页右下角「开始阅读」FAB + 悬浮胶囊菜单（参考 MH-ARK：播放键展开四选项，展开后变关闭键）：
 * 收起态为播放键；展开后出现四颗彩色胶囊 + 半透明遮罩（点遮罩/返回键收起），
 * [onPick] 由调用方执行对应动作（打开阅读器）。
 */
@Composable
internal fun QuickReadMenuOverlay(
    open: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (QuickReadKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = open) { onDismiss() }
    Box(modifier.fillMaxSize()) {
        // 遮罩：展开时点击任意空白收起
        AnimatedVisibility(visible = open, enter = fadeIn(tween(130)), exit = fadeOut(tween(110))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(ScrimColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 菜单项自下而上排列，展开时轻微上浮 + 淡入
            AnimatedVisibility(
                visible = open,
                enter = fadeIn(tween(150)) + slideInVertically(tween(150)) { height -> height / 4 },
                exit = fadeOut(tween(100)) + slideOutVertically(tween(100)) { height -> height / 4 },
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    QuickReadKind.entries.forEach { kind ->
                        QuickReadMenuItem(kind = kind, onClick = { onPick(kind) })
                    }
                }
            }
            FloatingActionButton(
                onClick = onToggle,
                shape = CircleShape,
                containerColor = if (open) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                contentColor = if (open) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
            ) {
                Icon(
                    imageVector = if (open) Icons.Outlined.Close else Icons.Filled.PlayArrow,
                    contentDescription = if (open) "关闭阅读菜单" else "开始阅读",
                )
            }
        }
    }
}

/** 菜单项：彩色圆形图标 + 标签的悬浮胶囊 */
@Composable
private fun QuickReadMenuItem(kind: QuickReadKind, onClick: () -> Unit) {
    val tint = when (kind) {
        QuickReadKind.SHELF_LAST -> accentColor(AccentColor.PURPLE)
        QuickReadKind.LAST -> accentColor(AccentColor.ORANGE)
        QuickReadKind.SHELF_RANDOM -> accentColor(AccentColor.GREEN)
        QuickReadKind.RANDOM -> accentColor(AccentColor.CYAN)
    }
    val icon = when (kind) {
        QuickReadKind.SHELF_LAST -> Icons.Outlined.Autorenew
        QuickReadKind.LAST -> Icons.Outlined.History
        QuickReadKind.SHELF_RANDOM -> Icons.Outlined.Shuffle
        QuickReadKind.RANDOM -> Icons.Outlined.Casino
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 18.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(shape = CircleShape, color = tint.copy(alpha = 0.16f)) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(18.dp),
                )
            }
            Text(
                text = kind.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

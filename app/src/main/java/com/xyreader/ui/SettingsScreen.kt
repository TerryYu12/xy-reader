package com.xyreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xyreader.BuildConfig
import kotlinx.coroutines.launch

/**
 * 设置页（对应 MH-ARK 设置页，Google 设置风格）：
 * 顶栏返回 +「设置」；overline 风格分组标题（仓库管理 / 漫画管理 / 其他）+
 * 大圆角卡片行（彩色图标块 + 标题 + 副标题/尾注，每行图标各有专属色相）。
 * 开发中条目以 Snackbar 提示；本地/远程仓库管理与 Google Drive 为可点击行，
 * 跳转对应管理页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenRepos: () -> Unit,
    onOpenRemoteRepos: () -> Unit,
    onOpenGdrive: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenReaderConfig: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenSupport: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionLabel("仓库管理", Modifier.padding(top = 12.dp, bottom = 10.dp))
            SettingsCard {
                SettingRow(
                    icon = Icons.Outlined.FolderOpen,
                    title = "本地仓库管理",
                    iconTint = accentColor(AccentColor.BLUE),
                    onClick = onOpenRepos,
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Outlined.Cloud,
                    title = "远程仓库管理",
                    subtitle = "支持 ZIP,7Z,TAR,RAR,PDF 流式阅读",
                    iconTint = accentColor(AccentColor.GREEN),
                    trailing = { BetaTag() },
                    onClick = onOpenRemoteRepos,
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Outlined.Cloud,
                    title = "Google Drive",
                    subtitle = "OAuth 授权，支持 ZIP,7Z,TAR,RAR,PDF 流式阅读",
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    trailing = { BetaTag() },
                    onClick = onOpenGdrive,
                )
            }

            SectionLabel("漫画管理", Modifier.padding(top = 24.dp, bottom = 10.dp))
            SettingsCard {
                SettingRow(
                    icon = Icons.Outlined.CollectionsBookmark,
                    title = "书架管理",
                    subtitle = "自定义分组，分类整理漫画",
                    iconTint = accentColor(AccentColor.PURPLE),
                    onClick = onOpenGroups,
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Outlined.Label,
                    title = "标签管理",
                    iconTint = accentColor(AccentColor.ORANGE),
                    onClick = { scope.launch { snackbar.showSnackbar("标签管理开发中") } },
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Outlined.Tune,
                    title = "阅读配置管理",
                    subtitle = "翻页模式 / 漫画方向 / 屏幕方向",
                    iconTint = accentColor(AccentColor.CYAN),
                    onClick = onOpenReaderConfig,
                )
            }

            SectionLabel("其他", Modifier.padding(top = 24.dp, bottom = 10.dp))
            SettingsCard {
                SettingRow(
                    icon = Icons.Outlined.Info,
                    title = "版本",
                    iconTint = accentColor(AccentColor.GRAY),
                    trailing = {
                        Text(
                            BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = null,
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Outlined.PrivacyTip,
                    title = "隐私政策",
                    iconTint = accentColor(AccentColor.GRAY),
                    onClick = onOpenPrivacy,
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Outlined.Coffee,
                    title = "支持作者",
                    subtitle = "请作者喝杯咖啡",
                    iconTint = accentColor(AccentColor.GOLD),
                    onClick = onOpenSupport,
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 圆角分组卡片：一组设置行收纳在同一张卡里（24dp 大圆角 + 留白分层，无描边） */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp), content = { content() })
    }
}

/** 卡片内两行之间的细分隔线（与文字列左对齐缩进） */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 68.dp, end = 14.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** 单个设置行：42dp 彩色图标块 + 标题（+副标题）+ 尾部内容，行高 ≥60dp */
@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = RoundedCornerShape(13.dp),
            color = iconTint.copy(alpha = 0.12f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = iconTint,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** 远程仓库行标题旁的「beta」小标签（功能已可用，处于测试阶段） */
@Composable
private fun BetaTag() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
        contentColor = MaterialTheme.colorScheme.tertiary,
    ) {
        Text(
            "beta",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

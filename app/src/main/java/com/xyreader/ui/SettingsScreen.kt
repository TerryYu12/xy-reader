package com.xyreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xyreader.BuildConfig
import kotlinx.coroutines.launch

/**
 * 设置页（设计源 index.html settingsView）：
 * 子页头（圆角描边返回钮 + 标题「设置」 + 副标题）→ 三个分组（仓库管理 / 漫画管理 / 其他），
 * 每组 = 小标题 + settings-card 容器；行样式 setting-row：强调色图标块（底色 = 强调色 13%）
 * + 标题/副标题 + 尾部（chevron / beta 徽标 / 版本号）。
 *
 * 强调色色相严格按设计色表：
 * 本地仓库=primary、远程=green、Drive=coral(tertiary)、书架=#c58af9(purple)、
 * 标签=gold、阅读配置=#78d9ec(cyan)、版本/隐私=quiet(gray)、支持作者=gold。
 * 开发中条目以 Snackbar 提示；各处入口跳转保持不变。
 */
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
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showAccentPicker by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SubpageHead(
                title = "设置",
                subtitle = "XY-READER 的阅读与书库选项",
                onBack = onBack,
            )

            GroupLabel("外观")
            SettingsCard {
                SettingRow(
                    icon = Icons.Outlined.Palette,
                    title = "强调色",
                    subtitle = "界面主色调跟随所选颜色",
                    iconTint = MaterialTheme.colorScheme.primary,
                    tailText = LocalThemeAccent.current.label,
                    showChevron = true,
                    onClick = { showAccentPicker = true },
                )
            }

            GroupLabel("仓库管理")
            SettingsCard {
                SettingRow(
                    icon = Icons.Outlined.FolderOpen,
                    title = "本地仓库管理",
                    subtitle = "添加本机文件夹并扫描书籍",
                    iconTint = MaterialTheme.colorScheme.primary,
                    showChevron = true,
                    onClick = onOpenRepos,
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Outlined.Cloud,
                    title = "远程仓库管理",
                    subtitle = "WebDAV · ZIP / 7Z / TAR / RAR / PDF 流式阅读",
                    iconTint = accentColor(AccentColor.GREEN),
                    tailBeta = true,
                    onClick = onOpenRemoteRepos,
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Outlined.Cloud,
                    title = "Google Drive",
                    subtitle = "OAuth 授权与云端扫描",
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    tailBeta = true,
                    onClick = onOpenGdrive,
                )
            }

            GroupLabel("漫画管理")
            SettingsCard {
                SettingRow(
                    icon = Icons.Outlined.CollectionsBookmark,
                    title = "书架管理",
                    subtitle = "自定义分组，分类整理书籍",
                    iconTint = accentColor(AccentColor.PURPLE),
                    showChevron = true,
                    onClick = onOpenGroups,
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Outlined.Label,
                    title = "标签管理",
                    subtitle = "开发中",
                    iconTint = accentColor(AccentColor.GOLD),
                    onClick = { scope.launch { snackbar.showSnackbar("标签管理开发中") } },
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Outlined.Tune,
                    title = "阅读配置管理",
                    subtitle = "翻页模式 / 漫画方向 / 屏幕方向",
                    iconTint = accentColor(AccentColor.CYAN),
                    showChevron = true,
                    onClick = onOpenReaderConfig,
                )
            }

            GroupLabel("其他")
            SettingsCard {
                SettingRow(
                    icon = Icons.Outlined.Info,
                    title = "版本",
                    subtitle = "点击检查更新",
                    iconTint = accentColor(AccentColor.GRAY),
                    tailText = BuildConfig.VERSION_NAME,
                    showChevron = true,
                    onClick = {
                        scope.launch {
                            runManualCheck(context) { msg ->
                                // msg == null 表示已发现新版本（对话框已弹），无需 Snackbar
                                if (msg != null) scope.launch { snackbar.showSnackbar(msg) }
                            }
                        }
                    },
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Outlined.PrivacyTip,
                    title = "隐私政策",
                    subtitle = "查看应用的数据处理说明",
                    iconTint = accentColor(AccentColor.GRAY),
                    showChevron = true,
                    onClick = onOpenPrivacy,
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Outlined.Coffee,
                    title = "支持作者",
                    subtitle = "请作者喝杯咖啡",
                    iconTint = accentColor(AccentColor.GOLD),
                    showChevron = true,
                    onClick = onOpenSupport,
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (showAccentPicker) {
        AccentPickerDialog(onDismiss = { showAccentPicker = false })
    }
}

/**
 * 子页头（对应设计 .subpage-head）：40dp 圆角描边返回钮 + 标题（titleLarge/Bold）+ 副标题。
 * 本文件私有实现，避免与其他子代理页面中的同名共用组件冲突（未改 Common.kt）。
 */
@Composable
private fun SubpageHead(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            onClick = onBack,
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(13.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 分组小标题（对应设计 .settings-label）：quiet 色小字 + 宽字距 + 加重 */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 9.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 圆角分组卡片（对应设计 .settings-card）：19dp 大圆角 + 1dp 描边 + surface 底 */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(19.dp),
            ),
        shape = RoundedCornerShape(19.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(content = { content() })
    }
}

/** 卡片内两行之间的细分隔线（对应设计 setting-row 的 border-bottom，全宽） */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * 单个设置行（对应设计 .setting-row）：38dp 强调色图标块（底色 = 强调色 13%，
 * master 指示③：底色与强调色同一色相）+ 标题/副标题 + 尾部，最小高度 64dp。
 */
@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    showChevron: Boolean = false,
    tailBeta: Boolean = false,
    tailText: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(38.dp),
            shape = RoundedCornerShape(12.dp),
            color = iconTint.copy(alpha = 0.12f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = iconTint,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
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
        when {
            tailBeta -> BetaTag()
            tailText != null -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    tailText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showChevron) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            showChevron -> Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 远程仓库 / Drive 行尾的「beta」小徽标（对应设计 .beta，coral 底） */
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
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

/**
 * 强调色选择弹窗（设置 → 外观 → 强调色）：
 * 8 档色块两行四列，色块取该档当前明暗下的 primary；选中态 = 2dp primary 外环 + 中央 onPrimary 对勾；
 * 点选即调 [LocalSetThemeAccent] 立即生效（不关窗，便于直接预览全局变化），底部「完成」关闭。
 */
@Composable
private fun AccentPickerDialog(onDismiss: () -> Unit) {
    val dark = when (LocalThemeMode.current) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val selected = LocalThemeAccent.current
    val setAccent = LocalSetThemeAccent.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                "强调色",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ThemeAccent.values().toList().chunked(4).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowItems.forEach { accent ->
                            AccentSwatch(
                                accent = accent,
                                selected = accent == selected,
                                dark = dark,
                                modifier = Modifier.weight(1f),
                                onClick = { setAccent(accent) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

/**
 * 单个强调色色块：44dp 圆（取该档当前明暗下的 primary）+ 档名小字；
 * 选中态叠加 2dp primary 外描边环与中央 onPrimary 对勾。
 */
@Composable
private fun AccentSwatch(
    accent: ThemeAccent,
    selected: Boolean,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(accent.primary(dark)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = accent.onPrimary(dark),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            accent.label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

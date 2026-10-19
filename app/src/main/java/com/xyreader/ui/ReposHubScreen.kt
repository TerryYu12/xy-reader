package com.xyreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 仓库总览（底部导航第三页）。
 *
 * 把「书从哪来」收敛到一处：本机文件夹 / WebDAV / Google Drive / 在线书源。
 * 四项各自复用原有子页，本页只做入口与说明，不重复实现配置逻辑。
 */
@Composable
fun ReposHubScreen(
    onOpenLocalRepos: () -> Unit,
    onOpenRemoteRepos: () -> Unit,
    onOpenGdrive: () -> Unit,
    onOpenBookSources: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val themeMode = LocalThemeMode.current
    val setThemeMode = LocalSetThemeMode.current
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "仓库",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "书籍的来源：本机文件夹、远程存储，或你自己导入的在线书源",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 仓库顶栏只有 日夜切换｜设置（规范 2.1：没有已确认的搜索功能，不补搜索按钮）
            IconButton(onClick = { setThemeMode(if (dark) ThemeMode.LIGHT else ThemeMode.DARK) }) {
                Icon(
                    if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                    contentDescription = if (dark) "切换到浅色模式" else "切换到深色模式",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        HubCard(
            icon = Icons.Outlined.FolderOpen,
            iconTint = MaterialTheme.colorScheme.primary,
            title = "本地仓库",
            onClick = onOpenLocalRepos,
        )
        Spacer(Modifier.height(10.dp))
        HubCard(
            icon = Icons.Outlined.CloudQueue,
            iconTint = MaterialTheme.colorScheme.primary,
            title = "远程仓库",
            badge = "beta",
            onClick = onOpenRemoteRepos,
        )
        Spacer(Modifier.height(10.dp))
        HubCard(
            icon = Icons.Outlined.Cloud,
            iconTint = MaterialTheme.colorScheme.primary,
            title = "Google Drive",
            badge = "beta",
            onClick = onOpenGdrive,
        )
        Spacer(Modifier.height(10.dp))
        HubCard(
            icon = Icons.Outlined.Search,
            iconTint = MaterialTheme.colorScheme.primary,
            title = "在线书源",
            badge = "beta",
            onClick = onOpenBookSources,
        )

        Spacer(Modifier.height(20.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(
                "本应用不提供、不内置任何书源或内容。在线书源的规则由你自行导入，" +
                    "指向的站点与本应用无关；抓取结果会作为普通本地书籍加入书架。",
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HubCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    onClick: () -> Unit,
    badge: String? = null,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(14.dp),
                // 参考图：浅粉圆角图标容器 + 玫瑰红图标
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = iconTint)
                }
            }
            // 只留入口名称 + beta 标记 + 箭头（对齐参考图）；入口下方的小字说明已按要求删除
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                badge?.let {
                    Spacer(Modifier.size(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        // 参考图：beta = 浅粉小标签 + 玫瑰红字
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            it,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

package com.xyreader.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xyreader.core.LibraryRepository
import com.xyreader.core.LocalRepoEntity
import java.util.Locale
import kotlinx.coroutines.launch

/** 扫描报告弹层的待展示数据：来源仓库 + 本次报告（原子持有，避免两份状态不同步） */
private data class PendingScanReport(
    val repo: LocalRepoEntity,
    val report: LibraryRepository.ScanReport,
)

/**
 * 本地仓库管理页（设置 → 仓库管理，对照 MH-ARK 原版重做）：
 * 顶部数量与添加入口，下方是仓库卡片列表（名称 + 美化地址 + 启用开关 + 刷新按钮 + 三点菜单）。
 * 添加仓库仍走 SAF 目录选择器和首页同一扫描链路。卡片"刷新仓库"完成后弹出
 * 扫描报告弹层（用时 / 新增 / 更新 / 删除四格统计）；三点菜单提供 配置仓库 与
 * 删除仓库（删除会连带移除仓库内已入库的书）。
 */
@Composable
fun ReposScreen(
    onBack: () -> Unit,
    onOpenConfig: (Long) -> Unit = {},
) {
    val repo = rememberLibraryRepository()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 顶部入口添加仓库：完成后 Snackbar 提示新增数（报告详情在各卡片"刷新仓库"里查看）
    val scan = rememberRepoScanHandle(repo) { report ->
        scope.launch { snackbar.showSnackbar("新增 ${report.added} 本") }
    }

    val localRepos by repo.localRepos.collectAsStateWithLifecycle(initialValue = emptyList())

    // 正在刷新的仓库 id 集合：对应卡片按钮转圈（各卡片互不阻塞）
    var scanningIds by remember { mutableStateOf(emptySet<Long>()) }
    // 待展示的扫描报告（页面级状态：弹层关闭/重组都不会中断刷新协程或丢报告）
    var pendingReport by remember { mutableStateOf<PendingScanReport?>(null) }
    // 待确认删除的仓库
    var pendingRemove by remember { mutableStateOf<LocalRepoEntity?>(null) }

    /** 刷新单个仓库：成功弹扫描报告弹层，异常（权限失效等）走 Snackbar */
    fun startScan(target: LocalRepoEntity) {
        if (target.id in scanningIds) return
        scope.launch {
            scanningIds = scanningIds + target.id
            try {
                val report = repo.scanLocalRepo(target.id)
                pendingReport = PendingScanReport(target, report)
            } catch (e: Exception) {
                val msg = e.message?.takeIf { it.isNotBlank() } ?: "扫描失败，请重试"
                snackbar.showSnackbar(msg)
            } finally {
                scanningIds = scanningIds - target.id
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SubpageHead(
                title = "本地仓库管理",
                subtitle = "扫描设备文件夹，把漫画入库",
                onBack = onBack,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LocalRepoHero(
                count = localRepos.size,
                scanning = scan.isScanning,
                onAdd = scan::launch,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            when {
                localRepos.isEmpty() && !scan.isScanning -> EmptyState(
                    icon = Icons.Outlined.FolderOpen,
                    title = "还没有仓库",
                    subtitle = "添加一个文件夹，扫描里面的漫画入库",
                    modifier = Modifier.weight(1f),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (scan.isScanning) {
                        item(key = "scanning-caption") {
                            ScanningCaption(Modifier.padding(horizontal = 2.dp, vertical = 2.dp))
                        }
                    }
                    items(localRepos, key = { it.id }) { item ->
                        LocalRepoCard(
                            item = item,
                            isScanning = item.id in scanningIds,
                            onToggleEnabled = { enabled ->
                                scope.launch { repo.setLocalRepoEnabled(item.id, enabled) }
                            },
                            onRefresh = { startScan(item) },
                            onConfigure = { onOpenConfig(item.id) },
                            onRemove = { pendingRemove = item },
                        )
                    }
                }
            }
        }
    }

    // 删除二次确认：仓库与其入库的书一并移除（原文件夹不受影响）
    pendingRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("删除「${target.name}」？") },
            text = { Text("仓库内的书会一并移除，原文件夹与文件不受影响；删除后需重新添加才能再次扫描。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    scope.launch {
                        repo.removeLocalRepo(target.id)
                        snackbar.showSnackbar("仓库已删除")
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            },
        )
    }

    // 扫描报告弹层（页面级状态驱动，各卡片刷新完成即弹）
    pendingReport?.let { pending ->
        ScanReportSheet(
            repo = pending.repo,
            report = pending.report,
            onDismiss = { pendingReport = null },
        )
    }
}

@Composable
private fun LocalRepoHero(
    count: Int,
    scanning: Boolean,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(19.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "$count 个仓库",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "可配置封面约定、新书默认分组与 PDF 合并。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (scanning) {
                Row(
                    modifier = Modifier.heightIn(min = 44.dp).padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "扫描中",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Button(
                    onClick = onAdd,
                    modifier = Modifier.heightIn(min = 44.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(13.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("添加", maxLines = 1)
                }
            }
        }
    }
}

/**
 * 本地仓库卡片：上部 图标块 + 名称/地址 + 启用开关，下部右侧"刷新仓库"按钮与三点菜单。
 * 仓库停用时正文（图标/名称/地址）降为半透明；开关与菜单保持可用，刷新按钮禁用
 * （停用仓库不允许刷新，与 data 层 scanLocalRepo 的约束一致）。
 */
@Composable
private fun LocalRepoCard(
    item: LocalRepoEntity,
    isScanning: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onConfigure: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // 停用仓库的正文内容降透明度
    val contentAlpha = if (item.enabled) 1f else 0.5f

    Surface(
        shape = RoundedCornerShape(19.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            // 上部：图标 + 名称/地址 + 启用开关
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.alpha(contentAlpha).size(38.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f).alpha(contentAlpha)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(2.dp))
                    Text(
                        prettyRepoAddress(item.uri),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 启用开关：停用后仓库不参与刷新，其书在书架隐藏
                Switch(
                    checked = item.enabled,
                    onCheckedChange = onToggleEnabled,
                )
            }
            // 下部右侧：刷新仓库 + 三点菜单
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onRefresh,
                    enabled = item.enabled && !isScanning,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("扫描中…")
                    } else {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("刷新仓库")
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = "更多操作",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("配置仓库") },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onConfigure()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "删除仓库",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onRemove()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 扫描报告弹层（对照 MH-ARK 原版）：顶部蓝色信息块（仓库名 + 地址 + "本地仓库"类型标签），
 * 中部 2x2 四格统计（扫描用时 / 新增 / 更新 / 删除），底部"扫描完成"按钮关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanReportSheet(
    repo: LocalRepoEntity,
    report: LibraryRepository.ScanReport,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "扫描报告",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // 头部信息块：淡 primary 底色，仿原版蓝色信息条
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            repo.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 类型小标签
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        ) {
                            Text(
                                "本地仓库",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        prettyRepoAddress(repo.uri),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 2x2 四格统计：数字大号 primary
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReportStatCell(
                    modifier = Modifier.weight(1f),
                    label = "扫描用时",
                    value = formatScanDuration(report.durationMs),
                )
                ReportStatCell(
                    modifier = Modifier.weight(1f),
                    label = "新增漫画",
                    value = report.added.toString(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReportStatCell(
                    modifier = Modifier.weight(1f),
                    label = "更新漫画",
                    value = report.updated.toString(),
                )
                ReportStatCell(
                    modifier = Modifier.weight(1f),
                    label = "删除漫画",
                    value = report.removed.toString(),
                )
            }
            // 底部完成按钮：关闭弹层
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(999.dp),
            ) {
                Text("扫描完成", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 报告统计格：大号 primary 数字 + 小号次级标签 */
@Composable
private fun ReportStatCell(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 扫描用时格式化：1 秒内显示 "xxx ms"，超过则显示 "x.x s" */
private fun formatScanDuration(ms: Long): String =
    if (ms < 1000) "$ms ms" else String.format(Locale.getDefault(), "%.1f s", ms / 1000.0)

/** 仓库地址美化：取 SAF tree URI 尾段并解码（content://…/tree/primary%3AComics -> primary:Comics） */
internal fun prettyRepoAddress(uriString: String): String = runCatching {
    val raw = Uri.parse(uriString).lastPathSegment ?: return@runCatching uriString
    Uri.decode(raw).removePrefix("tree/").ifBlank { uriString }
}.getOrDefault(uriString)

/**
 * 子页头（对应设计 .subpage-head）：40dp 圆角描边返回钮 + 标题（titleLarge/Bold）+ 副标题。
 * 本文件私有实现，避免与其他页面中的同名共用组件冲突（未改 Common.kt）。
 */
@Composable
private fun SubpageHead(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
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

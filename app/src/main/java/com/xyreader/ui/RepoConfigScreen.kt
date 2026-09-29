package com.xyreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xyreader.core.BookGroupEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 配置仓库页（本地仓库管理 → 三点菜单 → 配置仓库，对照 MH-ARK 原版）：
 * 预填当前值的表单——仓库名（≤30 字）、封面文件名约定（≤10 字，多章节漫画根目录用）、
 * 默认添加分组（新扫描的书自动归组）。保存走 updateLocalRepoConfig，成功提示后返回。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoConfigScreen(repoId: Long, onBack: () -> Unit) {
    val repo = rememberLibraryRepository()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // localRepos 流：initialValue 用 null 区分「尚未加载」与「加载完确实不存在」
    val reposState by repo.localRepos.collectAsStateWithLifecycle(initialValue = null)
    // 可选分组列表（默认添加分组下拉用）
    val groups by repo.groups.collectAsStateWithLifecycle(initialValue = emptyList())

    val current = reposState?.firstOrNull { it.id == repoId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配置仓库", fontWeight = FontWeight.SemiBold) },
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
        when {
            // Room 流首帧尚未发射：居中加载态
            reposState == null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            // 仓库不存在（已删除或非法参数）：错误态 + 返回
            current == null -> RepoNotFoundState(
                modifier = Modifier.fillMaxSize().padding(padding),
                onBack = onBack,
            )
            else -> {
                // 表单初始值以仓库 id 为 key 记住：flow 重复发射同 id 仓库时不重置用户输入
                val initial = remember(current.id) {
                    Triple(current.name, current.coverFileName, current.defaultGroupId)
                }
                var name by remember(initial) { mutableStateOf(initial.first) }
                var coverFileName by remember(initial) { mutableStateOf(initial.second) }
                var defaultGroupId by remember(initial) { mutableStateOf(initial.third) }
                var groupMenuOpen by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        // 纵向可滚动兜底小屏；imePadding 键盘弹出时内容整体上移
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // 仓库名：最多 30 字
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= 30) name = it },
                        label = { Text("仓库名") },
                        supportingText = { Text("${name.length}/30") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 封面文件名约定：最多 10 字，不含扩展名，不区分大小写
                    OutlinedTextField(
                        value = coverFileName,
                        onValueChange = { if (it.length <= 10) coverFileName = it },
                        label = { Text("封面文件名") },
                        supportingText = {
                            Column {
                                Text("${coverFileName.length}/10")
                                Text(
                                    "多章节漫画根目录下封面文件名，不区分大小写，留空不启用",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 默认添加分组下拉：不自动添加 + 各分组
                    Box {
                        Surface(
                            onClick = { groupMenuOpen = true },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "默认添加分组",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.size(2.dp))
                                    Text(
                                        selectedGroupLabel(defaultGroupId, groups),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                    )
                                }
                                Icon(
                                    Icons.Outlined.ArrowDropDown,
                                    contentDescription = "选择分组",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = groupMenuOpen,
                            onDismissRequest = { groupMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "不自动添加",
                                        color = if (defaultGroupId == null) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        fontWeight = if (defaultGroupId == null) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Normal
                                        },
                                    )
                                },
                                leadingIcon = {
                                    if (defaultGroupId == null) {
                                        Icon(
                                            Icons.Outlined.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    } else {
                                        Spacer(Modifier.size(18.dp))
                                    }
                                },
                                onClick = {
                                    defaultGroupId = null
                                    groupMenuOpen = false
                                },
                            )
                            groups.forEach { group ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            group.name,
                                            color = if (defaultGroupId == group.id) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            fontWeight = if (defaultGroupId == group.id) {
                                                FontWeight.SemiBold
                                            } else {
                                                FontWeight.Normal
                                            },
                                        )
                                    },
                                    leadingIcon = {
                                        if (defaultGroupId == group.id) {
                                            Icon(
                                                Icons.Outlined.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        } else {
                                            Spacer(Modifier.size(18.dp))
                                        }
                                    },
                                    onClick = {
                                        defaultGroupId = group.id
                                        groupMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        "新扫描到的漫画自动添加到此分组",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )

                    Spacer(Modifier.height(8.dp))
                    // 保存：名称非空才提交；成功提示后返回仓库列表
                    Button(
                        onClick = {
                            if (name.isBlank()) {
                                scope.launch { snackbar.showSnackbar("请填写仓库名") }
                            } else {
                                scope.launch {
                                    repo.updateLocalRepoConfig(
                                        repoId,
                                        name.trim(),
                                        coverFileName.trim(),
                                        defaultGroupId,
                                    )
                                    // 返回前短暂展示成功提示（返回后本页 Snackbar 会随页面销毁）
                                    scope.launch { snackbar.showSnackbar("已保存") }
                                    delay(600)
                                    onBack()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            "保存",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/** 下拉框当前显示文案：null = 不自动添加；分组被删时给出提示文案 */
private fun selectedGroupLabel(groupId: Long?, groups: List<BookGroupEntity>): String = when (groupId) {
    null -> "不自动添加"
    else -> groups.firstOrNull { it.id == groupId }?.name ?: "分组不存在"
}

/** 仓库不存在错误态：96dp 图标容器 + 文案 + 返回按钮（与新版 EmptyState 同语言） */
@Composable
private fun RepoNotFoundState(modifier: Modifier = Modifier, onBack: () -> Unit) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Icon(
                Icons.Outlined.FolderOpen,
                contentDescription = null,
                modifier = Modifier
                    .padding(28.dp)
                    .size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.size(24.dp))
        Text(
            "仓库不存在",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "它可能已被删除，请返回仓库列表查看",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(20.dp))
        Button(onClick = onBack) { Text("返回") }
    }
}

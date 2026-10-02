package com.xyreader.ui

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xyreader.core.WebDavConfigEntity
import kotlinx.coroutines.launch

/**
 * 远程仓库管理页（设置 → 仓库管理 → 远程仓库管理 beta）：
 * WebDAV 配置列表（每条支持 扫描入库 / 删除配置），右下角 FAB 弹出底部添加表单
 * （测试连接 + 保存）。扫描与测试均为 suspend 调用，repository 内部已切 IO，
 * UI 层直接 rememberCoroutineScope + scope.launch。
 */
@Composable
fun RemoteReposScreen(onBack: () -> Unit) {
    val repo = rememberLibraryRepository()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val configs by repo.webdavConfigs.collectAsStateWithLifecycle(initialValue = emptyList())

    // 正在扫描的配置 id 集合：对应卡片按钮转圈（各卡片互不阻塞）
    var scanningIds by remember { mutableStateOf(emptySet<Long>()) }
    // 待确认删除的配置
    var pendingRemove by remember { mutableStateOf<WebDavConfigEntity?>(null) }
    // 添加表单（底部弹层）开关
    var showForm by remember { mutableStateOf(false) }
    // 测试连接进行中：状态放在页面级，弹层关闭也不会中断/丢失
    var testing by remember { mutableStateOf(false) }

    /** 扫描一个远程仓库配置：成功提示新增数量，异常提示错误消息 */
    fun startScan(configId: Long) {
        if (configId in scanningIds) return
        scope.launch {
            scanningIds = scanningIds + configId
            try {
                val added = repo.scanWebDav(configId)
                snackbar.showSnackbar("新增 $added 本")
            } catch (e: Exception) {
                val msg = e.message?.takeIf { it.isNotBlank() } ?: "扫描失败，请检查网络与配置"
                snackbar.showSnackbar(msg)
            } finally {
                scanningIds = scanningIds - configId
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // 与首页/本地仓库页一致的 primary 色圆角方形 FAB
            FloatingActionButton(
                onClick = { showForm = true },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加远程仓库")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SubpageHead(
                title = "远程仓库管理",
                subtitle = "WebDAV · 流式阅读不占空间",
                onBack = onBack,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (configs.isEmpty()) {
                RemoteEmptyState(Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 能力说明条：流式阅读 vs 下载缓存
                    item(key = "stream-info") { StreamInfoCard() }
                    items(configs, key = { it.id }) { config ->
                        RemoteRepoCard(
                            config = config,
                            isScanning = config.id in scanningIds,
                            onScan = { startScan(config.id) },
                            onRemove = { pendingRemove = config },
                        )
                    }
                }
            }
        }
    }

    // 删除二次确认：仅移除配置，已入库的远程书保留
    pendingRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("删除「${target.name}」？") },
            text = { Text("删除后已入库的远程书保留，仅移除配置。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    scope.launch {
                        repo.removeWebDavConfig(target.id)
                        snackbar.showSnackbar("配置已删除，远程书籍保留")
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            },
        )
    }

    if (showForm) {
        AddWebDavSheet(
            testing = testing,
            onDismiss = { showForm = false },
            onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
            // 测试与保存在页面级 scope 执行：即使弹层随后关闭，协程也不会被连带取消
            onTest = { url, username, password ->
                scope.launch {
                    testing = true
                    try {
                        val error = repo.testWebDav(url, username, password)
                        snackbar.showSnackbar(error ?: "连接成功")
                    } catch (e: Exception) {
                        val msg = e.message?.takeIf { it.isNotBlank() } ?: "连接失败，请检查网络"
                        snackbar.showSnackbar(msg)
                    } finally {
                        testing = false
                    }
                }
            },
            onSave = { name, url, username, password ->
                scope.launch {
                    repo.addWebDavConfig(name, url, username, password)
                    showForm = false
                    snackbar.showSnackbar("已添加")
                }
            },
        )
    }
}

/** 空状态：统一 EmptyState（96dp 图标容器 + 引导文案） */
@Composable
private fun RemoteEmptyState(modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Outlined.CloudOff,
        title = "还没有远程仓库",
        subtitle = "点 + 添加坚果云或 Alist 的 WebDAV 地址",
        modifier = modifier,
    )
}

/** 顶部能力说明条：流式阅读的收益与 RAR/PDF 的缓存策略 */
@Composable
private fun StreamInfoCard() {
    Surface(
        shape = RoundedCornerShape(19.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 图标块：淡 primary 底色圆角块，与设置页行图标同语言
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                "流式阅读：ZIP/7Z/TAR 翻页即时加载不占空间；RAR/PDF 首次打开会自动下载到缓存",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 远程仓库配置卡片：绿色云图标块 + 名称/短地址 + 扫描/删除动作按钮 */
@Composable
private fun RemoteRepoCard(
    config: WebDavConfigEntity,
    isScanning: Boolean,
    onScan: () -> Unit,
    onRemove: () -> Unit,
) {
    val greenTint = accentColor(AccentColor.GREEN)
    Surface(
        shape = RoundedCornerShape(19.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(12.dp),
                color = greenTint.copy(alpha = 0.12f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = greenTint,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    config.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    shortenWebDavUrl(config.baseUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 扫描：进行中该按钮的图标原地换成转圈
            IconButton(onClick = onScan, enabled = !isScanning) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        Icons.Outlined.CloudSync,
                        contentDescription = "扫描",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 删除：弹出确认对话框
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "删除配置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * 添加 WebDAV 配置的底部弹层表单：名称 / 服务器地址 / 用户名 / 密码，
 * 支持 测试连接 与 保存 两个动作。表单字段状态留在弹层内部（关闭即重置），
 * 测试/保存通过回调交给页面层执行 suspend 调用，结果统一走页面级 Snackbar。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddWebDavSheet(
    testing: Boolean,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onTest: (url: String, username: String, password: String) -> Unit,
    onSave: (name: String, url: String, username: String, password: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            // imePadding：键盘弹出时内容整体上移，纵向可滚动兜底小屏
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "添加远程仓库",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("服务器地址") },
                placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Outlined.VisibilityOff
                            else Icons.Outlined.Visibility,
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                        )
                    }
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            // 测试连接：地址/用户名非空才发请求；null 视为成功，否则展示错误描述
            OutlinedButton(
                onClick = {
                    val normalized = normalizeWebDavUrl(url)
                    if (normalized.isBlank() || username.isBlank()) {
                        onMessage("请先填写服务器地址和用户名")
                    } else {
                        onTest(normalized, username.trim(), password)
                    }
                },
                enabled = !testing,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("测试中…")
                } else {
                    Text("测试连接")
                }
            }
            // 保存：名称/地址/用户名非空才入库（全宽胶囊主按钮）
            Button(
                onClick = {
                    when {
                        name.isBlank() -> onMessage("请填写名称")
                        url.isBlank() -> onMessage("请填写服务器地址")
                        username.isBlank() -> onMessage("请填写用户名")
                        else -> onSave(
                            name.trim(),
                            normalizeWebDavUrl(url),
                            username.trim(),
                            password,
                        )
                    }
                },
                enabled = !testing,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("保存")
            }
            Text(
                "坚果云请使用应用密码而非登录密码；Alist、InfiniCLOUD 等均适用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 提交前补全协议头：无 http(s):// 前缀时自动补 https:// */
private fun normalizeWebDavUrl(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return t
    val hasScheme = t.startsWith("http://", ignoreCase = true) ||
        t.startsWith("https://", ignoreCase = true)
    return if (hasScheme) t else "https://$t"
}

/** baseUrl 短化显示：去 scheme 留 host+路径（https://dav.jianguoyun.com/dav/ -> dav.jianguoyun.com/dav/） */
private fun shortenWebDavUrl(url: String): String {
    val schemeEnd = url.indexOf("://")
    return if (schemeEnd >= 0) url.substring(schemeEnd + 3) else url
}

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

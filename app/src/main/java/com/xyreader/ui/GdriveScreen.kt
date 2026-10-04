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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xyreader.core.GoogleDriveAccountEntity
import com.xyreader.data.GoogleDriveAuth
import kotlinx.coroutines.launch

/**
 * Google Drive（Google One）账号管理页（设置 → 仓库管理 → Google Drive beta）：
 * OAuth 说明与账号列表（每条支持 扫描入库 / 删除授权），列表内添加入口弹出底部表单
 * （名称 / Client ID / Client Secret / 目标文件夹 ID → 授权并保存）。
 *
 * 扫描与授权均为 suspend 调用：repository 内部已切 IO，UI 层直接页面级
 * rememberCoroutineScope + scope.launch。特别注意 authorize 会拉起浏览器并
 * 长时间阻塞等待回调，绝不能在弹层内部 scope 里执行——弹层 dismiss 会取消
 * 其内部协程，导致授权流程被连带杀掉；页面级协程不受弹层关闭影响。
 */
@Composable
fun GdriveScreen(onBack: () -> Unit) {
    val repo = rememberLibraryRepository()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val accounts by repo.gdriveAccounts.collectAsStateWithLifecycle(initialValue = emptyList())

    // 正在扫描的账号 id 集合：对应卡片按钮转圈（各卡片互不阻塞）
    var scanningIds by remember { mutableStateOf(emptySet<Long>()) }
    // 待确认删除的账号
    var pendingRemove by remember { mutableStateOf<GoogleDriveAccountEntity?>(null) }
    // 添加表单（底部弹层）开关
    var showForm by remember { mutableStateOf(false) }
    // 授权 + 保存进行中：状态放在页面级，弹层关闭也不会中断/丢失授权流程
    var authorizing by remember { mutableStateOf(false) }

    /** 扫描一个 Google Drive 账号：成功提示新增数量，异常直接展示错误消息（如"Google 授权已失效"） */
    fun startScan(accountId: Long) {
        if (accountId in scanningIds) return
        scope.launch {
            scanningIds = scanningIds + accountId
            try {
                val added = repo.scanGdrive(accountId)
                snackbar.showSnackbar("新增 $added 本")
            } catch (e: Exception) {
                val msg = e.message?.takeIf { it.isNotBlank() } ?: "扫描失败，请检查网络与授权"
                snackbar.showSnackbar(msg)
            } finally {
                scanningIds = scanningIds - accountId
            }
        }
    }

    /**
     * 走浏览器授权并保存账号：authorize 长时间阻塞（等用户在浏览器完成登录与同意），
     * 必须在页面级 scope 执行；授权期间弹层保持打开并显示"等待浏览器授权…"状态。
     */
    fun authorizeAndSave(name: String, clientId: String, clientSecret: String, folderId: String) {
        if (authorizing) return
        scope.launch {
            authorizing = true
            try {
                val refreshToken = GoogleDriveAuth.authorize(context, clientId, clientSecret)
                repo.addGdriveAccount(name, clientId, clientSecret, folderId, refreshToken)
                showForm = false
                snackbar.showSnackbar("授权成功，已保存")
            } catch (e: Exception) {
                val msg = e.message?.takeIf { it.isNotBlank() } ?: "授权失败，请重试"
                snackbar.showSnackbar(msg)
            } finally {
                authorizing = false
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SubpageHead(
                title = "Google Drive",
                subtitle = "OAuth 授权与云端扫描",
                onBack = onBack,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 顶部说明条：能力、网络要求与 OAuth 客户端创建指引；空态也保留说明卡。
                item(key = "gdrive-info") { GdriveInfoCard() }
                if (accounts.isEmpty()) {
                    item(key = "empty-state") {
                        GdriveEmptyState(
                            modifier = Modifier.fillMaxWidth().height(320.dp),
                            onAdd = { showForm = true },
                        )
                    }
                } else {
                    items(accounts, key = { it.id }) { account ->
                        GdriveAccountCard(
                            account = account,
                            isScanning = account.id in scanningIds,
                            onScan = { startScan(account.id) },
                            onRemove = { pendingRemove = account },
                        )
                    }
                    item(key = "add-account") {
                        GdriveAddAction(
                            label = "添加 Google Drive 账号",
                            primary = false,
                            onClick = { showForm = true },
                        )
                    }
                }
            }
        }
    }

    // 删除二次确认：仅移除授权信息，已入库的云端书保留
    pendingRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("删除「${target.name}」？") },
            text = { Text("删除后已入库的云端书保留，仅移除授权。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    scope.launch {
                        repo.removeGdriveAccount(target.id)
                        snackbar.showSnackbar("授权已删除，云端书籍保留")
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            },
        )
    }

    if (showForm) {
        AddGdriveSheet(
            authorizing = authorizing,
            onDismiss = { showForm = false },
            onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
            // 授权与保存在页面级 scope 执行：即使弹层随后关闭，授权流程也不会被连带取消
            onAuthorizeAndSave = { name, clientId, clientSecret, folderId ->
                authorizeAndSave(name, clientId, clientSecret, folderId)
            },
        )
    }
}

/** 空状态：与预览一样在空态内保留实际添加入口。 */
@Composable
private fun GdriveEmptyState(modifier: Modifier = Modifier, onAdd: () -> Unit) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(
            icon = Icons.Outlined.CloudOff,
            title = "还没有 Google Drive 账号",
            subtitle = "需联网完成 Google OAuth 授权，并填写客户端信息",
            modifier = Modifier.weight(1f),
        )
        GdriveAddAction(label = "添加账号", primary = true, onClick = onAdd)
    }
}

@Composable
private fun GdriveAddAction(label: String, primary: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        if (primary) {
            Button(
                onClick = onClick,
                modifier = Modifier.height(44.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(13.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, maxLines = 1)
            }
        } else {
            FilledTonalButton(
                onClick = onClick,
                modifier = Modifier.height(44.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(13.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, maxLines = 1)
            }
        }
    }
}

/** 顶部说明条：流式阅读能力、网络要求与 OAuth 客户端创建指引（文案较长，图标顶对齐） */
@Composable
private fun GdriveInfoCard() {
    Surface(
        shape = RoundedCornerShape(19.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
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
                "授权后可扫描 Google Drive（Google One）中的漫画文件夹；" +
                    "ZIP/7Z/TAR 翻页即时加载不占空间，RAR/PDF 首次打开自动下载到缓存。" +
                    "添加账号需联网完成 Google OAuth 授权，并填写客户端 ID 和密钥；目标文件夹 ID 可选。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Google Drive 账号卡片：珊瑚色云图标块 + 名称/短 Client ID + 扫描/删除动作按钮 */
@Composable
private fun GdriveAccountCard(
    account: GoogleDriveAccountEntity,
    isScanning: Boolean,
    onScan: () -> Unit,
    onRemove: () -> Unit,
) {
    val tertiaryTint = MaterialTheme.colorScheme.tertiary
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
                color = tertiaryTint.copy(alpha = 0.12f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = tertiaryTint,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    account.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    shortenClientId(account.clientId),
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
                    contentDescription = "删除授权",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * 添加 Google Drive 账号的底部弹层表单：名称 / Client ID / Client Secret（密文 + 可见性切换）/
 * 目标文件夹 ID（可选），唯一动作"授权并保存"。表单字段状态留在弹层内部（关闭即重置），
 * 授权通过回调交给页面层执行长时间 suspend 调用，结果统一走页面级 Snackbar。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddGdriveSheet(
    authorizing: Boolean,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onAuthorizeAndSave: (name: String, clientId: String, clientSecret: String, folderId: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var folderId by remember { mutableStateOf("") }
    var secretVisible by remember { mutableStateOf(false) }

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
                "添加 Google Drive 账号",
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
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("Client ID") },
                placeholder = { Text("xxxx.apps.googleusercontent.com") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = clientSecret,
                onValueChange = { clientSecret = it },
                label = { Text("Client Secret") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (secretVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { secretVisible = !secretVisible }) {
                        Icon(
                            if (secretVisible) Icons.Outlined.VisibilityOff
                            else Icons.Outlined.Visibility,
                            contentDescription = if (secretVisible) "隐藏密钥" else "显示密钥",
                        )
                    }
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = folderId,
                onValueChange = { folderId = it },
                label = { Text("目标文件夹 ID（可选）") },
                placeholder = { Text("留空扫描整个 My Drive") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            // 授权并保存：三项必填非空才发起；authorize 阻塞等待浏览器回调，期间按钮转为等待态
            Button(
                onClick = {
                    when {
                        name.isBlank() -> onMessage("请填写名称")
                        clientId.isBlank() -> onMessage("请填写 Client ID")
                        clientSecret.isBlank() -> onMessage("请填写 Client Secret")
                        else -> onAuthorizeAndSave(
                            name.trim(),
                            clientId.trim(),
                            clientSecret.trim(),
                            folderId.trim(),
                        )
                    }
                },
                enabled = !authorizing,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (authorizing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("等待浏览器授权…")
                } else {
                    Text("授权并保存")
                }
            }
            Text(
                "Client ID / Client Secret 来自 Google Cloud 控制台创建的桌面应用 OAuth 客户端。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** clientId 短化显示：前 8 字符 + 省略号（过短则原样展示） */
private fun shortenClientId(clientId: String): String =
    if (clientId.length <= 8) clientId else clientId.take(8) + "…"

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

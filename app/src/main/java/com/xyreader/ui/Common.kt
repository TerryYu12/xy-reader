package com.xyreader.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xyreader.core.LibraryRepository
import com.xyreader.data.AppGraph
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 获取全局书库仓库（AppGraph 单例，随 Activity 生命周期记住即可） */
@Composable
fun rememberLibraryRepository(): LibraryRepository {
    val context = LocalContext.current
    return remember { AppGraph.libraryRepository(context) }
}

/**
 * 目录扫描控制器：封装「选择目录 -> 持久化读权限 -> 入库扫描」的完整链路。
 * 首页 FAB、书架页 + 按钮、仓库管理页共用。
 */
class RepoScanHandle internal constructor(
    private val pickTree: () -> Unit,
    private val scanning: State<Boolean>,
) {
    /** 是否正在扫描（用于把 FAB/按钮切换成加载态） */
    val isScanning: Boolean get() = scanning.value

    /** 打开系统目录选择器；扫描中重复点击无效 */
    fun launch() {
        if (!scanning.value) pickTree()
    }
}

/**
 * 创建一个 [RepoScanHandle]。
 * @param onFinished 扫描完成回调，参数为本次扫描报告（新增/更新/删除/用时）
 */
@Composable
fun rememberRepoScanHandle(
    repo: LibraryRepository,
    onFinished: (LibraryRepository.ScanReport) -> Unit = {},
): RepoScanHandle {
    val context = LocalContext.current
    val scope: CoroutineScope = rememberCoroutineScope()
    val scanning = remember { mutableStateOf(false) }
    val latestOnFinished by rememberUpdatedState(onFinished)

    // SAF 目录树选择器：拿到 tree Uri 后持久化权限并入库扫描（本地仓库 Room 链路）
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            scanning.value = true
            try {
                // 个别文件选择器可能不授予持久权限，失败时仍尝试扫描本次会话
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                runCatching {
                    // addLocalRepo 按 uri 幂等：重复添加同一目录返回已有仓库 id
                    val repoId = repo.addLocalRepo(uri.toString(), null)
                    repo.scanLocalRepo(repoId)
                }.onSuccess { report -> latestOnFinished(report) }
            } finally {
                scanning.value = false
            }
        }
    }

    return remember(picker) { RepoScanHandle({ picker.launch(null) }, scanning) }
}

/** 右下角扫描 FAB：闲置时是主色 + 号，扫描中变成转圈容器 */
@Composable
fun ScanFab(scan: RepoScanHandle, modifier: Modifier = Modifier) {
    if (scan.isScanning) {
        // 扫描中：与 FAB 同尺寸的加载容器，避免布局跳动
        Surface(
            modifier = modifier.size(56.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    } else {
        FloatingActionButton(
            onClick = scan::launch,
            modifier = modifier,
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "导入漫画")
        }
    }
}

/** 行内「正在扫描…」小提示 */
@Composable
fun ScanningCaption(modifier: Modifier = Modifier, text: String = "正在扫描…") {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 分组小标题（仓库管理/漫画管理/其他、我的书架），overline 风格小字 + 宽字距 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * 通用空状态：96dp 大圆角图标容器（surfaceContainerHigh 底 + primary 图标 40dp）
 * + 主文案 + 副文案，整体居中并带 32dp 边距留白。
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(28.dp)
                    .size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 把仓库 tree URI 美化成短名字：…/tree/primary%3AComics -> "Comics" */
fun prettyRepoName(uriString: String): String = runCatching {
    val uri = Uri.parse(uriString)
    val raw = uri.lastPathSegment ?: return@runCatching uriString
    val decoded = Uri.decode(raw).removePrefix("tree/")
    // primary:Comics / 1A2B-3C4D:Download/Comics 这类取冒号后的可读部分
    val name = decoded.substringAfter(':', missingDelimiterValue = decoded)
    name.substringAfterLast('/').ifBlank { decoded }
}.getOrDefault(uriString)

/** epoch 毫秒 -> yyyy-MM-dd HH:mm（书签列表用） */
private val bookmarkDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

fun formatDate(epochMs: Long): String = bookmarkDateFormat.format(Date(epochMs))

/** 允许的颜色：仅主题色；此处仅供内部 半透明浮层 使用 */
internal val ScrimColor: Color = Color.Black.copy(alpha = 0.45f)

/**
 * 顶部胶囊分组钮（阅读配置管理页与阅读设置弹层共用）：
 * 选中填充主色容器、未选中用中性容器（Surface(onClick) 带涟漪反馈，避免个别设备上
 * clip+clickable 组合点击不响）。
 */
@Composable
fun CapsuleTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

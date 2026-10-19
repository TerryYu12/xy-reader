package com.xyreader.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 社区里比较常用的「源从哪来」入口（仅链接，应用不内置、不托管任何源） */
private data class SourceLink(val title: String, val subtitle: String, val url: String)

private val SOURCE_LINKS = listOf(
    SourceLink(
        title = "Legado 源聚合站（社区）",
        subtitle = "legado.aoaostar.com · 小说/漫画/AI 源合集",
        url = "https://legado.aoaostar.com/",
    ),
    SourceLink(
        title = "aoaostar/legado 源仓库",
        subtitle = "github.com · 书源 JSON 的公开仓库",
        url = "https://github.com/aoaostar/legado",
    ),
    SourceLink(
        title = "Legado 官方仓库",
        subtitle = "github.com/gedoor/legado · 阅读本体与书源格式说明",
        url = "https://github.com/gedoor/legado",
    ),
)

/**
 * 「从哪里找源」卡片：只放社区入口链接，点击用浏览器打开。
 *
 * 边界：这些是第三方社区资源，与本应用无关；应用不内置、不托管、不代理任何源与内容。
 */
@Composable
fun SourceLinksCard() {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("从哪里找源", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "下面是社区里常用的公开入口，点开用浏览器访问；导入前请自行确认来源合法。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SOURCE_LINKS.forEach { link ->
                Surface(
                    onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                link.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                link.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = "在浏览器打开",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

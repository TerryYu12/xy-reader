package com.xyreader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 隐私政策页（设置 → 隐私政策）。
 *
 * 内容与应用仓库根目录的 PRIVACY.md 保持同步：本应用不收集任何数据，
 * 网络行为仅发生在用户主动配置远程仓库（WebDAV / Google Drive）时。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("隐私政策", fontWeight = FontWeight.SemiBold) },
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                "XY reader 隐私政策",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "更新日期：2026-09-30",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PolicyParagraph(
                "总则",
                "XY reader 是一款本地漫画 / 小说阅读工具。本应用的设计以隐私为优先：" +
                    "不收集你的任何数据，所有信息都只保存在你自己的设备上。",
            )
            PolicyParagraph(
                "信息收集",
                "本应用不收集任何个人信息：\n\n" +
                    "• 无账号系统，无需注册或登录；\n" +
                    "• 无广告、无统计分析、无崩溃上报等第三方追踪组件；\n" +
                    "• 不向作者或任何第三方服务器发送你的数据。",
            )
            PolicyParagraph(
                "本地数据",
                "以下数据仅保存在设备本地：\n\n" +
                    "• 书库记录、阅读进度、书签、分组与阅读设置；\n" +
                    "• 远程仓库（WebDAV）的服务器地址与账号凭据；\n" +
                    "• Google Drive 的 OAuth 授权令牌。\n\n" +
                    "卸载应用即可清除全部本地数据。",
            )
            PolicyParagraph(
                "网络通信",
                "仅在以下情形发起网络请求，且全部由你的操作直接触发、直达你指定的服务器：\n\n" +
                    "• WebDAV 远程仓库：连接你填写的服务器地址，浏览目录、读取书籍；\n" +
                    "• Google Drive：通过 Google 官方 API 读取你授权范围内（drive.readonly，只读）的文件。\n\n" +
                    "除此之外，本应用不进行任何网络通信。",
            )
            PolicyParagraph(
                "权限使用",
                "• 网络权限（INTERNET）：仅用于连接你配置的远程仓库；\n" +
                    "• 文件与文件夹访问：通过系统文件选择器授权，应用仅访问你主动选择的文件。",
            )
            PolicyParagraph(
                "第三方服务",
                "使用 Google Drive 功能时，相关数据访问同时受 Google 隐私政策约束；" +
                    "授权可随时在 Google 账号的「第三方应用访问权限」中撤销。",
            )
            PolicyParagraph(
                "政策更新",
                "本政策如有变更，将随应用版本更新进行说明。如有疑问，欢迎通过 GitHub Issues 反馈。",
            )

            Spacer(Modifier.height(36.dp))
        }
    }
}

/** 政策分节：小标题 + 正文段落（行高加大便于阅读） */
@Composable
private fun PolicyParagraph(title: String, body: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 23.sp,
    )
}

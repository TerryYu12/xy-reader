package com.xyreader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xyreader.feedback.FeedbackResult
import com.xyreader.feedback.FeedbackUploader
import com.xyreader.feedback.feedbackErrorMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * BUG 反馈对话框（设置 → 其他 → BUG 反馈）。
 *
 * 流程：填写问题描述（必填，≤ [FeedbackUploader.MAX_NOTE_CHARS] 字）→「提交」→ 显示进度、禁用输入 →
 * 成功：切到成功态，显示编号并自动复制到剪贴板，提供「复制编号」；失败：显示对应文案，可直接重试。
 * 上传期间点「取消」会中止上传并回到编辑态（不会白白在服务端留下半截请求）。
 *
 * - 上传协程挂在 [rememberCoroutineScope]：对话框离开组合（关闭）时一并取消；已经完成的结果不受影响，
 *   编号早已写入剪贴板；
 * - 问题描述与成功编号用 [rememberSaveable]：旋转屏幕（应用有「自动旋屏」）不会丢掉用户写了一半的内容；
 * - 对话框本身不触碰网络细节，全部委托给 [FeedbackUploader]，测试时可注入 [uploader]。
 */
@Composable
fun FeedbackDialog(
    onDismiss: () -> Unit,
    uploader: FeedbackUploader = remember { FeedbackUploader() },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var note by rememberSaveable { mutableStateOf("") }
    /** 成功后的编号；空串 = 尚未成功 */
    var successId by rememberSaveable { mutableStateOf("") }
    var copied by rememberSaveable { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var uploadJob by remember { mutableStateOf<Job?>(null) }

    val succeeded = successId.isNotEmpty()
    val canSubmit = note.isNotBlank() && !uploading

    fun submit() {
        if (!canSubmit) return
        val text = note.trim()
        uploading = true
        errorMessage = null
        uploadJob = scope.launch {
            val result = uploader.upload(text)
            uploading = false
            uploadJob = null
            when (result) {
                is FeedbackResult.Success -> {
                    successId = result.id
                    copied = copyFeedbackId(context, result.id)
                }
                is FeedbackResult.Failure -> errorMessage = feedbackErrorMessage(result.code)
            }
        }
    }

    fun cancelUpload() {
        uploadJob?.cancel()
        uploadJob = null
        uploading = false
    }

    AlertDialog(
        // 上传中不响应点外部 / 返回键，避免误触中断；想中止请点「取消」
        onDismissRequest = { if (!uploading) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                if (succeeded) "提交成功" else "BUG 反馈",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (succeeded) {
                    FeedbackSuccessContent(id = successId, copied = copied)
                } else {
                    FeedbackFormContent(
                        note = note,
                        onNoteChange = { note = FeedbackUploader.clampNote(it) },
                        uploading = uploading,
                        errorMessage = errorMessage,
                    )
                }
            }
        },
        confirmButton = {
            if (succeeded) {
                TextButton(onClick = onDismiss) { Text("完成") }
            } else {
                TextButton(onClick = ::submit, enabled = canSubmit) {
                    Text(
                        when {
                            uploading -> "提交中…"
                            errorMessage != null -> "重试"
                            else -> "提交"
                        },
                    )
                }
            }
        },
        dismissButton = {
            if (succeeded) {
                TextButton(
                    onClick = {
                        copied = copyFeedbackId(context, successId)
                        Toast.makeText(
                            context,
                            if (copied) "已复制编号" else "复制失败，请手动记下编号",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                ) { Text("复制编号") }
            } else {
                TextButton(onClick = { if (uploading) cancelUpload() else onDismiss() }) { Text("取消") }
            }
        },
    )
}

/** 编辑态：输入框 + 计数 + 上传内容说明 + 进度 / 错误提示 */
@Composable
private fun FeedbackFormContent(
    note: String,
    onNoteChange: (String) -> Unit,
    uploading: Boolean,
    errorMessage: String?,
) {
    OutlinedTextField(
        value = note,
        onValueChange = onNoteChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !uploading,
        label = { Text("问题描述（必填）") },
        placeholder = { Text("遇到了什么问题？如何复现？") },
        minLines = 4,
        maxLines = 8,
        shape = RoundedCornerShape(14.dp),
        supportingText = {
            Text(
                "${note.length}/${FeedbackUploader.MAX_NOTE_CHARS}",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
            )
        },
    )
    Text(
        "提交后会上传：问题描述、应用版本、设备型号与系统版本、最近的运行日志" +
            "（已去除密码、令牌与链接参数）。这些内容存放在作者的私有 GitHub 仓库，仅用于排查问题。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (uploading) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "正在上传…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (errorMessage != null) {
        Text(
            errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** 成功态：醒目的编号 + 已复制 / 请记下 的提示 */
@Composable
private fun FeedbackSuccessContent(id: String, copied: Boolean) {
    Text(
        "反馈编号",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SelectionContainer {
        Text(
            id,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Spacer(Modifier.height(2.dp))
    Text(
        if (copied) {
            "编号已复制，请发给作者（如 GitHub Issues）"
        } else {
            "请记下编号并发给作者（如 GitHub Issues）"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** 把编号写入系统剪贴板；没有剪贴板服务或写入失败返回 false */
private fun copyFeedbackId(context: Context, id: String): Boolean = runCatching {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (manager == null) {
        false
    } else {
        manager.setPrimaryClip(ClipData.newPlainText("反馈编号", id))
        true
    }
}.getOrDefault(false)

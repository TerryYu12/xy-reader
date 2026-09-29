package com.xyreader

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 外部「用其他应用打开 / 分享」进来的单个文件 */
data class SharedOpen(
    val uri: Uri,
    val mimeType: String?,
    /** 发送方附带的显示名提示（可空；导入时向 ContentResolver 兜底查询） */
    val nameHint: String?,
)

/**
 * 外部文件接收入口：MainActivity 在 onCreate / onNewIntent 投递，导航层消费。
 *
 * 只接收 content:// 与 file:// 的本地来源（http(s) 等交给浏览器）；
 * 状态仅存内存：进程被杀后系统会用原 Intent 重新拉起 Activity，无需持久化。
 */
object SharedIntake {

    private val _pending = MutableStateFlow<SharedOpen?>(null)

    /** 待处理的外部文件流（导航层收集） */
    val pending: StateFlow<SharedOpen?> = _pending.asStateFlow()

    /** 解析并投递一个外部意图；非 VIEW/SEND 或没有可用本地 Uri 时忽略 */
    fun submit(intent: Intent?) {
        val open = parse(intent) ?: return
        _pending.value = open
    }

    /** 标记已消费（导入开始前调用，避免重复触发） */
    fun consume() {
        _pending.value = null
    }

    private fun parse(intent: Intent?): SharedOpen? {
        intent ?: return null
        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        } ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "content" && scheme != "file") return null
        return SharedOpen(
            uri = uri,
            mimeType = intent.type,
            nameHint = intent.getStringExtra(Intent.EXTRA_TITLE),
        )
    }
}

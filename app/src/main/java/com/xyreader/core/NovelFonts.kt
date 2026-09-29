package com.xyreader.core

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.res.ResourcesCompat
import com.xyreader.R
import java.io.File

/**
 * 小说字体解析与「导入字体」管理。
 *
 * - 内置字体（霞鹜文楷 Lite / MiSans / 朱雀仿宋）随 APK 打包在 res/font；
 * - 系统三族用 Typeface.create 的家庭名；
 * - 用户导入的字体（本地 ttf/otf/ttc）复制进 filesDir/fonts 私有目录，
 *   [ReaderPrefs.novelCustomFont] 存文件名，非空时优先于字体族枚举。
 *
 * resolve 结果按 key 缓存（Typeface 构建较贵）；任何加载失败都退回系统无衬线，
 * 绝不把异常带进排版管线。
 */
object NovelFonts {

    /** 内置字体 → res/font 资源 id（构建期必须有对应文件） */
    private val BUNDLED_RES: Map<NovelFontFamily, Int> = mapOf(
        NovelFontFamily.BUNDLED_WENKAI to R.font.lxgw_wenkai_lite,
        NovelFontFamily.BUNDLED_MISANS to R.font.misans_regular,
        NovelFontFamily.BUNDLED_ZHUQUE to R.font.zhuque_fangsong,
    )

    private const val IMPORT_DIR = "fonts"

    private val cache = mutableMapOf<String, Typeface>()

    // ---------- 导入字体目录 ----------

    /** 导入字体目录（可能尚不存在） */
    fun importedDir(context: Context): File = File(context.filesDir, IMPORT_DIR)

    /** 已导入字体文件（仅字体后缀，按文件名排序） */
    fun listImported(context: Context): List<File> =
        importedDir(context)
            .listFiles { f -> f.isFile && isFontFile(f.name) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /** 列表显示名：去掉扩展名 */
    fun displayName(file: File): String = file.name.substringBeforeLast('.')

    fun isFontFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc")
    }

    /**
     * 从 SAF Uri 导入字体：复制进私有目录 → 用 Typeface 实际加载校验
     * （失败即删除并报错）→ 返回落地文件。重名时追加时间戳后缀。
     */
    fun import(context: Context, uri: Uri): Result<File> = runCatching {
        val dir = importedDir(context)
        if (!dir.exists() && !dir.mkdirs()) error("无法创建字体目录")
        val rawName = queryDisplayName(context, uri) ?: "font_${System.currentTimeMillis()}"
        val sanitized = rawName
            .replace(Regex("[^0-9A-Za-z._\\-\\u4e00-\\u9fa5]"), "_")
            .take(64)
            .ifBlank { "font" }
        val base = if (isFontFile(sanitized)) sanitized else "$sanitized.ttf"
        var target = File(dir, base)
        if (target.exists()) {
            val ts = System.currentTimeMillis() % 100000
            target = File(dir, "${base.substringBeforeLast('.')}_$ts.${base.substringAfterLast('.')}")
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取所选文件")
        // 校验：能构建 Typeface 才算有效字体；否则删除脏文件
        try {
            Typeface.createFromFile(target)
        } catch (e: Exception) {
            target.delete()
            error("不是有效的字体文件（支持 ttf / otf / ttc）")
        }
        target
    }

    /** 删除导入字体文件 */
    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()

    // ---------- Typeface 解析 ----------

    /**
     * 解析当前生效的 Typeface：导入字体 > 内置字体 > 系统族；失败逐级回退。
     * [bold] 为 true 时用合成加粗（Typeface.create(base, BOLD)），与旧行为一致。
     */
    fun resolve(
        context: Context,
        family: NovelFontFamily,
        customFont: String?,
        bold: Boolean,
    ): Typeface {
        val base = resolveCustom(context, customFont)
            ?: resolveBundled(context, family)
            ?: systemFamily(family)
        return if (bold) Typeface.create(base, Typeface.BOLD) else base
    }

    private fun resolveCustom(context: Context, customFont: String?): Typeface? {
        val name = customFont?.takeIf { it.isNotBlank() } ?: return null
        val file = File(importedDir(context), name)
        if (!file.isFile) return null
        val key = "file:${file.absolutePath}:${file.lastModified()}"
        cache[key]?.let { return it }
        val tf = runCatching { Typeface.createFromFile(file) }.getOrNull() ?: return null
        cache[key] = tf
        return tf
    }

    private fun resolveBundled(context: Context, family: NovelFontFamily): Typeface? {
        val res = BUNDLED_RES[family] ?: return null
        val key = "res:$res"
        cache[key]?.let { return it }
        val tf = runCatching { ResourcesCompat.getFont(context, res) }.getOrNull() ?: return null
        cache[key] = tf
        return tf
    }

    private fun systemFamily(family: NovelFontFamily): Typeface = when (family) {
        NovelFontFamily.SYSTEM_SERIF -> Typeface.SERIF
        NovelFontFamily.SYSTEM_MONOSPACE -> Typeface.MONOSPACE
        else -> Typeface.SANS_SERIF
    }
}

package com.xyreader.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xyreader.core.ImageScale
import com.xyreader.core.MangaDirection
import com.xyreader.core.NovelFontFamily
import com.xyreader.core.NovelFontSize
import com.xyreader.core.NovelFontWeight
import com.xyreader.core.PageMode
import com.xyreader.core.ReadBackground
import com.xyreader.core.ReaderPrefs
import com.xyreader.core.ScreenOrientation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 阅读配置的 DataStore。注意：同一个 preferences 文件全局只能存在一个
 * DataStore 实例，因此委托必须定义在文件顶层；文件名 "reader_prefs"
 * 与仓库列表用的 "repositories" 不同，两个实例互不冲突。
 */
private val Context.readerPrefsDataStore by preferencesDataStore(name = "reader_prefs")

/** 各配置项在 DataStore 里的 key（存枚举的 name 字符串） */
private val KEY_PAGE_MODE = stringPreferencesKey("page_mode")
private val KEY_MANGA_DIRECTION = stringPreferencesKey("manga_direction")
private val KEY_SCREEN_ORIENTATION = stringPreferencesKey("screen_orientation")

/** 阅读背景色：存 ReadBackground.name，缺失/非法值回退纯黑 */
private val KEY_READ_BACKGROUND = stringPreferencesKey("read_background")

/** 阅读亮度：存 Float.toString（如 "0.35"）；空串或缺失 = 跟随系统（null） */
private val KEY_BRIGHTNESS = stringPreferencesKey("brightness")

/** 点击翻页：缺省 true（未设置时保持历史行为 = 点击分区翻页） */
private val KEY_TAP_TURN_PAGE = booleanPreferencesKey("tap_turn_page")

/** 双击放大（页内 1x ↔ 2.5x）：缺省 true */
private val KEY_DOUBLE_TAP_ZOOM = booleanPreferencesKey("double_tap_zoom")

/** 阅读时屏幕常亮：缺省 false */
private val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")

/** 图片缩放：存 ImageScale.name，缺失/非法值回退 FIT */
private val KEY_IMAGE_SCALE = stringPreferencesKey("image_scale")

/** 文字小说字号：存 NovelFontSize.name，缺失/非法值回退中号 */
private val KEY_NOVEL_FONT_SIZE = stringPreferencesKey("novel_font_size")

/** 连续字号（12-36sp）；缺失时读取旧版的三档枚举 */
private val KEY_NOVEL_FONT_SIZE_SP = stringPreferencesKey("novel_font_size_sp")
private val KEY_NOVEL_FONT_FAMILY = stringPreferencesKey("novel_font_family")
private val KEY_NOVEL_FONT_WEIGHT = stringPreferencesKey("novel_font_weight")

/** 导入的自定义字体文件名；空串/缺失 = 未选导入字体（用枚举字体族） */
private val KEY_NOVEL_CUSTOM_FONT = stringPreferencesKey("novel_custom_font")

/** 小说正文行距倍率、四边页边距（px）和字间距（px） */
private val KEY_NOVEL_LINE_SPACING = stringPreferencesKey("novel_line_spacing")
private val KEY_NOVEL_MARGIN_TOP = stringPreferencesKey("novel_margin_top_px")
private val KEY_NOVEL_MARGIN_BOTTOM = stringPreferencesKey("novel_margin_bottom_px")
private val KEY_NOVEL_MARGIN_LEFT = stringPreferencesKey("novel_margin_left_px")
private val KEY_NOVEL_MARGIN_RIGHT = stringPreferencesKey("novel_margin_right_px")
private val KEY_NOVEL_LETTER_SPACING = stringPreferencesKey("novel_letter_spacing_px")

/** 首行缩进：缺省 true（中文小说标准排版，可关闭） */
private val KEY_NOVEL_FIRST_LINE_INDENT = booleanPreferencesKey("novel_first_line_indent")

/** 章首另起一页：缺省 true（主流阅读器惯例，可关闭） */
private val KEY_NOVEL_CHAPTER_NEW_PAGE = booleanPreferencesKey("novel_chapter_new_page")

/** 把存储的字符串解析回枚举；缺失或非法值回退到默认项 */
private inline fun <reified T : Enum<T>> parseEnum(raw: String?, fallback: T): T =
    raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

/**
 * 阅读器配置（设置页-阅读配置）的持久化读写。
 * 由 [LibraryRepositoryImpl] 持有单例，间接保证 DataStore 实例唯一。
 */
class ReaderPrefsStore(context: Context) {

    private val dataStore = context.applicationContext.readerPrefsDataStore

    /** 当前阅读配置；任何一项未写入过或值非法时回退该项默认值，与历史存量行为一致 */
    val prefs: Flow<ReaderPrefs> = dataStore.data.map { p ->
        val legacyFontSize = parseEnum(p[KEY_NOVEL_FONT_SIZE], NovelFontSize.MEDIUM)
        ReaderPrefs(
            pageMode = parseEnum(p[KEY_PAGE_MODE], PageMode.LEFT_RIGHT),
            mangaDirection = parseEnum(p[KEY_MANGA_DIRECTION], MangaDirection.LTR),
            screenOrientation = parseEnum(p[KEY_SCREEN_ORIENTATION], ScreenOrientation.SYSTEM),
            readBackground = parseEnum(p[KEY_READ_BACKGROUND], ReadBackground.BLACK),
            // 空串/缺失/非法数字都视为"跟随系统"；合法值约束回 0.01-1.0
            brightness = p[KEY_BRIGHTNESS]
                ?.takeIf { it.isNotBlank() }
                ?.toFloatOrNull()
                ?.coerceIn(0.01f, 1f),
            // 布尔项：未写入过取默认值，与历史存量行为一致
            tapTurnPage = p[KEY_TAP_TURN_PAGE] ?: true,
            doubleTapZoom = p[KEY_DOUBLE_TAP_ZOOM] ?: true,
            keepScreenOn = p[KEY_KEEP_SCREEN_ON] ?: false,
            imageScale = parseEnum(p[KEY_IMAGE_SCALE], ImageScale.FIT),
            novelFontSize = legacyFontSize,
            novelFontSizeSp = p[KEY_NOVEL_FONT_SIZE_SP]
                ?.toFloatOrNull()
                ?.coerceIn(12f, 36f)
                ?: legacyFontSize.sp,
            novelFontFamily = parseEnum(p[KEY_NOVEL_FONT_FAMILY], NovelFontFamily.SYSTEM_SANS),
            novelCustomFont = p[KEY_NOVEL_CUSTOM_FONT]?.takeIf { it.isNotBlank() },
            novelFontWeight = parseEnum(p[KEY_NOVEL_FONT_WEIGHT], NovelFontWeight.NORMAL),
            novelLineSpacingMultiplier = p[KEY_NOVEL_LINE_SPACING]
                ?.toFloatOrNull()
                ?.coerceIn(0.8f, 2.5f)
                ?: 1.5f,
            novelMarginTopPx = p[KEY_NOVEL_MARGIN_TOP]
                ?.toFloatOrNull()
                ?.coerceIn(0f, 96f)
                ?: 64f,
            novelMarginBottomPx = p[KEY_NOVEL_MARGIN_BOTTOM]
                ?.toFloatOrNull()
                ?.coerceIn(0f, 96f)
                ?: 64f,
            novelMarginLeftPx = p[KEY_NOVEL_MARGIN_LEFT]
                ?.toFloatOrNull()
                ?.coerceIn(0f, 96f)
                ?: 48f,
            novelMarginRightPx = p[KEY_NOVEL_MARGIN_RIGHT]
                ?.toFloatOrNull()
                ?.coerceIn(0f, 96f)
                ?: 48f,
            novelLetterSpacingPx = p[KEY_NOVEL_LETTER_SPACING]
                ?.toFloatOrNull()
                ?.coerceIn(-4f, 12f)
                ?: 0f,
            novelFirstLineIndent = p[KEY_NOVEL_FIRST_LINE_INDENT] ?: true,
            novelChapterNewPage = p[KEY_NOVEL_CHAPTER_NEW_PAGE] ?: true,
        )
    }

    /** 写入全部十项配置 */
    suspend fun set(prefs: ReaderPrefs) {
        dataStore.edit { p ->
            p[KEY_PAGE_MODE] = prefs.pageMode.name
            p[KEY_MANGA_DIRECTION] = prefs.mangaDirection.name
            p[KEY_SCREEN_ORIENTATION] = prefs.screenOrientation.name
            p[KEY_READ_BACKGROUND] = prefs.readBackground.name
            // null（跟随系统）落为空串，读取时空串回 null
            p[KEY_BRIGHTNESS] = prefs.brightness?.toString().orEmpty()
            p[KEY_TAP_TURN_PAGE] = prefs.tapTurnPage
            p[KEY_DOUBLE_TAP_ZOOM] = prefs.doubleTapZoom
            p[KEY_KEEP_SCREEN_ON] = prefs.keepScreenOn
            p[KEY_IMAGE_SCALE] = prefs.imageScale.name
            p[KEY_NOVEL_FONT_SIZE] = prefs.novelFontSize.name
            p[KEY_NOVEL_FONT_SIZE_SP] = prefs.novelFontSizeSp.coerceIn(12f, 36f).toString()
            p[KEY_NOVEL_FONT_FAMILY] = prefs.novelFontFamily.name
            p[KEY_NOVEL_CUSTOM_FONT] = prefs.novelCustomFont.orEmpty()
            p[KEY_NOVEL_FONT_WEIGHT] = prefs.novelFontWeight.name
            p[KEY_NOVEL_LINE_SPACING] = prefs.novelLineSpacingMultiplier.coerceIn(0.8f, 2.5f).toString()
            p[KEY_NOVEL_MARGIN_TOP] = prefs.novelMarginTopPx.coerceIn(0f, 96f).toString()
            p[KEY_NOVEL_MARGIN_BOTTOM] = prefs.novelMarginBottomPx.coerceIn(0f, 96f).toString()
            p[KEY_NOVEL_MARGIN_LEFT] = prefs.novelMarginLeftPx.coerceIn(0f, 96f).toString()
            p[KEY_NOVEL_MARGIN_RIGHT] = prefs.novelMarginRightPx.coerceIn(0f, 96f).toString()
            p[KEY_NOVEL_LETTER_SPACING] = prefs.novelLetterSpacingPx.coerceIn(-4f, 12f).toString()
            p[KEY_NOVEL_FIRST_LINE_INDENT] = prefs.novelFirstLineIndent
            p[KEY_NOVEL_CHAPTER_NEW_PAGE] = prefs.novelChapterNewPage
        }
    }
}

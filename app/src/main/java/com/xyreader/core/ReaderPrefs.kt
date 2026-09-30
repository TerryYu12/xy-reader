package com.xyreader.core

/** 阅读翻页模式 */
enum class PageMode(val label: String) {
    LEFT_RIGHT("左右翻页"),
    UP_DOWN("上下滚动"),
}

/** 漫画阅读方向 */
enum class MangaDirection(val label: String) {
    LTR("西式（从左到右）"),
    RTL("日漫（从右到左）"),
}

/** 阅读时屏幕方向 */
enum class ScreenOrientation(val label: String) {
    SYSTEM("跟随系统"),
    PORTRAIT("锁定竖屏"),
    LANDSCAPE("锁定横屏"),
}

/** 阅读背景颜色（作用于页面边距区域，ContentScale.Fit 的留白处） */
enum class ReadBackground(val label: String, val argb: Long) {
    BLACK("纯黑", 0xFF000000),
    DARK_GRAY("深灰", 0xFF202124),
    SEPIA("护眼黄", 0xFFF5EFDC),
    WHITE("纯白", 0xFFFFFFFF),
}

/** 图片缩放模式 */
enum class ImageScale(val label: String) {
    FIT("适合屏幕"),
    FILL_WIDTH("适合宽度"),
}

/** 文字小说字号（NovelPageSource 排版用） */
enum class NovelFontSize(val label: String, val sp: Float) {
    SMALL("小", 16f),
    MEDIUM("中", 19f),
    LARGE("大", 22f),
}

/**
 * 小说字体：系统三族（无需下载）+ 三款内置开源字体（随 APK 打包）。
 * 「导入字体」添加的自定义字体不占枚举位——见 [ReaderPrefs.novelCustomFont]。
 */
enum class NovelFontFamily(val label: String) {
    SYSTEM_SANS("系统无衬线"),
    SYSTEM_SERIF("系统衬线"),
    SYSTEM_MONOSPACE("系统等宽"),
    BUNDLED_WENKAI("霞鹜文楷"),
    BUNDLED_MISANS("MiSans"),
    BUNDLED_ZHUQUE("朱雀仿宋"),
}

/** 字重选项确保中文 fallback 字体也能明显区分。 */
enum class NovelFontWeight(val label: String) {
    NORMAL("标准"),
    BOLD("加粗"),
}

/**
 * 阅读器配置（设置页-阅读配置管理；DataStore 持久化）。
 * 全部有默认值，历史存量行为与未设置时一致。
 */
data class ReaderPrefs(
    val pageMode: PageMode = PageMode.LEFT_RIGHT,
    val mangaDirection: MangaDirection = MangaDirection.LTR,
    val screenOrientation: ScreenOrientation = ScreenOrientation.SYSTEM,
    /** 阅读背景色 */
    val readBackground: ReadBackground = ReadBackground.BLACK,
    /** 阅读亮度（0.01-1.0，作用于本 App 窗口）；null = 跟随系统 */
    val brightness: Float? = null,
    /** 点击翻页：开启时左右分区点击翻页；关闭时点击只呼出/隐藏工具栏 */
    val tapTurnPage: Boolean = true,
    /** 双击放大（页内 1x ↔ 2.5x） */
    val doubleTapZoom: Boolean = true,
    /** 阅读时屏幕常亮 */
    val keepScreenOn: Boolean = false,
    /** 图片缩放模式 */
    val imageScale: ImageScale = ImageScale.FIT,
    /** 文字小说字号 */
    val novelFontSize: NovelFontSize = NovelFontSize.MEDIUM,
    /** 文字小说字号（sp）；保留旧枚举以兼容已存配置与调用方 */
    val novelFontSizeSp: Float = novelFontSize.sp,
    /** 文字小说字体族：系统族或内置字体 */
    val novelFontFamily: NovelFontFamily = NovelFontFamily.SYSTEM_SANS,
    /**
     * 导入的自定义字体文件名（位于应用私有 fonts 目录）。非空时优先于
     * [novelFontFamily]（= 用户选了一款导入字体）；选回枚举字体时置空。
     */
    val novelCustomFont: String? = null,
    /** 文字小说字重 */
    val novelFontWeight: NovelFontWeight = NovelFontWeight.NORMAL,
    /** 小说正文行距倍率；1.5 保持旧版固定行距。 */
    val novelLineSpacingMultiplier: Float = 1.5f,
    /** 小说正文页边距，单位 px；默认值保持旧版排版。 */
    val novelMarginTopPx: Float = 64f,
    val novelMarginBottomPx: Float = 64f,
    val novelMarginLeftPx: Float = 48f,
    val novelMarginRightPx: Float = 48f,
    /** 字符间距，单位 px；0 表示沿用字体原始字距。 */
    val novelLetterSpacingPx: Float = 0f,
    /**
     * 首行缩进：每段首行缩进 2 个全角字符宽（段落已有前导空白的按总宽对齐、不叠加）。
     * 默认开启——中文小说的标准排版。
     */
    val novelFirstLineIndent: Boolean = true,
)

package com.xyreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xyreader.R
import com.xyreader.core.ImageQuality
import com.xyreader.core.ImageScale
import com.xyreader.core.MangaDirection
import com.xyreader.core.NovelFontFamily
import com.xyreader.core.NovelFontSize
import com.xyreader.core.NovelFonts
import com.xyreader.core.NovelFontWeight
import com.xyreader.core.PageMode
import com.xyreader.core.ReadBackground
import com.xyreader.core.ReaderPrefs
import com.xyreader.core.ScreenOrientation
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 分组页签：翻页模式 / 页面 / 字体 */
private val CONFIG_TABS = listOf("翻页模式", "页面", "字体")

/**
 * 阅读配置管理页（设计源 index.html configView）：
 * 子页头（返回圆钮 + 标题 + 副标题）+ 顶部胶囊分组（CapsuleTab）+ 横向分页。
 * 控件外观对齐设计 .pref-card 体系：pref-title/pref-hint、choice-row（radio-dot + 选中描边）、
 * pill-option（描边胶囊，选中 = primary-container 底）、switch-row、range-card（滑条 + 数值）、
 * color-choice 圆色块。功能与存储键零改动；修改立即持久化。
 */
@Composable
fun ReaderConfigScreen(onBack: () -> Unit) {
    val repo = rememberLibraryRepository()
    val scope = rememberCoroutineScope()
    val prefs by repo.readerPrefs.collectAsStateWithLifecycle(initialValue = ReaderPrefs())
    val pagerState = rememberPagerState { CONFIG_TABS.size }
    // 统一更新入口：任意改动做变换后整包落库，DataStore 回流 UI 与阅读器
    val onUpdate: ((ReaderPrefs) -> ReaderPrefs) -> Unit = { transform ->
        scope.launch { repo.setReaderPrefs(transform(prefs)) }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SubpageHead(
                title = "阅读配置管理",
                subtitle = "更改会显示在阅读器预览中。",
                onBack = onBack,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            // —— 顶部胶囊分组：点胶囊换组，与横滑分页双向同步 ——
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CONFIG_TABS.forEachIndexed { index, label ->
                    CapsuleTab(
                        label = label,
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                when (page) {
                    0 -> PagingGroupPage(prefs = prefs, onUpdate = onUpdate)
                    1 -> DisplayGroupPage(prefs = prefs, onUpdate = onUpdate)
                    else -> FontGroupPage(prefs = prefs, onUpdate = onUpdate)
                }
            }
        }
    }
}

/**
 * 子页头（对应设计 .subpage-head）：40dp 圆角描边返回钮 + 标题（titleLarge/Bold）+ 副标题。
 * 本文件私有实现，避免与其他子代理页面中的同名共用组件冲突（未改 Common.kt）。
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
            .padding(top = 10.dp, bottom = 14.dp),
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

/** 组内容容器：统一内边距与间距；内容超高时可内部滚动（常规屏幕无需滚动） */
@Composable
private fun GroupPage(scrollable: Boolean = false, content: @Composable () -> Unit) {
    val base = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp)
    Column(
        modifier = if (scrollable) base.verticalScroll(rememberScrollState()) else base,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
        Spacer(Modifier.height(24.dp))
    }
}

// ---------- 组 1：翻页模式 ----------

/** 翻页方式 / 阅读手势（点击翻页、双击放大）/ 漫画方向 */
@Composable
private fun PagingGroupPage(prefs: ReaderPrefs, onUpdate: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    GroupPage {
        ChoiceCard(
            title = "翻页模式",
            options = PageMode.entries,
            selected = prefs.pageMode,
            label = { it.label },
            sublabel = {
                if (it == PageMode.UP_DOWN) "连续滚动，适合文字小说" else "一次一页，左右滑动"
            },
            onSelect = { mode -> onUpdate { it.copy(pageMode = mode) } },
        )
        SwitchCard(
            title = "阅读手势",
            items = listOf(
                SwitchSpec(
                    title = "点击翻页",
                    hint = "开启：点屏幕左右两侧翻页；关闭：点击只呼出/收起工具栏（滑动翻页不受影响）",
                    checked = prefs.tapTurnPage,
                    onChange = { value -> onUpdate { it.copy(tapTurnPage = value) } },
                ),
                SwitchSpec(
                    title = "双击放大",
                    hint = "双击页内放大到 2.5 倍，再双击还原",
                    checked = prefs.doubleTapZoom,
                    onChange = { value -> onUpdate { it.copy(doubleTapZoom = value) } },
                ),
            ),
        )
        ChoiceCard(
            title = "漫画方向",
            options = MangaDirection.entries,
            selected = prefs.mangaDirection,
            label = { it.label },
            sublabel = { if (it == MangaDirection.RTL) "翻页从右往左，日漫适用" else null },
            onSelect = { dir -> onUpdate { it.copy(mangaDirection = dir) } },
        )
    }
}

// ---------- 组 2：页面 ----------

/** 阅读背景 / 图片缩放 / 图片渲染质量 / 屏幕方向 / 亮度 / 屏幕常亮 */
@Composable
private fun DisplayGroupPage(prefs: ReaderPrefs, onUpdate: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    GroupPage(scrollable = true) {
        BackgroundCard(
            selected = prefs.readBackground,
            onSelect = { bg -> onUpdate { it.copy(readBackground = bg) } },
        )
        PillCard(
            title = "图片缩放",
            options = ImageScale.entries,
            selected = prefs.imageScale,
            label = { it.label },
            hint = "整页显示，或铺满屏幕宽度。",
            onSelect = { scale -> onUpdate { it.copy(imageScale = scale) } },
        )
        PillCard(
            title = "图片渲染质量",
            options = ImageQuality.entries,
            selected = prefs.imageQuality,
            label = { it.label },
            hint = "漫画与图片页的显示清晰度。",
            onSelect = { quality -> onUpdate { it.copy(imageQuality = quality) } },
        )
        ChoiceCard(
            title = "屏幕方向",
            options = ScreenOrientation.entries,
            selected = prefs.screenOrientation,
            label = { it.label },
            onSelect = { orientation -> onUpdate { it.copy(screenOrientation = orientation) } },
        )
        BrightnessCard(
            brightness = prefs.brightness,
            onBrightness = { value -> onUpdate { it.copy(brightness = value) } },
        )
        SwitchCard(
            title = "屏幕",
            items = listOf(
                SwitchSpec(
                    title = "屏幕常亮",
                    hint = "阅读时屏幕不自动熄灭",
                    checked = prefs.keepScreenOn,
                    onChange = { value -> onUpdate { it.copy(keepScreenOn = value) } },
                ),
            ),
        )
    }
}

/** 阅读背景：四档圆色块（选中 = 强调色描边圈） */
@Composable
private fun BackgroundCard(selected: ReadBackground, onSelect: (ReadBackground) -> Unit) {
    ConfigCard(title = "阅读背景", hint = "页面四周留白的颜色。") {
        Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            ReadBackground.entries.forEach { bg ->
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .then(
                            if (selected == bg) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onSelect(bg) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(bg.argb.toInt()))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                }
            }
        }
    }
}

/** 阅读亮度：滑条 + 跟随系统重置（与阅读器内亮度行同语义） */
@Composable
private fun BrightnessCard(brightness: Float?, onBrightness: (Float?) -> Unit) {
    var sliderValue by remember(brightness) { mutableFloatStateOf(brightness ?: 0.5f) }
    ConfigCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "阅读亮度",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = { onBrightness(null) }) {
                Text("跟随系统", style = MaterialTheme.typography.labelMedium)
            }
        }
        Slider(
            value = sliderValue,
            onValueChange = { value ->
                sliderValue = value
                onBrightness(value)
            },
            valueRange = 0.01f..1f,
        )
        Text(
            if (brightness == null) "当前：跟随系统" else "当前：${(sliderValue * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- 组 3：字体 ----------

/** 字体选择（系统族 / 内置 / 导入）+ 字重 + 字号；仅文字小说生效 */
@Composable
private fun FontGroupPage(prefs: ReaderPrefs, onUpdate: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    val context = LocalContext.current
    var imported by remember { mutableStateOf(NovelFonts.listImported(context)) }
    var importError by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            NovelFonts.import(context, uri)
                .onSuccess { file ->
                    imported = NovelFonts.listImported(context)
                    importError = null
                    // 导入即选中，立即预览
                    onUpdate { it.copy(novelCustomFont = file.name) }
                }
                .onFailure { e -> importError = e.message ?: "导入失败" }
        }
    }

    GroupPage(scrollable = true) {
        Text(
            "字体与间距设置只对文字小说生效（TXT / 文字版 EPUB / 文字版 MOBI）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FontListCard(
            prefs = prefs,
            imported = imported,
            onSelectFamily = { family ->
                onUpdate { it.copy(novelFontFamily = family, novelCustomFont = null) }
            },
            onSelectImported = { file -> onUpdate { it.copy(novelCustomFont = file.name) } },
            onDeleteImported = { file ->
                NovelFonts.delete(file)
                imported = NovelFonts.listImported(context)
                if (prefs.novelCustomFont == file.name) onUpdate { it.copy(novelCustomFont = null) }
            },
            onImportClick = { launcher.launch(arrayOf("*/*")) },
        )
        importError?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        PillCard(
            title = "字体粗细",
            options = NovelFontWeight.entries,
            selected = prefs.novelFontWeight,
            label = { it.label },
            onSelect = { weight -> onUpdate { it.copy(novelFontWeight = weight) } },
        )
        NovelFontSizeCard(
            sizeSp = prefs.novelFontSizeSp,
            onSizeChange = { value ->
                val nearestLegacy = NovelFontSize.entries.minBy { kotlin.math.abs(it.sp - value) }
                onUpdate { it.copy(novelFontSize = nearestLegacy, novelFontSizeSp = value) }
            },
        )
        NovelSpacingControls(prefs = prefs, onUpdate = onUpdate)
        SwitchCard(
            title = "段落与章节",
            items = listOf(
                SwitchSpec(
                    title = "首行缩进",
                    hint = "每段首行缩进 2 个字符；段落已带空白缩进时按总宽对齐，不叠加",
                    checked = prefs.novelFirstLineIndent,
                    onChange = { value -> onUpdate { it.copy(novelFirstLineIndent = value) } },
                ),
                SwitchSpec(
                    title = "章首另起一页",
                    hint = "每章从新的一页开始，上一页剩余空间留白（主流阅读器默认行为）；关闭后章节连排",
                    checked = prefs.novelChapterNewPage,
                    onChange = { value -> onUpdate { it.copy(novelChapterNewPage = value) } },
                ),
            ),
        )
        Text(
            "内置字体：霞鹜文楷（SIL OFL 1.1）、朱雀仿宋（SIL OFL 1.1 · 璇玑造字）、" +
                "MiSans（© 小米科技，依其字体许可用于本应用）。" +
                "导入字体仅存本机，版权归字体作者所有。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 字体列表：系统三族 + 内置 + 已导入；每行用该字体本身渲染名称作预览 */
@Composable
private fun FontListCard(
    prefs: ReaderPrefs,
    imported: List<File>,
    onSelectFamily: (NovelFontFamily) -> Unit,
    onSelectImported: (File) -> Unit,
    onDeleteImported: (File) -> Unit,
    onImportClick: () -> Unit,
) {
    ConfigCard(title = "小说字体") {
        NovelFontFamily.entries.forEach { family ->
            val familyPreview = remember(family) { previewFamilyOf(family) }
            FontRow(
                title = family.label,
                selected = prefs.novelCustomFont == null && prefs.novelFontFamily == family,
                previewFamily = familyPreview,
                onClick = { onSelectFamily(family) },
            )
        }
        imported.forEach { file ->
            val filePreview = remember(file) {
                runCatching { FontFamily(Font(file)) }.getOrNull()
            }
            FontRow(
                title = NovelFonts.displayName(file),
                selected = prefs.novelCustomFont == file.name,
                previewFamily = filePreview,
                onClick = { onSelectImported(file) },
                trailing = {
                    IconButton(onClick = { onDeleteImported(file) }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "删除字体",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }

        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = onImportClick, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("导入字体（ttf / otf / ttc）")
        }
    }
}

/** 字体行：单选点 + 以该字体渲染的字体名（导入字体加载失败则退回默认字体渲染） */
@Composable
private fun FontRow(
    title: String,
    selected: Boolean,
    previewFamily: FontFamily?,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(11.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioDot(selected)
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = previewFamily,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        trailing?.invoke()
    }
}

/** 字体族 → Compose 预览用 FontFamily：系统族走泛型族，内置字体走 res/font 资源 */
private fun previewFamilyOf(family: NovelFontFamily): FontFamily = when (family) {
    NovelFontFamily.SYSTEM_SANS -> FontFamily.SansSerif
    NovelFontFamily.SYSTEM_SERIF -> FontFamily.Serif
    NovelFontFamily.SYSTEM_MONOSPACE -> FontFamily.Monospace
    NovelFontFamily.BUNDLED_WENKAI -> FontFamily(Font(R.font.lxgw_wenkai_lite))
    NovelFontFamily.BUNDLED_MISANS -> FontFamily(Font(R.font.misans_regular))
    NovelFontFamily.BUNDLED_ZHUQUE -> FontFamily(Font(R.font.zhuque_fangsong))
}

// ---------- 共用组件（对齐设计 .pref-card 体系） ----------

/**
 * 设置卡外壳（对应设计 .pref-card）：18dp 圆角 + 1dp 描边 + surface 底；
 * 标题（pref-title）+ 可选说明（pref-hint）+ 内容。
 */
@Composable
private fun ConfigCard(
    title: String? = null,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(18.dp),
            ),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(if (hint == null) 10.dp else 6.dp))
            }
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

/** 单选点（对应设计 .radio-dot）：16dp 圈，选中为强调色描边 + 内实心点 */
@Composable
private fun RadioDot(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .border(
                1.5.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

/** 单选行（对应设计 .choice-row）：选中 = 主色淡底 + 主色描边 */
@Composable
private fun ChoiceRow(
    label: String,
    sublabel: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(11.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            )
            .then(
                if (selected) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.36f), shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioDot(selected)
        Spacer(Modifier.width(9.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (sublabel != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 一组单选设置卡片（对应设计 choiceCard）：标题 + 多个 choice-row */
@Composable
private fun <T> ChoiceCard(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    sublabel: ((T) -> String?)? = null,
    onSelect: (T) -> Unit,
) {
    ConfigCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                ChoiceRow(
                    label = label(option),
                    sublabel = sublabel?.invoke(option),
                    selected = selected == option,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

/** 描边胶囊选项（对应设计 .pill-option）：选中 = primary-container 底、无描边 */
@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
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

/** 一组胶囊选项卡片（对应设计 pillCard）：标题 + 可选说明 + 描边胶囊行 */
@Composable
private fun <T> PillCard(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    hint: String? = null,
    onSelect: (T) -> Unit,
) {
    ConfigCard(title = title, hint = hint) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            options.forEach { option ->
                Pill(label = label(option), selected = selected == option) { onSelect(option) }
            }
        }
    }
}

/** 开关行配置项（对应设计 switch-row 的 data 源） */
private data class SwitchSpec(
    val title: String,
    val hint: String,
    val checked: Boolean,
    val onChange: (Boolean) -> Unit,
)

/** 开关设置卡（对应设计 switchCard）：标题 + 若干 switch-row，行间细分隔线 */
@Composable
private fun SwitchCard(title: String, items: List<SwitchSpec>) {
    ConfigCard(title = title) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { item.onChange(!item.checked) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Switch(checked = item.checked, onCheckedChange = item.onChange)
            }
        }
    }
}

/** 范围卡标题行（对应设计 .range-head）：左侧标题 + 右侧主色数值 */
@Composable
private fun RangeTitle(title: String, valueText: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            valueText,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 小说字号卡（对应设计 rangeCard）：标题 + 数值 + 滑条 + 重排提示 */
@Composable
private fun NovelFontSizeCard(sizeSp: Float, onSizeChange: (Float) -> Unit) {
    val sliderValue = remember(sizeSp) { mutableFloatStateOf(sizeSp.coerceIn(12f, 36f)) }
    ConfigCard {
        RangeTitle("小说字号", "${sliderValue.floatValue.roundToInt()} sp")
        Slider(
            value = sliderValue.floatValue,
            onValueChange = { sliderValue.floatValue = it },
            onValueChangeFinished = { onSizeChange(sliderValue.floatValue) },
            valueRange = 12f..36f,
            steps = 23,
        )
        Text(
            "字号会重新分页，并尽量保留当前阅读位置",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

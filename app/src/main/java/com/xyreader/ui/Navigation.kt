package com.xyreader.ui

import android.widget.Toast
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xyreader.SharedIntake
import com.xyreader.core.ShelfSection
import com.xyreader.reader.ReaderScreen
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.withFrameNanos

/** 路由表（保持 ArkNavHost() 对 MainActivity 的签名不变） */
private object Routes {
    const val HOME = "home"
    const val SHELF = "shelf"
    const val SETTINGS = "settings"
    const val REPOS = "settings/repos"
    const val REPO_CONFIG = "settings/repo-config/{repoId}"
    const val REMOTE_REPOS = "settings/remote"
    const val GDRIVE = "settings/gdrive"
    const val GROUPS = "settings/groups"
    const val READER_CONFIG = "settings/reader-config"
    const val GROUP_BOOKS = "shelf/group/{groupId}"
    const val BOOKMARKS = "bookmarks"
    const val SHELF_LIST = "shelf/list/{section}"
    const val BOOK_DETAIL = "book/{bookId}"
    const val READER = "reader/{bookId}?page={page}&restart={restart}"
}

/**
 * 应用导航骨架：
 *  - 底部导航两 tab（首页 / 书架），悬浮胶囊底栏（Gmail / Play 商店风格）；
 *    底栏仅在 home / shelf 两个顶层路由显示，reader 与各子页隐藏；
 *  - 设置页从首页搜索框右侧齿轮进入，本地/远程仓库管理、Google Drive 账号管理为二级页，
 *    本地仓库卡片的"配置仓库"为三级页（settings/repo-config/{repoId}）；
 *  - 书架的 收藏/历史 分区走 shelf/list/{section}，书签独立一页；
 *  - 点击书本先进详情页 book/{bookId}（目录先行，见 BookDetailScreen）；
 *    书签跳转仍直达阅读页；
 *  - reader/{bookId}?page={page}&restart={restart} 挂载 reader 模块的 ReaderScreen
 *    （签名：bookId: Long, onBack: () -> Unit, initialPage: Int = 0,
 *    startFromBeginning: Boolean = false；restart=true 表示无视已存进度从头读）。
 */
@Composable
fun ArkNavHost() {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val homeSelected = currentRoute == Routes.HOME
    val shelfSelected = currentRoute == Routes.SHELF
    val showBottomBar = homeSelected || shelfSelected

    val context = LocalContext.current
    val repository = rememberLibraryRepository()
    var importing by remember { mutableStateOf(false) }

    // 外部「用其他应用打开 / 分享」：导入单文件并直接进阅读器；失败提示后留在原地。
    // 关键：不能用 LaunchedEffect(shared) —— 消费时把 shared 置 null 会改变 key，
    // Compose 随即取消正在执行的导入协程（LeftCompositionCancellationException，
    // 现象=「无法打开这个文件：The coroutine scope left the composition」）。
    // 用 Unit 键安静收集：消费只更新流值、不动 key；导入中途被活动销毁取消时不吞异常、
    // 也不弹误导提示——下一实例因 pending 未清而自动重试（导入本身幂等）。
    LaunchedEffect(Unit) {
        SharedIntake.pending.collect { open ->
            println("DIAG-A collect open=${open != null}") // TEMP-CI-DIAG：定位 CI 导入链断点，定位后移除
            if (open == null) return@collect
            importing = true
            val result = try {
                Result.success(
                    repository.importSharedFile(
                        uriString = open.uri.toString(),
                        nameHint = open.nameHint,
                        mimeType = open.mimeType,
                    ),
                )
            } catch (e: CancellationException) {
                throw e // 取消不吞：留给重建后的实例重试
            } catch (e: Exception) {
                Result.failure(e)
            }
            importing = false
            println("DIAG-A import result=$result") // TEMP-CI-DIAG：定位 CI 导入链断点，定位后移除
            // 冷启动首个组合期内，导入可能赶在 NavHost 完成 setGraph 之前结束：
            // 等一帧再导航（生产无感 ~16ms；消除「Navigation graph has not been set」竞态）
            withFrameNanos { }
            SharedIntake.consume()
            result
                .onSuccess { bookId -> nav.openBook(bookId, page = 0) }
                .onFailure { e ->
                    Toast.makeText(
                        context,
                        "无法打开这个文件：${e.message ?: "未知错误"}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                FloatingNavBar(
                    homeSelected = homeSelected,
                    shelfSelected = shelfSelected,
                    onHome = { nav.navigateTab(Routes.HOME) },
                    onShelf = { nav.navigateTab(Routes.SHELF) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            // 屏幕切换不要淡入淡出：navigation-compose 2.7+ 默认 700ms 交叉淡入淡出，
            // 用户明确不要，全部置为瞬切
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
            // consumeWindowInsets：外层已消费的窗口内边距不再被内层 Scaffold/TopAppBar 重复应用
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            // 首页：搜索 + 封面网格 + FAB 扫描
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenBook = { bookId -> nav.openBookDetail(bookId) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpenReader = { bookId -> nav.openBook(bookId, page = 0) },
                )
            }

            // 书架：分组 chips + 2x2 入口卡片
            composable(Routes.SHELF) {
                ShelfScreen(
                    onOpenBook = { bookId -> nav.openBookDetail(bookId) },
                    onOpenReader = { bookId -> nav.openBook(bookId, page = 0) },
                    onOpenSection = { section ->
                        nav.navigate("shelf/list/${section.name}")
                    },
                    onOpenBookmarks = { nav.navigate(Routes.BOOKMARKS) },
                    onOpenGroup = { groupId -> nav.navigate("shelf/group/$groupId") },
                    onOpenGroupManage = { nav.navigate(Routes.GROUPS) },
                )
            }

            // 设置：仓库管理 / 漫画管理 / 其他
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenRepos = { nav.navigate(Routes.REPOS) },
                    onOpenRemoteRepos = { nav.navigate(Routes.REMOTE_REPOS) },
                    onOpenGdrive = { nav.navigate(Routes.GDRIVE) },
                    onOpenGroups = { nav.navigate(Routes.GROUPS) },
                    onOpenReaderConfig = { nav.navigate(Routes.READER_CONFIG) },
                )
            }

            // 本地仓库管理（卡片三点菜单 → 配置仓库子页）
            composable(Routes.REPOS) {
                ReposScreen(
                    onBack = { nav.popBackStack() },
                    onOpenConfig = { repoId -> nav.navigate("settings/repo-config/$repoId") },
                )
            }

            // 配置仓库（名称 / 封面文件名约定 / 默认添加分组）
            composable(
                route = Routes.REPO_CONFIG,
                arguments = listOf(navArgument("repoId") { type = NavType.LongType }),
            ) { entry ->
                val repoId = entry.arguments?.getLong("repoId") ?: 0L
                RepoConfigScreen(repoId = repoId, onBack = { nav.popBackStack() })
            }

            // 远程仓库管理（WebDAV 配置管理，beta）
            composable(Routes.REMOTE_REPOS) {
                RemoteReposScreen(onBack = { nav.popBackStack() })
            }

            // Google Drive 账号管理（OAuth 授权 + 扫描入库，beta）
            composable(Routes.GDRIVE) {
                GdriveScreen(onBack = { nav.popBackStack() })
            }

            // 书架管理（自定义分组增删改）
            composable(Routes.GROUPS) {
                GroupManageScreen(onBack = { nav.popBackStack() })
            }

            // 阅读配置管理（翻页模式 / 漫画方向 / 屏幕方向）
            composable(Routes.READER_CONFIG) {
                ReaderConfigScreen(onBack = { nav.popBackStack() })
            }

            // 分组书列表
            composable(
                route = Routes.GROUP_BOOKS,
                arguments = listOf(navArgument("groupId") { type = NavType.LongType }),
            ) { entry ->
                val groupId = entry.arguments?.getLong("groupId") ?: 0L
                GroupBooksScreen(
                    groupId = groupId,
                    onBack = { nav.popBackStack() },
                    onOpenBook = { bookId -> nav.openBookDetail(bookId) },
                )
            }

            // 书架分区列表（全部 / 收藏 / 历史）
            composable(
                route = Routes.SHELF_LIST,
                arguments = listOf(navArgument("section") { type = NavType.StringType }),
            ) { entry ->
                val name = entry.arguments?.getString("section") ?: ShelfSection.ALL.name
                val section = ShelfSection.entries.firstOrNull { it.name == name }
                    ?: ShelfSection.ALL
                ListScreen(
                    section = section,
                    onBack = { nav.popBackStack() },
                    onOpenBook = { bookId -> nav.openBookDetail(bookId) },
                )
            }

            // 书签列表
            composable(Routes.BOOKMARKS) {
                BookmarksScreen(
                    onBack = { nav.popBackStack() },
                    onOpenBookPage = { bookId, page -> nav.openBookAtPage(bookId, page) },
                )
            }

            // 书籍详情（点击书本先看目录再进入阅读）
            composable(
                route = Routes.BOOK_DETAIL,
                arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
            ) { entry ->
                val bookId = entry.arguments?.getLong("bookId") ?: 0L
                BookDetailScreen(
                    bookId = bookId,
                    onBack = { nav.popBackStack() },
                    onContinue = { nav.openBook(bookId, page = 0) },
                    onStartFromBeginning = { nav.openBookFromStart(bookId) },
                    onOpenChapter = { page -> nav.openBookAtPage(bookId, page) },
                    onDeleted = { nav.popBackStack() },
                )
            }

            // 阅读器（签名必须与 reader 模块一字不差）
            composable(
                route = Routes.READER,
                arguments = listOf(
                    navArgument("bookId") { type = NavType.LongType },
                    navArgument("page") {
                        type = NavType.IntType
                        defaultValue = 0
                    },
                    navArgument("restart") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { entry ->
                val bookId = entry.arguments?.getLong("bookId") ?: 0L
                val initialPage = entry.arguments?.getInt("page") ?: 0
                val restart = entry.arguments?.getBoolean("restart") ?: false
                ReaderScreen(
                    bookId = bookId,
                    onBack = { nav.popBackStack() },
                    initialPage = initialPage,
                    startFromBeginning = restart,
                )
            }
        }
            if (importing) {
                // 导入中遮罩：大文件（数百 MB 漫画）拷贝可能停留数秒，给用户明确反馈
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * 悬浮胶囊底栏（Gmail / Play 商店风格）：
 * 距屏幕左右与底部各 16dp，surfaceContainer 大圆角胶囊 + 阴影悬浮在内容之上；
 * 选中项为 primaryContainer 图标胶囊 + 高亮 label，未选中为描线图标 + 次级 label。
 * navigationBarsPadding 让胶囊浮在系统手势条之上；Scaffold 会把底栏整体高度
 * （含 inset 与外边距）计入内容 padding，FAB 也会自动悬停在底栏上方 16dp。
 */
@Composable
private fun FloatingNavBar(
    homeSelected: Boolean,
    shelfSelected: Boolean,
    onHome: () -> Unit,
    onShelf: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavTab(
                modifier = Modifier.weight(1f),
                selected = homeSelected,
                label = "首页",
                iconSelected = Icons.Filled.Home,
                iconUnselected = Icons.Outlined.Home,
                onClick = onHome,
            )
            NavTab(
                modifier = Modifier.weight(1f),
                selected = shelfSelected,
                label = "书架",
                iconSelected = Icons.AutoMirrored.Filled.MenuBook,
                iconUnselected = Icons.AutoMirrored.Outlined.MenuBook,
                onClick = onShelf,
            )
        }
    }
}

/** 悬浮底栏的单个 tab：图标胶囊（选中 primaryContainer 底）+ 文字标签 */
@Composable
private fun NavTab(
    modifier: Modifier = Modifier,
    selected: Boolean,
    label: String,
    iconSelected: ImageVector,
    iconUnselected: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        ) {
            Icon(
                if (selected) iconSelected else iconUnselected,
                contentDescription = label,
                modifier = Modifier
                    .padding(horizontal = 18.dp, vertical = 8.dp)
                    .size(22.dp),
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** tab 间切换：保存/恢复状态，回到起始目的地之上 */
private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** 打开书本详情页（目录先行）；网格/列表的"点击书本"统一走这里 */
private fun NavHostController.openBookDetail(bookId: Long) {
    navigate("book/$bookId")
}

/** 打开阅读器；page 为书签跳转页（0 表示续读已存进度） */
private fun NavHostController.openBook(bookId: Long, page: Int) {
    navigate("reader/$bookId?page=$page")
}

/** 打开阅读器并跳到精确页（含第 0 页；"跳转"不沿用已存进度）。章节点击 / 书签跳转用 */
private fun NavHostController.openBookAtPage(bookId: Long, page: Int) {
    if (page <= 0) navigate("reader/$bookId?page=0&restart=true")
    else navigate("reader/$bookId?page=$page")
}

/** 打开阅读器并从头开始读（无视已存进度，详情页"从头开始"用） */
private fun NavHostController.openBookFromStart(bookId: Long) {
    navigate("reader/$bookId?page=0&restart=true")
}

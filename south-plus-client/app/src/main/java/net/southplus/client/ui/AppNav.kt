package net.southplus.client.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import net.southplus.client.AppGraph
import net.southplus.client.core.links.LinkOpener
import net.southplus.client.core.links.LinkTarget
import net.southplus.client.core.settings.AppSettings
import net.southplus.client.ui.board.BoardScreen
import net.southplus.client.ui.components.copyToClipboard
import net.southplus.client.ui.diag.DiagScreen
import net.southplus.client.ui.favor.FavorScreen
import net.southplus.client.ui.history.HistoryScreen
import net.southplus.client.ui.home.HomeScreen
import net.southplus.client.ui.login.LoginScreen
import net.southplus.client.ui.mine.MineScreen
import net.southplus.client.ui.mine.UserPanelScreen
import net.southplus.client.ui.pm.PmScreen
import net.southplus.client.ui.reader.ReaderScreen
import net.southplus.client.ui.search.SearchScreen
import net.southplus.client.ui.settings.SettingsScreen
import net.southplus.client.ui.user.UserScreen

object Routes {
    const val HOME = "home"
    const val DISCOVER = "discover"
    const val SEARCH = "search"
    const val PM = "pm"
    const val MINE = "mine"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val HISTORY = "history"
    const val FAVOR = "favor"
    const val DIAG = "diag"
    const val NETWORK = "network"
    // 「我的」子页（u.php 用户中心 / 论坛任务插件）
    const val USER_TOPIC = "mine/topic"
    const val USER_REPLY = "mine/reply"
    const val USER_FRIEND = "mine/friend"
    const val USER_TRADE = "mine/trade"
    const val USER_TASK = "mine/task"
    fun board(fid: String) = "board/$fid"
    fun thread(tid: String, page: Int = 1) = "thread/$tid?page=$page"
    fun user(uid: String) = "user/$uid"
    /** 用户空间子面板（u.php?action-{panel}-uid-{uid}.html：TA的主题/回复/收藏/好友/商品）。 */
    fun userPanel(uid: String, panel: String) = "user/$uid/$panel"
}

/** 用户空间子面板标题（self 与他人共用中性措辞）。 */
internal fun userPanelTitle(panel: String): String = when (panel) {
    "topic" -> "主题"
    "post" -> "回复"
    "favor" -> "收藏"
    "friend" -> "好友"
    "trade" -> "商品"
    else -> panel
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val sessionState by AppGraph.session.state.collectAsStateWithLifecycle()

    // ---- 自动签到（设置开关，默认关）：每天最多一次，有动作时 Toast 摘要 ----
    val appContext = LocalContext.current
    LaunchedEffect(Unit) {
        net.southplus.client.core.tasks.AutoCheckIn
            .runIfDue(AppGraph.settings, AppGraph.repo, AppGraph.session)
            ?.let { summary ->
                android.widget.Toast.makeText(appContext, "自动签到\n$summary", android.widget.Toast.LENGTH_LONG).show()
            }
    }

    // 底部四栏：「搜索」标签由「发现」（版块目录）替代；关键词搜索从发现页放大镜进入
    val tabs = listOf(
        Triple(Routes.HOME, "首页", Icons.Filled.Home),
        Triple(Routes.DISCOVER, "发现", Icons.Filled.Explore),
        Triple(Routes.PM, "消息", Icons.Filled.Email),
        Triple(Routes.MINE, "我的", Icons.Filled.Person),
    )
    val showBottom = tabs.any { it.first == currentRoute }
    val unread = (sessionState as? net.southplus.client.core.model.SessionState.LoggedIn)?.user?.unreadPm ?: 0

    Scaffold(
        // 页面顶栏负责顶部 inset；这里仅消费底部导航实际占用的空间。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottom) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        tonalElevation = 0.dp,
                    ) {
                        tabs.forEach { (route, label, filledIcon) ->
                            val selected = currentRoute == route
                            val icon = if (selected) filledIcon else when (route) {
                                Routes.HOME -> Icons.Outlined.Home
                                Routes.DISCOVER -> Icons.Outlined.Explore
                                Routes.PM -> Icons.Outlined.Email
                                else -> Icons.Outlined.Person
                            }
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    nav.navigate(route) {
                                        popUpTo(Routes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    if (route == Routes.PM && unread > 0) {
                                        BadgedBox(badge = { Badge { Text(if (unread > 99) "99+" else unread.toString()) } }) {
                                            Icon(icon, null)
                                        }
                                    } else {
                                        Icon(icon, null)
                                    }
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.DISCOVER) { net.southplus.client.ui.home.DiscoverScreen(nav) }
            composable(Routes.SEARCH) { SearchScreen(nav) }
            composable(Routes.PM) { PmScreen(nav) }
            composable(Routes.MINE) { MineScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.LOGIN) { LoginScreen(nav) }
            composable(Routes.HISTORY) { HistoryScreen(nav) }
            composable(Routes.FAVOR) { FavorScreen(nav) }
            composable(Routes.DIAG) { DiagScreen(nav) }
            composable(Routes.NETWORK) { net.southplus.client.ui.settings.NetworkScreen(nav) }
            composable(Routes.USER_TOPIC) { UserPanelScreen(nav, "我的主题", "u.php?action-topic.html") }
            composable(Routes.USER_REPLY) { UserPanelScreen(nav, "我的回复", "u.php?action-post.html") }
            composable(Routes.USER_FRIEND) { UserPanelScreen(nav, "我的好友", "u.php?action-friend.html") }
            composable(Routes.USER_TRADE) { UserPanelScreen(nav, "我的商品", "u.php?action-trade.html") }
            composable(Routes.USER_TASK) { UserPanelScreen(nav, "论坛任务", "plugin.php?H_name-tasks.html") }
            composable("board/{fid}") { entry ->
                BoardScreen(nav, entry.arguments?.getString("fid").orEmpty())
            }
            composable("thread/{tid}?page={page}") { entry ->
                ReaderScreen(
                    nav,
                    entry.arguments?.getString("tid").orEmpty(),
                    entry.arguments?.getString("page")?.toIntOrNull() ?: 1,
                )
            }
            composable("user/{uid}") { entry ->
                UserScreen(nav, entry.arguments?.getString("uid").orEmpty())
            }
            composable("user/{uid}/{panel}") { entry ->
                val uid = entry.arguments?.getString("uid").orEmpty()
                val panel = entry.arguments?.getString("panel").orEmpty()
                UserPanelScreen(nav, userPanelTitle(panel), "u.php?action-$panel-uid-$uid.html")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForumTopBar(
    title: String,
    nav: NavHostController,
    actions: @Composable () -> Unit = {},
) {
    val entry by nav.currentBackStackEntryAsState()
    val isRoot = entry?.destination?.route in setOf(Routes.HOME, Routes.SEARCH, Routes.PM, Routes.MINE)
    TopAppBar(
        title = {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = if (isRoot) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            )
        },
        navigationIcon = {
            if (!isRoot && nav.previousBackStackEntry != null) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

private val UID_IN_PATH = Regex("uid-(\\d+)|uid=(\\d+)")

/**
 * 站内外链接统一处理（方案 §6.4.4 LinkRouter）：
 * 站内 → 应用内路由（跨镜像改写已完成）；特殊协议 → 系统；网盘/图床页/外链 → Custom Tabs。
 * onImage 非空时，图床直链交给调用方做应用内大图预览（如帖子阅读页的灯箱）。
 */
fun handleLink(
    context: Context,
    nav: NavHostController,
    url: String,
    settings: AppSettings,
    onImage: ((String) -> Unit)? = null,
) {
    when (val t = AppGraph.router.classify(url)) {
        is LinkTarget.Internal -> {
            when {
                t.tid != null -> nav.navigate(Routes.thread(t.tid))
                t.fid != null -> nav.navigate(Routes.board(t.fid))
                t.path.contains("u.php") -> {
                    val uid = UID_IN_PATH.find(t.path)?.let { m ->
                        m.groupValues[1].ifEmpty { m.groupValues[2] }
                    }
                    if (!uid.isNullOrEmpty()) nav.navigate(Routes.user(uid)) else LinkOpener.open(context, url, settings)
                }
                else -> LinkOpener.open(context, url, settings)
            }
        }
        is LinkTarget.TorrentProtocol -> {
            if (!LinkOpener.openProtocol(context, t.url)) {
                copyToClipboard(context, "链接", t.url)
                android.widget.Toast.makeText(context, "未找到 BT 客户端，链接已复制", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        is LinkTarget.PanLink -> openPan(context, t.url, null, settings)
        is LinkTarget.ImageDirect -> (onImage ?: { LinkOpener.open(context, t.url, settings) })(t.url)
        is LinkTarget.ImagePage -> LinkOpener.open(context, t.url, settings)
        is LinkTarget.External -> LinkOpener.open(context, t.url, settings)
    }
}

/** 资源卡「打开」流程（§6.4.5）：先复制提取码（网盘自动检测剪贴板），再 Custom Tabs 打开。 */
fun openPan(context: Context, url: String, code: String?, settings: AppSettings) {
    if (!code.isNullOrEmpty()) {
        copyToClipboard(context, "提取码", code)
        android.widget.Toast.makeText(context, "提取码 $code 已复制", android.widget.Toast.LENGTH_SHORT).show()
    }
    LinkOpener.open(context, url, settings)
}

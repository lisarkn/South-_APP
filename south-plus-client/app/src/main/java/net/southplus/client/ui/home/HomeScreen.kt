package net.southplus.client.ui.home

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import net.southplus.client.AppGraph
import net.southplus.client.core.model.Board
import net.southplus.client.core.model.IndexPage
import net.southplus.client.core.model.RecentReply
import net.southplus.client.core.model.RssItem
import net.southplus.client.core.model.SessionState
import net.southplus.client.core.settings.AppSettings
import net.southplus.client.ui.ForumTopBar
import net.southplus.client.ui.Routes
import net.southplus.client.ui.components.ErrorPane
import net.southplus.client.ui.components.IconTile
import net.southplus.client.ui.components.LoadingPane
import net.southplus.client.ui.components.OnlinePill
import net.southplus.client.ui.components.SectionHeader
import net.southplus.client.ui.components.StatusBadge
import net.southplus.client.ui.components.uiMessage
import net.southplus.client.ui.graphViewModel
import net.southplus.client.ui.theme.Spacing
import net.southplus.client.ui.theme.extended

class HomeViewModel : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val page: IndexPage? = null,
        val latest: List<RssItem> = emptyList(), // rss.php 全站最新帖；失败为空，UI 隐藏该区
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load(force: Boolean = false) {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(loading = it.page == null, refreshing = force, error = null) }
            // 首页与 RSS 并行；RSS 失败不打断版块列表
            val indexDeferred = async { runCatching { AppGraph.repo.index(force) } }
            val rssDeferred = async { runCatching { AppGraph.repo.latestThreads(force) } }
            indexDeferred.await()
                .onSuccess { p ->
                    // 保留已有数据，显式结束加载，避免默认状态重新进入转圈。
                    _state.update {
                        it.copy(
                            loading = false, refreshing = false, page = p, error = null,
                            latest = rssDeferred.await().getOrDefault(emptyList()),
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            loading = false, refreshing = false, error = e.uiMessage(),
                            latest = rssDeferred.await().getOrDefault(emptyList()),
                        )
                    }
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val vm: HomeViewModel = graphViewModel { HomeViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()
    val sessionState by AppGraph.session.state.collectAsStateWithLifecycle()
    val settings by AppGraph.settings.linkSettings.collectAsStateWithLifecycle(
        initialValue = AppGraph.settings.currentLinkSettings,
    )
    val context = LocalContext.current
    val loggedIn = sessionState is SessionState.LoggedIn
    var latestLimit by rememberSaveable { mutableStateOf(20) }

    Scaffold(
        topBar = {
            ForumTopBar(title = "南+", nav = nav, actions = {
                IconButton(onClick = { vm.load(force = true) }, enabled = !state.loading && !state.refreshing) {
                    Icon(Icons.Filled.Refresh, "刷新首页")
                }
                if (!loggedIn) {
                    IconButton(onClick = { nav.navigate(Routes.LOGIN) }) {
                        Icon(Icons.AutoMirrored.Filled.Login, "登录")
                    }
                }
            })
        },
    ) { padding ->
        val page = state.page
        when {
            state.loading -> LoadingPane(Modifier.padding(padding))
            state.error != null && page == null -> ErrorPane(
                Modifier.padding(padding),
                message = state.error.orEmpty(),
                onRetry = { vm.load(force = true) },
            )
            page != null && page.sections.isEmpty() -> ErrorPane(
                Modifier.padding(padding),
                message = "页面已获取，但未解析出版块内容。可在设置 → 网络与连接中查看诊断。\n当前镜像：${AppGraph.mirrors.current.host}",
                onRetry = { vm.load(force = true) },
                extraLabel = "用浏览器打开",
                onExtra = {
                    net.southplus.client.core.links.LinkOpener.open(
                        context, "https://${AppGraph.mirrors.current.host}/index.php", settings,
                    )
                },
            )
            page != null -> Column(Modifier.padding(padding).fillMaxSize()) {
                if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = Spacing.xl),
                ) {
                    item(key = "welcome") {
                        CommunityHeader(
                            onSearch = { nav.navigate(Routes.SEARCH) },
                            onFavor = { nav.navigate(Routes.FAVOR) },
                            onHistory = { nav.navigate(Routes.HISTORY) },
                        )
                    }
                    state.error?.let { error ->
                        item(key = "error") { ErrorPane(message = error, onRetry = { vm.load(force = true) }) }
                    }
                    page.onlineInfo?.let { info ->
                        item(key = "online") { OnlinePill(info, Modifier.fillMaxWidth()) }
                    }
                    // 全站最新帖（rss.php，游客可见）
                    if (state.latest.isNotEmpty()) {
                        item(key = "sec_latest") { SectionHeader("最新发表") }
                        val shown = state.latest.take(latestLimit)
                        itemsIndexed(shown, key = { _, item -> "rss_${item.tid}" }) { index, item ->
                            FeedRow(
                                title = item.title,
                                meta = listOfNotNull(item.boardName, item.author, formatFeedTime(item.publishedAt))
                                    .joinToString(" · "),
                                first = index == 0,
                                last = index == shown.lastIndex && state.latest.size <= latestLimit,
                                onClick = { nav.navigate(Routes.thread(item.tid)) },
                            )
                        }
                        if (state.latest.size > latestLimit) {
                            item(key = "latest_more") {
                                TextButton(
                                    onClick = { latestLimit += 20 },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
                                ) { Text("显示更多（还有 ${state.latest.size - latestLimit} 条）") }
                            }
                        }
                    }
                    // 各版块「最后回复」汇总（网页版首页同款数据）：全站正在活跃的帖子
                    if (page.recentReplies.isNotEmpty()) {
                        item(key = "sec_reply") { SectionHeader("最近回复") }
                        val shown = page.recentReplies.take(20)
                        itemsIndexed(shown, key = { _, reply -> "rr_${reply.tid}" }) { index, reply ->
                            FeedRow(
                                title = reply.title,
                                meta = listOfNotNull(reply.boardName, reply.author, shortIndexTime(reply.time))
                                    .joinToString(" · "),
                                first = index == 0,
                                last = index == shown.lastIndex,
                                onClick = { nav.navigate(Routes.thread(reply.tid)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityHeader(onSearch: () -> Unit, onFavor: () -> Unit, onHistory: () -> Unit) {    Row(
        Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        QuickEntry("站内搜索", Icons.Outlined.Search, Modifier.weight(1f), onSearch)
        QuickEntry("我的收藏", Icons.Outlined.StarBorder, Modifier.weight(1f), onFavor)
        QuickEntry("浏览历史", Icons.Outlined.History, Modifier.weight(1f), onHistory)
    }
}

@Composable
private fun QuickEntry(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(
            Modifier.padding(vertical = Spacing.m, horizontal = Spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoardRow(board: Board, first: Boolean, last: Boolean, onNavigate: (String) -> Unit) {
    val icon = when {
        board.name.contains("图墙") -> Icons.Outlined.GridView
        board.name.contains("Comic") || board.name.contains("同人") || board.name.contains("汉化") -> Icons.Outlined.AutoStories
        else -> Icons.Outlined.Forum
    }
    Surface(
        onClick = { onNavigate(board.fid) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
        shape = RoundedCornerShape(
            topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp,
            bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp,
        ),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column {
            Row(
                Modifier.padding(Spacing.l),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                IconTile(icon)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        Text(board.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (board.isCertified) StatusBadge("认证", MaterialTheme.extended.certRed, MaterialTheme.colorScheme.onError)
                    }
                    board.desc?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
            }
            // 行内子版块（与网页版一致，收进主版块行内的小链接）
            if (board.children.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 72.dp, end = Spacing.l).padding(bottom = Spacing.m),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    board.children.forEach { child ->
                        Text(
                            child.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { onNavigate(child.fid) }
                                .padding(horizontal = Spacing.s, vertical = 2.dp),
                        )
                    }
                }
            }
            if (!last) HorizontalDivider(Modifier.padding(start = 72.dp, end = Spacing.l), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun FeedRow(title: String, meta: String, first: Boolean, last: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
        shape = RoundedCornerShape(
            topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp,
            bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp,
        ),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!last) HorizontalDivider(Modifier.padding(start = Spacing.l, end = Spacing.l), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

private fun formatFeedTime(millis: Long): String? = if (millis <= 0L) null else runCatching {
    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.ofEpochMilli(millis))
}.getOrNull()

/** 「2026-10-01 05:44」→「10-01 05:44」，首页 feed 里省年份。 */
private fun shortIndexTime(time: String?): String? = when {
    time == null -> null
    time.length >= 16 -> time.substring(5)
    else -> time
}

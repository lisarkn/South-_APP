package net.southplus.client.ui.mine

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.southplus.client.AppGraph
import net.southplus.client.core.model.ThreadItem
import net.southplus.client.core.model.UserListItem
import net.southplus.client.core.network.ChallengeException
import net.southplus.client.ui.ForumTopBar
import net.southplus.client.ui.Routes
import net.southplus.client.ui.components.AvatarBox
import net.southplus.client.ui.components.EmptyPane
import net.southplus.client.ui.components.ErrorPane
import net.southplus.client.ui.components.LoadingPane
import net.southplus.client.ui.components.ThreadRow
import net.southplus.client.ui.components.uiMessage
import net.southplus.client.ui.graphViewModel
import net.southplus.client.ui.theme.Spacing

/**
 * 「我的」子页（我的主题/回复/好友/商品/论坛任务）共用壳：
 * 同为 u.php 用户中心 / 任务插件页的列表，仅路径与标题不同（§6.5 / 2026-09 校准）。
 * 论坛任务等插件页的子分类（新任务选择/进行中…）是站内链接，走应用内下钻栈。
 */
class UserPanelViewModel(private val rootPath: String) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val items: List<UserListItem> = emptyList(),
        val nextUrl: String? = null,
        val error: String? = null,
        val loginRequired: Boolean = false,
        val notice: String? = null,
        val canGoUp: Boolean = false,
        val actingUrl: String? = null,   // 正在执行的任务动作（申请/领奖），用于禁用按钮
    )

    private val pathStack = ArrayDeque(listOf(rootPath))
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        loadCurrent(force = false)
    }

    fun load(force: Boolean = false) = loadCurrent(force)

    /** 站内下钻（任务分类等）：入栈并加载。 */
    fun openPath(url: String) {
        pathStack.addLast(url)
        loadCurrent(force = false)
    }

    /** 返回上一层（系统返回键拦截）；已到根则返回 false 交给导航退出。 */
    fun goUp(): Boolean {
        if (pathStack.size <= 1) return false
        pathStack.removeLast()
        loadCurrent(force = false)
        return true
    }

    fun loadMore() {
        val next = _state.value.nextUrl ?: return
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { AppGraph.repo.userPanel(next) }
                .onSuccess { p ->
                    _state.update {
                        it.copy(
                            loading = false,
                            items = (it.items + p.items).distinctBy { i -> i.href ?: i.title },
                            nextUrl = p.nextUrl,
                            notice = it.notice ?: p.notice,
                        )
                    }
                }
                .onFailure { e -> applyError(e) }
        }
    }

    private fun loadCurrent(force: Boolean) {
        val path = pathStack.last()
        viewModelScope.launch {
            _state.update { it.copy(loading = it.items.isEmpty() && it.notice == null, error = null) }
            runCatching { AppGraph.repo.userPanel(path, force = force) }
                .onSuccess { p ->
                    _state.value = State(
                        loading = false,
                        items = p.items,
                        nextUrl = p.nextUrl,
                        notice = p.notice,
                        canGoUp = pathStack.size > 1,
                    )
                }
                .onFailure { e -> applyError(e) }
        }
    }

    private fun applyError(e: Throwable) {
        _state.update {
            it.copy(
                loading = false,
                error = e.uiMessage(),
                loginRequired = e is ChallengeException &&
                    e.challenge == net.southplus.client.core.model.Challenge.NOT_LOGGED_IN,
            )
        }
    }

    /** 论坛任务申请/领取：结果经回调提示；成功后强刷当前列表让任务状态页内流转。 */
    fun runAction(item: UserListItem, onResult: (String) -> Unit) {
        val url = item.actionUrl ?: return
        if (_state.value.actingUrl != null) return
        viewModelScope.launch {
            _state.update { it.copy(actingUrl = url) }
            runCatching { AppGraph.repo.runTaskAction(url) }
                .onSuccess { message ->
                    _state.update { it.copy(actingUrl = null) }
                    loadCurrent(force = true)
                    onResult(message)
                }
                .onFailure { e ->
                    _state.update { it.copy(actingUrl = null) }
                    onResult(e.uiMessage())
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserPanelScreen(nav: NavHostController, title: String, path: String) {
    val vm: UserPanelViewModel = graphViewModel { UserPanelViewModel(path) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }

    BackHandler(enabled = state.canGoUp) { vm.goUp() }

    fun performTask(item: UserListItem) {
        vm.runAction(item) { message ->
            scope.launch { snackbar.showSnackbar(message, duration = androidx.compose.material3.SnackbarDuration.Short) }
        }
    }

    fun openItem(item: UserListItem) {
        when {
            item.actionUrl != null -> performTask(item) // 任务卡：整行点击即申请/领取
            item.kind == UserListItem.Kind.THREAD && item.tid != null ->
                nav.navigate(Routes.thread(item.tid))
            item.kind == UserListItem.Kind.USER && item.uid != null ->
                nav.navigate(Routes.user(item.uid))
            item.fid != null ->
                nav.navigate(Routes.board(item.fid))
            item.href != null && item.href.let { it.contains("plugin.php") || it.contains("u.php?") } ->
                vm.openPath(item.href) // 论坛任务分类等站内插件页：应用内下钻（浏览器无登录态）
            item.href != null -> scope.launch {
                net.southplus.client.core.links.LinkOpener.open(context, item.href, AppGraph.settings.snapshot())
            }
        }
    }

    Scaffold(
        topBar = { ForumTopBar(title, nav) },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
    ) { padding ->
        val s = state
        when {
            s.loading && s.items.isEmpty() && s.notice == null -> LoadingPane(Modifier.padding(padding))
            s.error != null && s.items.isEmpty() -> ErrorPane(
                Modifier.padding(padding),
                message = s.error ?: "",
                onRetry = { vm.load(force = true) },
                extraLabel = if (s.loginRequired) "去登录" else null,
                onExtra = if (s.loginRequired) ({ nav.navigate(Routes.LOGIN) }) else null,
            )
            s.items.isEmpty() && s.notice == null -> EmptyPane(
                text = "这里还没有内容。",
                modifier = Modifier.padding(padding),
                icon = Icons.Outlined.Person,
                title = "暂无内容",
            )
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(vertical = Spacing.s),
            ) {
                s.notice?.let { notice ->
                    item(key = "notice") {
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.l, vertical = Spacing.s),
                            shape = MaterialTheme.shapes.large,
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            ),
                        ) {
                            Text(
                                notice,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(Spacing.l),
                            )
                        }
                    }
                }
                s.items.forEachIndexed { idx, item ->
                    item(key = "${item.href ?: item.title}#$idx") {
                        UserItemRow(
                            item = item,
                            acting = s.actingUrl != null && s.actingUrl == item.actionUrl,
                            onAction = item.actionUrl?.let { { performTask(item) } },
                            onClick = { openItem(item) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
                s.error?.let { error ->
                    item(key = "error") { ErrorPane(message = error, onRetry = { vm.load(force = true) }) }
                }
                if (s.nextUrl != null && s.error == null) {
                    item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(vertical = Spacing.m), contentAlignment = Alignment.Center) {
                            if (s.loading) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                TextButton(onClick = vm::loadMore) { Text("加载更多") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserItemRow(
    item: UserListItem,
    acting: Boolean = false,
    onAction: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    when (item.kind) {
        UserListItem.Kind.THREAD -> ThreadRow(
            ThreadItem(tid = item.tid.orEmpty(), title = item.title, lastReplyTime = item.meta),
        ) { onClick() }
        UserListItem.Kind.USER -> Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarBox(item.title, size = 40.dp, avatarUrl = item.avatarUrl)
            Column(Modifier.weight(1f).padding(start = Spacing.l)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.meta?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        UserListItem.Kind.GENERIC -> Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.meta?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onAction != null) {
                // 论坛任务卡：申请任务 / 领取奖励（AJAX 由客户端代为执行）
                FilledTonalButton(
                    onClick = onAction,
                    enabled = !acting,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.padding(start = Spacing.m),
                ) {
                    if (acting) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(item.actionLabel ?: "执行", style = MaterialTheme.typography.labelLarge)
                    }
                }
            } else {
                Icon(
                    Icons.Filled.OpenInNew,
                    contentDescription = "在浏览器打开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

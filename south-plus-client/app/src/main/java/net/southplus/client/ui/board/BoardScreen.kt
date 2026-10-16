package net.southplus.client.ui.board

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.southplus.client.core.util.LatestRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.southplus.client.AppGraph
import net.southplus.client.core.model.Challenge
import net.southplus.client.core.model.ThreadItem
import net.southplus.client.core.model.ThreadListPage
import net.southplus.client.core.network.ChallengeException
import net.southplus.client.core.settings.AppSettings
import net.southplus.client.ui.ForumTopBar
import net.southplus.client.ui.Routes
import net.southplus.client.ui.components.ErrorPane
import net.southplus.client.ui.components.LoadingPane
import net.southplus.client.ui.components.ThreadRow
import net.southplus.client.ui.components.uiMessage
import net.southplus.client.ui.graphViewModel
import net.southplus.client.ui.theme.Spacing

class BoardViewModel(private val fid: String) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val refreshing: Boolean = false,
        val failedLoadMore: Boolean = false,
        val boardName: String? = null,
        val threads: List<ThreadItem> = emptyList(),
        val page: Int = 1,
        val totalPages: Int = 1,
        val error: String? = null,
        val needLogin: Boolean = false,
    )

    private val requests = LatestRequest(viewModelScope)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load(force: Boolean = false) {
        requests.launch {
            _state.update { it.copy(loading = it.threads.isEmpty(), loadingMore = false, refreshing = it.threads.isNotEmpty(), failedLoadMore = false, error = null, needLogin = false) }
            runCatching { AppGraph.repo.board(fid, page = 1, force = force) }
                .onSuccess { p -> apply(p, append = false) }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = e.uiMessage(),
                            needLogin = (e as? ChallengeException)?.challenge in
                                listOf(Challenge.NO_PERMISSION, Challenge.NOT_LOGGED_IN),
                        )
                    }
                }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (requests.isRunning || s.loading || s.loadingMore || s.page >= s.totalPages) return
        requests.launch {
            _state.update { it.copy(loadingMore = true, error = null, failedLoadMore = false) }
            runCatching { AppGraph.repo.board(fid, page = s.page + 1) }
                .onSuccess { p -> apply(p, append = true) }
                .onFailure { e -> _state.update { it.copy(loadingMore = false, failedLoadMore = true, error = e.uiMessage()) } }
        }
    }

    private fun apply(p: ThreadListPage, append: Boolean) {
        _state.update {
            it.copy(
                loading = false,
                loadingMore = false,
                refreshing = false,
                failedLoadMore = false,
                boardName = p.boardName ?: it.boardName,
                threads = if (append) (it.threads + p.threads).distinctBy { t -> t.tid } else p.threads,
                page = p.page,
                totalPages = p.totalPages,
                error = null,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(nav: NavHostController, fid: String) {
    val vm: BoardViewModel = graphViewModel { BoardViewModel(fid) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by AppGraph.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val context = LocalContext.current

    Scaffold(
        topBar = {
            ForumTopBar(
                title = state.boardName ?: "版块 $fid",
                nav = nav,
                actions = {
                    IconButton(onClick = { vm.load(force = true) }, enabled = !state.loading && !state.refreshing && !state.loadingMore) { Icon(Icons.Filled.Refresh, "刷新") }
                },
            )
        },
    ) { padding ->
        val s = state
        when {
            s.loading -> LoadingPane(Modifier.padding(padding))
            s.error != null && s.threads.isEmpty() -> ErrorPane(
                Modifier.padding(padding),
                message = s.error ?: "",
                onRetry = { vm.load(force = true) },
                extraLabel = if (s.needLogin) "去登录" else null,
                onExtra = if (s.needLogin) ({ nav.navigate(Routes.LOGIN) }) else null,
            )
            s.threads.isEmpty() -> ErrorPane(
                Modifier.padding(padding),
                message = "未解析出帖子列表（版块可能为空或站点结构变化）\n当前镜像：${AppGraph.mirrors.current.host}\n可在 设置 → 网络诊断 查看详情",
                onRetry = { vm.load(force = true) },
                extraLabel = "用浏览器打开",
                onExtra = {
                    net.southplus.client.core.links.LinkOpener.open(
                        context, "https://${AppGraph.mirrors.current.host}/thread.php?fid-$fid.html",
                        settings,
                    )
                },
            )
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(vertical = Spacing.s),
            ) {
                item(key = "heading") {
                    if (s.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("版块讨论 · 已加载 ${s.threads.size} 帖", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m))
                }
                if (s.error != null && !s.failedLoadMore) {
                    item(key = "refresh_error") { ErrorPane(message = s.error, onRetry = { vm.load(force = true) }) }
                }
                s.threads.forEach { item ->
                    item(key = item.tid) {
                        ThreadRow(item) { nav.navigate(Routes.thread(item.tid)) }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = Spacing.l),
                        )
                    }
                }
                item(key = "footer") {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = Spacing.m),
                        contentAlignment = Alignment.Center,
                    ) {
                        val canMore = s.page < s.totalPages && !s.refreshing
                        when {
                            s.error != null && s.failedLoadMore -> ErrorPane(message = s.error, onRetry = vm::loadMore)
                            s.refreshing -> Text("正在刷新…", style = MaterialTheme.typography.labelMedium)
                            s.loadingMore -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            canMore -> TextButton(onClick = { vm.loadMore() }) {
                                Text("加载更多（第 ${s.page + 1}/${s.totalPages} 页）")
                            }
                            else -> Text(
                                "已加载全部 ${s.threads.size} 帖",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

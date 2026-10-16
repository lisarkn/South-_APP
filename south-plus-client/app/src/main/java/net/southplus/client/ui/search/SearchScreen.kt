package net.southplus.client.ui.search

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
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
import java.net.URLEncoder
import net.southplus.client.AppGraph
import net.southplus.client.core.model.SearchResultPage
import net.southplus.client.core.model.ThreadItem
import net.southplus.client.ui.ForumTopBar
import net.southplus.client.ui.Routes
import net.southplus.client.ui.components.EmptyPane
import net.southplus.client.ui.components.ErrorPane
import net.southplus.client.ui.components.LoadingPane
import net.southplus.client.ui.components.ThreadRow
import net.southplus.client.ui.components.uiMessage
import net.southplus.client.ui.graphViewModel
import net.southplus.client.ui.theme.Spacing

class SearchViewModel : ViewModel() {
    data class State(
        val keyword: String = "",
        val submittedKeyword: String? = null,
        val loading: Boolean = false,
        val results: List<ThreadItem> = emptyList(),
        val page: Int = 1,
        val totalPages: Int = 1,
        val error: String? = null,
        val searchDenied: Boolean = false, // 用户组无搜索权限（站点对游客/低级组封锁）
        val loginRequired: Boolean = false, // 会话失效导致（非权限问题）
        val filter: SearchFilter = SearchFilter(),
        val advancedOpen: Boolean = false,
        val boards: List<SearchFilter.BoardChoice> = emptyList(),
    )

    private val requests = LatestRequest(viewModelScope)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        // 版块选择常驻搜索页：进页即预载首页分区（走缓存）
        loadBoards()
    }

    fun setKeyword(k: String) = _state.update { it.copy(keyword = k) }

    fun toggleAdvanced(open: Boolean? = null) = _state.update {
        it.copy(advancedOpen = open ?: !it.advancedOpen)
    }

    fun setFilter(f: SearchFilter) = _state.update { it.copy(filter = f) }

    private fun loadBoards() {
        viewModelScope.launch {
            // 首页走缓存（isList TTL），失败静默：高级面板仍可用时间/排序，只是没有版块列表
            runCatching { AppGraph.repo.index() }.onSuccess { index ->
                _state.update { it.copy(boards = SearchFilter.boardChoices(index)) }
            }
        }
    }

    fun search(keyword: String) {
        if (keyword.isBlank()) return
        val f = _state.value.filter
        requests.launch {
            _state.update { it.copy(loading = true, error = null, keyword = keyword, submittedKeyword = keyword.trim(), results = emptyList()) }
            runCatching { AppGraph.repo.search(keyword.trim(), fid = f.fid, schTime = f.schTime, orderway = f.orderway) }
                .onSuccess { p -> apply(p, append = false) }
                .onFailure { e -> applyError(e) }
        }
    }

    fun loadMore() {
        val s = _state.value
        val keyword = s.submittedKeyword ?: return
        if (s.loading || s.page >= s.totalPages) return
        val f = s.filter
        requests.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                AppGraph.repo.search(keyword, fid = f.fid, page = s.page + 1, schTime = f.schTime, orderway = f.orderway)
            }
                .onSuccess { p -> apply(p, append = true) }
                .onFailure { e -> applyError(e) }
        }
    }

    private fun applyError(e: Throwable) {
        _state.update {
            it.copy(
                loading = false,
                error = e.uiMessage(),
                searchDenied = e is net.southplus.client.core.network.SearchPermissionException,
                loginRequired = e is net.southplus.client.core.network.ChallengeException &&
                    e.challenge == net.southplus.client.core.model.Challenge.NOT_LOGGED_IN,
            )
        }
    }

    private fun apply(p: SearchResultPage, append: Boolean) {
        _state.update {
            it.copy(
                loading = false,
                results = if (append) (it.results + p.results).distinctBy { r -> r.tid } else p.results,
                page = p.page,
                totalPages = p.totalPages,
                error = null,
                searchDenied = false,
                loginRequired = false,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: NavHostController) {
    val vm: SearchViewModel = graphViewModel { SearchViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val submit = {
        if (!state.loading && state.keyword.isNotBlank()) {
            keyboard?.hide()
            vm.search(state.keyword)
        }
    }

    Scaffold(topBar = { ForumTopBar("搜索", nav) }) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.l, vertical = Spacing.s),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.keyword,
                    onValueChange = vm::setKeyword,
                    modifier = Modifier.weight(1f),
                    label = { Text("搜索关键词") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.keyword.isNotEmpty()) {
                            IconButton(onClick = { vm.setKeyword("") }) {
                                Icon(Icons.Filled.Close, "清空", Modifier.size(18.dp))
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.large,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    singleLine = true,
                )
                Button(
                    onClick = submit,
                    enabled = !state.loading && state.keyword.isNotBlank(),
                    modifier = Modifier.heightIn(min = 52.dp),
                ) { Text("搜索") }
            }
            // ---- 版块范围（常驻，分区供选择，对应站点搜索表单的版块下拉） ----
            var boardPickerOpen by remember { mutableStateOf(false) }
            BoardScopeRow(
                label = if (state.filter.fid == null) "全站" else state.filter.boardLabel,
                onClick = { boardPickerOpen = true },
            )
            if (boardPickerOpen) {
                BoardPickerDialog(
                    boards = state.boards,
                    currentFid = state.filter.fid,
                    onPick = { choice ->
                        val f = state.filter
                        vm.setFilter(
                            if (choice == null) {
                                SearchFilter(schTime = f.schTime, timeLabel = f.timeLabel, orderway = f.orderway, orderLabel = f.orderLabel)
                            } else {
                                f.copy(fid = choice.fid, boardLabel = choice.name)
                            },
                        )
                        boardPickerOpen = false
                    },
                )
            }
            // ---- 高级选项（时间范围 / 排序方式，默认收起） ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { vm.toggleAdvanced() }) {
                    Icon(
                        if (state.advancedOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        if (state.advancedOpen) "收起高级选项"
                        else if (!state.filter.isDefault) "高级选项（${state.filter.summary()}）"
                        else "高级选项",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (state.advancedOpen) {
                AdvancedFilterPanel(
                    filter = state.filter,
                    onFilter = vm::setFilter,
                )
            }
            Text(
                "提示：站点搜索可能要求登录",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.l),
            )
            val s = state
            val googleFallback: (() -> Unit)? = if (s.searchDenied) {
                {
                    // 用户组无搜索权限 → Google site: 检索当前镜像作为替代入口
                    keyboard?.hide()
                    scope.launch {
                        val host = AppGraph.mirrors.current.host
                        val url = "https://www.google.com/search?q=site:${host}+${URLEncoder.encode(s.submittedKeyword ?: s.keyword, "UTF-8")}"
                        net.southplus.client.core.links.LinkOpener.open(context, url, AppGraph.settings.snapshot())
                    }
                }
            } else null
            val loginAction: (() -> Unit)? = if (s.loginRequired) ({ nav.navigate(Routes.LOGIN) }) else null
            when {
                s.loading && s.results.isEmpty() -> LoadingPane()
                s.error != null && s.results.isEmpty() -> ErrorPane(
                    message = s.error.orEmpty(),
                    onRetry = { vm.search(s.submittedKeyword ?: s.keyword) },
                    extraLabel = if (s.searchDenied) "Google 站内搜索" else if (s.loginRequired) "去登录" else null,
                    onExtra = googleFallback ?: loginAction,
                )
                s.submittedKeyword == null -> EmptyPane(
                    "输入关键词，发现感兴趣的话题与分享。",
                    icon = Icons.Outlined.Search,
                    title = "从一个关键词开始",
                )
                s.results.isEmpty() -> EmptyPane("试试更简短的词语，或换一种说法。", icon = Icons.Outlined.Search, title = "没有找到相关帖子")
                else -> LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = Spacing.m)) {
                    item(key = "query") {
                        Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) {
                            Text(
                                "“${s.submittedKeyword}” 的搜索结果",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!s.filter.isDefault) {
                                Text(
                                    s.filter.summary(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                    s.results.forEach { item ->
                        item(key = item.tid) {
                            ThreadRow(item) { nav.navigate(Routes.thread(item.tid)) }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(start = Spacing.l),
                            )
                        }
                    }
                    s.error?.let { error ->
                        item(key = "error") { ErrorPane(message = error, onRetry = vm::loadMore) }
                    }
                    if (s.page < s.totalPages && s.error == null) {
                        item(key = "more") {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = Spacing.m),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (s.loading) {
                                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                } else {
                                    TextButton(onClick = { vm.loadMore() }) {
                                        Text("加载更多（第 ${s.page + 1}/${s.totalPages} 页）")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 版块范围行（常驻搜索页）：点击打开分区分组的版块选择对话框。 */
@Composable
private fun BoardScopeRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(
            "版块",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f).heightIn(min = 40.dp),
        ) {
            Row(
                Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = "选择版块",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 高级搜索面板：时间范围 + 排序方式（版块范围在上方常驻行选择）。 */
@Composable
private fun AdvancedFilterPanel(
    filter: SearchFilter,
    onFilter: (SearchFilter) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs)) {
        // 时间范围
        FilterRow("时间", SearchOptions.TIME, filter.schTime) { opt ->
            onFilter(filter.copy(schTime = opt.value, timeLabel = opt.label))
        }
        // 排序方式
        FilterRow("排序", SearchOptions.ORDER, filter.orderway) { opt ->
            onFilter(filter.copy(orderway = opt.value, orderLabel = opt.label))
        }
    }
}

@Composable
private fun FilterRow(label: String, options: List<SearchOption>, current: String, onPick: (SearchOption) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            options.forEach { opt ->
                FilterChip(
                    selected = opt.value == current,
                    onClick = { onPick(opt) },
                    label = { Text(opt.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}

@Composable
private fun BoardPickerDialog(
    boards: List<SearchFilter.BoardChoice>,
    currentFid: String?,
    onPick: (SearchFilter.BoardChoice?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onPick(null) },
        title = { Text("限定版块") },
        text = {
            if (boards.isEmpty()) {
                Text(
                    "版块列表加载中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item(key = "all") {
                        PickerRow(label = "全站（不限版块）", selected = currentFid == null) { onPick(null) }
                    }
                    boards.groupBy { it.section }.forEach { (section, list) ->
                        item(key = "sec:$section") {
                            Text(
                                section,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
                            )
                        }
                        list.forEach { b ->
                            item(key = b.fid) {
                                PickerRow(label = b.name, selected = b.fid == currentFid) { onPick(b) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onPick(null) }) { Text("关闭") } },
    )
}

@Composable
private fun PickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "已选择", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

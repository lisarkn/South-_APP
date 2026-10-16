package net.southplus.client.ui.pm

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import net.southplus.client.core.model.SessionState
import net.southplus.client.core.settings.AppSettings
import net.southplus.client.ui.Routes
import net.southplus.client.ui.handleLink
import net.southplus.client.ui.components.AvatarBox
import net.southplus.client.ui.components.EmptyPane
import net.southplus.client.ui.components.StatusBadge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.southplus.client.AppGraph
import net.southplus.client.core.model.PmDetail
import net.southplus.client.core.model.PmSummary
import net.southplus.client.ui.ForumTopBar
import net.southplus.client.ui.components.ErrorPane
import net.southplus.client.ui.components.HtmlText
import net.southplus.client.ui.components.LoadingPane
import net.southplus.client.ui.components.uiMessage
import net.southplus.client.ui.graphViewModel
import net.southplus.client.ui.theme.Spacing

class PmViewModel : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val items: List<PmSummary> = emptyList(),
        val error: String? = null,
        val detail: PmDetail? = null,
        val busy: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load(force: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.items.isEmpty(), error = null) }
            runCatching { AppGraph.repo.pmList() }
                .onSuccess { p -> _state.update { it.copy(loading = false, items = p.items) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.uiMessage()) } }
        }
    }

    fun dismissDetail() = _state.update { it.copy(detail = null) }

    fun open(mid: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(detail = null, busy = true, error = null) }
            runCatching { AppGraph.repo.pmDetail(mid) }
                .onSuccess { d -> _state.update { it.copy(detail = d, busy = false) } }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.uiMessage()) } }
        }
    }

    fun send(to: String, title: String, content: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { AppGraph.repo.pmSend(to, title, content) }
                .onSuccess { _state.update { it.copy(busy = false) }; onSuccess(it) }
                .onFailure { _state.update { it.copy(busy = false) }; onError(it.uiMessage()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PmScreen(nav: NavHostController) {
    val vm: PmViewModel = graphViewModel { PmViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()
    val session by AppGraph.session.state.collectAsStateWithLifecycle()
    val settings by AppGraph.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var composeOpen by remember { mutableStateOf(false) }
    LaunchedEffect(session) { if (session is SessionState.LoggedIn) vm.load() }

    Scaffold(
        topBar = { ForumTopBar("消息", nav, actions = {
            IconButton(onClick = { vm.load() }, enabled = !state.loading && !state.busy) { Icon(Icons.Filled.Refresh, "刷新消息") }
        }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (session is SessionState.LoggedIn) {
                FloatingActionButton(onClick = { composeOpen = true }) { Icon(Icons.AutoMirrored.Filled.Send, "写私信") }
            }
        },
    ) { padding ->
        val s = state
        when {
            session is SessionState.Guest -> EmptyPane(
                "登录后查看站内消息，与大家保持联系。", Modifier.padding(padding),
                icon = Icons.Outlined.Email, title = "你的消息，在这里",
                action = { Button(onClick = { nav.navigate(Routes.LOGIN) }) { Text("去登录") } },
            )
            s.loading -> LoadingPane(Modifier.padding(padding))
            s.error != null && s.items.isEmpty() -> ErrorPane(
                Modifier.padding(padding), message = s.error, onRetry = { vm.load() },
                extraLabel = if (session !is SessionState.LoggedIn) "去登录" else null,
                onExtra = { nav.navigate(Routes.LOGIN) },
            )
            s.items.isEmpty() -> EmptyPane("收到的私信会显示在这里。", Modifier.padding(padding),
                icon = Icons.Outlined.Email, title = "暂无新消息")
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(top = Spacing.s, bottom = 96.dp),
            ) {
                if (s.busy) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                s.error?.let { error -> item(key = "error") { ErrorPane(message = error, onRetry = { vm.load() }) } }
                items(s.items.size, key = { i -> s.items[i].mid }) { i ->
                    val pm = s.items[i]
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .clickable(enabled = !s.busy) { vm.open(pm.mid) }.padding(Spacing.l),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    ) {
                        AvatarBox(pm.fromUser ?: "?")
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(pm.subject, style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (pm.isUnread) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(pm.fromUser ?: "未知发件人", pm.time).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (pm.isUnread) StatusBadge("未读", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = Spacing.l))
                }
            }
        }
    }

    state.detail?.let { d ->
        AlertDialog(
            onDismissRequest = vm::dismissDetail,
            title = { Text(d.subject ?: "无标题") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    Text(listOfNotNull(d.fromUser?.let { "来自：$it" }, d.time).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (d.html.isNotBlank()) {
                        HtmlText(html = d.html, onLink = { vm.dismissDetail(); handleLink(context, nav, it, settings) })
                    } else Text("无内容")
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissDetail) { Text("关闭") } },
        )
    }

    if (composeOpen) {
        var to by remember { mutableStateOf("") }
        var title by remember { mutableStateOf("") }
        var content by remember { mutableStateOf("") }
        var sendError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!state.busy) composeOpen = false },
            title = { Text("写私信") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    OutlinedTextField(value = to, onValueChange = { to = it }, label = { Text("收件人") }, singleLine = true,
                        enabled = !state.busy, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, singleLine = true,
                        enabled = !state.busy, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                    OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("正文") },
                        enabled = !state.busy, minLines = 3, maxLines = 8,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp))
                    sendError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = {
                Button(enabled = !state.busy && to.isNotBlank() && content.isNotBlank(), onClick = {
                    sendError = null
                    vm.send(to, title, content,
                        onSuccess = { msg -> composeOpen = false; scope.launch { snackbar.showSnackbar(msg) } },
                        onError = { sendError = it })
                }) { Text(if (state.busy) "发送中…" else "发送") }
            },
            dismissButton = { TextButton(onClick = { composeOpen = false }, enabled = !state.busy) { Text("取消") } },
        )
    }
}

package net.southplus.client.ui.reader

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.southplus.client.AppGraph
import net.southplus.client.core.model.Attachment
import net.southplus.client.core.model.Challenge
import net.southplus.client.core.model.ContentBlock
import net.southplus.client.core.model.Post
import net.southplus.client.core.model.ResourceLink
import net.southplus.client.core.model.ThreadPage
import net.southplus.client.core.network.ChallengeException
import net.southplus.client.core.settings.AppSettings
import net.southplus.client.core.webview.WebViewActivity
import net.southplus.client.ui.ForumTopBar
import net.southplus.client.ui.Routes
import net.southplus.client.ui.components.AvatarBox
import net.southplus.client.ui.components.ErrorPane
import net.southplus.client.ui.components.HtmlText
import net.southplus.client.ui.components.ImageViewerDialog
import net.southplus.client.ui.components.LoadingPane
import net.southplus.client.ui.components.NetImage
import net.southplus.client.ui.components.ResourceCardView
import net.southplus.client.ui.components.StatusBadge
import net.southplus.client.ui.components.copyToClipboard
import net.southplus.client.ui.components.uiMessage
import net.southplus.client.ui.graphViewModel
import net.southplus.client.ui.handleLink
import net.southplus.client.ui.openPan
import net.southplus.client.ui.theme.Spacing
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import net.southplus.client.core.util.PageMemoryCache
import androidx.compose.runtime.saveable.rememberSaveable
import android.text.Spanned
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics

// ---------------- ViewModel ----------------

class ReaderViewModel(private val tid: String, startPage: Int = 1) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val page: ThreadPage? = null,
        val renderedPosts: List<RenderedPost> = emptyList(),
        val error: String? = null,
        val challengeUrl: String? = null,
        val needLogin: Boolean = false,
        val busy: Boolean = false,
        val filterAuthor: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private var currentPage = startPage.coerceAtLeast(1)
    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private val pages = PageMemoryCache<RenderedPage>()
    private var cacheNamespace: Triple<Long, String, Any>? = null

    // 0 SP币自动购买去重：同一购买链接本会话只自动尝试一次（失败不自动重试，可手动购买）
    private val autoBuyTried = mutableSetOf<String>()

    fun tryAutoBuy(buyUrl: String): Boolean = synchronized(autoBuyTried) { autoBuyTried.add(buyUrl) }

    init {
        load(currentPage)
    }

    fun load(page: Int, force: Boolean = false): Job {
        val requestedPage = page.coerceAtLeast(1)
        if (!force && requestedPage == currentPage && loadJob?.isActive == true) return loadJob!!
        loadJob?.cancel()
        val generation = ++loadGeneration
        currentPage = requestedPage
        return viewModelScope.launch {
            val namespace = Triple(AppGraph.session.cacheVersion, AppGraph.mirrors.current.host, AppGraph.selectors as Any)
            if (force || namespace != cacheNamespace) pages.clear()
            cacheNamespace = namespace
            _state.update { it.copy(loading = it.page == null || requestedPage != it.page?.page,
                refreshing = it.page != null && requestedPage == it.page.page,
                error = null, challengeUrl = null, needLogin = false) }
            runCatching {
                val cached = if (force) null else pages.get(requestedPage)
                if (cached != null) {
                    AppGraph.repo.recordVisit(cached.page)
                    cached
                } else {
                    val p = AppGraph.repo.thread(tid, requestedPage, force)
                    withContext(Dispatchers.Default) { prepareReaderPage(p, AppGraph.selectors) }
                }
            }.onSuccess { rendered ->
                currentCoroutineContext().ensureActive()
                if (generation == loadGeneration) {
                    pages.put(requestedPage, rendered, rendered.weight)
                    _state.update { it.copy(loading = false, refreshing = false, page = rendered.page, renderedPosts = rendered.posts, error = null) }
                }
            }.onFailure { e ->
                if (generation == loadGeneration) _state.update {
                    it.copy(loading = false, refreshing = false, error = e.uiMessage(),
                        challengeUrl = (e as? ChallengeException)?.takeIf { ce -> ce.challenge == Challenge.CLOUDFLARE }?.url,
                        needLogin = (e as? ChallengeException)?.challenge in listOf(Challenge.NO_PERMISSION, Challenge.NOT_LOGGED_IN))
                }
            }
        }.also { loadJob = it }
    }

    private suspend fun invalidatePages() {
        loadJob?.cancel()
        ++loadGeneration
        pages.clear()
        AppGraph.repo.invalidateThread(tid)
    }

    fun retry(force: Boolean = false) = load(currentPage, force = true)

    fun onlyAuthor(name: String?) = _state.update { it.copy(filterAuthor = name) }

    fun reply(content: String, onDone: (String) -> Unit, onError: (String) -> Unit) {
        val page = _state.value.page ?: return
        val url = page.replyUrl ?: run { onError("未找到回复入口（需登录）"); return }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { AppGraph.repo.reply(url, title = null, content = content) }
                .onSuccess {
                    _state.update { it.copy(busy = false) }
                    onDone(it)
                    invalidatePages()
                    load(currentPage, force = true)
                }
                .onFailure {
                    _state.update { it.copy(busy = false) }
                    onError(it.uiMessage())
                }
        }
    }

    fun buy(buyUrl: String, onDone: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { AppGraph.repo.buy(buyUrl) }
                .onSuccess {
                    onDone(it) // 展示服务端真实反馈，不硬编码「购买完成」
                    // 等强刷完成再解除 busy：卡片上的「正在购买…」持续到解锁内容可见，
                    // 避免 POST 后静默等待整页刷新被感知为卡顿
                    invalidatePages()
                    load(currentPage, force = true).join()
                    _state.update { it.copy(busy = false) }
                }
                .onFailure {
                    _state.update { it.copy(busy = false) }
                    onError(it.uiMessage())
                }
        }
    }

    fun addFavor(onResult: (String) -> Unit) {
        val url = _state.value.page?.favorUrl ?: run { onResult("未找到收藏入口（需登录）"); return }
        viewModelScope.launch {
            runCatching { AppGraph.repo.addFavor(url) }
                .onSuccess(onResult)
                .onFailure { onResult(it.uiMessage()) }
        }
    }

    fun editPost(editUrl: String, content: String, onDone: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { AppGraph.repo.editPost(editUrl, title = null, content = content) }
                .onSuccess {
                    _state.update { it.copy(busy = false) }
                    onDone(it)
                    invalidatePages()
                    load(currentPage, force = true)
                }
                .onFailure {
                    _state.update { it.copy(busy = false) }
                    onError(it.uiMessage())
                }
        }
    }

    fun loadEditContent(url: String, onLoaded: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { AppGraph.repo.fetchEditContent(url) }
                .onSuccess(onLoaded)
                .onFailure { onLoaded(null) }
        }
    }
}

// ---------------- Screen ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(nav: NavHostController, tid: String, startPage: Int) {
    val vm: ReaderViewModel = graphViewModel { ReaderViewModel(tid, startPage) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val wifi by rememberWifiState()
    val settings by AppGraph.settings.readerSettings.collectAsStateWithLifecycle(initialValue = AppGraph.settings.currentReaderSettings)

    var replyDialog by remember { mutableStateOf(false) }
    var replyInitial by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<String?>(null) }
    var editInitial by remember { mutableStateOf<String?>(null) }
    var buyTarget by remember { mutableStateOf<String?>(null) }
    var downloadTarget by remember { mutableStateOf<Attachment?>(null) }
    var jumpPage by remember { mutableStateOf(false) }
    var viewImage by remember { mutableStateOf<String?>(null) }

    val challengeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.retry(force = true)
    }

    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    // 0 SP币付费贴自动购买（设置开关，默认开）：售价为 0 时免确认直接购买，
    // 每个购买链接仅自动尝试一次，失败不自动重试（卡片按钮仍可手动购买）。
    LaunchedEffect(state.page, settings.autoBuyFree) {
        val p = state.page ?: return@LaunchedEffect
        if (!settings.autoBuyFree) return@LaunchedEffect
        val url = p.posts.asSequence()
            .flatMap { it.contentBlocks.asSequence() }
            .filterIsInstance<ContentBlock.PaidWall>()
            .firstOrNull { it.price == 0 && it.buyUrl != null }
            ?.buyUrl ?: return@LaunchedEffect
        if (state.busy || !vm.tryAutoBuy(url)) return@LaunchedEffect
        vm.buy(url, onDone = { msg -> toast("0 SP币，已自动购买：$msg") }, onError = { toast(it) })
    }

    Scaffold(
        topBar = {
            ForumTopBar(
                title = state.page?.boardName ?: "帖子阅读",
                nav = nav,
                actions = {
                    IconButton(onClick = { vm.retry() }, enabled = !state.loading && !state.refreshing) {
                        Icon(Icons.Filled.Refresh, "刷新帖子")
                    }
                    IconButton(onClick = {
                        state.page?.favorUrl?.let { vm.addFavor { msg -> toast(msg) } } ?: toast("未找到收藏入口（需登录）")
                    }) { Icon(Icons.Filled.Star, "收藏") }
                },
            )
        },
        bottomBar = {
            val page = state.page
            if (page != null) {
                ReaderToolbar(
                    page = page.page, totalPages = page.totalPages,
                    loading = state.loading || state.refreshing,
                    canReply = !state.busy,
                    onPrevious = { vm.load(page.page - 1) }, onNext = { vm.load(page.page + 1) },
                    onJump = { jumpPage = true }, onReply = { replyDialog = true },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = state
        val page = s.page
        when {
            s.loading -> LoadingPane(Modifier.padding(padding))
            s.error != null && page == null -> {
                // 次按钮：CF 盾 → WebView 过盾；无权限/未登录 → 跳登录页
                val extraLabel = when {
                    s.challengeUrl != null -> "打开验证页面"
                    s.needLogin -> "去登录"
                    else -> null
                }
                val onExtra: (() -> Unit)? = when {
                    s.challengeUrl != null -> ({
                        challengeLauncher.launch(
                            Intent(context, WebViewActivity::class.java)
                                .putExtra(WebViewActivity.EXTRA_URL, s.challengeUrl)
                                .putExtra(WebViewActivity.EXTRA_AUTO_CLOSE, true),
                        )
                    })
                    s.needLogin -> ({ nav.navigate(Routes.LOGIN) })
                    else -> null
                }
                ErrorPane(
                    Modifier.padding(padding),
                    message = s.error ?: "",
                    onRetry = { vm.retry(force = true) },
                    extraLabel = extraLabel,
                    onExtra = onExtra,
                )
            }
            page != null && page.posts.isEmpty() -> ErrorPane(
                Modifier.padding(padding),
                message = "正文未解析出楼层（站点结构可能变化）\n当前镜像：${AppGraph.mirrors.current.host}\n可在 设置 → 网络诊断 查看详情并复制原始页面",
                onRetry = { vm.retry(force = true) },
                extraLabel = "应用内打开原页",
                onExtra = {
                    context.startActivity(
                        android.content.Intent(context, WebViewActivity::class.java)
                            .putExtra(WebViewActivity.EXTRA_URL, "https://${AppGraph.mirrors.current.host}/read.php?tid-$tid-page-${page.page}.html")
                            .putExtra(WebViewActivity.EXTRA_AUTO_CLOSE, false),
                    )
                },
            )
            page != null -> {
                val posts = remember(s.renderedPosts, s.filterAuthor) {
                    if (s.filterAuthor != null) s.renderedPosts.filter { it.post.author.name == s.filterAuthor }
                    else s.renderedPosts
                }
                LazyColumn(
                    Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(vertical = Spacing.s),
                ) {
                    item(key = "title", contentType = "title") {
                        ReaderTitle(page.title, page.page, page.totalPages)
                        if (s.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    s.error?.let { error ->
                        item(key = "error") {
                            ErrorPane(message = error, onRetry = { vm.retry() },
                                extraLabel = if (s.needLogin) "去登录" else if (s.challengeUrl != null) "打开验证页面" else null,
                                onExtra = when {
                                    s.needLogin -> ({ nav.navigate(Routes.LOGIN) })
                                    s.challengeUrl != null -> ({
                                        challengeLauncher.launch(Intent(context, WebViewActivity::class.java)
                                            .putExtra(WebViewActivity.EXTRA_URL, s.challengeUrl)
                                            .putExtra(WebViewActivity.EXTRA_AUTO_CLOSE, true))
                                    })
                                    else -> null
                                })
                        }
                    }
                    if (s.filterAuthor != null) {
                        item(key = "filter") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs)) {
                                Surface(
                                    onClick = { vm.onlyAuthor(null) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                                        modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "取消只看",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                        Text(
                                            "只看：${s.filterAuthor}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    posts.forEach { rendered ->
                        val post = rendered.post
                        item(key = "${rendered.key}:header", contentType = "floor-header") {
                            FloorHeader(
                                post = post, plainText = rendered.plainText,
                                onUser = { uid -> uid?.let { nav.navigate(Routes.user(it)) } },
                                onQuoteReply = { quote -> replyInitial = quote; replyDialog = true },
                                onEdit = { url ->
                                    editTarget = url; editInitial = null
                                    vm.loadEditContent(url) { editInitial = it }
                                },
                                onOnlyAuthor = { vm.onlyAuthor(post.author.name) },
                            )
                        }
                        items(post.contentBlocks.size,
                            key = { "${rendered.key}:block:$it" },
                            contentType = { post.contentBlocks[it].javaClass.name },
                        ) { index ->
                            FloorBlock(
                                block = post.contentBlocks[index], prepared = rendered.text[index],
                                settings = settings, busy = state.busy && post.contentBlocks[index] is ContentBlock.PaidWall, wifi = wifi,
                                onLink = { handleLink(context, nav, it, settings, onImage = { viewImage = it }) },
                                onViewImage = { viewImage = it },
                                onDownload = { downloadTarget = it }, onBuy = { buyTarget = it },
                            )
                        }
                        items(rendered.cards.size, key = { "${rendered.key}:resource:$it" }, contentType = { "resource" }) { index ->
                            Box(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs)) {
                                ResourceCardView(rendered.cards[index],
                                    onOpen = { openPan(context, it.url, it.code, settings) },
                                    onCopyLink = { copyToClipboard(context, "链接", it.url) },
                                    onCopyCode = { copyToClipboard(context, "提取码", it.code.orEmpty()) })
                            }
                        }
                        items(post.attachments.size, key = { "${rendered.key}:attachment:$it" }, contentType = { "attachment" }) { index ->
                            Box(Modifier.padding(horizontal = Spacing.l)) { AttachmentChip(post.attachments[index]) { downloadTarget = it } }
                        }
                        item(key = "${rendered.key}:divider", contentType = "divider") {
                            HorizontalDivider(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }

    // ---- 全屏图片查看器：点击帖子图片应用内看大图，不跳浏览器 ----
    viewImage?.let { url ->
        ImageViewerDialog(url = url, onDismiss = { viewImage = null })
    }

    // ---- 回复对话框（轻量 BBCode 工具条，方案 §6.5） ----
    if (replyDialog) {
        ReplyDialog(
            busy = state.busy,
            initialContent = replyInitial.takeIf { it.isNotEmpty() },
            onDismiss = { replyDialog = false; replyInitial = "" },
            onSend = { content ->
                vm.reply(content, onDone = { msg -> replyDialog = false; replyInitial = ""; toast(msg) }, onError = { toast(it) })
            },
        )
    }
    if (editTarget != null) {
        ReplyDialog(
            busy = state.busy,
            title = "编辑楼层",
            initialContent = editInitial,
            onDismiss = { editTarget = null },
            onSend = { content ->
                vm.editPost(editTarget!!, content, onDone = { msg -> editTarget = null; toast(msg) }, onError = { toast(it) })
            },
        )
    }
    if (buyTarget != null) {
        val wall = state.page?.posts?.asSequence()
            ?.flatMap { it.contentBlocks.asSequence() }
            ?.filterIsInstance<ContentBlock.PaidWall>()?.firstOrNull()
        AlertDialog(
            onDismissRequest = { buyTarget = null },
            title = { Text("购买确认") },
            text = {
                Text(
                    buildString {
                        append(wall?.saleText ?: "本帖为付费贴")
                        state.page?.userHeader?.credit?.let { append("（当前余额：$it）") }
                        append("\n购买后SP币不可退回，确定购买并阅读？")
                    },
                )
            },
            confirmButton = {
                Button(
                    enabled = !state.busy,
                    onClick = {
                        val url = buyTarget
                        buyTarget = null
                        url?.let { vm.buy(it, onDone = { msg -> toast(msg) }, onError = { toast(it) }) }
                    },
                ) { Text(if (state.busy) "购买中…" else "购买并阅读") }
            },
            dismissButton = { TextButton(onClick = { buyTarget = null }) { Text("取消") } },
        )
    }
    downloadTarget?.let { att ->
        AlertDialog(
            onDismissRequest = { downloadTarget = null },
            title = { Text("下载附件") },
            text = { Text("「${att.name}」\n将保存到 Download/SouthPlus/，使用系统下载器并携带登录态。") },
            confirmButton = {
                Button(onClick = {
                    downloadTarget = null
                    runCatching { AppGraph.downloader.enqueue(att) }
                        .onSuccess { toast("已开始下载") }
                        .onFailure { toast("下载失败：${it.message}") }
                }) { Text("下载") }
            },
            dismissButton = { TextButton(onClick = { downloadTarget = null }) { Text("取消") } },
        )
    }
    if (jumpPage) {
        var pageText by remember { mutableStateOf(state.page?.page?.toString() ?: "1") }
        AlertDialog(
            onDismissRequest = { jumpPage = false },
            title = { Text("跳转页码") },
            text = {
                OutlinedTextField(
                    value = pageText,
                    onValueChange = { pageText = it.filter(Char::isDigit).take(4) },
                    label = { Text("第 1 - ${state.page?.totalPages ?: 1} 页") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(enabled = pageText.toIntOrNull() in 1..(state.page?.totalPages ?: 1), onClick = {
                    val p = pageText.toIntOrNull()
                    jumpPage = false
                    if (p != null && p >= 1) vm.load(p)
                }) { Text("跳转") }
            },
            dismissButton = { TextButton(onClick = { jumpPage = false }) { Text("取消") } },
        )
    }
}

// ---------------- 楼层卡（方案 §6.4.2） ----------------

@Composable
private fun FloorHeader(
    post: Post, plainText: String,
    onUser: (String?) -> Unit, onQuoteReply: (String) -> Unit,
    onEdit: (String) -> Unit, onOnlyAuthor: () -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        // 楼层头：头像 + 作者/楼主 + 楼层/时间 + 操作菜单
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Row(
                Modifier.weight(1f).heightIn(min = 48.dp)
                    .clickable(enabled = post.author.uid != null, role = Role.Button) { onUser(post.author.uid) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                AvatarBox(post.author.name, 40.dp, avatarUrl = post.author.avatarUrl)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        Text(post.author.name, style = MaterialTheme.typography.titleSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (post.isOp) StatusBadge("楼主", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                    }
                    Text(listOfNotNull("${post.floor} 楼", post.time).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, "更多操作", Modifier.size(20.dp))
            }
                if (menuOpen) {
                    androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("引用回复") },
                            onClick = {
                                menuOpen = false
                                onQuoteReply("[quote]${post.author.name}[/quote]\n")
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("只看该作者") },
                            onClick = { menuOpen = false; onOnlyAuthor() },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("跳转用户空间") },
                            onClick = { menuOpen = false; onUser(post.author.uid) },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("复制正文") },
                            onClick = {
                                menuOpen = false
                                copyToClipboard(context, "正文", plainText)
                            },
                        )
                        post.editUrl?.let { url ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("编辑") },
                                onClick = { menuOpen = false; onEdit(url) },
                            )
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun FloorBlock(
    block: ContentBlock, prepared: Spanned?, settings: AppSettings, busy: Boolean, wifi: Boolean,
    onLink: (String) -> Unit, onViewImage: (String) -> Unit,
    onDownload: (Attachment) -> Unit, onBuy: (String) -> Unit,
) {
    val fontSize = (16 * settings.readerFontScale).sp
    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs)) {
        when (block) {
            is ContentBlock.Text -> HtmlText(
                html = block.html,
                prepared = prepared,
                onLink = onLink,
                fontSize = fontSize,
            )
            is ContentBlock.Image -> OffsiteImage(
                url = block.url,
                isSmiley = block.isSmiley,
                aspectRatio = if (block.width != null && block.height != null) block.width.toFloat() / block.height else null,
                blur = settings.blurOffsiteImages && block.isOffsite,
                loadPolicy = settings.imageWifiOnly && block.isOffsite && !wifi,
                onViewImage = onViewImage,
            )
            is ContentBlock.Quote -> QuoteView(block, onLink, fontSize, prepared)
            is ContentBlock.PaidWall -> Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(Spacing.m),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            block.saleText ?: "本帖为付费贴",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    if (busy) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("正在购买…", style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        Button(
                            onClick = { block.buyUrl?.let(onBuy) },
                            enabled = block.buyUrl != null,
                        ) { Text("购买并阅读") }
                    }
                }
            }
            is ContentBlock.AttachmentItem -> AttachmentChip(block.att, onDownload)
            is ContentBlock.Resource -> Unit // 资源卡在下方统一渲染
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OffsiteImage(
    url: String,
    isSmiley: Boolean,
    aspectRatio: Float?,
    blur: Boolean,
    loadPolicy: Boolean, // true = 需要点击加载（移动网络 + 站外图）
    onViewImage: (String) -> Unit,
) {
    var requested by rememberSaveable(url) { mutableStateOf(false) }
    var revealed by rememberSaveable(url) { mutableStateOf(false) }
    val loaded = requested || !loadPolicy
    if (isSmiley) {
        NetImage(url = url, modifier = Modifier.size(32.dp), blur = false, aspectRatio = 1f)
        return
    }
    if (!loaded) {
        Surface(
            onClick = { requested = true },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = Spacing.m),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(Spacing.s))
                Text(
                    "[站外图片，点击加载]",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    NetImage(
        url = url,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onViewImage(url) },
                onLongClickLabel = if (blur && !revealed) "查看原图" else "恢复模糊",
                onLongClick = { revealed = !revealed },
            ),
        contentDescription = if (blur && !revealed) "已模糊的帖子图片，长按查看原图" else "帖子图片，点击看大图",
        blur = blur && !revealed, aspectRatio = aspectRatio,
    )
}

@Composable
private fun QuoteView(block: ContentBlock.Quote, onLink: (String) -> Unit, fontSize: androidx.compose.ui.unit.TextUnit, prepared: Spanned?) {
    var expanded by rememberSaveable(block.html) { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
            Column(Modifier.padding(Spacing.m)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                        .clickable(role = Role.Button) { expanded = !expanded },
                ) {
                    Text(
                        block.author?.let { "引用 $it" } ?: "引用",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起引用" else "展开引用",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (expanded) {
                    HtmlText(
                        html = block.html, prepared = prepared,
                        onLink = onLink,
                        fontSize = fontSize * 0.9f,
                        modifier = Modifier.padding(top = Spacing.s),
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentChip(att: Attachment, onDownload: (Attachment) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onDownload(att) }
            .heightIn(min = 48.dp)
            .padding(vertical = Spacing.s),
    ) {
        Icon(
            Icons.Filled.AttachFile,
            contentDescription = "下载附件",
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            att.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

// ---------------- 回复编辑对话框 ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplyDialog(
    busy: Boolean,
    title: String = "回复",
    initialContent: String? = null,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var text by remember(initialContent) { mutableStateOf(initialContent ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    listOf("粗体" to "b", "斜体" to "i", "下划线" to "u", "引用" to "quote", "链接" to "url", "图片" to "img").forEach { (label, tag) ->
                        TextButton(onClick = { text += "[$tag][/$tag]" }, enabled = !busy) {
                            Text(label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    label = { Text("正文") },
                    enabled = !busy,
                    placeholder = { Text("写下你的回复…") },
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && text.isNotBlank(),
                onClick = { onSend(text) },
            ) { Text(if (busy) "发送中…" else "发送") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

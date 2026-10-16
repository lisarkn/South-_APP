package net.southplus.client.core.data

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.southplus.client.core.config.SelectorConfig
import net.southplus.client.core.database.AppDatabase
import net.southplus.client.core.database.CachedPageEntity
import net.southplus.client.core.database.HistoryEntity
import net.southplus.client.core.database.ReadMarkEntity
import net.southplus.client.core.model.FavorEntry
import net.southplus.client.core.model.ContentBlock
import net.southplus.client.core.model.IndexPage
import net.southplus.client.core.model.PmDetail
import net.southplus.client.core.model.PmListPage
import net.southplus.client.core.model.RssItem
import net.southplus.client.core.model.SearchResultPage
import net.southplus.client.core.model.ThreadItem
import net.southplus.client.core.model.ThreadListPage
import net.southplus.client.core.model.ThreadPage
import net.southplus.client.core.model.UserProfile
import net.southplus.client.core.model.UserListPage
import net.southplus.client.core.links.LinkRouter
import net.southplus.client.core.network.ChallengeException
import net.southplus.client.core.network.DiagLog
import net.southplus.client.core.network.ForumException
import net.southplus.client.core.network.HtmlGateway
import net.southplus.client.core.network.MirrorManager
import net.southplus.client.core.network.SearchGuard
import net.southplus.client.core.network.orThrow
import net.southplus.client.core.parser.FormParser
import net.southplus.client.core.parser.IndexParser
import net.southplus.client.core.parser.PmParser
import net.southplus.client.core.parser.PostFormParser
import net.southplus.client.core.parser.ReadParser
import net.southplus.client.core.parser.RssParser
import net.southplus.client.core.parser.ThreadListParser
import net.southplus.client.core.parser.UserHeaderParser
import net.southplus.client.core.parser.UserParser
import net.southplus.client.core.parser.ajaxUrlFrom
import net.southplus.client.core.parser.allBy
import net.southplus.client.core.parser.engineNotice
import net.southplus.client.core.parser.extractId
import net.southplus.client.core.parser.firstBy
import net.southplus.client.core.session.SessionManager
import net.southplus.client.core.settings.SettingsRepository
import org.jsoup.Jsoup

/**
 * 论坛仓库（方案 §4.1 Data 层）：
 * 网络（HtmlGateway）→ 解析 → Room 缓存（激进缓存+离线兜底 §3.3）→ 屏蔽/已读过滤。
 */
class ForumRepository(
    private val gateway: HtmlGateway,
    private val mirrors: MirrorManager,
    private val db: AppDatabase,
    private val session: SessionManager,
    private val settings: SettingsRepository,
    private val configProvider: () -> SelectorConfig,
) {

    private val siteBase get() = "https://${mirrors.current.host}/"

    // ---------------- 缓存基建（§3.3：列表 2 分钟 / 帖子 5 分钟；低流量 x5） ----------------

    private suspend fun ttlFor(isList: Boolean): Long {
        val low = settings.snapshot().lowTraffic
        return when {
            isList && low -> 10 * 60_000L
            isList -> 2 * 60_000L
            low -> 30 * 60_000L
            else -> 5 * 60_000L
        }
    }

    private suspend fun <T> fetchWithCache(
        key: String,
        path: String,
        isList: Boolean,
        force: Boolean,
        authCheck: Boolean = true,
        parse: (html: String, baseUri: String) -> T,
    ): Pair<T, String> = withContext(Dispatchers.IO) {
        val cfg = configProvider()
        val cached = db.cacheDao().get(key)
        val now = System.currentTimeMillis()
        val fresh = cached != null &&
            cached.schemaVersion == cfg.schemaVersion &&
            now - cached.fetchedAt < ttlFor(isList)
        if (!force && cached != null && fresh) {
            return@withContext Pair(parse(cached.html, cached.url), cached.url)
        }

        try {
            val outcome = gateway.fetch(path, authCheck = authCheck)
            val html = outcome.orThrow()
            val finalUrl = (outcome as? HtmlGateway.Outcome.Ok)?.finalUrl ?: (siteBase + path)
            db.cacheDao().put(
                CachedPageEntity(key, finalUrl, html, now, cfg.schemaVersion, html.toByteArray(Charsets.UTF_8).size),
            )
            trimCacheIfNeeded()
            Pair(parse(html, finalUrl), finalUrl)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ChallengeException) {
            throw e
        } catch (e: Exception) {
            // 网络失败 → 离线兜底已缓存内容（方案 §1.3 辅助：离线缓存）
            DiagLog.log("CACHE", "$path 网络失败，使用缓存兜底: ${e.message}")
            if (cached != null) Pair(parse(cached.html, cached.url), cached.url) else throw e
        }
    }

    private val cacheWrites = java.util.concurrent.atomic.AtomicInteger()

    private suspend fun trimCacheIfNeeded() {
        // First write after process start, then every 16 writes. No stale in-memory byte total
        // when Settings clears the cache or selector updates invalidate it.
        if (cacheWrites.getAndIncrement() % 16 == 0) {
            db.cacheDao().trimToBytes(500L * 1024 * 1024)
        }
    }

    // ---------------- 浏览 ----------------

    suspend fun index(force: Boolean = false): IndexPage {
        // 首页游客可见，登录态交给页头解析，避免整页误判为「需要登录」
        val (result, _) = fetchWithCache("index", "index.php", isList = true, force = force, authCheck = false) { html, base ->
            IndexParser.parse(html, base, configProvider())
        }
        DiagLog.log(
            "PARSE",
            "index → sections=${result.sections.size} " +
                "boards=${result.sections.sumOf { it.boards.size }} replies=${result.recentReplies.size} " +
                "loggedIn=${result.userHeader?.loggedIn}",
        )
        session.updateFromPage(result.userHeader)
        return result
    }

    /**
     * 全站最新帖（rss.php，游客可见，与网页版页头「最新帖子」同源）。
     * 搜索/统计页对游客与低用户组都关闭，RSS 是唯一无需权限的站级 feed。
     * 90s 内存缓存；失败回退旧缓存或空列表，不打断首页。
     */
    private var rssCache: Pair<Long, List<RssItem>>? = null

    suspend fun latestThreads(force: Boolean = false): List<RssItem> {
        rssCache?.let { (at, items) ->
            val fresh = System.nanoTime() - at < 90_000_000_000L
            if (!force && fresh && items.isNotEmpty()) return items
        }
        val xml = when (val outcome = gateway.fetch("rss.php", authCheck = false)) {
            is HtmlGateway.Outcome.Ok -> outcome.body
            is HtmlGateway.Outcome.Challenged -> {
                DiagLog.log("NET", "rss.php 需要网页验证，最新帖 feed 跳过")
                return rssCache?.second ?: emptyList()
            }
            is HtmlGateway.Outcome.Error -> {
                DiagLog.log("NET", "rss.php 拉取失败：${outcome.message}")
                return rssCache?.second ?: emptyList()
            }
        }
        return RssParser.parse(xml).also { items ->
            DiagLog.log("PARSE", "rss → items=${items.size}")
            if (items.isNotEmpty()) rssCache = System.nanoTime() to items
        }
    }

    suspend fun board(fid: String, page: Int = 1, force: Boolean = false): ThreadListPage =
        boardInternal(fid, page, force, allowWallRewrite = true)

    private suspend fun boardInternal(
        fid: String,
        page: Int,
        force: Boolean,
        allowWallRewrite: Boolean,
    ): ThreadListPage {
        val path = if (page <= 1) "thread.php?fid-$fid.html" else "thread.php?fid-$fid-page-$page.html"
        val (result, finalUrl) = fetchWithCache("thread:$fid:$page", path, isList = true, force = force) { html, base ->
            ThreadListParser.parse(html, base, fid, page, configProvider())
        }
        // 「图墙模式」版块是母版块别名：thread.php?fid-103 → 302 thread_new.php?fid-44。
        // thread_new 页（2026-09 站点改版）只渲染置顶帖，正常帖列表走页面内 AJAX，
        // 且 fid-N-page-M 的 302 会丢页码跳回第 1 页 → 加载更多只会拿到重复内容。
        // 落地 URL 自带母版块 fid，改抓经典 thread.php 列表（内容与图墙本就重复）。
        val landedFid = if (allowWallRewrite) wallRedirectTargetFid(finalUrl) else null
        if (landedFid != null && landedFid != fid) {
            DiagLog.log("PARSE", "图墙 fid=$fid → 母版块 fid=$landedFid 经典列表（thread_new 仅置顶/翻页丢页码）")
            return boardInternal(landedFid, page, force, allowWallRewrite = false)
        }
        DiagLog.log("PARSE", "thread fid=$fid p$page → rows=${result.threads.size} 总页=${result.totalPages}")
        return applyListFilters(result)
    }

    suspend fun thread(tid: String, page: Int = 1, force: Boolean = false): ThreadPage {
        val path = if (page <= 1) "read.php?tid-$tid.html" else "read.php?tid-$tid-page-$page.html"
        val (result, _) = fetchWithCache("read:$tid:$page", path, isList = false, force = force) { html, base ->
            markOffsite(ReadParser.parse(html, base, tid, page, configProvider()))
        }
        DiagLog.log("PARSE", "read tid=$tid p$page → floors=${result.posts.size} 总页=${result.totalPages}")
        session.updateFromPage(result.userHeader)
        recordVisit(result)
        return result
    }

    // 搜索翻页模板（POST 首页响应分页里的 sid 链接；key = keyword|fid）
    private val searchPageTemplates = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val SEARCH_SID_HREF =
        Regex("""search\.php\?step-2-keyword-[^"\s]*-sid-\d+-[^"\s]*-page-\d+\.html""")

    suspend fun search(
        keyword: String,
        fid: String? = null,
        page: Int = 1,
        schTime: String = "all",
        orderway: String = "lastpost",
        asc: String = "DESC",
    ): SearchResultPage = withContext(Dispatchers.IO) {
        // 站点搜索只在 step=2 表单 POST 时真正执行查询：GET search.php?keyword=X&step=2
        // 一律返回「没有查找匹配的内容」（2026-10 真机校准）。第 1 页走站点搜索表单原文
        // POST；后续页用响应分页里 search.php?step-2-keyword-X-sid-N-seekfid-Y-page-M.html
        // 模板换页码 GET（sid 由服务端缓存本次查询，过期后重新 POST）。
        val key = "$keyword|${fid ?: "all"}|$schTime|$orderway|$asc"
        val html = if (page == 1) {
            postSearchForm(keyword, fid, key, schTime, orderway, asc)
        } else {
            var template = searchPageTemplates[key]
            if (template == null) postSearchForm(keyword, fid, key, schTime, orderway, asc)
            template = searchPageTemplates[key]
                ?: throw ForumException("搜索会话已失效，请重新搜索")
            gateway.fetch(template.replace("{PAGE}", page.toString()), authCheck = false).orThrow()
        }
        val base = siteBase + "search.php"
        val doc = Jsoup.parse(html, base)
        SearchGuard.ensureSearchable(html, base, UserHeaderParser.parse(doc, configProvider()).loggedIn)
        val result = net.southplus.client.core.parser.SearchParser.parse(html, base, keyword, page, configProvider())
        val rules = db.blockDao().allNow()
        val filtered = result.copy(results = filterThreads(result.results, rules))
        // 0 条结果但页面是引擎「提示信息」页（搜索间隔太短/关键词过短等）→ 透传站点原文
        if (filtered.results.isEmpty()) {
            engineNotice(doc)?.let {
                DiagLog.log("PARSE", "search '$keyword' p$page → 0 条，引擎提示: $it")
                throw ForumException(it)
            }
        }
        return@withContext filtered
    }

    /** 站点搜索表单原文 POST（search.php? sF 表单，2026-10 真机校准），并记录翻页模板。 */
    private suspend fun postSearchForm(
        keyword: String,
        fid: String?,
        key: String,
        schTime: String = "all",
        orderway: String = "lastpost",
        asc: String = "DESC",
    ): String {
        val form = linkedMapOf<String, String?>(
            "step" to "2",
            "keyword" to keyword,
            "method" to "OR",
            "sch_area" to "0",
            // 高级搜索值域为 phpwind 8 原始取值（sch_time 秒数窗口，orderway 排序字段）
            "sch_time" to schTime,
            "orderway" to orderway,
            "asc" to asc,
            "submit" to "提 交",
        )
        if (!fid.isNullOrEmpty()) form["f_fid"] = fid
        val html = gateway.postForm("search.php", form, authCheck = false).orThrow()
        SEARCH_SID_HREF.find(html)?.let { m ->
            searchPageTemplates[key] = m.value.replace(Regex("page-\\d+"), "page-{PAGE}")
        }
        return html
    }

    /** 「我的」用户中心列表页（我的主题/回复/好友/商品、论坛任务插件页通用）。 */
    suspend fun userPanel(path: String, force: Boolean = false): UserListPage {
        val key = "upanel:${path.substringBefore('#')}"
        val result = try {
            val (parsed, _) = fetchWithCache(key, path, isList = true, force = force) { html, base ->
                val page = net.southplus.client.core.parser.UserPanelParser.parse(html, base, configProvider())
                ensureOwnPanel(Jsoup.parse(html, base), configProvider(), base)
                page
            }
            parsed
        } catch (e: ChallengeException) {
            // 论坛任务等插件页对部分用户组同样返回「需要登录」错误页：
            // 会话有效时给出如实的权限提示，避免误导用户反复登录。
            if (e.challenge == net.southplus.client.core.model.Challenge.NOT_LOGGED_IN &&
                session.state.value is net.southplus.client.core.model.SessionState.LoggedIn
            ) {
                throw ForumException("站点返回该功能需要登录或权限不足。您已登录，可能是该功能对当前用户组不开放。")
            }
            throw e
        }
        DiagLog.log("PARSE", "upanel $path → items=${result.items.size}")
        result.userHeader?.let { session.updateFromPage(it) }
        return result
    }

    /**
     * 论坛任务 AJAX 动作（2026-10 校准，hack/tasks 插件）：
     * 申请 = actions=job，领取奖励 = actions=job2；响应 `<?xml…><ajax><![CDATA[code\t文案]]></ajax>`。
     * 成功返回站点文案（调用方随后强刷任务列表），失败抛出站点原文。
     */
    suspend fun runTaskAction(actionUrl: String): String = withContext(Dispatchers.IO) {
        val html = gateway.fetch(
            actionUrl,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
        ).orThrow()
        val body = net.southplus.client.core.parser.ajaxCdata(html)
            ?: throw ForumException("任务响应无法识别，请刷新任务列表后重试")
        val parts = body.split('\t')
        val ok = parts[0].equals("success", ignoreCase = true)
        val message = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (ok) "操作成功" else "操作失败"
        DiagLog.log("TASK", "$actionUrl → ${parts[0]} $message")
        if (ok) message else throw ForumException(message)
    }

    suspend fun userPage(uid: String): UserProfile = withContext(Dispatchers.IO) {
        val html = gateway.fetch("u.php?action-show-uid-$uid.html").orThrow()
        return@withContext UserParser.parse(html, siteBase + "u.php", uid, configProvider())
    }

    // ---------------- 互动（方案 §6.5） ----------------

    /** 回复：先 GET 回复表单（隐藏域防伪 token 原样镜像），再 POST（§6.5）。 */
    suspend fun reply(replyUrl: String, title: String?, content: String): String = withContext(Dispatchers.IO) {
        val cfg = configProvider()
        val formHtml = gateway.fetch(replyUrl).orThrow()
        val snapshot = PostFormParser.parse(formHtml, replyUrl, cfg)
            ?: throw ForumException("未找到回复表单，可能需要登录或权限不足")
        val fields = snapshot.overriding(
            listOfNotNull(
                (snapshot.contentField ?: "atc_content") to content,
                title?.takeIf { it.isNotBlank() }?.let { (snapshot.titleField ?: "atc_title") to it },
            ),
        )
        val html = gateway.postForm(snapshot.action, fields).orThrow()
        return@withContext extractActionFeedback(html)
    }

    suspend fun newTopic(fid: String, title: String, content: String): String = withContext(Dispatchers.IO) {
        val cfg = configProvider()
        val formHtml = gateway.fetch("post.php?fid=$fid&action=new").orThrow()
        val snapshot = PostFormParser.parse(formHtml, siteBase, cfg)
            ?: throw ForumException("未找到发帖表单，可能需要登录或权限不足")
        val fields = snapshot.overriding(
            listOf(
                (snapshot.titleField ?: "atc_title") to title,
                (snapshot.contentField ?: "atc_content") to content,
            ),
        )
        val html = gateway.postForm(snapshot.action, fields).orThrow()
        return@withContext extractActionFeedback(html)
    }

    suspend fun editPost(editUrl: String, title: String?, content: String): String = withContext(Dispatchers.IO) {
        val cfg = configProvider()
        val formHtml = gateway.fetch(editUrl).orThrow()
        val snapshot = PostFormParser.parse(formHtml, editUrl, cfg)
            ?: throw ForumException("未找到编辑表单")
        val fields = snapshot.overriding(
            listOfNotNull(
                (snapshot.contentField ?: "atc_content") to content,
                title?.takeIf { it.isNotBlank() }?.let { (snapshot.titleField ?: "atc_title") to it },
            ),
        )
        val html = gateway.postForm(snapshot.action, fields).orThrow()
        return@withContext extractActionFeedback(html)
    }

    /** 编辑回填：从修改表单里取原正文（方案 §6.5 编辑）。 */
    suspend fun fetchEditContent(editUrl: String): String? = withContext(Dispatchers.IO) {
        val html = gateway.fetch(editUrl).orThrow()
        val snapshot = PostFormParser.parse(html, editUrl, configProvider()) ?: return@withContext null
        return@withContext snapshot.contentField?.let { snapshot.fields[it] }
    }

    /** 付费贴购买（方案 §6.6）：GET 购买确认页 → 镜像其表单提交 → 返回反馈。 */
    suspend fun buy(buyUrl: String): String = withContext(Dispatchers.IO) {
        val html = gateway.fetch(buyUrl).orThrow()
        val doc = Jsoup.parse(html, buyUrl)
        // 2026-09 真机校准：确认页表单 action 含 buytopic；兜底须排除页头搜索/登录表单
        val formEl = FormParser.findConfirmForm(doc)
        return@withContext if (formEl != null) {
            val snap = FormParser.snapshot(formEl, buyUrl)
            gateway.postForm(snap.action, snap.fields).orThrow().let(::extractActionFeedback)
        } else {
            extractActionFeedback(html)
        }
    }

    // ---------------- 收藏 / 短消息（方案 §6.5） ----------------

    /**
     * 收藏动作（2026-09 真机校准）：阅读页收藏按钮请求的是
     * pw_ajax.php?action=favor&tid=X，返回 AJAX 片段——可能是
     * 确认对话框（选择收藏目录的表单），也可能直接是成功/错误提示。
     * 有确认表单时镜像提交（同付费贴 buy 流程），再解析反馈文案。
     */
    suspend fun addFavor(favorUrl: String): String = withContext(Dispatchers.IO) {
        val html = gateway.fetch(favorUrl).orThrow()
        val doc = Jsoup.parse(html, favorUrl)
        val formEl = FormParser.findConfirmForm(doc, actionKeyword = "favor")
        return@withContext if (formEl != null) {
            val snap = FormParser.snapshot(formEl, favorUrl)
            gateway.postForm(snap.action, snap.fields).orThrow().let(::extractActionFeedback)
        } else {
            extractActionFeedback(html)
        }
    }

    /** 收藏列表（2026-09 校准：favor.php 在本引擎已不存在（404），真实地址是 u.php?action-favor）。 */
    suspend fun favorList(force: Boolean = false): List<FavorEntry> {
        val (page, _) = fetchWithCache("favor", FAVOR_PATH, isList = true, force = force) { html, base ->
            parseFavor(html, base, configProvider())
        }
        DiagLog.log("PARSE", "favor → entries=${page.size}")
        return page
    }

    private fun parseFavor(html: String, base: String, cfg: SelectorConfig): List<FavorEntry> {
        val doc = Jsoup.parse(html, base)
        ensureOwnPanel(doc, cfg, base)
        return net.southplus.client.core.parser.UserPanelParser.parseFavorRows(html, base, cfg)
    }

    /**
     * u.php 引擎怪癖（2026-09 抓包）：游客访问 u.php?action-favor 等页不报登录错误，
     * 而是渲染某个「默认用户」的面板（页头无登录表单，ChallengeDetector 拦不住）。
     * 这里按页头登录态显式拦截，避免游客把别人的空面板当成自己的数据。
     */
    private fun ensureOwnPanel(doc: org.jsoup.nodes.Document, cfg: SelectorConfig, url: String) {
        val header = net.southplus.client.core.parser.UserHeaderParser.parse(doc, cfg)
        if (!header.loggedIn && session.state.value !is net.southplus.client.core.model.SessionState.LoggedIn) {
            throw ChallengeException(net.southplus.client.core.model.Challenge.NOT_LOGGED_IN, url)
        }
    }

    suspend fun pmList(page: Int = 1): PmListPage = withContext(Dispatchers.IO) {
        val path = if (page <= 1) "message.php" else "message.php?page=$page"
        val html = gateway.fetch(path).orThrow()
        return@withContext PmParser.parseList(html, siteBase + path, page, configProvider())
    }

    suspend fun pmDetail(mid: String): PmDetail = withContext(Dispatchers.IO) {
        val html = gateway.fetch("message.php?action=read&mid=$mid").orThrow()
        return@withContext PmParser.parseDetail(html, siteBase, mid, configProvider())
    }

    suspend fun pmSend(to: String, title: String, content: String): String = withContext(Dispatchers.IO) {
        val cfg = configProvider()
        val writeUrl = siteBase + "message.php?action=write"
        val html = gateway.fetch("message.php?action=write").orThrow()
        val doc = Jsoup.parse(html, writeUrl)
        val formEl = FormParser.findForm(
            doc, cfg.candidates("pm_page.form"),
            fallbackFilter = { it.select("textarea").isNotEmpty() },
        ) ?: throw ForumException("未找到私信表单")
        val snap = FormParser.snapshot(
            formEl, writeUrl,
            FormParser.FieldHints(
                recipient = cfg.candidates("pm_page.recipient_field"),
                title = cfg.candidates("pm_page.title_field"),
                content = cfg.candidates("pm_page.content_field"),
            ),
        )
        val fields = snap.overriding(
            listOf(
                (snap.recipientField ?: "touser") to to,
                (snap.titleField ?: "atc_title") to title,
                (snap.contentField ?: "atc_content") to content,
            ),
        )
        return@withContext gateway.postForm(snap.action, fields).orThrow().let(::extractActionFeedback)
    }

    // ---------------- 本地记录（§6.9） ----------------

    suspend fun recordVisit(page: ThreadPage) {
        runCatching {
            val now = System.currentTimeMillis()
            db.readMarkDao().set(ReadMarkEntity(page.tid, page.page, now))
            db.historyDao().record(
                HistoryEntity(tid = page.tid, title = page.title, url = siteBase + "read.php?tid-${page.tid}.html", visitedAt = now),
            )
        }
    }

    suspend fun invalidateThread(tid: String) = db.cacheDao().invalidatePrefix("read:$tid:%")

    suspend fun readMarkOf(tid: String): ReadMarkEntity? = db.readMarkDao().get(tid)

    suspend fun applyListFilters(p: ThreadListPage): ThreadListPage = withContext(Dispatchers.IO) {
        val rules = db.blockDao().allNow()
        val readTids = db.readMarkDao().readTids(p.threads.map { it.tid }).toSet()
        return@withContext p.copy(threads = filterThreads(p.threads, rules).map { it.copy(isRead = it.tid in readTids) })
    }

    private suspend fun filterThreads(
        threads: List<ThreadItem>,
        rules: List<net.southplus.client.core.database.BlockRuleEntity>,
    ): List<ThreadItem> = filterByRules(threads, rules)

    private fun filterByRules(
        threads: List<ThreadItem>,
        rules: List<net.southplus.client.core.database.BlockRuleEntity>,
    ): List<ThreadItem> {
        if (rules.isEmpty()) return threads
        val users = rules.filter { it.type == "USER" }.map { it.value.lowercase() }.toSet()
        val keywords = rules.filter { it.type == "KEYWORD" }.map { it.value.lowercase() }
        return threads.filter { t ->
            val author = t.author?.name?.lowercase()
            (author == null || author !in users) && keywords.none { keyword -> t.title.contains(keyword, ignoreCase = true) }
        }
    }

    fun blockThreadFilter(threads: List<ThreadItem>, rules: List<net.southplus.client.core.database.BlockRuleEntity>): List<ThreadItem> =
        filterByRules(threads, rules)

    /** 站外图标记（方案 §6.4.6：站外图独立限速/点击加载由 UI 按此字段区分）。 */
    private fun markOffsite(page: ThreadPage): ThreadPage {
        val mirrorHosts = mirrors.hosts
        fun offsite(url: String): Boolean {
            val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
            return mirrorHosts.none { host == it || host.endsWith(".$it") }
        }
        return page.copy(
            posts = page.posts.map { post ->
                post.copy(
                    contentBlocks = post.contentBlocks.map { b ->
                        if (b is ContentBlock.Image) b.copy(isOffsite = offsite(b.url)) else b
                    },
                )
            },
        )
    }

    // ---------------- 杂项 ----------------

    suspend fun captchaBytes(url: String): ByteArray? = gateway.fetchBytes(url)

    /** 操作反馈提取（成功文案 / 错误信息）。 */
    private fun extractActionFeedback(html: String): String {
        val text = Jsoup.parse(html).text()
        ACTION_FEEDBACK_PATTERN.find(text)?.let {
            return it.groupValues[1].trim()
        }
        return when {
            text.contains("成功") || text.contains("恭喜") || text.contains("谢谢") -> "操作成功"
            text.contains("您尚未登录") || text.contains("请先登录") -> "登录已失效，请重新登录"
            text.contains("余额不足") || text.contains("积分不足") -> "SP币余额不足，购买未完成"
            else -> "已提交（请刷新确认结果）"
        }
    }
}
/** thread_new.php 落地页 URL 里的真实版块 fid（fid-36.html 与 fid=36 两种风格），非图墙落地返回 null。 */
internal fun wallRedirectTargetFid(finalUrl: String): String? {
    if (!finalUrl.contains("thread_new.php?")) return null
    return LinkRouter.fidOf(finalUrl)
}

private val ACTION_FEEDBACK_PATTERN = Regex("(?:错误信息|提示信息|出错信息)\\s*[:：]?\\s*(.{2,80})")

/** 收藏列表真实地址（2026-09 校准：favor.php 已 404）。 */
private const val FAVOR_PATH = "u.php?action-favor.html"

package net.southplus.client.core.parser

import net.southplus.client.core.config.SelectorConfig
import net.southplus.client.core.model.UserBrief
import net.southplus.client.core.model.UserListItem
import net.southplus.client.core.model.UserListPage
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 「我的」用户中心列表页解析：u.php?action-topic/post/favor/friend/trade 与
 * 论坛任务插件页（plugin.php?H_name-tasks）共用一套行解析。
 *
 * 2026-09 真机抓包校准（blue-plus 镜像，u.php 用户中心改版结构）：
 *  - 内容表格 class 为 u-table，数据行无 tr3 类，首列表头行（标题/作者/目录…）无链接；
 *  - 我的回复行主链接是 job.php?action-topost-tid-X-pid-Y.html（跳回楼层），
 *    同行带版块 a[thread.php] 与日期 span.gray；我的主题行是 read.php?tid-X；
 *  - 好友行主链接是 u.php?action-show-uid-X.html，头像 img 在行内；
 *  - 页内子选项卡（全部主题/精华/收藏的版块/待验证好友…）同为 u-table 行内链接，
 *    以 href 特征（-type- / -o- / skinco / u.php?action-）排除，避免混进列表。
 */
object UserPanelParser {

    fun parse(html: String, pageUrl: String, cfg: SelectorConfig): UserListPage {
        val doc = Jsoup.parse(html, pageUrl)
        val userHeader = UserHeaderParser.parse(doc, cfg)

        val items = LinkedHashMap<String, UserListItem>()
        for (row in doc.allBy(cfg.candidates("user_panel.rows"))) {
            val item = parseRow(row, cfg) ?: continue
            val key = item.href ?: "${item.kind}:${item.title}"
            if (key !in items) items[key] = item
        }
        // 选择器整体不命中时兜底：全页扫描主题/用户主链接（方案 §6.3 通用降级）
        if (items.isEmpty()) fallbackScan(doc, cfg)?.forEach { item ->
            val key = item.href ?: item.title
            if (key !in items) items[key] = item
        }
        // 论坛任务卡片（hack/tasks 插件页）：图标行+描述行成对、申请按钮是 AJAX，
        // 通用行解析抓不到 —— 单独提取后追加。
        parseTaskCards(doc).forEach { item ->
            val key = item.href ?: "task:${item.title}"
            if (key !in items) items[key] = item
        }

        DiagParse.log("upanel $pageUrl → items=${items.size} next=${nextUrl(doc, pageUrl, cfg) != null}")
        return UserListPage(
            items = items.values.toList(),
            nextUrl = nextUrl(doc, pageUrl, cfg),
            userHeader = userHeader,
            notice = noticeOf(doc),
        )
    }

    private fun parseRow(row: Element, cfg: SelectorConfig): UserListItem? {
        // 主题行（我的主题/回复/收藏的帖子）：read.php?tid / job.php?action-topost
        val threadEl = row.firstBy(cfg.candidates("user_panel.thread_link"))
        if (threadEl != null) {
            val href = threadEl.absHref()
            val tid = extractId(threadEl, cfg.spec("user_panel.tid"))
                ?: TID_FALLBACK.find(href)?.groupValues?.getOrNull(1)
            if (tid == null) return null
            val authorEl = row.firstBy(cfg.candidates("user_panel.user_link"))
            return UserListItem(
                title = threadEl.text().trim(),
                href = href,
                kind = UserListItem.Kind.THREAD,
                tid = tid,
                pid = TOPOST_PID_PATTERN.find(href)?.groupValues?.getOrNull(1),
                meta = rowMeta(row, threadEl),
                author = authorEl?.let { UserBrief(uid = extractId(it, cfg.spec("user_panel.uid")), name = it.text().trim()) },
            )
        }
        // 好友行：u.php?action-show-uid-X（头像/用户名/加入时间）
        val userEl = row.firstBy(cfg.candidates("user_panel.user_link"))
        if (userEl != null) {
            val name = userEl.text().trim()
            if (name.isEmpty()) return null
            return UserListItem(
                title = name,
                href = userEl.absHref(),
                kind = UserListItem.Kind.USER,
                uid = extractId(userEl, cfg.spec("user_panel.uid")),
                avatarUrl = row.firstBy(cfg.candidates("user_panel.avatar"))?.absSrc()?.takeIf { it.isNotEmpty() },
                meta = rowMeta(row, userEl),
            )
        }
        // 通用行（收藏的版块 → thread.php?fid；论坛任务 → job.php/plugin.php 动作链接）
        for (a in row.select("a[href]")) {
            val href = a.absHref()
            if (!isContentHref(href)) continue
            // 站外链接（插件版权声明等）不算内容行
            val abs = a.absUrl("href")
            val host = abs.substringAfter("://").substringBefore('/').substringBefore(':')
            if (abs.startsWith("http") && host.isNotEmpty() &&
                host != row.ownerDocument().location().substringAfter("://").substringBefore('/').substringBefore(':')
            ) continue
            val title = a.text().trim()
            if (title.isEmpty()) continue
            return UserListItem(
                title = title,
                href = href,
                kind = UserListItem.Kind.GENERIC,
                fid = LinkRouterSupport.fidOf(href),
                meta = rowMeta(row, a),
            )
        }
        return null
    }

    /** 行内次要信息（版块名、日期），排除主链接自身的文本。 */
    private fun rowMeta(row: Element, primary: Element): String? {
        val board = row.select("a[href*='thread.php']").firstOrNull()?.text()?.trim()
        val date = row.select("span.gray").lastOrNull()?.text()?.trim()
        return listOfNotNull(
            board?.takeIf { it.isNotEmpty() && it != primary.text().trim() },
            date?.takeIf { it.isNotEmpty() },
        ).joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    /** 通用行链接白名单：排除选项卡/导航/皮肤切换等一切非内容链接。 */
    private fun isContentHref(href: String): Boolean {
        if (href.isEmpty()) return false
        val lower = href.lowercase()
        if (!lower.contains("php")) return false
        return GENERIC_EXCLUDE.none { lower.contains(it) }
    }

    /**
     * 论坛任务卡片（2026-09 真机校准，hack/tasks 插件页）：
     * `<tr class=f_one><td><img src=hack/tasks/image/xx.png></td><td><b>周常</b>(人气…)<font>任务时效…</font></td>
     *  <td>奖励 : SP币 7 G</td><td>状态或 startjob 申请按钮</td></tr>
     *  <tr class=f_one><td>任务描述</td></tr>`
     * 申请/领奖按钮是 AJAX：新任务页 `startjob('15')`（img sh.png「按这申请此任务」→ actions=job），
     * 进行中页同名函数重定义为领奖（img god.png「领取此奖励」→ actions=job2）；
     * 2026-10 起客户端代为执行：plugin.php?H_name=tasks&action=ajax&actions=job[2]&cid=X。
     */
    private fun parseTaskCards(doc: Document): List<UserListItem> {
        val out = mutableListOf<UserListItem>()
        for (tr in doc.select("tr.f_one:has(td img[src*='hack/tasks/image'])")) {
            val tds = tr.select("td")
            if (tds.size < 3) continue
            val name = tds.getOrNull(1)?.select("b")?.firstOrNull()?.text()?.trim() ?: continue
            if (name.isEmpty()) continue
            val reward = tds.getOrNull(2)?.text()?.trim()
            val statusCell = tds.getOrNull(3)
            val action = statusCell?.select("a[onclick*='startjob']")?.firstOrNull()
            val cid = action?.attr("onclick")?.let { STARTJOB_CID.find(it)?.groupValues?.getOrNull(1) }
            val claim = action != null && (
                action.attr("title").contains("领取") ||
                    action.select("img").attr("src").contains("god.")
                )
            val actionUrl = cid?.let {
                "plugin.php?H_name=tasks&action=ajax&actions=${if (claim) "job2" else "job"}&cid=$it"
            }
            val actionLabel = when {
                cid == null -> null
                claim -> "领取奖励"
                else -> "申请任务"
            }
            val status = statusCell?.ownText()?.trim()
            val desc = tr.nextElementSibling()
                ?.takeIf { it.tagName() == "tr" && it.select("img[src*='hack/tasks']").isEmpty() }
                ?.text()?.trim()
            val meta = listOfNotNull(
                reward?.takeIf { it.isNotEmpty() },
                when {
                    action != null && !claim -> "可申请"
                    !status.isNullOrEmpty() -> status
                    else -> null
                },
                desc?.takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            out.add(
                UserListItem(
                    title = name,
                    kind = UserListItem.Kind.GENERIC,
                    meta = meta.takeIf { it.isNotEmpty() },
                    actionUrl = actionUrl,
                    actionLabel = actionLabel,
                )
            )
        }
        return out
    }

    private val GENERIC_EXCLUDE = listOf(
        "javascript:", "skinco", "-type-", "-o-",
        "u.php?action-", "u.php?uid-", "profile.php", "userpay.php",
        "login.php", "register.php", "search.php", "memo.php", "sendpwd",
        "message.php", "job.php?action-erasecookie", "action=erasecookie",
    )

    /** 兜底：整页扫描主题/用户主链接（子选项卡特征同样排除）。 */
    private fun fallbackScan(doc: Document, cfg: SelectorConfig): List<UserListItem>? {
        val found = mutableListOf<UserListItem>()
        doc.select("a[href*='read.php?tid'], a[href*='action-topost'], a[href*='action=topost']").forEach { a ->
            val href = a.absHref()
            val tid = TID_FALLBACK.find(href)?.groupValues?.getOrNull(1) ?: return@forEach
            val title = a.text().trim()
            if (title.isEmpty()) return@forEach
            found.add(UserListItem(title = title, href = href, kind = UserListItem.Kind.THREAD, tid = tid))
        }
        return found.takeIf { it.isNotEmpty() }
    }

    /** 下一页链接：.pages 区里文本为「下一页/下页/›/»」的锚。 */
    private fun nextUrl(doc: Document, pageUrl: String, cfg: SelectorConfig): String? {
        for (a in doc.allBy(cfg.candidates("user_panel.pagination_links"))) {
            val text = a.text().trim()
            val href = a.absHref()
            if (href.isEmpty()) continue
            if (NEXT_TEXT.any { text.contains(it) }) return href
        }
        return null
    }

    /**
     * 收藏行解析（u.php?action-favor）：与 user_panel 同一套行选择器，
     * 但产出 FavorEntry（tid + 标题 + 删除链接）供收藏夹使用。
     */
    fun parseFavorRows(html: String, pageUrl: String, cfg: SelectorConfig): List<net.southplus.client.core.model.FavorEntry> {
        val doc = Jsoup.parse(html, pageUrl)
        return doc.allBy(cfg.candidates("favor_page.rows")).mapNotNull { row ->
            val subjectEl = row.firstBy(cfg.candidates("favor_page.subject")) ?: return@mapNotNull null
            val tid = extractId(subjectEl, cfg.spec("favor_page.tid"))
                ?: TID_FALLBACK.find(subjectEl.absHref())?.groupValues?.getOrNull(1)
                ?: return@mapNotNull null
            net.southplus.client.core.model.FavorEntry(
                tid = tid,
                title = subjectEl.text().trim(),
                removeUrl = row.firstBy(cfg.candidates("favor_page.remove_link"))
                    ?.ajaxUrlFrom(pageUrl)?.takeIf { it.isNotEmpty() },
            )
        }
    }

    private val NEXT_TEXT = listOf("下一页", "下页", "›", "»")
    private val STARTJOB_CID = Regex("startjob\\(\\s*'?\"?(\\d+)")

    /** 引擎提示信息页文案，见 ParserSupport.engineNotice。 */
    private fun noticeOf(doc: Document): String? = engineNotice(doc)

    private val TID_FALLBACK = Regex("tid-(\\d+)|tid=(\\d+)")
    private val TOPOST_PID_PATTERN = Regex("topost-tid-\\d+-pid-(\\d+)")
}

/** LinkRouter.fidOf 的解析层镜像（避免 parser → links 反向依赖）。 */
private object LinkRouterSupport {
    fun fidOf(url: String): String? =
        Regex("fid-(\\d+)|fid=(\\d+)").find(url)?.let { m ->
            m.groupValues[1].ifEmpty { m.groupValues[2] }
        }
}

private object DiagParse {
    fun log(message: String) {
        net.southplus.client.core.network.DiagLog.log("PARSE", message)
    }
}

package net.southplus.client.core.parser

import net.southplus.client.core.config.SelectorConfig
import net.southplus.client.core.model.Board
import net.southplus.client.core.model.IndexPage
import net.southplus.client.core.model.RecentReply
import net.southplus.client.core.model.SearchResultPage
import net.southplus.client.core.model.Section
import net.southplus.client.core.model.ThreadItem
import net.southplus.client.core.model.ThreadListPage
import net.southplus.client.core.model.UserBrief
import net.southplus.client.core.model.UserHeader
import net.southplus.client.core.network.DiagLog
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 页头登录态解析（方案 §2.4-4 会话失效特征 / 附录 A #8）。 */
object UserHeaderParser {

    fun parse(doc: Document, cfg: SelectorConfig): UserHeader {
        val userLink = doc.firstBy(cfg.candidates("user_header.user_link"))
        val uid = extractId(userLink, cfg.spec("user_header.uid"))
        val loggedIn = userLink != null && !uid.isNullOrEmpty()

        val avatar = doc.firstBy(cfg.candidates("user_header.avatar"))
            ?.absSrc()?.takeIf { it.isNotEmpty() }

        val unread = doc.firstBy(cfg.candidates("user_header.unread_pm"))?.let { el ->
            val text = el.text()
            cfg.string("user_header.unread_pm_regex")
                ?.let { re -> runCatching { Regex(re) }.getOrNull() }
                ?.find(text)?.groupValues
                // 跳过 groupValues[0]（全匹配），取第一个非空捕获组
                ?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
        } ?: 0

        return UserHeader(
            // 2026-09 校准：本引擎登录页头没有用户名链接，user_link 命中的是
            // 菜单里的「查看个人资料」锚（登录后带 uid，游客为空 uid）——
            // 它只用于判定登录态与取 uid，文案不能当用户名展示。
            loggedIn = loggedIn,
            userName = userLink?.text()?.trim()
                ?.takeIf { it.isNotEmpty() && it != "查看个人资料" },
            uid = uid,
            avatarUrl = avatar,
            unreadPm = unread ?: 0,
            credit = parseCredit(doc, cfg),
        )
    }

    /**
     * SP币余额（方案 §6.6，2026-09 真机校准）：真实位置是 #user_info #showface
     * 弹出面板里的「SP币: <span class=s3 f10>428 G</span>」，站点无 job=pay 链接。
     * 限定 #user_info 作用域，避免取到楼层侧栏其他作者的 SP币。
     */
    private fun parseCredit(doc: Document, cfg: SelectorConfig): String? {
        val block = doc.firstBy(cfg.candidates("user_header.credit_block"))
        if (block != null) {
            cfg.string("user_header.credit_regex")
                ?.let { re -> runCatching { Regex(re) }.getOrNull() }
                ?.find(block.text())?.groupValues?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it.trim() }
        }
        return doc.textBy(cfg.candidates("user_header.credit"))
    }
}

/** 首页解析（方案 §2.3 index.php）：分区 → 版块树（行内子版块）→ 最近回复汇总。 */
object IndexParser {

    fun parse(html: String, pageUrl: String, cfg: SelectorConfig): IndexPage {
        val doc = Jsoup.parse(html, pageUrl)
        val userHeader = UserHeaderParser.parse(doc, cfg)

        val sectionTables = doc.allBy(cfg.candidates("index_page.sections"))
        val sections = mutableListOf<Section>()
        val seenBoards = mutableSetOf<String>()
        val recentReplies = mutableListOf<RecentReply>()

        if (sectionTables.isNotEmpty()) {
            for (table in sectionTables) {
                val sectionName = table.textBy(cfg.candidates("index_page.section_name")) ?: "其他版块"
                val boards = mutableListOf<Board>()
                for (row in table.select("tr")) {
                    val cell = row.selectFirst("th") ?: continue
                    // 每行一个主版块：h3 内是主版块链接，同一格里其余 thread.php 链接是行内子版块
                    // （网页版以小链接形式内嵌在版块行里，不再平铺成独立行）。
                    val anchors = cell.allBy(cfg.candidates("index_page.board_link"))
                    if (anchors.isEmpty()) continue
                    val parentAnchor = cell.selectFirst("h3 a[href*='thread.php?fid']") ?: anchors.first()
                    val parent = boardFromAnchor(parentAnchor, cfg, seenBoards) ?: continue
                    val children = anchors.filter { it !== parentAnchor }
                        .mapNotNull { boardFromAnchor(it, cfg, seenBoards) }
                    boards.add(parent.copy(children = children))
                    lastReplyOf(row, parent.name, cfg)?.let { recentReplies.add(it) }
                }
                if (boards.isNotEmpty()) sections.add(Section(sectionName, boards))
            }
        } else {
            // 通用兜底（真机第一课：站点为深度改版皮肤，候选选择器可能整体不命中）：
            // 全页扫描所有版块链接，平铺为一个分区，保证首页总能给出可用内容。
            val boards = doc.allBy(listOf("a[href*='thread.php?fid']"))
                .mapNotNull { boardFromAnchor(it, cfg, seenBoards) }
            if (boards.isNotEmpty()) sections.add(Section("全部版块", boards))
            DiagLog.log("PARSE", "index 选择器未命中分区表 → 平铺兜底 boards=${boards.size}")
        }

        return IndexPage(
            sections = sections,
            onlineInfo = doc.textBy(cfg.candidates("index_page.online_info")),
            userHeader = userHeader,
            recentReplies = recentReplies.sortedByDescending { it.atMillis },
        )
    }

    /**
     * 行内「最后回复」格（第二个 th）：`Re:标题` + 回复人 + [yyyy-MM-dd HH:mm]。
     * 认证版块该格只显示「认证论坛」，没有回复链接，返回 null。
     */
    private fun lastReplyOf(row: Element, boardName: String, cfg: SelectorConfig): RecentReply? {
        val ths = row.select("th")
        if (ths.size < 2) return null
        val cell = ths.last()
        val a = cell.selectFirst("a[href*='read.php']") ?: return null
        val tid = extractId(a, cfg.spec("thread_list.tid")) ?: return null
        val title = a.text().trim().removePrefix("Re:").trim().takeIf { it.isNotEmpty() } ?: return null
        val author = cell.selectFirst("span.f12")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: cell.selectFirst("span.gray2 span")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val time = Regex("(\\d{4}-\\d{2}-\\d{2})[ ]?(\\d{2}:\\d{2})").find(cell.text())
            ?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }
        return RecentReply(
            tid = tid,
            title = title,
            boardName = boardName,
            author = author,
            time = time,
            atMillis = time?.let { parseIndexTime(it) } ?: 0L,
        )
    }

    /** 站点时间为北京时间；同源数据排序用统一时区即可，解析失败记 0 沉底。 */
    internal fun parseIndexTime(text: String): Long = runCatching {
        java.time.LocalDateTime.parse(text.replace(' ', 'T'))
            .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    private fun boardFromAnchor(
        a: Element,
        cfg: SelectorConfig,
        seen: MutableSet<String>,
    ): Board? {
        val fid = extractId(a, cfg.spec("index_page.board_fid")) ?: return null
        val name = a.text().trim().takeIf { it.isNotEmpty() } ?: return null
        if (!seen.add(fid)) return null
        val row = a.closest("tr") ?: a.parent() ?: a
        val desc = row.textBy(cfg.candidates("index_page.board_desc"))
            ?.removePrefix(name)?.trim()?.takeIf { it.isNotEmpty() }
        val certified = row.firstBy(cfg.candidates("index_page.certified_marker")) != null
        return Board(fid = fid, name = name, desc = desc, isCertified = certified)
    }
}

/** 版块帖子列表解析（thread.php，方案 §2.3）。 */
object ThreadListParser {

    fun parse(html: String, pageUrl: String, fid: String, fallbackPage: Int, cfg: SelectorConfig): ThreadListPage {
        val doc = Jsoup.parse(html, pageUrl)
        val rows = doc.allBy(cfg.candidates("thread_list.rows"))
        var threads = rows.mapNotNull { row -> rowToThread(row, cfg, "thread_list") }

        // 通用兜底：行选择器未命中时，全页扫描帖子主题链接（排除分页/楼内定位）
        if (threads.isEmpty()) {
            threads = doc.select("a[href*='read.php?tid-'], a[href*='read.php?tid=']")
                .filter { a ->
                    val href = a.absHref()
                    a.text().trim().length >= 4 &&
                        a.closest(".pages") == null &&
                        !href.contains("-page-") && !href.contains("#")
                }
                .mapNotNull { a ->
                    val tid = extractId(a, cfg.spec("thread_list.tid")) ?: return@mapNotNull null
                    val row = a.closest("tr")
                    val authorEl = row?.firstBy(cfg.candidates("thread_list.author"))
                    ThreadItem(
                        tid = tid,
                        title = a.text().trim(),
                        author = authorEl?.let {
                            UserBrief(extractId(it, cfg.spec("thread_list.author_uid")), it.text().trim())
                        },
                        lastReplyTime = row?.textBy(cfg.candidates("thread_list.last_reply_time")),
                    )
                }
                .distinctBy { it.tid }
            DiagLog.log("PARSE", "thread fid=$fid 行选择器未命中 → 链接兜底 ${threads.size} 条")
        }

        // 置顶帖常在页面上出现两次：保留首见（置顶位），避免重复
        threads = threads.distinctBy { it.tid }

        val (page, total) = pagination(doc, cfg, "thread_list", fallbackPage)
        return ThreadListPage(
            fid = fid,
            boardName = doc.textBy(cfg.candidates("thread_list.board_name")),
            threads = threads,
            page = page,
            totalPages = total,
        )
    }

    /** 行 → ThreadItem（thread_list / search_page 两个 section 复用）。 */
    fun rowToThread(row: Element, cfg: SelectorConfig, section: String): ThreadItem? {
        val subjectEl = row.firstBy(cfg.candidates("$section.subject")) ?: return null
        val tid = extractId(subjectEl, cfg.spec("$section.tid")) ?: return null
        val title = subjectEl.text().trim().takeIf { it.isNotEmpty() } ?: return null

        val authorEl = row.firstBy(cfg.candidates("$section.author"))
        val nums = row.allBy(cfg.candidates("$section.nums"))
            .flatMap { it.text().split('/', '|', ' ') }
            .mapNotNull { it.filter(Char::isDigit).takeIf { d -> d.isNotEmpty() }?.toIntOrNull() }

        return ThreadItem(
            tid = tid,
            title = title,
            author = authorEl?.let {
                UserBrief(
                    uid = extractId(it, cfg.spec("$section.author_uid")),
                    name = it.text().trim(),
                )
            },
            replies = nums.getOrNull(0) ?: 0,
            views = nums.getOrNull(1) ?: 0,
            lastReplyTime = row.textBy(cfg.candidates("$section.last_reply_time")),
            lastReplyUser = row.textBy(cfg.candidates("$section.last_reply_user")),
            isPinned = row.firstBy(cfg.candidates("$section.pinned_marker")) != null,
            isDigest = row.firstBy(cfg.candidates("$section.digest_marker")) != null,
            isLocked = row.firstBy(cfg.candidates("$section.locked_marker")) != null,
            hasAttachment = row.firstBy(cfg.candidates("$section.attach_marker")) != null,
        )
    }
}

/** 搜索结果解析（search.php，方案 §6.4.3）。 */
object SearchParser {

    fun parse(html: String, pageUrl: String, keyword: String, fallbackPage: Int, cfg: SelectorConfig): SearchResultPage {
        val doc = Jsoup.parse(html, pageUrl)
        val rows = doc.allBy(cfg.candidates("search_page.result_rows"))
        val results = rows.mapNotNull { row ->
            ThreadListParser.rowToThread(row, cfg, "search_page")
        }
        val (page, total) = pagination(doc, cfg, "search_page", fallbackPage)
        return SearchResultPage(results, page, total, keyword)
    }
}

package net.southplus.client.core.parser

import net.southplus.client.core.config.SelectorConfig
import net.southplus.client.core.model.Attachment
import net.southplus.client.core.model.ContentBlock
import net.southplus.client.core.model.FormSnapshot
import net.southplus.client.core.model.PmDetail
import net.southplus.client.core.model.PmListPage
import net.southplus.client.core.model.PmSummary
import net.southplus.client.core.model.Post
import net.southplus.client.core.model.ThreadPage
import net.southplus.client.core.model.UserBrief
import net.southplus.client.core.model.UserProfile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * 帖子阅读页解析（read.php，方案 §6.4.1 / §6.4.2）：
 * 楼层 → Post（内容块流 + 附件 + 付费墙 + 编辑链接）。
 */
object ReadParser {

    private const val DEFAULT_PER_PAGE = 20

    fun parse(
        html: String,
        pageUrl: String,
        tid: String,
        fallbackPage: Int,
        cfg: SelectorConfig,
    ): ThreadPage {
        val doc = Jsoup.parse(html, pageUrl)
        val userHeader = UserHeaderParser.parse(doc, cfg)
        val title = doc.textBy(cfg.candidates("read_page.thread_title")) ?: "帖子 $tid"

        val floorEls = doc.allBy(cfg.candidates("read_page.floors"))
            .takeIf { it.isNotEmpty() }
            ?: fallbackFloors(doc, cfg)

        val posts = mutableListOf<Post>()
        for ((i, fl) in floorEls.withIndex()) {
            val contentEl = fl.firstBy(cfg.candidates("read_page.content")) ?: continue
            val authorEl = fl.firstBy(cfg.candidates("read_page.author")) ?: continue
            val authorName = authorEl.text().trim().takeIf { it.isNotEmpty() } ?: continue

            val floorText = fl.text()
            val blocks = buildBlocks(contentEl, cfg)
            val attachments = collectAttachments(fl, cfg)

            // 付费贴未购买态（方案 §6.6）：购买入口 + 售价文案 → PaidWall 卡片
            val buyEl = fl.firstBy(cfg.candidates("read_page.buy_link"))
            if (buyEl != null && floorText.contains("售价")) {
                // 2026-09 真机校准：售价文案在 span.s3（全句「此帖售价 X SP币,已有 N 人购买」）
                val priceRe = cfg.salePattern
                val saleText = fl.textBy(cfg.candidates("read_page.sale_text"))
                    ?: priceRe?.find(floorText)?.value
                val price = priceRe?.find(saleText ?: floorText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val buyUrl = buyEl.buyUrlFrom(pageUrl)
                if (buyUrl != null) {
                    // 售价区块（h6.quote.jumbotron）已由 PaidWall 呈现，避免重复
                    blocks.removeAll { b ->
                        val html = when (b) {
                            is ContentBlock.Text -> b.html
                            is ContentBlock.Quote -> b.html
                            else -> null
                        }
                        html != null && html.contains("售价") && html.contains("购买")
                    }
                    blocks.add(0, ContentBlock.PaidWall(saleText, buyUrl, price = price))
                }
            }

            val time = fl.textBy(cfg.candidates("read_page.post_time"))
                ?: POST_TIME_PATTERN.find(floorText)?.value

            // 楼层作者头像（2026-10 真机：作者侧栏 div.user-pic 内的 img，
            // src 多为相对路径 attachment/upload/…，absSrc() 按 baseUri 补全）
            val avatarUrl = fl.firstBy(cfg.candidates("read_page.author_avatar"))
                ?.absSrc()?.takeIf { it.isNotEmpty() }

            val floorNo = FLOOR_PATTERN.find(floorText)?.groupValues?.get(1)?.toIntOrNull()
                ?: ((fallbackPage - 1) * DEFAULT_PER_PAGE + i + 1)

            posts.add(
                Post(
                    floor = floorNo,
                    author = UserBrief(
                        extractId(authorEl, cfg.spec("read_page.author_uid")),
                        authorName,
                        avatarUrl = avatarUrl,
                    ),
                    time = time,
                    contentBlocks = blocks,
                    attachments = attachments,
                    isOp = false, // 解析后统一标记
                    pid = POST_ID_PATTERN.find(fl.html())?.groupValues?.getOrNull(1),
                    editUrl = fl.firstBy(cfg.candidates("read_page.edit_link"))?.absHref()?.takeIf { it.isNotEmpty() },
                ),
            )
        }

        // 楼主标记：与首楼作者同名（同昵称碰撞概率低，可接受）
        val opName = posts.firstOrNull()?.author?.name
        val marked = posts.map { it.copy(isOp = it.author.name == opName) }

        val (page, total) = pagination(doc, cfg, "read_page", fallbackPage)

        return ThreadPage(
            tid = tid,
            title = title,
            fid = BOARD_ID_PATTERN.find(html)?.let { m ->
                m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { null }
            },
            boardName = doc.textBy(cfg.candidates("thread_list.board_name")),
            posts = marked,
            page = page,
            totalPages = total,
            replyUrl = doc.firstBy(cfg.candidates("read_page.reply_link"))?.absHref()
                ?.takeIf { it.contains("post.php") },
            newTopicUrl = doc.firstBy(cfg.candidates("read_page.new_topic_link"))?.absHref(),
            favorUrl = favorUrlOf(doc, pageUrl, cfg),
            userHeader = userHeader,
        )
    }

    /**
     * 收藏入口（2026-09 真机校准）：南+ 阅读页的「收藏」按钮没有 href，是
     * `<a id="favor" onclick="sendmsg('pw_ajax.php?action=favor&tid=X','',this.id)">收藏</a>`。
     * 旧选择器 a[href*='favor.php'] 永远落空 → 收藏功能不可用。
     * 候选命中后用 ajaxUrlFrom：优先 href（兼容旧式 favor.php?action=add），否则取 onclick。
     */
    private fun favorUrlOf(doc: Document, pageUrl: String, cfg: SelectorConfig): String? {
        for (sel in cfg.candidates("read_page.favor_link")) {
            val el = runCatching { doc.select(sel).first() }.getOrNull() ?: continue
            val url = el?.ajaxUrlFrom(pageUrl)
            if (!url.isNullOrEmpty()) return url
        }
        return null
    }

    /** 无 table.t5 结构时的兜底：含作者链接与正文容器的最内层表格。 */
    private fun fallbackFloors(doc: Document, cfg: SelectorConfig): List<Element> {
        val contentSel = cfg.candidates("read_page.content")
        val qualifying = doc.select("table").filter { t ->
            t.select("a[href*='u.php']").isNotEmpty() &&
                contentSel.any { sel -> runCatching { t.select(sel).isNotEmpty() }.getOrDefault(false) }
        }
        // 仅保留最内层（外层表格只作布局容器）
        return qualifying.filter { t -> qualifying.none { other -> other !== t && t in other.parents() } }
    }

    /** 楼层正文 → 内容块流（方案 §6.4.1 ContentBlock）。 */
    private fun buildBlocks(content: Element, cfg: SelectorConfig): MutableList<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        val textBuf = StringBuilder()
        val smileyRe = cfg.smileyPattern

        fun flush() {
            val html = textBuf.toString().trim()
            if (html.isNotEmpty()) blocks.add(ContentBlock.Text(html))
            textBuf.clear()
        }

        fun walk(el: Element) {
            // jumbotron 是出售区块专属样式（售价行/解锁正文），按正文展开而非折叠引用。
            // 2026-10 真机：解锁正文常含大量 img，必须逐子节点下走拆出图片块——
            // 整块塞 Text 会被 fromHtml（无 ImageGetter）渲染成一排小方块占位。
            val isQuoteShell = el.tagName().lowercase() == "blockquote" ||
                el.hasClass("quote") || el.hasClass("blockquote")
            if (el.hasClass("jumbotron") && isQuoteShell) {
                flush()
                el.childNodes().forEach { node ->
                    when (node) {
                        is Element -> walk(node)
                        is TextNode -> textBuf.append(node.outerHtml())
                    }
                }
                return
            }
            when (el.tagName().lowercase()) {
                "script", "style", "iframe", "form", "button", "input" -> return
                "img" -> {
                    val src = el.absSrc().ifEmpty { el.attr("data-src") }
                    if (src.isEmpty()) return
                    flush()
                    val isSmiley = smileyRe?.containsMatchIn(src) == true ||
                        (el.attr("width").toIntOrNull() ?: 99) < 40
                    blocks.add(ContentBlock.Image(src, isSmiley, width = el.attr("width").toIntOrNull()?.takeIf { it > 0 }, height = el.attr("height").toIntOrNull()?.takeIf { it > 0 }))
                }
                "blockquote" -> {
                    flush()
                    // jumbotron 已在 walk 开头展开为正文流；这里只剩真正的引用块
                    val author = el.text().lineSequence().firstOrNull()
                        ?.substringBefore("于")?.trim()?.takeIf { it.length <= 40 }
                    blocks.add(ContentBlock.Quote(author, el.html()))
                }
                "a" -> {
                    val href = el.absHref()
                    if (isAttachmentHref(href, cfg)) {
                        flush()
                        blocks.add(
                            ContentBlock.AttachmentItem(
                                Attachment(
                                    name = el.text().trim().ifEmpty { "附件" },
                                    url = href,
                                    isTorrent = href.endsWith(".torrent", true) ||
                                        el.text().endsWith(".torrent", true),
                                ),
                            ),
                        )
                    } else {
                        // 普通链接保留在文本流里（HtmlText 可路由）
                        textBuf.append(el.outerHtml())
                    }
                }
                else -> if (el.hasClass("quote") || el.hasClass("blockquote")) {
                    // 部分皮肤引用是 div/table.quote 而非 blockquote（真机校准备注）
                    flush()
                    val author = el.text().lineSequence().firstOrNull()
                        ?.substringBefore("于")?.trim()?.takeIf { it.length <= 40 }
                    blocks.add(ContentBlock.Quote(author, el.html()))
                } else el.childNodes().forEach { node ->
                    when (node) {
                        is Element -> walk(node)
                        is TextNode -> textBuf.append(node.outerHtml())
                    }
                }
            }
        }

        content.childNodes().forEach { node ->
            when (node) {
                is Element -> walk(node)
                is TextNode -> textBuf.append(node.outerHtml())
            }
        }
        flush()
        return blocks
    }

    private fun isAttachmentHref(href: String, cfg: SelectorConfig): Boolean =
        href.contains("job=download") ||
            href.contains("action=attachment") ||
            cfg.attachmentFragments.any { href.contains(it) }

    /** 楼层附件集合（去重）。 */
    private fun collectAttachments(fl: Element, cfg: SelectorConfig): List<Attachment> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Attachment>()
        cfg.candidates("read_page.attachments").forEach { sel ->
            runCatching { fl.select(sel) }.getOrDefault(emptyList()).forEach { a ->
                val href = a.absHref()
                if (href.isNotEmpty() && seen.add(href)) {
                    out.add(
                        Attachment(
                            name = a.text().trim().ifEmpty { "附件" },
                            url = href,
                            isTorrent = href.endsWith(".torrent", true) || a.text().endsWith(".torrent", true),
                        ),
                    )
                }
            }
        }
        return out
    }
}

/** 短消息解析（message.php / pm.php，方案 §6.5）。 */
object PmParser {

    fun parseList(html: String, pageUrl: String, fallbackPage: Int, cfg: SelectorConfig): PmListPage {
        val doc = Jsoup.parse(html, pageUrl)
        val items = doc.allBy(cfg.candidates("pm_page.list_rows")).mapNotNull { row ->
            val readEl = row.firstBy(cfg.candidates("pm_page.read_link")) ?: return@mapNotNull null
            val mid = extractId(readEl, cfg.spec("pm_page.mid")) ?: return@mapNotNull null
            val subjectEl = row.firstBy(cfg.candidates("pm_page.subject")) ?: readEl
            PmSummary(
                mid = mid,
                fromUser = row.textBy(cfg.candidates("pm_page.from_user")),
                subject = subjectEl.text().trim().ifEmpty { "(无标题)" },
                isUnread = row.text().contains("未读"),
            )
        }
        val (page, total) = pagination(doc, cfg, "pm_page", fallbackPage)
        return PmListPage(items, page, total)
    }

    fun parseDetail(html: String, pageUrl: String, mid: String, cfg: SelectorConfig): PmDetail {
        val doc = Jsoup.parse(html, pageUrl)
        val contentEl = doc.firstBy(cfg.candidates("read_page.content"))
        val formEl = FormParser.findForm(
            doc,
            cfg.candidates("pm_page.form"),
            fallbackFilter = { it.select("textarea").isNotEmpty() },
        )
        val replyForm = formEl?.let {
            FormParser.snapshot(
                it, pageUrl,
                FormParser.FieldHints(
                    recipient = cfg.candidates("pm_page.recipient_field"),
                    title = cfg.candidates("pm_page.title_field"),
                    content = cfg.candidates("pm_page.content_field"),
                ),
            )
        }
        return PmDetail(
            mid = mid,
            fromUser = doc.firstBy(cfg.candidates("pm_page.from_user"))?.text()?.trim(),
            subject = doc.textBy(listOf("h1", "#subject", "td.h span")),
            html = contentEl?.html() ?: "",
            time = POST_TIME_PATTERN.find(doc.text())?.value,
            replyForm = replyForm,
        )
    }
}

/** 收藏夹解析（favor.php，方案 §6.5）。 */
object FavorParser {

    fun parse(html: String, pageUrl: String, cfg: SelectorConfig): List<Pair<String, String>> {
        val doc = Jsoup.parse(html, pageUrl)
        return doc.allBy(cfg.candidates("favor_page.rows")).mapNotNull { row ->
            val subjectEl = row.firstBy(cfg.candidates("favor_page.subject")) ?: return@mapNotNull null
            val tid = extractId(subjectEl, cfg.spec("favor_page.tid")) ?: return@mapNotNull null
            tid to subjectEl.text().trim()
        }
    }
}

/** 用户空间解析（u.php，方案附录 A #19）。 */
object UserParser {

    fun parse(html: String, pageUrl: String, uid: String, cfg: SelectorConfig): UserProfile {
        val doc = Jsoup.parse(html, pageUrl)
        val name = doc.textBy(listOf("h1", "h2", "#head .name", ".name"))?.take(24)
        val avatar = doc.selectFirst("img[src*='face'], img[src*='avatar'], img[src*='uid']")?.absSrc()
        val stats = doc.select("table tr").take(40).mapNotNull { tr ->
            val cells = tr.select("td, th")
            if (cells.size >= 2) cells[0].text().trim().take(10) to cells[1].text().trim().take(24) else null
        }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toMap()
        return UserProfile(
            uid = uid,
            name = name,
            avatarUrl = avatar?.takeIf { it.isNotEmpty() },
            infoText = doc.title(),
            stats = stats,
        )
    }
}

/** 发帖/回复表单解析（post.php，方案 §6.5）。 */
object PostFormParser {

    fun parse(html: String, pageUrl: String, cfg: SelectorConfig): FormSnapshot? {
        val doc = Jsoup.parse(html, pageUrl)
        val formEl = FormParser.findForm(
            doc,
            cfg.candidates("post_form.form"),
            fallbackFilter = { it.select("textarea").isNotEmpty() },
        ) ?: return null
        return FormParser.snapshot(
            formEl, pageUrl,
            FormParser.FieldHints(
                title = cfg.candidates("post_form.title_field"),
                content = cfg.candidates("post_form.content_field"),
            ),
        )
    }
}

private val POST_TIME_PATTERN = Regex("\\d{4}-\\d{1,2}-\\d{1,2} \\d{1,2}:\\d{2}")
private val FLOOR_PATTERN = Regex("#?(\\d+)楼")
private val POST_ID_PATTERN = Regex("post[_-](\\d+)")
private val BOARD_ID_PATTERN = Regex("fid-(\\d+)|fid=(\\d+)")

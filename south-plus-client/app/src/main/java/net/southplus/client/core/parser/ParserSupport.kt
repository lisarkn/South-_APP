package net.southplus.client.core.parser

import net.southplus.client.core.config.AttrSpec
import net.southplus.client.core.config.SelectorConfig
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 在候选选择器列表中取第一个命中的元素（方案 §6.3 配置化解析核心原语）。 */
fun Element.firstBy(candidates: List<String>): Element? =
    candidates.firstNotNullOfOrNull { sel ->
        runCatching { selectFirst(sel) }.getOrNull()
    }

/** 取第一个有命中的候选选择器的全部元素。 */
fun Element.allBy(candidates: List<String>): List<Element> {
    for (selector in candidates) {
        val elements = runCatching { select(selector) }.getOrNull()
        if (!elements.isNullOrEmpty()) return elements
    }
    return emptyList()
}

fun Element.textBy(candidates: List<String>): String? =
    firstBy(candidates)?.text()?.trim()?.takeIf { it.isNotEmpty() }

fun Element.attrBy(candidates: List<String>, attr: String): String? =
    firstBy(candidates)?.attr(attr)?.trim()?.takeIf { it.isNotEmpty() }

/** {attr, regex} 规格：返回第一个非空捕获组。 */
fun extractId(el: Element?, spec: AttrSpec?): String? {
    if (el == null || spec == null) return null
    val value = el.attr(spec.attr)
    if (value.isEmpty()) return null
    if (spec.regex == null) return value
    val m = spec.compiled?.find(value) ?: return null
    for (i in 1 until m.groupValues.size) {
        if (m.groupValues[i].isNotEmpty()) return m.groupValues[i]
    }
    return null
}

/** 绝对链接（Jsoup 以页面 URL 为 baseUri 解析后 absUrl 可用，否则回退原值）。 */
fun Element.absHref(): String = absUrl("href").ifEmpty { attr("href") }

fun Element.absSrc(): String = absUrl("src").ifEmpty { attr("src") }

/**
 * 购买入口 URL（方案 §6.6，2026-09 真机校准）：南+ 的购买按钮是
 * input[onclick="location.href='job.php?action=buytopic&…'"] 而非 <a>，
 * 优先取 href，没有则从 onclick 提取并以页面 URL 为基准绝对化。
 */
fun Element.buyUrlFrom(pageUrl: String): String? {
    val fromOnclick = LOCATION_PATTERN
        .find(attr("onclick"))?.groupValues?.get(1)
    val raw = absHref().ifEmpty { fromOnclick.orEmpty() }
    if (raw.isEmpty()) return null
    if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
    return runCatching { java.net.URL(java.net.URL(pageUrl), raw).toString() }.getOrNull() ?: raw
}

/**
 * AJAX 动作 URL（2026-09 真机校准）：阅读页的收藏按钮没有 href，是
 * `<a id="favor" onclick="sendmsg('pw_ajax.php?action=favor&tid=X','',this.id)">收藏</a>`，
 * 优先取 href，没有则从 onclick 的 sendmsg('URL',…) 提取并以页面 URL 为基准绝对化。
 */
fun Element.ajaxUrlFrom(pageUrl: String): String? {
    val fromOnclick = SENDMSG_PATTERN.find(attr("onclick"))?.groupValues?.get(1)
    val raw = absHref().ifEmpty { fromOnclick.orEmpty() }
    if (raw.isEmpty()) return null
    if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
    return runCatching { java.net.URL(java.net.URL(pageUrl), raw).toString() }.getOrNull() ?: raw
}

/** 分页信息：从候选元素与链接里推断（当前页, 总页数）。 */
fun pagination(
    doc: Document,
    cfg: SelectorConfig,
    section: String,
    fallbackPage: Int,
): Pair<Int, Int> {
    var current = fallbackPage
    var total = fallbackPage

    doc.firstBy(cfg.candidates("$section.pagination_current"))?.text()?.let { t ->
        // 支持 "2/10" 与纯数字两种形态
        PAGE_FRACTION_PATTERN.find(t)?.groupValues?.let { g ->
            g[1].toIntOrNull()?.let { current = it }
            g[2].toIntOrNull()?.let { total = maxOf(total, it) }
        } ?: t.filter(Char::isDigit).takeIf { it.isNotEmpty() }?.toIntOrNull()?.let { current = it }
    }

    doc.allBy(cfg.candidates("$section.pagination_links")).forEach { a ->
        val href = a.absHref()
        val fromHref = PAGE_PATH_PATTERN.find(href)?.groupValues?.get(1)?.toIntOrNull()
            ?: PAGE_QUERY_PATTERN.find(href)?.groupValues?.get(1)?.toIntOrNull()
        val fromText = a.text().filter(Char::isDigit).takeIf { it.isNotEmpty() }?.toIntOrNull()
        val n = fromHref ?: fromText
        if (n != null && n > total) total = n
    }
    return current to maxOf(current, total)
}

private val LOCATION_PATTERN = Regex("location\\.href\\s*=\\s*['\"]([^'\"]+)['\"]")
private val SENDMSG_PATTERN = Regex("sendmsg\\(\\s*['\"]([^'\"]+)['\"]")
private val PAGE_FRACTION_PATTERN = Regex("(\\d+)\\s*/\\s*(\\d+)")
// \b 词边界不可省：read.php 分页链接形如 read.php?tid-X-fpage-0-toread--page-N.html，
// 无边界时 find() 先命中 fpage-0 里的 page-0，总页数恒等于当前页（阅读页翻不了页）
private val PAGE_PATH_PATTERN = Regex("\\bpage-(\\d+)")
private val PAGE_QUERY_PATTERN = Regex("[?&]page=(\\d+)")

/**
 * 引擎提示信息页（任务接受结果、权限提示、搜索间隔限制等）文案：结构固定为
 * `<div class=t><table><tr><td class=h>…提示信息</td></tr><tr class=tr1><th>正文+表单</th></tr></table>`。
 * 只取 th 里表单之前的文本，避免把登录表单字段一起拼进来。
 */
internal fun engineNotice(doc: Document): String? {
    val head = doc.select("td.h").firstOrNull { it.text().contains("提示信息") } ?: return null
    val table = head.closest("table") ?: return null
    val th = table.select("th").firstOrNull() ?: return null
    val parts = mutableListOf<String>()
    for (node in th.childNodes()) {
        if (node is org.jsoup.nodes.Element && node.tagName().lowercase() == "form") break
        val text = when (node) {
            is org.jsoup.nodes.Element -> node.text()
            is org.jsoup.nodes.TextNode -> node.text()
            else -> ""
        }.trim()
        if (text.isNotEmpty()) parts.add(text)
    }
    val joined = parts.joinToString(" ").ifBlank { th.text().trim() }
    return joined.takeIf { it.isNotEmpty() }?.take(300)
}

/**
 * 插件 AJAX 响应正文（2026-10 真机校准，hack/tasks 任务插件）：
 * `<?xml …?><ajax><![CDATA[code\t文案]]></ajax>`，code 为 success/错误码，\t 分隔。
 */
fun ajaxCdata(html: String): String? =
    Regex("<ajax[^>]*>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</ajax>", RegexOption.DOT_MATCHES_ALL)
        .find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

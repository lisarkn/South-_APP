package net.southplus.client.core.parser

import net.southplus.client.core.model.RssItem

/**
 * rss.php（页头「最新帖子」同源的全站最新 200 帖，游客可见）。
 * 输出是固定机器格式，正则直取字段；不用 HTML 解析器，避免 CDATA 被改写。
 */
object RssParser {

    private val itemRegex = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    private val cdataRegex = Regex("<(\\w+)><!\\[CDATA\\[(.*?)\\]\\]></\\1>", RegexOption.DOT_MATCHES_ALL)
    private val plainRegex = Regex("<(\\w+)>([^<]*)</\\1>")
    private val tidRegex = Regex("tid[-=](\\d+)")

    fun parse(xml: String): List<RssItem> = itemRegex.findAll(xml).mapNotNull { m ->
        val body = m.groupValues[1]
        val fields = buildMap {
            cdataRegex.findAll(body).forEach { put(it.groupValues[1].lowercase(), it.groupValues[2]) }
            plainRegex.findAll(body).forEach { pair ->
                putIfAbsent(pair.groupValues[1].lowercase(), pair.groupValues[2].trim())
            }
        }
        val tid = tidRegex.find(fields["link"] ?: "")?.groupValues?.get(1) ?: return@mapNotNull null
        val title = fields["title"]?.trim().takeIf { !it.isNullOrEmpty() } ?: return@mapNotNull null
        RssItem(
            tid = tid,
            title = title,
            boardName = fields["category"]?.trim()?.takeIf { it.isNotEmpty() },
            author = fields["author"]?.trim()?.takeIf { it.isNotEmpty() },
            publishedAt = fields["pubdate"]?.let { parseRfc1123(it) } ?: 0L,
        )
    }.toList()

    /** RFC-1123（如 Thu, 01 Oct 2026 07:02:02 +0000）；解析失败记 0 沉底。 */
    internal fun parseRfc1123(value: String): Long = runCatching {
        java.time.ZonedDateTime.parse(value.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli()
    }.getOrDefault(0L)
}

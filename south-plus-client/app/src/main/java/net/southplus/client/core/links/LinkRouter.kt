package net.southplus.client.core.links

import net.southplus.client.core.config.SelectorConfig
import net.southplus.client.core.model.ResourceLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 链接路由（方案 §6.4.4 / §6.4.5 / §6.4.6）。
 * 分类：站内路由（跨镜像改写）/ 网盘资源卡 / 图床 / 特殊协议 / 普通外链。
 * host 清单来自 selectors.yaml（pan_hosts 等），可随配置热修。
 */
sealed interface LinkTarget {
    /** 站内链接 → 应用内页面路由（跨镜像改写到当前镜像后进入）。 */
    data class Internal(val path: String, val tid: String? = null, val fid: String? = null) : LinkTarget

    /** 特殊协议（magnet/ed2k/thunder）→ 交给系统 BT/下载器。 */
    data class TorrentProtocol(val url: String) : LinkTarget

    /** 网盘链接 → 资源卡片（提取码自动复制，§6.4.5）。 */
    data class PanLink(val url: String, val host: String) : LinkTarget

    /** 图床直链 → 内联图。 */
    data class ImageDirect(val url: String) : LinkTarget

    /** 图床页面链接（resolver 或卡片）。 */
    data class ImagePage(val url: String, val host: String) : LinkTarget

    /** 其余站外链接 → Custom Tabs 打开策略。 */
    data class External(val url: String) : LinkTarget
}

class LinkRouter(
    mirrorHosts: Set<String>,
    panHosts: List<String>,
    private val imageDirectHosts: Set<String>,
) {

    private val mirrors = mirrorHosts.map { it.lowercase() }
    private val pans = panHosts.map { it.lowercase() }

    fun classify(rawUrl: String): LinkTarget {
        val url = rawUrl.trim()
        if (url.startsWith("magnet:", true) || url.startsWith("ed2k:", true) ||
            url.startsWith("thunder:", true)
        ) return LinkTarget.TorrentProtocol(url)

        if (!url.startsWith("http", true)) return LinkTarget.External(url)

        val parsed = url.toHttpUrlOrNull() ?: return LinkTarget.External(url)
        val host = parsed.host

        // 站内（含跨镜像写法）→ 改写 host 后应用内路由（§6.4.4）
        if (mirrors.any { host == it || host.endsWith(".$it") }) {
            val path = parsed.encodedPath + (parsed.encodedQuery?.let { "?$it" } ?: "")
            return when {
                // 注意：thread.php 包含子串 read.php，必须先判 thread
                path.contains("thread.php") -> LinkTarget.Internal(path, fid = fidOf(path))
                path.contains("read.php") -> LinkTarget.Internal(path, tid = tidOf(path))
                else -> LinkTarget.Internal(path)
            }
        }

        // 网盘
        if (pans.any { host.contains(it) }) return LinkTarget.PanLink(url, host)

        // 图床
        if (imageDirectHosts.any { host == it || host.endsWith(".$it") }) {
            return LinkTarget.ImageDirect(url)
        }
        if (IMAGE_PAGE_HOSTS.any { host == it || host.endsWith(".$it") }) {
            return LinkTarget.ImagePage(url, host)
        }

        return LinkTarget.External(url)
    }

    companion object {
        /** 页面型图床（点进去才见图，带广告；§6.4.6 resolver/卡片）。 */
        private val IMAGE_PAGE_HOSTS = setOf("postimg.cc", "pixhost.to", "imgbox.com", "ibb.co", "imgur.com", "pixiv.net")

        /** 常见直链图床 host（直链渲染进楼层流）。 */
        val DEFAULT_DIRECT_HOSTS = setOf(
            "i.postimg.cc", "catbox.moe", "litter.catbox.moe", "files.catbox.moe",
            "i.ibb.co", "i.imgur.com", "i.pximg.net", "thumbs2.pixhost.to",
        )

        fun tidOf(path: String): String? =
            THREAD_PATH_PATTERN.find(path)?.groupValues?.get(1)
                ?: THREAD_QUERY_PATTERN.find(path)?.groupValues?.get(1)

        fun fidOf(path: String): String? =
            BOARD_PATH_PATTERN.find(path)?.groupValues?.get(1)
                ?: BOARD_QUERY_PATTERN.find(path)?.groupValues?.get(1)

        fun default(cfg: SelectorConfig, mirrorHosts: Set<String>): LinkRouter =
            LinkRouter(mirrorHosts, cfg.panHosts(), DEFAULT_DIRECT_HOSTS)
    }
}

/**
 * 资源卡提取（方案 §6.4.5）：识别楼层文本中的「网盘链接 + 提取码」组合。
 * 同楼多链接聚合为卡片列表；提取码用于「打开前先复制」流程。
 */
class ResourceExtractor(cfg: SelectorConfig) {

    private val panHosts: List<String> = cfg.panHosts()
    private val panNames: Map<String, String> = cfg.panNames()
    private val codePatterns: List<Regex> = cfg.extractCodePatterns()

    private val urlRegex = RESOURCE_URL_PATTERN

    fun extract(text: String): List<ResourceLink> {
        val out = mutableListOf<ResourceLink>()
        for (m in urlRegex.findAll(text)) {
            val url = m.value.trimEnd(',', '。', '，', ')')
            val host = url.substringAfter("://").substringBefore('/').lowercase()
            val pan = panHosts.firstOrNull { host.contains(it) } ?: continue
            val window = text.substring(m.range.first, minOf(text.length, m.range.last + 1 + 40))
            val code = codePatterns.firstNotNullOfOrNull { re -> re.find(window)?.groupValues?.getOrNull(1) }
            out.add(ResourceLink(url, host, panNames[pan] ?: pan, code))
        }
        return out.distinctBy { it.url }
    }
}

private val THREAD_PATH_PATTERN = Regex("tid-(\\d+)")
private val THREAD_QUERY_PATTERN = Regex("[?&]tid=(\\d+)")
private val BOARD_PATH_PATTERN = Regex("fid-(\\d+)")
private val BOARD_QUERY_PATTERN = Regex("[?&]fid=(\\d+)")
private val RESOURCE_URL_PATTERN = Regex("https?://[^\\s<>\"'\u3000]+", RegexOption.IGNORE_CASE)

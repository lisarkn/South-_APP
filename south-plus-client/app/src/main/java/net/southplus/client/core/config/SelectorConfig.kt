package net.southplus.client.core.config

import android.content.Context
import net.southplus.client.core.network.ImageHostRule
import net.southplus.client.core.network.ImageHeaderRules

/** {attr: href, regex: "tid-(\\d+)"} 形式的属性提取规格。 */
data class AttrSpec(val attr: String, val regex: String?) {
    val compiled: Regex? = regex?.let { runCatching { Regex(it) }.getOrNull() }
}

/**
 * 选择器配置（方案 §6.3「选择器配置化」）。
 * 所有 CSS 选择器/正则集中在 selectors.yaml；解析代码只引用逻辑字段名。
 * 站点改版时优先改配置，不改代码；schema_version 升级使旧缓存失效。
 */
class SelectorConfig(private val root: Map<String, Any?>) {
    private val specs: Map<String, AttrSpec> = buildMap {
        fun visit(value: Map<*, *>, prefix: String) {
            value.forEach { (key, child) ->
                val path = if (prefix.isEmpty()) key.toString() else "$prefix.$key"
                if (child is Map<*, *>) {
                    child["attr"]?.let { put(path, AttrSpec(it.toString(), child["regex"]?.toString())) }
                    visit(child, path)
                }
            }
        }
        visit(root, "")
    }
    private val candidateCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    val headers by lazy { ImageHeaderRules(imageHostRules()) }
    val salePattern by lazy { string("read_page.sale_text_regex")?.let { runCatching { Regex(it) }.getOrNull() } }
    val smileyPattern by lazy { string("read_page.smiley_src_regex")?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() } }
    val attachmentFragments by lazy {
        candidates("read_page.attachments").mapNotNull { ATTACHMENT_PATTERN.find(it)?.groupValues?.get(1) }
    }


    val schemaVersion: Int = (descend("meta.schema_version") as? String)?.toIntOrNull()
        ?: (descend("meta.schema_version") as? Int) ?: 1

    /** 取某逻辑字段的候选选择器列表（path 形如 "read_page.floors"）。 */
    fun candidates(path: String): List<String> = candidateCache.getOrPut(path) {
        when (val value = descend(path)) {
            is List<*> -> value.mapNotNull { it?.toString() }
            is String -> listOf(value)
            else -> emptyList()
        }
    }

    fun string(path: String): String? = descend(path)?.toString()

    fun spec(path: String): AttrSpec? = specs[path]

    @Suppress("UNCHECKED_CAST")
    fun imageHostRules(): List<ImageHostRule> =
        (descend("image_hosts") as? List<Map<String, Any?>>)?.map { e ->
            ImageHostRule(
                hostSuffix = e["host"]?.toString().orEmpty(),
                referer = e["referer"]?.toString(),
                userAgent = e["userAgent"]?.toString(),
            )
        }.orEmpty().filter { it.hostSuffix.isNotBlank() }

    fun panHosts(): List<String> = candidates("pan_hosts")

    @Suppress("UNCHECKED_CAST")
    fun panNames(): Map<String, String> =
        (descend("pan_names") as? Map<String, Any?>)?.mapValues { it.value?.toString().orEmpty() }.orEmpty()

    private val codePatterns by lazy {
        candidates("extract_code_patterns").mapNotNull { pattern -> runCatching { Regex(pattern) }.getOrNull() }
    }
    fun extractCodePatterns(): List<Regex> = codePatterns

    private fun descend(path: String): Any? {
        var cur: Any? = root
        for (seg in path.split('.')) {
            cur = (cur as? Map<*, *>)?.get(seg) ?: return null
        }
        return cur
    }

    companion object {
        fun fromYaml(text: String): SelectorConfig = SelectorConfig(SelectorYaml.parse(text))

        fun fromAssets(context: Context): SelectorConfig =
            fromYaml(context.assets.open("selectors.yaml").bufferedReader().use { it.readText() })
    }
}

private val ATTACHMENT_PATTERN = Regex("href\\*='([^']+)'")

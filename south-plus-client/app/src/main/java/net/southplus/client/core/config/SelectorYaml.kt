package net.southplus.client.core.config

/**
 * 极简 YAML 子集解析器（无第三方依赖，JVM 单测可用）。
 * 支持：嵌套映射（2 空格缩进）、行内列表 ["a","b"]、块列表 "- x"、
 *       行内对象 {k: v}、引号字符串、行注释 #。
 * 约定：字符串值【不做转义处理】（正则原样保留）。
 */
object SelectorYaml {

    fun parse(text: String): Map<String, Any?> {
        val lines = text.lines()
            .map { stripComment(it) }
            .filter { it.isNotBlank() }
        val pos = IntArray(1)
        val block = parseBlock(lines, minIndent(lines, pos), pos)
        @Suppress("UNCHECKED_CAST")
        return (block as? Map<String, Any?>) ?: emptyMap()
    }

    private fun stripComment(line: String): String {
        var inQuote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuote != null) {
                if (c == inQuote) inQuote = null
            } else when (c) {
                '"', '\'' -> inQuote = c
                '#' -> if (i == 0 || line[i - 1].isWhitespace()) return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun minIndent(lines: List<String>, pos: IntArray): Int {
        if (lines.isEmpty()) return 0
        return indentOf(lines[0])
    }

    private fun indentOf(line: String): Int {
        var n = 0
        while (n < line.length && line[n] == ' ') n++
        return n
    }

    /** 解析一个块（map 或 list），pos 推进到块的下一行。 */
    private fun parseBlock(lines: List<String>, indent: Int, pos: IntArray): Any? {
        if (pos[0] >= lines.size) return null
        val line = lines[pos[0]]
        val ownIndent = indentOf(line)
        if (ownIndent < indent) return null
        val content = line.trimStart()
        return if (content.startsWith("- ")) parseList(lines, ownIndent, pos)
        else parseMap(lines, ownIndent, pos)
    }

    private fun parseMap(lines: List<String>, indent: Int, pos: IntArray): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        while (pos[0] < lines.size) {
            val raw = lines[pos[0]]
            val ind = indentOf(raw)
            if (ind < indent) break
            val content = raw.trimStart()
            if (content.startsWith("- ")) break
            if (ind > indent) { // 异常缩进，跳过防御
                pos[0]++
                continue
            }
            val colon = findMapColon(content)
            if (colon == null) {
                pos[0]++
                continue
            }
            val key = unquote(content.substring(0, colon).trim())
            val rest = content.substring(colon + 1).trim()
            pos[0]++
            map[key] = if (rest.isEmpty()) {
                // 块值：取下一行（更深缩进）开始的子块
                if (pos[0] < lines.size && indentOf(lines[pos[0]]) > indent) {
                    parseBlock(lines, indentOf(lines[pos[0]]), pos)
                } else null
            } else {
                parseInline(rest)
            }
        }
        return map
    }

    private fun parseList(lines: List<String>, indent: Int, pos: IntArray): List<Any?> {
        val list = mutableListOf<Any?>()
        while (pos[0] < lines.size) {
            val raw = lines[pos[0]]
            val ind = indentOf(raw)
            if (ind < indent) break
            val content = raw.trimStart()
            if (!content.startsWith("- ")) break
            if (ind > indent) { pos[0]++; continue }
            var itemContent = content.removePrefix("- ").trim()
            pos[0]++
            // "- key: value" → map 项，后续同项字段以更深缩进续行
            if (itemContent.contains(": ") || itemContent.endsWith(":")) {
                val itemMap = LinkedHashMap<String, Any?>()
                var rest = itemContent
                while (true) {
                    val colon = findMapColon(rest)
                    if (colon == null) { if (rest.isNotBlank()) itemMap["_"] = unquote(rest); break }
                    val key = unquote(rest.substring(0, colon).trim())
                    val value = rest.substring(colon + 1).trim()
                    itemMap[key] = if (value.isEmpty()) null else parseInline(value)
                    if (pos[0] < lines.size && indentOf(lines[pos[0]]) > indent &&
                        !lines[pos[0]].trimStart().startsWith("- ")
                    ) {
                        rest = lines[pos[0]].trimStart()
                        pos[0]++
                    } else break
                }
                list.add(itemMap)
            } else {
                list.add(parseInline(itemContent))
            }
        }
        return list
    }

    /** 找到 map 键值对冒号（引号外、无方括号深度内的第一个 ':'）。 */
    private fun findMapColon(s: String): Int? {
        var inQuote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inQuote != null) {
                if (c == inQuote) inQuote = null
            } else when (c) {
                '"', '\'' -> inQuote = c
                ':' -> return i
            }
            i++
        }
        return null
    }

    /** 行内值：[a, b] / {k: v} / 纯量。 */
    fun parseInline(rawIn: String): Any? {
        val raw = rawIn.trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith("[") && raw.endsWith("]")) {
            return splitTop(raw.substring(1, raw.length - 1), ',')
                .map { parseInline(it) }
                .filterNotNull()
        }
        if (raw.startsWith("{") && raw.endsWith("}")) {
            val map = LinkedHashMap<String, Any?>()
            splitTop(raw.substring(1, raw.length - 1), ',').forEach { pair ->
                val idx = findMapColon(pair) ?: return@forEach
                map[unquote(pair.substring(0, idx).trim())] = parseInline(pair.substring(idx + 1))
            }
            return map
        }
        return unquote(raw)
    }

    private fun splitTop(s: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var inQuote: Char? = null
        val cur = StringBuilder()
        for (c in s) {
            if (inQuote != null) {
                cur.append(c)
                if (c == inQuote) inQuote = null
                continue
            }
            when (c) {
                '"', '\'' -> { inQuote = c; cur.append(c) }
                '[', '{' -> { depth++; cur.append(c) }
                ']', '}' -> { depth--; cur.append(c) }
                delimiter -> if (depth == 0) { out.add(cur.toString()); cur.clear() } else cur.append(c)
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) out.add(cur.toString())
        return out
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '\'' && t.last() == '\''))) {
            return t.substring(1, t.length - 1)
        }
        return t
    }
}

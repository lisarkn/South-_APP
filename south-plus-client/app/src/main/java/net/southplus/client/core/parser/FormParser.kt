package net.southplus.client.core.parser

import net.southplus.client.core.config.SelectorConfig
import net.southplus.client.core.model.FormSnapshot
import org.jsoup.nodes.Element

/**
 * 通用表单快照（方案 §6.5「从页面提取回复表单隐藏域」）：
 * 不猜站点字段名 —— 把表单里所有 input/textarea/select 原样镜像，
 * 提交时仅覆盖用户输入的字段。对 PHPWind 各变体鲁棒。
 */
object FormParser {

    data class FieldHints(
        val username: List<String> = emptyList(),
        val password: List<String> = emptyList(),
        val captcha: List<String> = emptyList(),
        val content: List<String> = emptyList(),
        val title: List<String> = emptyList(),
        val recipient: List<String> = emptyList(),
    )

    fun snapshot(formEl: Element, pageUrl: String, hints: FieldHints = FieldHints()): FormSnapshot {
        val fields = LinkedHashMap<String, String>()

        var submitSeen = false
        formEl.select("input").forEach { input ->
            val name = input.attr("name").trim()
            if (name.isEmpty()) return@forEach
            when (input.attr("type").lowercase()) {
                "checkbox", "radio" -> if (input.hasAttr("checked")) fields[name] = input.attr("value")
                "button", "file", "image", "reset" -> Unit
                "submit" -> if (!submitSeen) { fields[name] = input.attr("value"); submitSeen = true }
                else -> fields[name] = input.attr("value")
            }
        }
        formEl.select("textarea").forEach { t ->
            val name = t.attr("name").trim()
            if (name.isNotEmpty()) fields[name] = t.text()
        }
        formEl.select("select").forEach { s ->
            val name = s.attr("name").trim()
            if (name.isEmpty()) return@forEach
            val chosen = s.selectFirst("option[selected]") ?: s.selectFirst("option") ?: return@forEach
            fields[name] = chosen.attr("value").ifEmpty { chosen.text() }
        }

        val action = formEl.absUrl("action").ifEmpty { formEl.attr("action") }.ifEmpty { pageUrl }

        return FormSnapshot(
            action = action,
            method = formEl.attr("method").uppercase().ifEmpty { "POST" },
            fields = fields,
            usernameField = fieldName(formEl, hints.username),
            passwordField = fieldName(formEl, hints.password),
            captchaField = fieldName(formEl, hints.captcha),
            contentField = fieldName(formEl, hints.content),
            titleField = fieldName(formEl, hints.title),
            recipientField = fieldName(formEl, hints.recipient),
        )
    }

    /** 从文档候选选择器定位表单元素。 */
    fun findForm(doc: org.jsoup.nodes.Document, formCandidates: List<String>, fallbackFilter: ((Element) -> Boolean)? = null): Element? {
        val found = doc.firstBy(formCandidates)
        if (found != null) return found
        val forms = doc.select("form")
        return forms.firstOrNull { fallbackFilter?.invoke(it) == true } ?: forms.firstOrNull()
    }

    /**
     * 操作确认页表单定位（方案 §6.6 购买等）：优先 action 含关键词（如 buytopic）的表单；
     * 兜底取第一个含提交按钮、且非登录（无密码框）非搜索的表单——
     * 页头搜索框常排在 DOM 更前面，按「第一个表单」选取会误提交到 search.php。
     */
    fun findConfirmForm(doc: org.jsoup.nodes.Document, actionKeyword: String = "buytopic"): Element? {
        doc.select("form").firstOrNull {
            it.attr("action").contains(actionKeyword) || it.absUrl("action").contains(actionKeyword)
        }?.let { return it }
        return doc.select("form").firstOrNull { f ->
            f.select("input[type='submit'], button").isNotEmpty() &&
                f.select("input[type='password']").isEmpty() &&
                !f.absUrl("action").contains("search.php")
        }
    }

    private fun fieldName(formEl: Element, candidates: List<String>): String? =
        formEl.firstBy(candidates)?.attr("name")?.trim()?.takeIf { it.isNotEmpty() }
}

/** 登录页解析（方案 §6.2.1 时序第 2 步）。 */
object LoginParser {

    fun parse(html: String, pageUrl: String, cfg: SelectorConfig): net.southplus.client.core.model.LoginFormData {
        val doc = org.jsoup.Jsoup.parse(html, pageUrl)
        val formEl = FormParser.findForm(
            doc,
            cfg.candidates("login_page.form"),
            fallbackFilter = { it.select("input[type='password']").isNotEmpty() },
        )
        val hints = FormParser.FieldHints(
            username = cfg.candidates("login_page.username_field"),
            password = cfg.candidates("login_page.password_field"),
            captcha = cfg.candidates("login_page.captcha_field"),
        )
        val snapshot = formEl
            ?.let { FormParser.snapshot(it, pageUrl, hints) }
            ?: FormSnapshot(action = pageUrl).also { s ->
                s.usernameField = hints.username.firstNotNullOfOrNull { selectorForName(it) }
                s.passwordField = hints.password.firstNotNullOfOrNull { selectorForName(it) }
                s.captchaField = hints.captcha.firstNotNullOfOrNull { selectorForName(it) }
            }

        // 验证码图：src 命中配置正则（与登录会话 Cookie 绑定，方案 §2.4-2）
        val captchaRe = cfg.string("login_page.captcha_img_regex")?.let {
            runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull()
        }
        if (captchaRe != null && snapshot.captchaImageUrl == null) {
            doc.select("img").firstOrNull { captchaRe.containsMatchIn(it.absSrc()) }?.let {
                snapshot.captchaImageUrl = it.absSrc()
            }
        }
        return net.southplus.client.core.model.LoginFormData(snapshot)
    }

    private val NAME_ATTR_RE = Regex("name='([^']+)'|name=\"([^\"]+)\"")

    /** 供无表单兜底：从 "input[name='xxx']" 选择器里提出字段名。 */
    private fun selectorForName(selector: String): String? =
        NAME_ATTR_RE.find(selector)?.let {
            it.groupValues[1].ifEmpty { it.groupValues[2] }
        }
}

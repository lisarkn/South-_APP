package net.southplus.client.core.network

import net.southplus.client.core.model.Challenge

/**
 * 挑战/异常页识别（方案 §6.1.3）：对每个 HTML 响应做特征分类。
 *
 * 误判防护（真机第一课）：
 *  - 游客浏览公开帖时，页面尾部/快捷回复区也会出现「您尚未登录」文案 ——
 *    因此 NOT_LOGGED_IN 必须同时出现【登录表单（password 输入框）】才成立；
 *  - 版块描述、认证版块名里到处是「认证」「权限」字样 ——
 *    NO_PERMISSION 只保留错误页专属的强特征长短语；
 *  - 南+整个站点在 Cloudflare 后面，【每个正常页面】都会被注入 JSD 脚本
 *    （`/cdn-cgi/challenge-platform/scripts/jsd/main.js`、`__CF$cv$params`）——
 *    这些是正常页特征，绝不能当挑战特征；挑战页专属的是
 *    「Just a moment」标题、cf-chl* 小部件、jschl_vc/__cf_chl_opt 表单域。
 */
object ChallengeDetector {

    private val cloudflarePatterns = listOf(
        "Just a moment",
        "cf-chl",
        "jschl_vc",
        "__cf_chl_opt",
        "cf-browser-verification",
        "Attention Required",
        "Checking your browser before accessing",
        "Enable JavaScript and cookies to continue",
    )

    private val maintenancePatterns = listOf(
        "论坛暂时关闭",
        "论坛维护中",
        "站点已关闭",
    )

    private val noPermissionPatterns = listOf(
        "您所在的用户组没有权限",
        "您无权访问本页面",
        "您无权访问本版块",
        "无权查看本版块",
        "无权浏览本版块",
        "浏览本论坛需要认证",
        // 2026-09 真机抓包校准：游客/未认证账号打开受限版块的两种真实错误页文案
        // ① 「对不起,本版块为认证版块,您没有权限查看此版块的内容!」
        // ② 「本版块为正规版块,只有注册会员才能进入!」
        "本版块为认证版块",
        "没有权限查看此版块",
        "只有注册会员才能进入",
    )

    private val notLoggedInPatterns = listOf(
        "您尚未登录",
        "您还没有登录",
        "请先登录后再",
        "还没有登录，无法",
    )

    private val passwordInputRegex =
        Regex("type\\s*=\\s*[\"']?password", RegexOption.IGNORE_CASE)

    /**
     * @param body HTML 全文
     * @param authCheck 登录页等场景传 false（登录页自身不能被判为「会话失效」）
     */
    fun detect(body: String, authCheck: Boolean = true): Challenge? {
        if (cloudflarePatterns.any { body.contains(it, ignoreCase = true) }) return Challenge.CLOUDFLARE
        if (maintenancePatterns.any { body.contains(it) }) return Challenge.MAINTENANCE
        if (authCheck) {
            val hasLoginForm = passwordInputRegex.containsMatchIn(body)
            if (noPermissionPatterns.any { body.contains(it) }) return Challenge.NO_PERMISSION
            if (notLoggedInPatterns.any { body.contains(it) } && hasLoginForm) {
                return Challenge.NOT_LOGGED_IN
            }
        }
        return null
    }
}

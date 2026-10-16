package net.southplus.client.core.network

/**
 * 搜索权限守卫（2026-09 真机抓包校准）：
 * 南+ 的 search.php 是【用户组权限】功能，游客与低等级用户组访问时返回
 * 提示页「用户组权限：你所属的用户组不能使用搜索功能」（页面同时带登录表单）。
 *
 * 旧行为的两层问题：
 *  1. 该页不含 NOT_LOGGED_IN 专属文案（「您尚未登录」等），ChallengeDetector 返回 null
 *     → SearchParser 对错误页解析出 0 条 → UI 显示「没有找到相关帖子」，误导用户以为没结果；
 *  2. 即使换成登录提示也不准确：已登录的低权限用户组同样被拒，真实原因是组权限而非会话。
 * 因此搜索走 authCheck=false 拉取，由本守卫显式分类。
 */
class SearchPermissionException(message: String) : ForumException(message)

object SearchGuard {

    /** 用户组权限拒绝页专属长短语（标题+正文均含，误判风险低）。 */
    private val groupDeniedPatterns = listOf(
        "不能使用搜索功能",
        "不能使用搜索",
    )

    fun isGroupDenied(html: String): Boolean =
        groupDeniedPatterns.any { html.contains(it) }

    /**
     * 对 search.php 响应做精确分类：
     *  - 组权限拒绝 + 页头未登录（headerLoggedIn=false）→ NOT_LOGGED_IN 挑战。
     *    组权限页同时也是游客被拒的返回页（游客组本就无搜索权限），会话失效后
     *   「搜索没法用」的真实原因是未登录，不能误报成「用户组权限不足」；
     *  - 组权限拒绝且页头已登录 → SearchPermissionException（UI 提供 Google 站内搜索替代）；
     *  - 其余挑战（真 CF 盾等）→ 原样抛 ChallengeException；
     *  - 正常结果页 → 静默通过。
     * headerLoggedIn 由调用方用 UserHeaderParser 解析响应页头得到；null 走旧分类。
     */
    fun ensureSearchable(html: String, url: String, headerLoggedIn: Boolean? = null) {
        if (isGroupDenied(html)) {
            if (headerLoggedIn == false) {
                throw ChallengeException(net.southplus.client.core.model.Challenge.NOT_LOGGED_IN, url)
            }
            throw SearchPermissionException(
                "南+ 的搜索功能对当前用户组不开放（游客与新注册用户组默认无此权限），" +
                    "登录并提升用户组后可用；可先用 Google 站内搜索代替。"
            )
        }
        ChallengeDetector.detect(html)?.let { throw ChallengeException(it, url) }
    }
}

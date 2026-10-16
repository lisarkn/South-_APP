package net.southplus.client.core.model

import androidx.compose.runtime.Immutable

/**
 * 领域模型（方案 §4.1 / §6.4.1）。
 * 解析层负责把 HTML 转换为这些模型；UI 与仓库只依赖本文件。
 */

@Immutable
data class UserBrief(
    val uid: String? = null,
    val name: String,
    val avatarUrl: String? = null,
)

/** 首页：分区（四大分区）→ 版块树（方案 §2.1） */
data class Section(
    val name: String,
    val boards: List<Board>,
)

@Immutable
data class Board(
    val fid: String,
    val name: String,
    val desc: String? = null,
    val isCertified: Boolean = false, // 认证论坛（方案 §2.1 内容可见性）
    val threadCount: String? = null,
    val postCount: String? = null,
    val children: List<Board> = emptyList(), // 行内子版块（网页版版块行内的小链接）
)

data class IndexPage(
    val sections: List<Section>,
    val onlineInfo: String? = null,
    val userHeader: UserHeader? = null,
    val recentReplies: List<RecentReply> = emptyList(), // 各版块「最后回复」汇总（网页版首页同款）
)

/** 首页各版块最后回复汇总出的全站最近动态条目。 */
@Immutable
data class RecentReply(
    val tid: String,
    val title: String, // 已去掉「Re:」前缀
    val boardName: String? = null,
    val author: String? = null,
    val time: String? = null,
    val atMillis: Long = 0L,
)

/** rss.php（全站最新 200 帖，游客可见）的一条。 */
@Immutable
data class RssItem(
    val tid: String,
    val title: String,
    val boardName: String? = null,
    val author: String? = null,
    val publishedAt: Long = 0L,
)

/** 页头登录态（从任意页面头部解析，方案附录 A #8） */
@Immutable
data class UserHeader(
    val loggedIn: Boolean,
    val userName: String? = null,
    val uid: String? = null,
    val avatarUrl: String? = null,
    val unreadPm: Int = 0,
    val credit: String? = null, // 论坛积分/余额（付费贴 §6.6 用）
)

@Immutable
data class ThreadItem(
    val tid: String,
    val title: String,
    val author: UserBrief? = null,
    val replies: Int = 0,
    val views: Int = 0,
    val lastReplyTime: String? = null,
    val lastReplyUser: String? = null,
    val isPinned: Boolean = false,
    val isDigest: Boolean = false,
    val isLocked: Boolean = false,
    val hasAttachment: Boolean = false,
    val isRead: Boolean = false,
)

data class ThreadListPage(
    val fid: String,
    val boardName: String? = null,
    val threads: List<ThreadItem>,
    val page: Int,
    val totalPages: Int,
)

/** 内容块（方案 §6.4.1 ContentBlock） */
@Immutable
sealed interface ContentBlock {
    data class Text(val html: String) : ContentBlock

    data class Image(
        val url: String,
        val isSmiley: Boolean = false,
        val isOffsite: Boolean = false,
        val width: Int? = null,
        val height: Int? = null,
    ) : ContentBlock

    data class Quote(
        val author: String?,
        val html: String,
    ) : ContentBlock

    /** 付费贴未购买态：售价 + 购买入口（方案 §6.6）；price 供 0 SP币自动购买判断 */
    data class PaidWall(
        val saleText: String?,
        val buyUrl: String?,
        val purchased: Boolean = false,
        val price: Int? = null,
    ) : ContentBlock

    data class AttachmentItem(val att: Attachment) : ContentBlock

    /** 网盘资源卡（方案 §6.4.5） */
    data class Resource(val link: ResourceLink) : ContentBlock
}

@Immutable
data class Attachment(
    val name: String,
    val url: String,
    val sizeText: String? = null,
    val isTorrent: Boolean = false,
)

/** 网盘链接 + 提取码（方案 §6.4.5 资源卡片） */
@Immutable
data class ResourceLink(
    val url: String,
    val host: String,
    val panName: String,
    val code: String? = null,
)

data class Post(
    val floor: Int,
    val author: UserBrief,
    val time: String?,
    val contentBlocks: List<ContentBlock>,
    val attachments: List<Attachment> = emptyList(),
    val isOp: Boolean = false,
    val pid: String? = null,
    val editUrl: String? = null, // 自己楼层的管理链接（编辑）
)

data class ThreadPage(
    val tid: String,
    val title: String,
    val fid: String? = null,
    val boardName: String? = null,
    val posts: List<Post>,
    val page: Int,
    val totalPages: Int,
    val replyUrl: String? = null,   // post.php?...action=reply...（方案 §6.5）
    val newTopicUrl: String? = null,
    val favorUrl: String? = null,   // 收藏入口（§6.5）
    val userHeader: UserHeader? = null,
)

data class SearchResultPage(
    val results: List<ThreadItem>,
    val page: Int,
    val totalPages: Int,
    val keyword: String,
)

/** 登录表单快照（方案 §6.2.1）：通用表单镜像策略，提交时回填全部原始字段 */
data class FormSnapshot(
    val action: String,           // 绝对 URL
    var method: String = "POST",
    val fields: LinkedHashMap<String, String> = LinkedHashMap(), // name -> value（含 hidden 默认值）
    var usernameField: String? = null,
    var passwordField: String? = null,
    var captchaField: String? = null,
    var captchaImageUrl: String? = null,
    var contentField: String? = null,   // 发帖/回复/私信正文 textarea
    var titleField: String? = null,     // 标题 input
    var recipientField: String? = null, // 私信收件人
) {
    fun overriding(overrides: List<Pair<String, String?>>): LinkedHashMap<String, String> {
        val map = LinkedHashMap(fields)
        overrides.forEach { (k, v) -> if (v != null) map[k] = v }
        return map
    }
}

data class LoginFormData(
    val form: FormSnapshot,
    val hints: List<String> = emptyList(),
)

/** 会话状态（方案 §2.4） */
sealed interface SessionState {
    data object Unknown : SessionState
    data class LoggedIn(val user: UserHeader) : SessionState
    data object Guest : SessionState
}

/** 挑战/异常页分类（方案 §6.1.3） */
enum class Challenge {
    CLOUDFLARE,
    NOT_LOGGED_IN,
    NO_PERMISSION,
    MAINTENANCE,
}

/** 用户资料页（u.php，方案附录 A #19） */
data class UserProfile(
    val uid: String,
    val name: String?,
    val avatarUrl: String? = null,
    val infoText: String? = null,
    val stats: Map<String, String> = emptyMap(),
)

/** 短消息（方案 §6.5） */
@Immutable
data class PmSummary(
    val mid: String,
    val fromUser: String?,
    val subject: String,
    val time: String? = null,
    val isUnread: Boolean = false,
)

data class PmListPage(
    val items: List<PmSummary>,
    val page: Int,
    val totalPages: Int,
)

data class PmDetail(
    val mid: String,
    val fromUser: String?,
    val subject: String?,
    val html: String,
    val time: String? = null,
    val replyForm: FormSnapshot? = null,
)

/** 收藏夹条目（方案 §6.5） */
@Immutable
data class FavorEntry(
    val tid: String,
    val title: String,
    val removeUrl: String? = null,
)

/**
 * 「我的」用户中心列表条目（u.php?action-topic/post/favor/friend/trade 与
 * 论坛任务插件页，2026-09 真机校准）。行内容形态不一，kind 决定点击跳转。
 */
@Immutable
data class UserListItem(
    val title: String,
    val href: String? = null,
    val kind: Kind = Kind.GENERIC,
    val tid: String? = null,   // THREAD：read.php / job.php?action-topost 的主题
    val pid: String? = null,   // THREAD：topost 链接里的楼层
    val fid: String? = null,   // GENERIC：thread.php?fid 版块（收藏的版块行）
    val uid: String? = null,   // USER：好友行的用户
    val avatarUrl: String? = null,
    val meta: String? = null,  // 版块名/日期等行内次要信息
    val author: UserBrief? = null,
    // 论坛任务动作（2026-10 校准：申请 actions=job / 领取奖励 actions=job2 的 AJAX 链接）
    val actionUrl: String? = null,
    val actionLabel: String? = null,
) {
    enum class Kind { THREAD, USER, GENERIC }
}

/** 用户中心列表页（我的主题/回复/好友/商品/任务通用） */
data class UserListPage(
    val items: List<UserListItem>,
    val nextUrl: String? = null,
    val userHeader: UserHeader? = null,
    /** 站点提示信息页文案（任务接受结果等 engine message 页解析不出列表时透出） */
    val notice: String? = null,
)

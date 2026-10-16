package net.southplus.client.ui.search

import net.southplus.client.core.model.IndexPage

/** 高级搜索的一个枚举选项（value 为站点表单原始取值）。 */
data class SearchOption(val value: String, val label: String)

/**
 * 站点搜索表单值域（phpwind 8 原始取值；表单字段名与默认值 all/lastpost/DESC
 * 来自 2026-10 真机表单抓包校准，sch_time 秒数窗口为 phpwind 8 标准值）。
 */
object SearchOptions {
    val TIME = listOf(
        SearchOption("all", "不限时间"),
        SearchOption("86400", "一天内"),
        SearchOption("604800", "一周内"),
        SearchOption("2592000", "一月内"),
        SearchOption("7776000", "三月内"),
    )

    val ORDER = listOf(
        SearchOption("lastpost", "最后发表"),
        SearchOption("postdate", "发布时间"),
        SearchOption("replies", "回复数"),
        SearchOption("hits", "浏览次数"),
    )
}

/** 高级搜索过滤条件；label 用于结果页与选择器展示。 */
data class SearchFilter(
    val fid: String? = null,
    val boardLabel: String = "全站",
    val schTime: String = "all",
    val timeLabel: String = "不限时间",
    val orderway: String = "lastpost",
    val orderLabel: String = "最后发表",
) {
    val isDefault: Boolean get() = fid == null && schTime == "all" && orderway == "lastpost"

    /** 结果页顶部的条件摘要（有自定义条件时才展示）。 */
    fun summary(): String =
        listOfNotNull(
            boardLabel.takeIf { fid != null },
            timeLabel.takeIf { schTime != "all" },
            orderLabel.takeIf { orderway != "lastpost" },
        ).joinToString(" · ")

    /** 版块选择器条目（首页分区展开后的平铺列表）。 */
    data class BoardChoice(val fid: String, val name: String, val section: String)

    companion object {
        fun boardChoices(index: IndexPage): List<BoardChoice> =
            index.sections.flatMap { s -> s.boards.map { BoardChoice(it.fid, it.name, s.name) } }
    }
}

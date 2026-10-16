package net.southplus.client.core.tasks

import java.time.LocalDate
import net.southplus.client.core.data.ForumRepository
import net.southplus.client.core.model.SessionState
import net.southplus.client.core.model.UserListItem
import net.southplus.client.core.session.SessionManager
import net.southplus.client.core.settings.SettingsRepository
import net.southplus.client.core.util.runCatchingCancellable as runCatching

/**
 * 论坛任务自动签到（2026-10）：开启后应用启动时替用户完成任务中心的
 * 「每日签到 / 周签到」类任务——申请（actions=job）后复查一次，
 * 能领奖（actions=job2）直接领取。结果以摘要文案返回，由 UI 层提示。
 *
 * 时机控制：默认关闭；每天（本地时区）最多自动尝试一次，标记在成功
 * 取到任务页之后写入——首次抓取失败不算完成，当天再次启动会重试。
 */
object AutoCheckIn {

    private const val TASKS_PATH = "plugin.php?H_name-tasks.html"

    /** 纯函数：从任务卡里挑出可执行的签到任务动作（标题含「签到」且有 AJAX 入口）。 */
    fun plan(items: List<UserListItem>): List<Pair<String, String>> =
        items.filter { it.title.contains("签到") && it.actionUrl != null }
            .map { it.title to it.actionUrl!! }

    /**
     * 到期则执行一轮签到，返回给用户看的摘要；不需要执行（开关关闭/今日已试/
     * 未登录/任务页拉取失败）返回 null，静默跳过。
     */
    suspend fun runIfDue(
        settings: SettingsRepository,
        repo: ForumRepository,
        session: SessionManager,
    ): String? {
        val s = settings.snapshot()
        if (!s.autoCheckIn) return null
        val today = LocalDate.now().toEpochDay()
        if (s.lastAutoCheckInDay == today) return null

        // 未登录（或会话未知）时静默跳过：自动签到不该在游客态反复打站点
        if (session.state.value is SessionState.Unknown) {
            runCatching { session.checkSession() }
        }
        if (session.state.value !is SessionState.LoggedIn) return null

        val page = runCatching { repo.userPanel(TASKS_PATH, force = true) }.getOrNull() ?: return null
        settings.setLastAutoCheckInDay(today) // 页面已取到，今天不再自动重试

        var acted = false
        val results = mutableListOf<String>()
        // 第一轮：申请（每日/周签到类任务接受即打卡完成）
        val firstPass = plan(page.items)
        for ((title, url) in firstPass) {
            acted = true
            exec(repo, title, url, results)
        }
        // 第二轮：申请成功后奖励通常立即可领，复查一次把奖励也领掉
        if (firstPass.isNotEmpty()) {
            val applied = firstPass.map { it.first }.toSet()
            val reloaded = runCatching { repo.userPanel(TASKS_PATH, force = true) }.getOrNull()
            for ((title, url) in reloaded?.items.orEmpty().let(::plan)) {
                if (title in applied && url.contains("actions=job2")) {
                    acted = true
                    exec(repo, title, url, results)
                }
            }
        }
        return if (acted) results.joinToString("\n") else null
    }

    private suspend fun exec(repo: ForumRepository, title: String, url: String, out: MutableList<String>) {
        runCatching { repo.runTaskAction(url) }
            .onSuccess { msg -> out += "$title：$msg" }
            .onFailure { out += "$title：失败（${it.message}）" }
    }
}

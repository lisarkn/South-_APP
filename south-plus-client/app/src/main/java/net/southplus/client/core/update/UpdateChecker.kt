package net.southplus.client.core.update

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 应用内更新检查（方案 §3.1 / §11）：GitHub API 查 latest release，
 * 用户确认后跳转下载；无第三方 SDK。
 */
object UpdateChecker {

    const val RELEASES_URL = "https://api.github.com/repos/south-plus/southplus-client/releases/latest"

    data class Release(
        val version: String,
        val notes: String,
        val pageUrl: String,
    )

    suspend fun latest(client: OkHttpClient, apiUrl: String = RELEASES_URL): Release? =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(
                    Request.Builder().url(apiUrl)
                        .header("Accept", "application/vnd.github+json")
                        .build(),
                ).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    Release(
                        version = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: return@use null,
                        notes = Regex("\"body\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
                            ?.groupValues?.get(1)
                            ?.replace("\\n", "\n")
                            ?.replace("\\\"", "\"")
                            ?.take(600)
                            .orEmpty(),
                        pageUrl = Regex("\"html_url\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: apiUrl,
                    )
                }
            }.getOrNull()
        }

    /** 语义比较 vX.Y.Z；remote 更新则 true。 */
    fun isNewer(remote: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val r = parts(remote)
        val c = parts(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrNull(i) ?: 0
            val cv = c.getOrNull(i) ?: 0
            if (rv != cv) return rv > cv
        }
        return false
    }
}

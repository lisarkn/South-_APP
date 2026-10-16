package net.southplus.client.core.config

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.southplus.client.core.network.Mirror
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 远程静态配置（方案 §6.1.2 / §6.3「紧急热修」）：
 * 镜像清单、选择器配置托管在本项目 GitHub 仓库（纯静态文件，非自建服务器）。
 * 默认走 jsDelivr CDN（raw.githubusercontent.com 部分网络不可达），允许用户自定义清单 URL。
 */
object RemoteConfig {

    const val MIRRORS_URL = "https://cdn.jsdelivr.net/gh/south-plus/client-config@main/mirrors.json"
    const val SELECTORS_URL = "https://cdn.jsdelivr.net/gh/south-plus/client-config@main/selectors.yaml"

    suspend fun fetchText(rawClient: OkHttpClient, url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            rawClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    /**
     * 解析镜像清单 JSON：{"mirrors":[{"host":"...","label":"..."}]}
     * 用正则而非 org.json 解析，保证 JVM 单测可用。
     */
    fun parseMirrors(json: String): List<Mirror> {
        val out = mutableListOf<Mirror>()
        Regex("\\{[^{}]*\\}").findAll(json).forEach { obj ->
            val host = Regex("\"host\"\\s*:\\s*\"([^\"]+)\"").find(obj.value)?.groupValues?.get(1) ?: return@forEach
            val label = Regex("\"label\"\\s*:\\s*\"([^\"]+)\"").find(obj.value)?.groupValues?.get(1)
            if (host.isNotBlank()) out.add(Mirror(host, label ?: host))
        }
        return out
    }
}

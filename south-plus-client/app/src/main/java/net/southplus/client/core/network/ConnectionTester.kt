package net.southplus.client.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.southplus.client.core.model.Challenge
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class ConnectionResult(
    val status: Status,
    val ms: Long,
    val detail: String,
    val finalUrl: String? = null,
) {
    enum class Status(val label: String) {
        OK("可用"), DNS_ERROR("解析失败"), CONNECT_ERROR("连接失败"),
        TLS_ERROR("TLS 失败"), TIMEOUT("请求超时"), HTTP_ERROR("HTTP 错误"),
        CHALLENGE("需要网页验证"), MAINTENANCE("站点维护"), UNEXPECTED("非论坛首页"),
    }
    val ok: Boolean get() = status == Status.OK
}

/** A fresh GET with the same UA, cookies and DNS as browsing, without mirror failover. */
class ConnectionTester(private val baseClient: OkHttpClient) {
    suspend fun test(url: String, dns: Dns = baseClient.dns): ConnectionResult =
        withTimeoutOrNull(15_000) {
            // The caller resumes on Main (viewModelScope); evictAll() closes idle TLS
            // sockets, which is network I/O — keep it off the main thread.
            withContext(Dispatchers.IO) { execute(url, dns) }
        } ?: ConnectionResult(ConnectionResult.Status.TIMEOUT, 15_000, "检测超时，请切换镜像或 DNS 后重试")

    private suspend fun execute(url: String, dns: Dns): ConnectionResult {
        val pool = ConnectionPool()
        val client = baseClient.newBuilder()
            .connectionPool(pool) // A cached connection must not bypass the DNS under test.
            .cache(null)
            .dns(dns)
            .retryOnConnectionFailure(true)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
        val started = System.nanoTime()
        fun result(status: ConnectionResult.Status, detail: String, finalUrl: String? = null) =
            ConnectionResult(status, (System.nanoTime() - started) / 1_000_000, detail, finalUrl)
        try {
            return client.newCall(Request.Builder().url(url).get().header("Cache-Control", "no-cache").build())
                .awaitBody { response ->
                    val html = decodeHtml(response.peekBody(1024 * 1024).bytes())
                    val finalUrl = response.request.url.toString()
                    when (ChallengeDetector.detect(html, authCheck = false)) {
                        Challenge.CLOUDFLARE -> result(ConnectionResult.Status.CHALLENGE, "已连接站点，需在网页中完成验证", finalUrl)
                        Challenge.MAINTENANCE -> result(ConnectionResult.Status.MAINTENANCE, "站点正在维护，请切换镜像或稍后重试", finalUrl)
                        else -> when {
                            !response.isSuccessful -> result(ConnectionResult.Status.HTTP_ERROR, "HTTP ${response.code}", finalUrl)
                            response.request.url.host != url.toHttpUrl().host -> result(
                                ConnectionResult.Status.UNEXPECTED, "首页跳转到其他域名：${response.request.url.host}", finalUrl,
                            )
                            !isForumIndex(html) -> result(ConnectionResult.Status.UNEXPECTED, "收到页面，但未发现论坛版块链接", finalUrl)
                            else -> result(ConnectionResult.Status.OK, "论坛首页已加载", finalUrl)
                        }
                    }
                }.also { DiagLog.log("CHECK", "$url → ${it.status.label} / ${it.ms}ms / ${it.detail}") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            val status = when (e) {
                is UnknownHostException -> ConnectionResult.Status.DNS_ERROR
                is SSLException -> ConnectionResult.Status.TLS_ERROR
                is InterruptedIOException -> ConnectionResult.Status.TIMEOUT
                else -> ConnectionResult.Status.CONNECT_ERROR
            }
            return result(status, e.message ?: status.label).also {
                DiagLog.log("CHECK", "$url → ${it.status.label} / ${it.ms}ms / ${it.detail}")
            }
        } finally {
            pool.evictAll()
        }
    }

    private fun isForumIndex(html: String): Boolean = Jsoup.parse(html).select("a[href]").any {
        val path = it.attr("href").substringBefore('?').substringAfterLast('/')
        path == "thread.php" || path == "thread_new.php"
    }
}

internal fun decodeHtml(bytes: ByteArray): String {
    val raw = String(bytes, Charsets.UTF_8)
    val declared = Regex("charset=[\"']?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
    return if (declared == null) raw else runCatching { String(bytes, charset(declared)) }.getOrDefault(raw)
}

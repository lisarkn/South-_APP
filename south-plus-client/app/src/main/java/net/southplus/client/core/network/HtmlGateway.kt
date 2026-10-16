package net.southplus.client.core.network

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit
import net.southplus.client.core.model.Challenge
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.jsoup.Jsoup
import java.io.IOException

/**
 * 统一数据网关（方案 §4.1 HtmlGateway）：所有论坛 HTML/字节流请求的唯一出口。
 * 职责：镜像故障切换（§6.1.2）、挑战页检测（§6.1.3）、字符集嗅探。
 */
class HtmlGateway(
    client: okhttp3.OkHttpClient,
    private val mirrors: MirrorManager,
    private val scheme: String = "https",
    private val clientProvider: () -> okhttp3.OkHttpClient = { client },
) {

    private fun readClient() = clientProvider().newBuilder()
        .retryOnConnectionFailure(true)
        .connectTimeout(4, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed class Outcome {
        data class Ok(val body: String, val finalUrl: String) : Outcome()
        data class Challenged(val challenge: Challenge, val url: String) : Outcome()
        data class Error(val message: String) : Outcome()
    }

    /** GET 一个页面/资源路径（绝对 URL 或站点相对路径）。 */
    suspend fun fetch(
        path: String,
        authCheck: Boolean = true,
        headers: Map<String, String> = emptyMap(),
    ): Outcome = withTimeoutOrNull(45_000) {
        execute(path, "GET", null, headers, authCheck)
    } ?: Outcome.Error("连接超时，请在网络与连接中检测当前镜像与 DNS")

    /** POST 表单（登录/回复/发帖/购买等，方案 §6.2 / §6.5 / §6.6）。 */
    suspend fun postForm(
        path: String,
        form: Map<String, String?>,
        authCheck: Boolean = true,
        headers: Map<String, String> = emptyMap(),
    ): Outcome {
        val bodyBuilder = FormBody.Builder()
        form.forEach { (k, v) -> if (v != null) bodyBuilder.add(k, v) }
        val formBody = bodyBuilder.build()
        // Also prevent HTTP 503/307 follow-ups from replaying a submitted form.
        val once = object : RequestBody() {
            override fun contentType() = formBody.contentType()
            override fun contentLength() = formBody.contentLength()
            override fun writeTo(sink: BufferedSink) = formBody.writeTo(sink)
            override fun isOneShot() = true
        }
        return execute(path, "POST", once, headers, authCheck)
    }

    /** 验证码图片等二进制资源（与登录会话绑定，方案 §6.2.1）。 */
    suspend fun fetchBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val abs = absolutize(url) ?: return@withContext null
        runCatching {
            readClient().newCall(Request.Builder().url(abs).build()).awaitBody { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        }.getOrNull()
    }

    private fun absolutize(path: String): HttpUrl? =
        if (path.startsWith("http://") || path.startsWith("https://")) path.toHttpUrlOrNull()
        else "$scheme://${mirrors.current.host}/".toHttpUrlOrNull()?.resolve(path)

    private suspend fun execute(
        path: String,
        method: String,
        body: RequestBody?,
        headers: Map<String, String>,
        authCheck: Boolean,
    ): Outcome = withContext(Dispatchers.IO) {
        val initialMirror = mirrors.current
        val original = absolutize(path) ?: return@withContext Outcome.Error("无效地址")
        val known = mirrors.all()
        val isForum = known.any { mirror ->
            val origin = "$scheme://${mirror.host}/".toHttpUrlOrNull()
            origin?.host == original.host && (origin.port == original.port ||
                (origin.port == 443 && original.scheme == "http" && original.port == 80))
        }
        // Keep POSTs on their original form origin. Safe reads can visit each mirror
        // once, including absolute forum links, without changing global state on failure.
        val candidates = if (method == "GET" && isForum) {
            (listOf(initialMirror) + known).distinctBy { it.host }.mapNotNull { mirror ->
                "$scheme://${mirror.host}/".toHttpUrlOrNull()?.let { origin ->
                    mirror to original.newBuilder().scheme(origin.scheme).host(origin.host).port(origin.port).build()
                }
            }
        } else listOf(null to original)
        var last: Outcome = Outcome.Error("网络错误")
        var verification: Outcome.Challenged? = null
        val shortPath = original.encodedPath.take(80)
        for ((mirror, url) in candidates) {
            currentCoroutineContext().ensureActive()
            val builder = Request.Builder().url(url)
            if (method == "POST" && body != null) builder.post(body) else builder.get()
            headers.forEach { (k, v) -> builder.header(k, v) }
            val t0 = System.nanoTime()
            try {
                val requestClient = if (method == "GET") readClient()
                    else clientProvider().newBuilder().retryOnConnectionFailure(false).build()
                val resp = requestClient.newCall(builder.build()).awaitBody { response ->
                    Downloaded(response.code, response.request.url.toString(), response.body?.bytes())
                }
                val html = resp.bytes?.let(::decodeHtml).orEmpty()
                var challenge = ChallengeDetector.detect(html, authCheck)
                if (challenge == null && authCheck &&
                    resp.finalUrl.substringBefore('?').substringAfterLast('/').startsWith("login.php")) {
                    challenge = Challenge.NOT_LOGGED_IN
                }
                DiagLog.log("HTTP", "$method $shortPath → ${resp.code} " +
                    "${(resp.bytes?.size ?: 0) / 1024}KB/${(System.nanoTime() - t0) / 1_000_000}ms via ${url.host}" +
                    (challenge?.let { " 挑战:$it" } ?: ""))
                if (method != "POST" && (html.length > 2048 || shortPath.contains("job.php"))) {
                    DiagLog.rememberHtml(shortPath, html)
                }
                last = when {
                    challenge != null -> Outcome.Challenged(challenge, resp.finalUrl)
                    resp.code in 200..299 -> Outcome.Ok(html, resp.finalUrl)
                    else -> Outcome.Error("${url.host}：HTTP ${resp.code}")
                }
                when {
                    challenge == Challenge.CLOUDFLARE -> verification = last as Outcome.Challenged
                    challenge == Challenge.MAINTENANCE -> Unit
                    challenge != null -> return@withContext last
                    resp.code in 200..299 -> {
                        // A concurrent user selection takes precedence over this request.
                        if (mirror != null) mirrors.selectIfCurrent(initialMirror, mirror.host)
                        return@withContext last
                    }
                    resp.code != 403 && resp.code !in 500..599 -> return@withContext last
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                DiagLog.log("NET", "$method $shortPath IO失败: ${e.message} via ${url.host}")
                last = Outcome.Error("${url.host}：${e.message ?: "连接失败"}")
            }
        }
        verification ?: last
    }

    private data class Downloaded(val code: Int, val finalUrl: String, val bytes: ByteArray?)

}

/** 网关结果到领域异常的转换（供 ViewModel 展示）；open 供细分异常（如搜索组权限）继承。 */
open class ForumException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ChallengeException(val challenge: Challenge, val url: String) :
    Exception("页面返回异常状态: $challenge")

/** 将网关 Outcome 解包为 HTML 字符串；挑战页抛 ChallengeException，网络错误抛 ForumException。 */
fun HtmlGateway.Outcome.orThrow(): String = when (this) {
    is HtmlGateway.Outcome.Ok -> body
    is HtmlGateway.Outcome.Challenged -> throw ChallengeException(challenge, url)
    is HtmlGateway.Outcome.Error -> throw ForumException(message)
}

/** 尽量返回内容，挑战/错误返回 null（用于可降级场景）。 */
fun HtmlGateway.Outcome.orNull(): String? = (this as? HtmlGateway.Outcome.Ok)?.body

/** 从 HTML 里取 <title>（自检/调试用）。 */
fun htmlTitle(html: String): String = Jsoup.parse(html).title().trim()

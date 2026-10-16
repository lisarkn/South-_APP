package net.southplus.client.core.network

import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 图床 / 站外域名的请求头覆写规则（方案 §6.4.6 per-host 配置）。 */
data class ImageHostRule(
    val hostSuffix: String,
    val referer: String? = null,
    val userAgent: String? = null,
)

class ImageHeaderRules(private val entries: List<ImageHostRule> = emptyList()) {
    fun ruleFor(host: String): ImageHostRule? =
        entries.firstOrNull { host == it.hostSuffix || host.endsWith(".${it.hostSuffix}") }
}

/**
 * 论坛 HTTP 客户端（方案 §6.1.1 请求管线）：
 * RateLimit（串行+抖动）→ Header（UA/Referer/防盗链）→ 图床覆写 → OkHttp Core。
 * Coil 的 ImageLoader 与本实例共享 CookieJar 与拦截器（方案 §6.1.4）。
 */
class ForumHttpClient(
    cookieJar: CookieJar,
    mirrorHosts: () -> Set<String>,
    currentMirrorHome: () -> String,
    imageRules: () -> ImageHeaderRules = { ImageHeaderRules() },
    lowTraffic: () -> Boolean = { false },
    dns: Dns? = null,
    private val dnsSnapshot: () -> Dns = { dns ?: Dns.SYSTEM },
) {

    companion object {
        /** 常见 Android Chrome UA（方案 §6.1.1，随版本更新维护）。 */
        const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"
    }

    private val headerInterceptor = Interceptor { chain ->
        val request = chain.request()
        val host = request.url.host
        val toMirror = mirrorHosts().any { host == it || host.endsWith(".$it") }
        val builder = request.newBuilder()
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")

        if (toMirror) {
            // 论坛域统一 Referer，避免站内图片防盗链（方案 §6.1.1）
            builder.header("Referer", currentMirrorHome())
        } else {
            imageRules().ruleFor(host)?.let { rule ->
                rule.referer?.let { builder.header("Referer", it) }
                rule.userAgent?.let { builder.header("User-Agent", it) }
            }
        }

        if (lowTraffic()) {
            builder.header("Save-Data", "on")
        }
        chain.proceed(builder.build())
    }

    val rateLimiter = RateLimitInterceptor(mirrorHosts)

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .apply { dns?.let { dns(it) } }
        .addInterceptor(headerInterceptor)
        .addInterceptor(rateLimiter)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(true)
        .build()

    private val baseImageClient: OkHttpClient = baseClient.newBuilder().apply {
        retryOnConnectionFailure(true)
        interceptors().remove(rateLimiter)
        dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 12
            maxRequestsPerHost = 4
        })
    }.build()

    /** 测速/远程清单用的轻量客户端（不带论坛 Cookie 与限速）。 */
    private val baseRawClient: OkHttpClient = OkHttpClient.Builder()
        .apply { dns?.let { dns(it) } }
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    // Builders share pools/dispatchers; the DNS identity separates routes after a switch,
    // including connections that were busy when evictAll() was called.
    val client: OkHttpClient get() = baseClient.newBuilder().dns(dnsSnapshot()).build()
    val imageClient: OkHttpClient get() = baseImageClient.newBuilder().dns(dnsSnapshot()).build()
    val rawClient: OkHttpClient get() = baseRawClient.newBuilder().dns(dnsSnapshot()).build()

    fun evictConnections() {
        baseClient.connectionPool.evictAll()
        baseImageClient.connectionPool.evictAll()
        baseRawClient.connectionPool.evictAll()
    }
}

/** 供日志/自检。 */
fun OkHttpClient.describe(): String = "OkHttp($dispatcher.maxRequestsPerHost)"

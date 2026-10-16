package net.southplus.client.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** DNS only: target connectivity and certificate/hostname verification belong to OkHttp. */
class DohController(private val fallback: Dns = Dns.SYSTEM) {
    data class Config(
        val providerUrl: String = DEFAULT_PROVIDER,
        val preferIpv6: Boolean = false,
        val useDeviceCerts: Boolean = true,
    )

    data class DohState(val active: Boolean, val config: Config)

    private val _state = MutableStateFlow(DohState(false, Config()))
    val state = _state.asStateFlow()
    val active: Boolean get() = state.value.active

    // Each configuration owns its resolver and cache. In-flight queries from an old
    // configuration cannot repopulate the cache used by a new configuration.
    @Volatile private var resolver: Dns = fallback
    var onChanged: (() -> Unit)? = null

    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = resolver.lookup(hostname)
    }

    /** Capture a stable DNS identity so OkHttp cannot reuse another configuration's routes. */
    fun snapshot(): Dns = resolver

    @Synchronized
    fun setActive(v: Boolean) {
        if (v == active) return
        resolver = if (v) createResolver(state.value.config) else fallback
        _state.value = state.value.copy(active = v)
        onChanged?.invoke()
    }

    @Synchronized
    fun updateConfig(cfg: Config) {
        resolver = if (active) createResolver(cfg) else fallback
        _state.value = state.value.copy(config = cfg)
        onChanged?.invoke()
    }

    /** Isolated resolver for testing a provider without changing the active configuration. */
    fun createResolver(cfg: Config = state.value.config): Dns {
        val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .apply {
                if (cfg.useDeviceCerts) deviceCertsSsl()?.let { sslSocketFactory(it.first, it.second) }
            }
            .build()
        val upstream = DnsOverHttps.Builder()
            .client(client)
            .url(cfg.providerUrl.toHttpUrl())
            .systemDns(fallback) // Only bootstraps the DoH endpoint; never resolves the target.
            .includeIPv6(cfg.preferIpv6)
            .build()
        val cache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                cache[hostname]?.let { (ips, expiry) ->
                    if (System.nanoTime() < expiry) return ips
                }
                try {
                    val ips = upstream.lookup(hostname).distinct()
                        .sortedBy { if (cfg.preferIpv6 && it is Inet6Address) 0 else 1 }
                    if (ips.isEmpty()) throw UnknownHostException("DoH 未返回地址：$hostname")
                    // Short cache; no negative cache and no silently mixed system answers.
                    if (cache.size >= 128) cache.clear()
                    cache[hostname] = ips to (System.nanoTime() + TimeUnit.SECONDS.toNanos(60))
                    DiagLog.log("DNS", "$hostname 经 ${labelOf(cfg.providerUrl)} 解析：${ips.map { it.hostAddress }}")
                    return ips
                } catch (e: Exception) {
                    DiagLog.log("DNS", "$hostname DoH 解析失败：${e.javaClass.simpleName}")
                    throw UnknownHostException("DoH 解析失败：$hostname（${labelOf(cfg.providerUrl)}）").apply { initCause(e) }
                }
            }
        }
    }

    private fun deviceCertsSsl(): Pair<SSLSocketFactory, X509TrustManager>? = runCatching {
        val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        ctx.socketFactory to tm
    }.getOrNull()

    companion object {
        const val DEFAULT_PROVIDER = "https://doh.pub/dns-query"
        val PRESETS = listOf(
            "腾讯 DNSPod" to DEFAULT_PROVIDER,
            "阿里 DNS" to "https://dns.alidns.com/dns-query",
            "阿里 DNS 223.5.5.5" to "https://223.5.5.5/dns-query",
            "阿里 DNS 223.6.6.6" to "https://223.6.6.6/dns-query",
        )

        fun normalizeProvider(value: String): String? = value.trim().toHttpUrlOrNull()
            ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null }
            ?.toString()

        fun labelOf(url: String): String = PRESETS.firstOrNull { it.second == url }?.first
            ?: url.removePrefix("https://").trimEnd('/')
    }
}

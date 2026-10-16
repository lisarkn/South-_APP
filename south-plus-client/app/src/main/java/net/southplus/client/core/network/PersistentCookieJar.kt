package net.southplus.client.core.network

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Cookie 持久化介质抽象（便于单测注入内存实现，方案 §6.9）。 */
interface CookieStore {
    fun saveAll(lines: List<String>)
    fun loadAll(): List<String>
    fun clear()
}

/** SharedPreferences 持久化。凭据本体走 EncryptedSharedPreferences（core/session）。 */
class PrefsCookieStore(context: Context) : CookieStore {
    private val prefs = context.getSharedPreferences("sp_cookies", Context.MODE_PRIVATE)

    override fun saveAll(lines: List<String>) {
        prefs.edit().putStringSet("cookies", lines.toSet()).apply()
    }

    override fun loadAll(): List<String> =
        prefs.getStringSet("cookies", emptySet())?.toList().orEmpty()

    override fun clear() {
        prefs.edit().remove("cookies").apply()
    }
}

class InMemoryCookieStore : CookieStore {
    private var lines: List<String> = emptyList()
    override fun saveAll(lines: List<String>) { this.lines = lines }
    override fun loadAll(): List<String> = lines
    override fun clear() { lines = emptyList() }
}

/**
 * 持久化 CookieJar（方案 §6.1.1 / §2.4-3）。
 * 会话 Cookie（形如 *_winduser）跨进程重启保留；多镜像域名按 Cookie 自身 domain 归档。
 */
class PersistentCookieJar(private val store: CookieStore) : CookieJar {

    // domainKey -> (cookieName -> Cookie)
    private val byDomain = LinkedHashMap<String, LinkedHashMap<String, Cookie>>()
    private val lock = Any()

    init {
        synchronized(lock) {
            store.loadAll().forEach { line ->
                runCatching { decode(line) }.getOrNull()?.let { c ->
                    byDomain.getOrPut(domainKeyOf(c)) { LinkedHashMap() }[c.name] = c
                }
            }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            cookies.forEach { c ->
                byDomain.getOrPut(domainKeyOf(c)) { LinkedHashMap() }[c.name] = c
            }
            persist()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        byDomain.values
            .asSequence()
            .flatMap { it.values }
            .filter { it.matches(url) }
            .sortedByDescending { it.path.length }
            .toList()
    }

    /** 供系统 DownloadManager 附加 Cookie 头（方案 §6.7）。 */
    fun cookieHeader(url: HttpUrl): String =
        loadForRequest(url).joinToString("; ") { "${it.name}=${it.value}" }

    /** 是否持有论坛会话 Cookie（*_winduser，方案 §2.4-3）。 */
    fun hasSessionCookie(): Boolean = synchronized(lock) {
        byDomain.values.any { bucket -> bucket.keys.any { it.endsWith("winduser") } }
    }

    /** WebView 过盾后回灌 Cookie（方案 §6.1.3：Cookie 落回 CookieJar）。 */
    fun ingestHeader(header: String, url: HttpUrl) {
        header.split(";").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@forEach
            val name = pair.substring(0, idx).trim()
            val value = pair.substring(idx + 1).trim()
            if (name.isEmpty()) return@forEach
            runCatching {
                val c = Cookie.Builder().name(name).value(value).hostOnlyDomain(url.host).build()
                synchronized(lock) {
                    byDomain.getOrPut(url.host) { LinkedHashMap() }[name] = c
                    persist()
                }
            }
        }
    }

    fun clear() = synchronized(lock) {
        byDomain.clear()
        store.clear()
    }

    private fun persist() {
        store.saveAll(byDomain.values.flatMap { it.values }.map { encode(it) })
    }

    private fun domainKeyOf(c: Cookie): String = c.domain.trimStart('.')

    private fun encode(c: Cookie): String = listOf(
        c.name, c.value, c.domain, c.path,
        c.expiresAt.toString(), c.secure.toString(), c.hostOnly.toString(),
    ).joinToString("\u0001")

    private fun decode(line: String): Cookie? {
        val p = line.split("\u0001")
        if (p.size < 7) return null
        val b = Cookie.Builder().name(p[0]).value(p[1]).path(p[3])
        if (p[6].toBoolean()) b.hostOnlyDomain(p[2]) else b.domain(p[2])
        p[4].toLongOrNull()?.let { b.expiresAt(it) }
        if (p[5].toBoolean()) b.secure()
        return b.build()
    }
}

package net.southplus.client.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 一个镜像域名（内容同源，方案 §2.2）。 */
data class Mirror(val host: String, val label: String = host)

/**
 * 多镜像管理：内置与远程清单合并、当前选择与成功后的故障切换。
 * 上线前以站内公告实际公布的清单为准逐一核验（方案 §2.2）。
 */
class MirrorManager(
    mirrors: List<Mirror> = DEFAULT_MIRRORS,
    savedHost: String? = null,
) {
    private val byHost = LinkedHashMap<String, Mirror>()
    @Volatile var current: Mirror = mirrors.first(); private set
    private val _currentState = MutableStateFlow(current)
    val currentState = _currentState.asStateFlow()

    /** 当前镜像变化时回调（用于持久化用户偏好）。 */
    @Volatile var onCurrentChanged: ((Mirror) -> Unit)? = null

    init {
        mirrors.forEach { byHost[it.host] = it }
        current = byHost[savedHost] ?: mirrors.first()
        _currentState.value = current
    }

    val hosts: Set<String> get() = synchronized(this) { byHost.keys.toSet() }
    val dnsHosts: Set<String> get() = hosts.mapNotNull { "https://$it/".toHttpUrlOrNull()?.host }.toSet()

    @Synchronized
    fun all(): List<Mirror> = byHost.values.toList()

    @Synchronized
    fun addOrUpdate(mirror: Mirror) {
        byHost[mirror.host] = mirror
    }

    /** 用户手动指定镜像；未知 host 返回 false。 */
    @Synchronized
    fun select(host: String): Boolean {
        val m = byHost[host] ?: return false
        if (m.host != current.host) {
            current = m
            _currentState.value = m
            onCurrentChanged?.invoke(m)
        }
        return true
    }

    @Synchronized
    fun selectIfCurrent(expected: Mirror, host: String): Boolean =
        current == expected && select(host)

    /** 故障切换：切到下一镜像。返回 false 表示只有一个镜像。 */
    @Synchronized
    fun switchNext(): Boolean {
        val list = byHost.values.toList()
        if (list.size <= 1) return false
        val idx = list.indexOfFirst { it.host == current.host }
        current = list[(idx + 1).mod(list.size)]
        _currentState.value = current
        onCurrentChanged?.invoke(current)
        return true
    }

    companion object {
        /** Accept a host or HTTPS origin, never a path, credentials or a cleartext URL. */
        fun normalizeHost(value: String): String? {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return null
            val url = (if ("://" in trimmed) trimmed else "https://$trimmed").toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.encodedPath != "/" || url.query != null || url.fragment != null) return null
            val host = if (':' in url.host) "[${url.host}]" else url.host
            return if (url.port == 443) host else "$host:${url.port}"
        }

        /** 内置镜像清单（远程清单可合并扩充，见 core/config/RemoteConfig）。 */
        val DEFAULT_MIRRORS = listOf(
            Mirror("www.south-plus.net", "主域"),
            Mirror("www.blue-plus.net", "蓝镜像"),
            Mirror("www.white-plus.net", "白镜像"),
            Mirror("www.summer-plus.net", "夏镜像"),
            Mirror("www.snow-plus.net", "冬镜像"),
            Mirror("www.north-plus.net", "北镜像"),
        )
    }
}

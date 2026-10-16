package net.southplus.client

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import android.content.Context
import coil.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import net.southplus.client.core.config.RemoteConfig
import net.southplus.client.core.config.SelectorConfig
import net.southplus.client.core.data.ForumRepository
import net.southplus.client.core.database.AppDatabase
import net.southplus.client.core.download.AttachmentDownloader
import net.southplus.client.core.links.LinkRouter
import net.southplus.client.core.links.ResourceExtractor
import net.southplus.client.core.network.DohController
import net.southplus.client.core.network.ForumHttpClient
import net.southplus.client.core.network.HtmlGateway
import net.southplus.client.core.network.MirrorManager
import net.southplus.client.core.network.PersistentCookieJar
import net.southplus.client.core.network.PrefsCookieStore
import net.southplus.client.core.session.SessionManager
import net.southplus.client.core.settings.SettingsRepository

/**
 * 轻量手工装配（方案 §4.3 的 Hilt 简化为 ServiceLocator，
 * 保持分层不变；规模变大后可平移到 Hilt 模块）。
 */
object AppGraph {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: SettingsRepository; private set
    lateinit var db: AppDatabase; private set
    lateinit var cookieJar: PersistentCookieJar; private set
    lateinit var mirrors: MirrorManager; private set
    lateinit var http: ForumHttpClient; private set
    lateinit var gateway: HtmlGateway; private set
    lateinit var session: SessionManager; private set
    lateinit var repo: ForumRepository; private set
    @Volatile lateinit var router: LinkRouter; private set
    @Volatile lateinit var extractor: ResourceExtractor; private set
    lateinit var downloader: AttachmentDownloader; private set
    lateinit var imageLoader: ImageLoader; private set
    val doh = DohController()

    /** 选择器配置：可被远程热修替换（方案 §6.3，默认关闭）。 */
    @Volatile
    lateinit var selectors: SelectorConfig
        private set

    sealed interface Initialization {
        data object Loading : Initialization
        data object Ready : Initialization
        data class Failed(val message: String) : Initialization
    }
    private val _initialization = MutableStateFlow<Initialization>(Initialization.Loading)
    val initialization = _initialization.asStateFlow()
    @Volatile private var lowTraffic = false
    @Volatile private var initialized = false

    suspend fun awaitReady() {
        val result = initialization.first { it !is Initialization.Loading }
        check(result is Initialization.Ready) { (result as Initialization.Failed).message }
    }

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        _initialization.value = Initialization.Loading
        val app = context.applicationContext
        if (!::settings.isInitialized) settings = SettingsRepository(app)
        scope.launch(Dispatchers.IO) {
            runCatching {
                selectors = SelectorConfig.fromAssets(app)
                val saved = settings.snapshot()
                settings.settings.first { it == saved }
                lowTraffic = saved.lowTraffic
                db = AppDatabase.get(app)
                cookieJar = PersistentCookieJar(PrefsCookieStore(app))
                val savedHost = saved.mirrorHost?.let(MirrorManager::normalizeHost)
                mirrors = MirrorManager(savedHost = savedHost)
                saved.customMirror?.let(MirrorManager::normalizeHost)?.let { host ->
                    mirrors.addOrUpdate(net.southplus.client.core.network.Mirror(host, "自定义"))
                    if (savedHost == host) mirrors.select(host)
                }
                mirrors.onCurrentChanged = { m -> scope.launch { runCatching { settings.setMirrorHost(m.host) } } }
                doh.updateConfig(DohController.Config(
                    DohController.normalizeProvider(saved.dohProviderUrl) ?: DohController.DEFAULT_PROVIDER,
                    saved.dohPreferIpv6, saved.dohUseDeviceCerts,
                ))
                doh.setActive(saved.dohAutoEnable)
                http = ForumHttpClient(
                    cookieJar = cookieJar,
                    mirrorHosts = { mirrors.dnsHosts },
                    currentMirrorHome = { "https://${mirrors.current.host}/" },
                    imageRules = { selectors.headers },
                    lowTraffic = { lowTraffic },
                    dns = doh.dns,
                    dnsSnapshot = doh::snapshot,
                )
                doh.onChanged = { http.evictConnections() }
                imageLoader = ImageLoader.Builder(app)
                    .callFactory(okhttp3.Call.Factory { request -> http.imageClient.newCall(request) })
                    .crossfade(false)
                    .build()
                gateway = HtmlGateway(http.client, mirrors, clientProvider = { http.client })
                session = SessionManager(app, gateway, cookieJar, { selectors }, onAuthChanged = { db.cacheDao().clear() })
                repo = ForumRepository(gateway, mirrors, db, session, settings, { selectors })
                refreshMirrorRoutes()
                extractor = ResourceExtractor(selectors)
                downloader = AttachmentDownloader(app, cookieJar, { mirrors.current.host })
                // Open/migrate Room off Main before screens can query it.
                db.cacheDao().invalidateByVersion(selectors.schemaVersion)
                _initialization.value = Initialization.Ready
                scope.launch { settings.settings.collect { lowTraffic = it.lowTraffic } }
                scope.launch { runCatching { refreshRemoteConfigs() } }
            }.onFailure {
                initialized = false
                _initialization.value = Initialization.Failed(it.message ?: "初始化失败")
            }
        }
    }

    /** 给 WebView 用的 UA（与 OkHttp 一致，方案 §3.2）。 */
    fun userAgent(): String = ForumHttpClient.UA

    fun refreshMirrorRoutes() {
        router = LinkRouter.default(selectors, mirrors.dnsHosts)
    }

    /**
     * 远程配置低频校验（每 24h 一次，方案 §6.1.2）：
     * 选择器热修（默认关闭）与镜像清单合并，均走 jsDelivr CDN。
     */
    private suspend fun refreshRemoteConfigs() {
        val s = runCatching { settings.snapshot() }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        if (now - s.lastConfigFetch < 24 * 3600_000L) return

        if (s.remoteSelectors) {
            val url = s.configUrl.ifBlank { RemoteConfig.SELECTORS_URL }
            RemoteConfig.fetchText(http.rawClient, url)?.let { text ->
                runCatching { SelectorConfig.fromYaml(text) }.getOrNull()?.let {
                    selectors = it
                    refreshMirrorRoutes()
                    extractor = ResourceExtractor(it)
                    runCatching { db.cacheDao().invalidateByVersion(it.schemaVersion) }
                    runCatching { settings.setLastConfigFetch(now) }
                    return
                }
            }
        }
        val mirrorUrl = s.configUrl.ifBlank { RemoteConfig.MIRRORS_URL }
        RemoteConfig.fetchText(http.rawClient, mirrorUrl)?.let { json ->
            RemoteConfig.parseMirrors(json).forEach { mirrors.addOrUpdate(it) }
            refreshMirrorRoutes()
        }
        runCatching { settings.setLastConfigFetch(now) }
    }
}

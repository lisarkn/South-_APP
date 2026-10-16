package net.southplus.client.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 设置快照（方案 §6.10）。 */
data class AppSettings(
    val mirrorHost: String? = null,
    val customMirror: String? = null,
    val themeMode: Int = 0,        // 0 跟随系统 / 1 浅色 / 2 深色
    val lowTraffic: Boolean = false,
    val imageWifiOnly: Boolean = true,
    val blurOffsiteImages: Boolean = false,
    val readerFontScale: Float = 1.0f,
    val keepPassword: Boolean = false,
    val openInCustomTabs: Boolean = true,
    val remoteSelectors: Boolean = false, // 选择器远程热修（默认关闭，§6.3）
    val autoBuyFree: Boolean = true,      // 0 SP币付费贴自动购买（免确认）
    val autoCheckIn: Boolean = false,     // 论坛任务自动签到（每日/周签到，默认关闭）
    val lastAutoCheckInDay: Long = 0,     // 上次自动签到的 epoch day（每天最多尝试一次）
    val configUrl: String = "",
    val lastConfigFetch: Long = 0,
    // Retain the existing preference key; one persistent switch now controls DoH.
    val dohAutoEnable: Boolean = false,
    val dohProviderUrl: String = "https://doh.pub/dns-query",
    val dohUseDeviceCerts: Boolean = true,
    val dohPreferIpv6: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    private object K {
        val mirrorHost = stringPreferencesKey("mirror_host")
        val customMirror = stringPreferencesKey("custom_mirror")
        val themeMode = intPreferencesKey("theme_mode")
        val lowTraffic = booleanPreferencesKey("low_traffic")
        val imageWifiOnly = booleanPreferencesKey("image_wifi_only")
        val blurOffsiteImages = booleanPreferencesKey("blur_offsite")
        val readerFontScale = floatPreferencesKey("reader_font_scale")
        val keepPassword = booleanPreferencesKey("keep_password")
        val openInCustomTabs = booleanPreferencesKey("open_custom_tabs")
        val remoteSelectors = booleanPreferencesKey("remote_selectors")
        val autoBuyFree = booleanPreferencesKey("auto_buy_free")
        val autoCheckIn = booleanPreferencesKey("auto_check_in")
        val lastAutoCheckInDay = longPreferencesKey("last_auto_checkin_day")
        val configUrl = stringPreferencesKey("config_url")
        val lastConfigFetch = longPreferencesKey("last_config_fetch")
        val dohAutoEnable = booleanPreferencesKey("doh_auto_enable")
        val dohProviderUrl = stringPreferencesKey("doh_provider_url")
        val dohUseDeviceCerts = booleanPreferencesKey("doh_use_device_certs")
        val dohPreferIpv6 = booleanPreferencesKey("doh_prefer_ipv6")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val persisted: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            mirrorHost = p[K.mirrorHost],
            customMirror = p[K.customMirror],
            themeMode = p[K.themeMode] ?: 0,
            lowTraffic = p[K.lowTraffic] ?: false,
            imageWifiOnly = p[K.imageWifiOnly] ?: true,
            blurOffsiteImages = p[K.blurOffsiteImages] ?: false,
            readerFontScale = p[K.readerFontScale] ?: 1.0f,
            keepPassword = p[K.keepPassword] ?: false,
            openInCustomTabs = p[K.openInCustomTabs] ?: true,
            remoteSelectors = p[K.remoteSelectors] ?: false,
            autoBuyFree = p[K.autoBuyFree] ?: true,
            autoCheckIn = p[K.autoCheckIn] ?: false,
            lastAutoCheckInDay = p[K.lastAutoCheckInDay] ?: 0,
            configUrl = p[K.configUrl] ?: "",
            lastConfigFetch = p[K.lastConfigFetch] ?: 0,
            dohAutoEnable = p[K.dohAutoEnable] ?: false,
            dohProviderUrl = p[K.dohProviderUrl] ?: "https://doh.pub/dns-query",
            dohUseDeviceCerts = p[K.dohUseDeviceCerts] ?: true,
            dohPreferIpv6 = p[K.dohPreferIpv6] ?: false,
        )
    }

    val settings = persisted.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    val themeMode = settings.map { it.themeMode }.distinctUntilChanged()
    // Exclude unrelated network/cache preferences from reader recomposition.
    private fun forReader(s: AppSettings) = AppSettings(
        readerFontScale = s.readerFontScale, imageWifiOnly = s.imageWifiOnly,
        blurOffsiteImages = s.blurOffsiteImages, autoBuyFree = s.autoBuyFree,
        openInCustomTabs = s.openInCustomTabs,
    )
    val currentReaderSettings: AppSettings get() = forReader(settings.value)
    val readerSettings = settings.map(::forReader).distinctUntilChanged()
    // Link opening only reads the Custom Tabs switch; keep unrelated preference
    // writes from recomposing the whole home screen.
    private fun forLink(s: AppSettings) = AppSettings(openInCustomTabs = s.openInCustomTabs)
    val currentLinkSettings: AppSettings get() = forLink(settings.value)
    val linkSettings = settings.map(::forLink).distinctUntilChanged()

    suspend fun snapshot(): AppSettings = persisted.first()

    suspend fun setMirrorHost(host: String?) = edit { it[K.mirrorHost] = host.orEmpty() }
    suspend fun setCustomMirror(host: String?) = edit { it[K.customMirror] = host.orEmpty() }
    suspend fun setThemeMode(mode: Int) = edit { it[K.themeMode] = mode }
    suspend fun setLowTraffic(v: Boolean) = edit { it[K.lowTraffic] = v }
    suspend fun setImageWifiOnly(v: Boolean) = edit { it[K.imageWifiOnly] = v }
    suspend fun setBlurOffsite(v: Boolean) = edit { it[K.blurOffsiteImages] = v }
    suspend fun setReaderFontScale(v: Float) = edit { it[K.readerFontScale] = v }
    suspend fun setKeepPassword(v: Boolean) = edit { it[K.keepPassword] = v }
    suspend fun setOpenInCustomTabs(v: Boolean) = edit { it[K.openInCustomTabs] = v }
    suspend fun setRemoteSelectors(v: Boolean) = edit { it[K.remoteSelectors] = v }
    suspend fun setAutoBuyFree(v: Boolean) = edit { it[K.autoBuyFree] = v }
    suspend fun setAutoCheckIn(v: Boolean) = edit { it[K.autoCheckIn] = v }
    suspend fun setLastAutoCheckInDay(day: Long) = edit { it[K.lastAutoCheckInDay] = day }
    suspend fun setConfigUrl(url: String) = edit { it[K.configUrl] = url }
    suspend fun setLastConfigFetch(at: Long) = edit { it[K.lastConfigFetch] = at }
    suspend fun setDohAutoEnable(v: Boolean) = edit { it[K.dohAutoEnable] = v }
    suspend fun setDohProviderUrl(url: String) = edit { it[K.dohProviderUrl] = url }
    suspend fun setDohUseDeviceCerts(v: Boolean) = edit { it[K.dohUseDeviceCerts] = v }
    suspend fun setDohPreferIpv6(v: Boolean) = edit { it[K.dohPreferIpv6] = v }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}

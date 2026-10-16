package net.southplus.client.core.session

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import net.southplus.client.core.config.SelectorConfig
import net.southplus.client.core.model.LoginFormData
import net.southplus.client.core.model.SessionState
import net.southplus.client.core.model.UserHeader
import net.southplus.client.core.network.HtmlGateway
import net.southplus.client.core.network.PersistentCookieJar
import net.southplus.client.core.network.orNull
import net.southplus.client.core.network.orThrow
import net.southplus.client.core.parser.LoginParser
import net.southplus.client.core.parser.UserHeaderParser
import org.jsoup.Jsoup
import java.security.MessageDigest

/** 登录结果（方案 §6.2.1：成功 / 失败文案）。 */
sealed class LoginResult {
    data class Success(val user: UserHeader) : LoginResult()
    data class Failure(val message: String) : LoginResult()
}

/**
 * 会话与凭据（方案 §6.2）：
 *  - 登录走「通用表单镜像」：解析 login.php 表单 → 回填凭据/验证码 → POST；
 *  - 密码预处理算法由 selectors.yaml 的 login_page.password_transform 配置
 *    （none | md5 | md5_upper），与站点算法同步演化（方案 §8 关键难点）；
 *  - 凭据存 EncryptedSharedPreferences（Android Keystore），异常时降级普通 prefs；
 *  - 静默重登：仅当记住密码且站点未强制验证码时可行（§6.2.2）。
 */
class SessionManager(
    private val context: Context,
    private val gateway: HtmlGateway,
    private val cookieJar: PersistentCookieJar,
    private val configProvider: () -> SelectorConfig,
    private val onAuthChanged: suspend () -> Unit = {},
) {

    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "sp_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            // Keystore 异常（厂商 ROM 问题等）降级为普通存储，仅保存可选的记住密码
            context.getSharedPreferences("sp_secure_fallback", Context.MODE_PRIVATE)
        }
    }

    private val authVersion = java.util.concurrent.atomic.AtomicLong()
    val cacheVersion: Long get() = authVersion.get()

    private val _state = MutableStateFlow<SessionState>(SessionState.Unknown)
    val state: StateFlow<SessionState> = _state

    var cachedLoginForm: LoginFormData? = null
        private set

    /** 拉取登录表单（验证码与临时会话 Cookie 绑定，方案 §2.4-2）。 */
    suspend fun loadLoginForm(force: Boolean = false): LoginFormData = withContext(Dispatchers.IO) {
        if (!force) cachedLoginForm?.let { return@withContext it }
        val outcome = gateway.fetch("login.php", authCheck = false)
        val html = when (outcome) {
            is HtmlGateway.Outcome.Ok -> outcome.body
            is HtmlGateway.Outcome.Challenged -> throw net.southplus.client.core.network.ChallengeException(outcome.challenge, outcome.url)
            is HtmlGateway.Outcome.Error -> throw net.southplus.client.core.network.ForumException(outcome.message)
        }
        LoginParser.parse(html, outcome.finalUrl, configProvider()).also { cachedLoginForm = it }
    }

    /**
     * 拉取验证码字节流（真机抓包校准 2026-09）：
     *  - 站点 JS（opencode）刷新验证码的真实请求是 `ck.php?nowtime=<ts>`，
     *    裸 `ck.php?`（页面 img 初始 src）返回的图【不是】登录提交时校验的那张；
     *  - 因此请求前强制把 query 规范为 `nowtime=<当前毫秒>`，与站点 JS 行为对齐；
     *  - 每次 GET ck.php 都会生成新码覆盖 session —— 必须「拉图 → 展示 → 提交」
     *    一一对应，禁止任何缓存/重复请求；
     *  - 走 gateway.fetchBytes 共享 Cookie/UA/Referer，绕开 Coil 的 MIME 校验
     *    与缓存（ck.php 返回 application/octet-stream）。
     */
    suspend fun fetchCaptcha(rawUrl: String): ByteArray {
        val base = rawUrl.substringBefore('?')
        val extra = rawUrl.substringAfter('?', "").takeIf { it.isNotBlank() }
        val url = buildString {
            append(base)
            append('?')
            if (extra != null) { append(extra); append('&') }
            append("nowtime=").append(System.currentTimeMillis())
        }
        return gateway.fetchBytes(url)
            ?: throw net.southplus.client.core.network.ForumException("验证码加载失败")
    }

    suspend fun login(
        identity: String,
        password: String,
        captcha: String,
        rememberPassword: Boolean,
    ): LoginResult = withContext(Dispatchers.IO) {
        val form = runCatching { loadLoginForm() }.getOrElse {
            return@withContext LoginResult.Failure("无法打开登录页：${it.message}")
        }
        val cfg = configProvider()
        val fields = form.form.overriding(
            listOf(
                (form.form.usernameField ?: "pwuser") to identity,
                (form.form.passwordField ?: "pwpwd") to transformPassword(password, cfg),
                (form.form.captchaField ?: "verify") to captcha.takeIf { it.isNotBlank() },
            ),
        )
        val outcome = gateway.postForm(form.form.action, fields, authCheck = false)
        val html = outcome.orNull()
            ?: return@withContext LoginResult.Failure((outcome as? HtmlGateway.Outcome.Error)?.message ?: "登录请求失败")

        // 校验登录态（§6.2.1：GET 确认）
        val user = checkSession()
        return@withContext if (user.loggedIn) {
            authVersion.incrementAndGet()
            onAuthChanged()
            cachedLoginForm = null
            if (rememberPassword) {
                securePrefs.edit()
                    .putString(KEY_IDENTITY, identity)
                    .putString(KEY_PASSWORD, password)
                    .putBoolean(KEY_REMEMBER, true)
                    .apply()
            } else {
                clearCredentials()
            }
            LoginResult.Success(user)
        } else {
            LoginResult.Failure(extractErrorText(html, cfg))
        }
    }

    /** 会话确认：GET index.php 解析页头（方案附录 A #8）。 */
    suspend fun checkSession(): UserHeader = withContext(Dispatchers.IO) {
        val outcome = gateway.fetch("index.php")
        val html = when (outcome) {
            is HtmlGateway.Outcome.Ok -> outcome.body
            is HtmlGateway.Outcome.Challenged -> throw net.southplus.client.core.network.ChallengeException(outcome.challenge, outcome.url)
            is HtmlGateway.Outcome.Error -> throw net.southplus.client.core.network.ForumException(outcome.message)
        }
        parseHeader(html, outcome.finalUrl)
    }

    /** 静默重登（§6.2.2）：不弹 UI，验证码强制时返回 false。 */
    suspend fun silentRelogin(): Boolean = withContext(Dispatchers.IO) {
        val identity = securePrefs.getString(KEY_IDENTITY, null) ?: return@withContext false
        val password = securePrefs.getString(KEY_PASSWORD, null) ?: return@withContext false
        runCatching {
            val result = login(identity, password, captcha = "", rememberPassword = true)
            result is LoginResult.Success
        }.getOrDefault(false)
    }

    /** 会话失效时调用：先静默重登，失败再由 UI 引导手动登录（方案 §2.4-4）。 */
    suspend fun recover(): Boolean {
        if (!cookieJar.hasSessionCookie() &&
            securePrefs.getString(KEY_IDENTITY, null) == null
        ) return false
        return silentRelogin()
    }

    /** 页面解析出的页头登录态回填（帖子页/首页都带页头）。 */
    fun updateFromPage(header: UserHeader?) {
        if (header == null) return
        _state.value = if (header.loggedIn) SessionState.LoggedIn(header) else SessionState.Guest
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        authVersion.incrementAndGet()
        onAuthChanged()
        cookieJar.clear()
        clearCredentials()
        cachedLoginForm = null
        _state.value = SessionState.Guest
    }

    fun hasStoredCredentials(): Boolean =
        securePrefs.getBoolean(KEY_REMEMBER, false) &&
            securePrefs.getString(KEY_IDENTITY, null) != null

    private fun clearCredentials() {
        securePrefs.edit().clear().apply()
    }

    private suspend fun parseHeader(html: String, baseUri: String): UserHeader {
        val doc = Jsoup.parse(html, baseUri)
        return UserHeaderParser.parse(doc, configProvider()).also {
            _state.value = if (it.loggedIn) SessionState.LoggedIn(it) else SessionState.Guest
        }
    }

    /** 从登录失败页提取文案（验证码错/密码错，方案 §6.2.1）。 */
    private fun extractErrorText(html: String, cfg: SelectorConfig): String {
        val doc = Jsoup.parse(html)
        cfg.candidates("login_page.error_text").forEach { sel ->
            runCatching { doc.select(sel).firstOrNull()?.text() }.getOrNull()
                ?.takeIf { it.isNotBlank() && it.length < 120 }
                ?.let { return it }
        }
        return "登录失败：请检查账号、密码或验证码"
    }

    /** 密码预处理（方案 §2.4-1 / §8）：算法以抓包为准，配置驱动。 */
    private fun transformPassword(password: String, cfg: SelectorConfig): String = when (
        cfg.string("login_page.password_transform")?.lowercase()
    ) {
        "md5" -> md5(password)
        "md5_upper" -> md5(password).uppercase()
        else -> password
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val KEY_IDENTITY = "identity"
        const val KEY_PASSWORD = "password"
        const val KEY_REMEMBER = "remember"
    }
}

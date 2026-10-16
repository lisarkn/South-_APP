package net.southplus.client.core.webview

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.southplus.client.AppGraph
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 兜底 WebView（方案 §6.1.3 / §6.2.3）：
 *  - Cloudflare 挑战：原地过盾，Cookie 落回 CookieJar 后自动关闭并重放；
 *  - 登录兜底 / 站外兜底：手动浏览，返回键退出（Cookie 持续同步）。
 */
class WebViewActivity : ComponentActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try { AppGraph.awaitReady() } catch (_: IllegalStateException) { finish(); return@launch }
            openPage()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openPage() {
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val autoClose = intent.getBooleanExtra(EXTRA_AUTO_CLOSE, false)

        val wv = WebView(this)
        webView = wv
        setContentView(wv)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 与网络层同一 UA，保证会话特征一致（方案 §3.2）
            userAgentString = AppGraph.userAgent()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        pushCookiesToWebView(url)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finishedUrl: String) {
                syncCookiesBack(finishedUrl, autoClose)
            }
        }
        wv.loadUrl(url)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (wv.canGoBack()) wv.goBack() else finish()
            }
        })
    }

    private fun pushCookiesToWebView(url: String) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val manager = CookieManager.getInstance()
        AppGraph.cookieJar.loadForRequest(httpUrl).forEach { c ->
            val domain = if (c.hostOnly) c.domain else c.domain
            manager.setCookie(url, "${c.name}=${c.value}; Path=${c.path}; Domain=$domain")
        }
        manager.flush()
    }

    private fun syncCookiesBack(url: String, autoClose: Boolean) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val cookies = CookieManager.getInstance().getCookie(url) ?: return
        AppGraph.cookieJar.ingestHeader(cookies, httpUrl)

        if (!autoClose) return
        // 挑战页是否已通过：页面 DOM 不再含挑战脚本特征（方案 §6.1.3）
        webView?.evaluateJavascript(
            "(function(){var h=document.documentElement.innerHTML;" +
                "return h.indexOf('cf-chl')>=0||h.indexOf('jschl')>=0||" +
                "document.title.indexOf('Just a moment')>=0;})()",
        ) { stillChallenged ->
            if (stillChallenged?.contains("true") != true) {
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_AUTO_CLOSE = "auto_close"
    }
}

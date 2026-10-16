package net.southplus.client.core.links

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import net.southplus.client.core.settings.AppSettings

/**
 * 站外链接打开策略（方案 §6.4.5）：默认 Chrome Custom Tabs，
 * 可切换系统浏览器；http:// 非加密链接弹确认由 UI 层处理。
 */
object LinkOpener {

    fun open(context: Context, url: String, settings: AppSettings) {
        val uri = Uri.parse(url)
        if (settings.openInCustomTabs) {
            try {
                CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                    .launchUrl(context, uri)
                return
            } catch (_: Exception) {
                // 无 Custom Tabs 提供方 → 落回系统浏览器
            }
        }
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure {
            android.widget.Toast.makeText(context, "未找到可用的浏览器", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** magnet/ed2k/thunder：交给系统处理器，无处理器时由 UI 复制到剪贴板（§6.4.4）。 */
    fun openProtocol(context: Context, url: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.isSuccess
}

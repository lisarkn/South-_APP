package net.southplus.client.core.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import net.southplus.client.core.model.Attachment
import net.southplus.client.core.network.PersistentCookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 文件名非法字符（Windows 风格黑名单，下载落盘用）。 */
private val ILLEGAL_FILENAME_CHARS = Regex("[/\\\\:*?\"<>|]")

/**
 * 附件下载（方案 §6.7）：系统 DownloadManager，带会话 Cookie 与 Referer，
 * 断点续传/通知由系统处理；种子文件注册 MIME 交给已装 BT 客户端。
 */
class AttachmentDownloader(
    private val context: Context,
    private val cookieJar: PersistentCookieJar,
    private val mirrorHost: () -> String,
) {

    fun enqueue(attachment: Attachment): Long {
        val httpUrl = attachment.url.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("无效的附件地址")
        val name = attachment.name.ifBlank { "attachment.bin" }.replace(ILLEGAL_FILENAME_CHARS, "_")

        val request = DownloadManager.Request(Uri.parse(attachment.url)).apply {
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "SouthPlus/$name")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setTitle(name)
            // DownloadManager 不走 OkHttp，需要手动附加会话 Cookie（方案 §6.7 ★）
            cookieJar.cookieHeader(httpUrl).takeIf { it.isNotEmpty() }?.let {
                addRequestHeader("Cookie", it)
            }
            addRequestHeader("Referer", "https://${mirrorHost()}/")
            if (attachment.isTorrent) setMimeType("application/x-bittorrent")
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }
}

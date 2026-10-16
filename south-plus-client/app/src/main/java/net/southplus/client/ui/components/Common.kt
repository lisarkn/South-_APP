package net.southplus.client.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.text.method.LinkMovementMethod
import android.view.MotionEvent
import android.widget.TextView
import android.text.Spanned
import android.text.SpannedString
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.aspectRatio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import net.southplus.client.AppGraph
import net.southplus.client.core.model.Challenge
import net.southplus.client.core.model.ResourceLink
import net.southplus.client.core.model.ThreadItem
import net.southplus.client.core.network.ChallengeException
import net.southplus.client.ui.theme.Spacing
import net.southplus.client.ui.theme.extended

// ---------------- 异常 → 用户文案 ----------------

fun Throwable.uiMessage(): String = when (this) {
    is ChallengeException -> when (challenge) {
        Challenge.CLOUDFLARE -> "站点防护验证中，请点击重试（将打开验证页面）"
        Challenge.NOT_LOGGED_IN -> "该内容需要登录后访问"
        Challenge.NO_PERMISSION -> "当前账号无权访问该版块（需注册/认证会员），请先登录"
        Challenge.MAINTENANCE -> "站点维护中，请稍后再试或切换镜像"
    }
    else -> message?.takeIf { it.isNotBlank() } ?: "网络错误，请稍后重试"
}

fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

// ---------------- 通用面板 ----------------

@Composable
fun LoadingPane(modifier: Modifier = Modifier, text: String = "加载中…") {
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            CircularProgressIndicator(Modifier.padding(Spacing.l).size(28.dp), strokeWidth = 2.dp)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ErrorPane(
    modifier: Modifier = Modifier,
    message: String,
    onRetry: (() -> Unit)? = null,
    extraLabel: String? = null,
    onExtra: (() -> Unit)? = null,
) {
    EmptyPane(
        text = message,
        modifier = modifier,
        icon = Icons.Filled.ErrorOutline,
        title = "暂时无法加载",
        action = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally)) {
                onRetry?.let { OutlinedButton(onClick = it) { Text("重新加载") } }
                if (extraLabel != null && onExtra != null) {
                    Button(onClick = onExtra) { Text(extraLabel) }
                }
            }
        },
    )
}

// ---------------- 帖子列表行（高密度论坛排版） ----------------

@Composable
fun ThreadRow(item: ThreadItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(onClick = onClick)
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            if (item.isPinned) StatusBadge("顶", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, Modifier.padding(top = 2.dp))
            if (item.isDigest) StatusBadge("精", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary, Modifier.padding(top = 2.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (item.isRead) FontWeight.Normal else FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                color = if (item.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            item.author?.name?.let { AvatarBox(it, size = 20.dp, avatarUrl = item.author?.avatarUrl) }
            Text(
                listOfNotNull(item.author?.name, item.lastReplyTime).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (item.isLocked) Icon(Icons.Filled.Lock, "锁定", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.hasAttachment) Icon(Icons.Filled.AttachFile, "含附件", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.replies > 0) {
                Icon(Icons.Outlined.ChatBubbleOutline, "回复数", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.replies.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------- HTML 文本渲染（楼层正文，方案 §6.4.2） ----------------

/**
 * 轻量 HTML 渲染：TextView + HtmlCompat；点击链接回调给 LinkRouter 应用内路由。
 * 图片不进入 Text 块（解析层已拆分为 Image 块）。
 * 链接色/选中高亮/正文色均从 Compose 主题取值，避免暗色模式下链接不可读。
 */
class RouteLinkMovementMethod(private val onLink: (String) -> Unit) : LinkMovementMethod() {
    override fun onTouchEvent(widget: TextView, buffer: android.text.Spannable, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val x = event.x.toInt() - widget.totalPaddingLeft + widget.scrollX
            val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
            val layout = widget.layout ?: return false
            val line = layout.getLineForVertical(y)
            val off = layout.getOffsetForHorizontal(line, x.toFloat())
            buffer.getSpans(off, off, android.text.style.URLSpan::class.java).firstOrNull()?.let { span ->
                onLink(span.url)
                return true
            }
        }
        return super.onTouchEvent(widget, buffer, event)
    }
}

private class HtmlTextView(context: Context) : TextView(context) {
    var rendered: Spanned? = null
    var appliedFontSize: Float = -1f
}

@Composable
fun HtmlText(
    html: String,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
    color: Color = Color.Unspecified,
    prepared: Spanned? = null,
) {
    val spanned = prepared ?: key(html) {
        val parsed by produceState<Spanned?>(null) {
            value = withContext(Dispatchers.Default) {
                SpannedString(HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY))
            }
        }
        parsed
    }
    val currentOnLink by rememberUpdatedState(onLink)
    val textColor = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val selectionColor = MaterialTheme.colorScheme.primaryContainer
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            HtmlTextView(ctx).apply {
                movementMethod = RouteLinkMovementMethod { link -> currentOnLink(link) }
                setLineSpacing(0f, 1.15f)
                setLinkTextColor(linkColor.toArgb())
                this.highlightColor = selectionColor.toArgb()
            }
        },
        update = { tv ->
            if (tv.rendered !== spanned) {
                tv.text = spanned ?: ""
                tv.rendered = spanned
            }
            if (tv.appliedFontSize != fontSize.value) {
                tv.textSize = fontSize.value
                tv.appliedFontSize = fontSize.value
            }
            if (tv.currentTextColor != textColor.toArgb()) tv.setTextColor(textColor.toArgb())
            if (tv.linkTextColors.defaultColor != linkColor.toArgb()) tv.setLinkTextColor(linkColor.toArgb())
            if (tv.highlightColor != selectionColor.toArgb()) tv.highlightColor = selectionColor.toArgb()
        },
    )
}

// ---------------- 图片 ----------------

// Small URL→ratio cache reserves the same space when an image returns to the viewport.
private val imageRatios = android.util.LruCache<String, Float>(256)

@Composable
fun NetImage(
    url: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    blur: Boolean = false,
    aspectRatio: Float? = null,
) {
    val context = LocalContext.current
    val request = remember(context, url) { ImageRequest.Builder(context).data(url).crossfade(false).build() }
    var measuredRatio by remember(url) { mutableStateOf(imageRatios.get(url)) }
    val ratio = (aspectRatio ?: measuredRatio)?.takeIf { it.isFinite() && it > 0f }
    val sized = if (ratio != null) modifier.aspectRatio(ratio) else modifier.heightIn(min = 160.dp)
    Box(
        modifier = (if (blur) sized.blur(24.dp) else sized)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        AsyncImage(
            model = request,
            imageLoader = AppGraph.imageLoader,
            contentDescription = contentDescription,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
            onSuccess = { state ->
                val drawable = state.result.drawable
                if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                    val value = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
                    imageRatios.put(url, value)
                    measuredRatio = value
                }
            },
        )
    }
}

// ---------------- 网盘资源卡（方案 §6.4.5） ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResourceCardView(
    link: ResourceLink,
    onOpen: (ResourceLink) -> Unit,
    onCopyLink: (ResourceLink) -> Unit,
    onCopyCode: (ResourceLink) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Link,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Text(link.panName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        "提取码 ${link.code ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.s, vertical = 2.dp),
                    )
                }
            }
            Text(
                link.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Button(onClick = { onOpen(link) }) { Text("打开") }
                OutlinedButton(onClick = { onCopyLink(link) }) { Text("复制链接") }
                if (link.code != null) {
                    OutlinedButton(onClick = { onCopyCode(link) }) { Text("复制提取码") }
                }
            }
        }
    }
}

// ---------------- 验证码图（方案 §6.2.3） ----------------

@Composable
fun CaptchaImage(bytes: ByteArray?, loading: Boolean, onClick: () -> Unit) {
    // 验证码不走 Coil/URL：ck.php 每次请求都会生成新码覆盖 session，
    // 必须由调用方以字节流直取（SessionManager.fetchCaptcha）后在此渲染。
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .fillMaxWidth()
                    .padding(Spacing.s),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = remember(bytes) {
                    bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "验证码",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                } else {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    } else {
                        Text(
                            "验证码加载失败，点击重试",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        Text(
            "看不清？点击图片刷新",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

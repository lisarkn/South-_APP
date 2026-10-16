package net.southplus.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import net.southplus.client.AppGraph

/**
 * 全屏图片查看器（灯箱）：帖子图片点击后应用内看大图，不再跳浏览器。
 * 双指缩放（1x–5x）/ 双击切换放大 / 缩放后拖动平移；未放大时单击或返回键关闭。
 * 复用 AppGraph.imageLoader，与楼层内联图共享缓存，打开即出图。
 */
@Composable
fun ImageViewerDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var viewSize by remember { mutableStateOf(IntSize.Zero) }
        var loading by remember { mutableStateOf(true) }
        var failed by remember { mutableStateOf(false) }

        // 以视图中心为缩放锚点时，平移量限制在 (scale-1)*半边长 内，图不会被拖出视野
        fun clamp(o: Offset, s: Float): Offset {
            val maxX = (s - 1f) * viewSize.width / 2f
            val maxY = (s - 1f) * viewSize.height / 2f
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }

        val transformState = rememberTransformableState { zoom, pan, _ ->
            val new = (scale * zoom).coerceIn(1f, 5f)
            scale = new
            offset = if (new <= 1f) Offset.Zero else clamp(offset + pan, new)
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onGloballyPositioned { viewSize = it.size }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (scale <= 1.01f) onDismiss() },
                        onDoubleTap = { tap ->
                            val old = scale
                            val target = if (old > 1.01f) 1f else 2.5f
                            scale = target
                            offset = if (target <= 1f) Offset.Zero
                            else clamp(offset + (tap - Offset(size.width / 2f, size.height / 2f)) * (old - target), target)
                        },
                    )
                }
                .transformable(transformState),
        ) {
            if (loading && !failed) {
                CircularProgressIndicator(Modifier.size(32.dp).align(Alignment.Center), color = Color.White)
            }
            if (failed) {
                Text(
                    "图片加载失败",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            AsyncImage(
                model = ImageRequest.Builder(context).data(url).crossfade(false).build(),
                imageLoader = AppGraph.imageLoader,
                contentDescription = "图片大图预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                onSuccess = { loading = false },
                onError = { loading = false; failed = true },
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(4.dp),
            ) {
                Icon(Icons.Filled.Close, "关闭", tint = Color.White)
            }
        }
    }
}

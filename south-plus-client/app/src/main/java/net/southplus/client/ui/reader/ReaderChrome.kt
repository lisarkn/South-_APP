package net.southplus.client.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.southplus.client.ui.theme.Spacing

@Composable
internal fun ReaderTitle(title: String, page: Int, totalPages: Int) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text("第 $page / $totalPages 页", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun ReaderToolbar(
    page: Int, totalPages: Int, loading: Boolean, canReply: Boolean,
    onPrevious: () -> Unit, onNext: () -> Unit, onJump: () -> Unit, onReply: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.s, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                IconButton(onClick = onPrevious, enabled = !loading && page > 1) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一页")
                }
                TextButton(onClick = onJump, enabled = !loading, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("$page / $totalPages", style = MaterialTheme.typography.labelLarge)
                }
                IconButton(onClick = onNext, enabled = !loading && page < totalPages) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一页")
                }
                Button(onClick = onReply, enabled = canReply && !loading,
                    contentPadding = PaddingValues(horizontal = Spacing.m), modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Reply, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text("回复")
                }
            }
        }
    }
}

package net.southplus.client.ui.diag

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import net.southplus.client.ui.components.EmptyPane
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.southplus.client.core.network.DiagLog
import net.southplus.client.ui.ForumTopBar
import net.southplus.client.ui.components.copyToClipboard
import net.southplus.client.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import androidx.navigation.NavHostController

/**
 * 网络诊断（真机排查「加载不出来」类问题）：
 *  - 最近 60 条请求/解析/兜底记录（状态码、耗时、镜像、挑战判定、解析产出）；
 *  - 复制最近一次抓取的原始 HTML（截断 400KB），用于校准 selectors.yaml。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiagScreen(nav: NavHostController) {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val entries = remember(version) { DiagLog.snapshot() }
    val fmt = remember { SimpleDateFormat("HH:mm:ss") }

    fun refresh() { version++ }

    Scaffold(topBar = { ForumTopBar("网络诊断", nav, actions = { IconButton(onClick = ::refresh) { Icon(Icons.Filled.Refresh, "刷新日志") } }) }) { padding ->
        Column(Modifier.padding(padding)) {
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.l, vertical = Spacing.s),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                OutlinedButton(onClick = {
                    val text = entries.joinToString("\n") { e ->
                        "${fmt.format(Date(e.at))} [${e.label}] ${e.detail}"
                    }
                    copyToClipboard(context, "诊断日志", text)
                    refresh()
                }) { Text("复制全部日志") }
                OutlinedButton(onClick = {
                    val (label, html) = DiagLog.lastHtml ?: return@OutlinedButton
                    copyToClipboard(context, "原始HTML($label)", html)
                    refresh()
                }) { Text("复制最近页面HTML") }
                OutlinedButton(onClick = { DiagLog.clear(); refresh() }) { Text("清空") }
            }
            Text(
                "每条记录：状态码/大小/耗时/镜像/挑战判定；PARSE 行显示解析产出条数。" +
                    "若 HTTP 200 但 PARSE 为 0，说明选择器需要校准（复制原始 HTML 反馈即可修复）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.l),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = Spacing.s),
            ) {
                if (entries.isEmpty()) item { EmptyPane("浏览页面后，连接与解析记录会显示在这里。", title = "暂无诊断记录") }
                items(entries.size) { i ->
                    val e = entries[i]
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { copyToClipboard(context, "日志", "${e.detail}") }
                            .padding(horizontal = Spacing.l, vertical = Spacing.s),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "${fmt.format(Date(e.at))}  [${e.label}]",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            e.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                e.detail.contains("失败") || e.detail.contains("挑战") ->
                                    MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

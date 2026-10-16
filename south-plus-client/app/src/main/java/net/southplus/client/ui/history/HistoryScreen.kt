package net.southplus.client.ui.history

import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.southplus.client.AppGraph
import net.southplus.client.ui.ForumTopBar
import net.southplus.client.ui.Routes
import net.southplus.client.ui.components.EmptyPane
import net.southplus.client.ui.graphViewModel
import net.southplus.client.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date

class HistoryViewModel : ViewModel() {
    val history = AppGraph.db.historyDao().recent(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clear() {
        viewModelScope.launch { AppGraph.db.historyDao().clear() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(nav: NavHostController) {
    val vm: HistoryViewModel = graphViewModel { HistoryViewModel() }
    val items by vm.history.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm") }

    Scaffold(topBar = { ForumTopBar("浏览历史", nav, actions = {}) }) { padding ->
        if (items.isEmpty()) {
            Column(Modifier.padding(padding)) {
                EmptyPane("读过的帖子会保存在这里，方便下次继续。", icon = Icons.Filled.History, title = "从一次阅读开始")
            }
        } else {
            LazyColumn(
                Modifier.padding(padding),
                contentPadding = PaddingValues(vertical = Spacing.s),
            ) {
                items(items.size, key = { i -> items[i].tid }) { i ->
                    val h = items[i]
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .clickable { nav.navigate(Routes.thread(h.tid)) }
                            .padding(horizontal = Spacing.l, vertical = Spacing.m),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(h.title, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(
                            fmt.format(Date(h.visitedAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

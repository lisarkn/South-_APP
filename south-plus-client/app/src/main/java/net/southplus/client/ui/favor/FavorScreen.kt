package net.southplus.client.ui.favor

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.southplus.client.AppGraph
import net.southplus.client.core.model.FavorEntry
import net.southplus.client.ui.ForumTopBar
import net.southplus.client.ui.Routes
import net.southplus.client.ui.components.EmptyPane
import net.southplus.client.ui.components.ErrorPane
import net.southplus.client.ui.components.LoadingPane
import net.southplus.client.ui.components.uiMessage
import net.southplus.client.ui.graphViewModel
import net.southplus.client.ui.theme.Spacing

class FavorViewModel : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val items: List<FavorEntry> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load(force: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.items.isEmpty(), error = null) }
            runCatching { AppGraph.repo.favorList(force = force) }
                .onSuccess { list -> _state.value = State(loading = false, items = list) }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.uiMessage()) } }
        }
    }

    fun remove(entry: FavorEntry) {
        val url = entry.removeUrl ?: return
        viewModelScope.launch {
            runCatching { AppGraph.repo.addFavor(url) } // GET 删除链接后刷新
                .onSuccess { load(force = true) }
                .onFailure { e -> _state.update { it.copy(error = e.uiMessage()) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavorScreen(nav: NavHostController) {
    val vm: FavorViewModel = graphViewModel { FavorViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { ForumTopBar("我的收藏", nav, actions = {}) }) { padding ->
        val s = state
        when {
            s.loading -> LoadingPane(Modifier.padding(padding))
            s.error != null && s.items.isEmpty() -> ErrorPane(
                Modifier.padding(padding),
                message = s.error ?: "",
                onRetry = { vm.load(force = true) },
            )
            else -> if (s.items.isEmpty()) {
                Column(Modifier.padding(padding)) {
                    EmptyPane("在帖子中点击星标，把喜欢的分享留在这里。", icon = Icons.Filled.Star, title = "收藏值得再读的内容")
                }
            } else {
                LazyColumn(
                    Modifier.padding(padding),
                    contentPadding = PaddingValues(vertical = Spacing.s),
                ) {
                    s.error?.let { error ->
                        item(key = "error") { ErrorPane(message = error, onRetry = { vm.load(force = true) }) }
                    }
                    s.items.forEach { entry ->
                        item(key = entry.tid) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                    .clickable { nav.navigate(Routes.thread(entry.tid)) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    entry.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = Spacing.l, vertical = Spacing.m),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                entry.removeUrl?.let {
                                    IconButton(onClick = { vm.remove(entry) }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            "取消收藏",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

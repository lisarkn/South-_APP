package net.southplus.client.ui.home

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.southplus.client.AppGraph
import net.southplus.client.core.model.IndexPage
import net.southplus.client.ui.ForumTopBar
import net.southplus.client.ui.Routes
import net.southplus.client.ui.components.ErrorPane
import net.southplus.client.ui.components.LoadingPane
import net.southplus.client.ui.components.uiMessage
import net.southplus.client.ui.graphViewModel
import net.southplus.client.ui.theme.Spacing

class DiscoverViewModel : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val page: IndexPage? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load(force: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.page == null, error = null) }
            runCatching { AppGraph.repo.index(force) }
                .onSuccess { p -> _state.update { it.copy(loading = false, page = p, error = null) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.uiMessage()) } }
        }
    }
}

/**
 * 底部「发现」标签：全站版块目录（按分区分组，点击进入版块）；
 * 页顶放大镜进入关键词搜索（原「搜索」标签的职能，入口保留在这里）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(nav: NavHostController) {
    val vm: DiscoverViewModel = graphViewModel { DiscoverViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(true) }

    Scaffold(
        topBar = {
            ForumTopBar("发现", nav, actions = {
                IconButton(onClick = { nav.navigate(Routes.SEARCH) }) {
                    Icon(Icons.Outlined.Search, "进入搜索")
                }
            })
        },
    ) { padding ->
        val page = state.page
        when {
            state.loading -> LoadingPane(Modifier.padding(padding))
            state.error != null && page == null -> ErrorPane(
                Modifier.padding(padding),
                message = state.error.orEmpty(),
                onRetry = { vm.load(force = true) },
            )
            page != null -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = Spacing.xl),
            ) {
                page.sections.forEachIndexed { si, section ->
                    if (section.boards.isEmpty()) return@forEachIndexed
                    item(key = "dsec_$si") { DiscoverSectionLabel(section.name) }
                    section.boards.forEach { board ->
                        item(key = "dbd_${section.name}_${board.fid}") {
                            BoardRow(
                                board = board,
                                first = board === section.boards.first(),
                                last = board === section.boards.last(),
                                onNavigate = { fid -> nav.navigate(Routes.board(fid)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverSectionLabel(name: String) {
    Text(
        name,
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
    )
}

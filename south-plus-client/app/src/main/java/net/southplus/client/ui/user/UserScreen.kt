package net.southplus.client.ui.user

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import net.southplus.client.ui.components.SectionCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.southplus.client.AppGraph
import net.southplus.client.core.model.UserProfile
import net.southplus.client.ui.ForumTopBar
import net.southplus.client.ui.components.AvatarBox
import net.southplus.client.ui.components.ErrorPane
import net.southplus.client.ui.components.LoadingPane
import net.southplus.client.ui.components.NetImage
import net.southplus.client.ui.components.uiMessage
import net.southplus.client.ui.graphViewModel
import net.southplus.client.ui.theme.Spacing

class UserViewModel(private val uid: String) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val profile: UserProfile? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { AppGraph.repo.userPage(uid) }
                .onSuccess { p -> _state.value = State(loading = false, profile = p) }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.uiMessage()) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserScreen(nav: NavHostController, uid: String) {
    val vm: UserViewModel = graphViewModel { UserViewModel(uid) }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { ForumTopBar("用户空间", nav, actions = {}) }) { padding ->
        val s = state
        when {
            s.loading -> LoadingPane(Modifier.padding(padding))
            s.error != null -> ErrorPane(
                Modifier.padding(padding),
                message = s.error ?: "",
                onRetry = { vm.load() },
            )
            else -> {
                val p = s.profile
                Column(
                    Modifier
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.l),
                ) {
                    SectionCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    ) {
                        AvatarBox(p?.name ?: uid, 56.dp, avatarUrl = p?.avatarUrl)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(p?.name ?: "用户 $uid", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "UID $uid",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    }
                    // 统计资料列表
                    if (p?.stats?.isNotEmpty() == true) {
                        SectionCard {
                            val entries = p.stats.entries.toList()
                            entries.forEachIndexed { idx, entry ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = Spacing.m),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        entry.key,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(entry.value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                }
                                if (idx < entries.size - 1) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                    // 用户面板入口：TA的主题/回复/收藏/好友/商品（u.php?action-X-uid-Y.html）
                    SectionCard {
                        userPanelEntries.forEachIndexed { idx, (panel, label, icon) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { nav.navigate(net.southplus.client.ui.Routes.userPanel(uid, panel)) }
                                    .padding(vertical = Spacing.s),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f).padding(start = Spacing.m),
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            if (idx < userPanelEntries.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
    }
}

private val userPanelEntries = listOf(
    Triple("topic", "主题", Icons.Outlined.Article),
    Triple("post", "回复", Icons.AutoMirrored.Outlined.Chat),
    Triple("favor", "收藏", Icons.Outlined.Star),
    Triple("friend", "好友", Icons.Outlined.Person),
    Triple("trade", "商品", Icons.Outlined.ShoppingCart),
)

package net.southplus.client.ui.mine

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.southplus.client.AppGraph
import net.southplus.client.core.model.SessionState
import net.southplus.client.ui.components.AvatarBox
import net.southplus.client.ui.ForumTopBar
import net.southplus.client.ui.Routes
import net.southplus.client.ui.graphViewModel
import net.southplus.client.ui.theme.Spacing

class MineViewModel : ViewModel() {
    data class State(val profileName: String? = null, val profileAvatarUrl: String? = null)

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // 会话 Unknown（首页尚未加载过）时主动确认一次登录态
            if (AppGraph.session.state.value is SessionState.Unknown) {
                runCatching { AppGraph.session.checkSession() }
            }
            // 2026-09 校准：站点页头没有用户名链接，从个人资料页补齐显示名与头像
            val loggedIn = AppGraph.session.state.value as? SessionState.LoggedIn ?: return@launch
            val uid = loggedIn.user.uid ?: return@launch
            runCatching { AppGraph.repo.userPage(uid) }.onSuccess { p ->
                _state.value = State(
                    profileName = p.name?.takeIf { it.isNotBlank() },
                    profileAvatarUrl = p.avatarUrl?.takeIf { it.isNotBlank() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MineScreen(nav: NavHostController) {
    val vm: MineViewModel = graphViewModel { MineViewModel() }
    val mineState by vm.state.collectAsStateWithLifecycle()
    val sessionState by AppGraph.session.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { ForumTopBar("我的", nav) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(vertical = Spacing.m),
        ) {
            item(key = "header") {
                val loggedIn = sessionState as? SessionState.LoggedIn
                ProfileHeader(
                    loggedIn = loggedIn,
                    displayName = mineState.profileName,
                    avatarUrl = mineState.profileAvatarUrl ?: loggedIn?.user?.avatarUrl,
                    onClick = {
                        if (loggedIn == null) nav.navigate(Routes.LOGIN) else loggedIn.user.uid?.let { nav.navigate(Routes.user(it)) }
                    },
                )
            }
            item(key = "section-community") { SectionLabel("社区") }
            entry("favor", "我的收藏", Icons.Outlined.Star) { nav.navigate(Routes.FAVOR) }
            entry("topic", "我的主题", Icons.Outlined.Article) { nav.navigate(Routes.USER_TOPIC) }
            entry("reply", "我的回复", Icons.AutoMirrored.Outlined.Chat) { nav.navigate(Routes.USER_REPLY) }
            entry("friend", "我的好友", Icons.Outlined.Person) { nav.navigate(Routes.USER_FRIEND) }
            entry("trade", "我的商品", Icons.Outlined.ShoppingCart) { nav.navigate(Routes.USER_TRADE) }
            entry("task", "论坛任务", Icons.Outlined.Assignment) { nav.navigate(Routes.USER_TASK) }
            item(key = "section-app") { SectionLabel("应用") }
            entry("history", "浏览历史", Icons.Outlined.History) { nav.navigate(Routes.HISTORY) }
            entry("settings", "设置", Icons.Outlined.Settings) { nav.navigate(Routes.SETTINGS) }
        }
    }
}

@Composable
private fun ProfileHeader(
    loggedIn: SessionState.LoggedIn?,
    displayName: String?,
    avatarUrl: String?,
    onClick: () -> Unit,
) {
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier.padding(Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val user = loggedIn?.user
            if (user != null) {
                AvatarBox(
                    name = displayName ?: user.userName ?: user.uid?.let { "UID $it" } ?: "论坛会员",
                    size = 52.dp,
                    avatarUrl = avatarUrl,
                )
            } else {
                Box(
                    Modifier.size(52.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.size(Spacing.l))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (user != null) {
                    Text(
                        displayName ?: user.userName ?: user.uid?.let { "UID $it" } ?: "论坛会员",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(user.credit?.let { "SP币 $it" }, user.unreadPm.takeIf { it > 0 }?.let { "未读消息 $it" })
                            .joinToString(" · ")
                            .ifEmpty { "查看个人资料" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text("登录 / 注册", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "登录后可使用收藏、好友、任务等账号功能",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
    )
}

private fun LazyListScope.entry(
    key: String,
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    item(key = key) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(start = Spacing.l),
            )
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

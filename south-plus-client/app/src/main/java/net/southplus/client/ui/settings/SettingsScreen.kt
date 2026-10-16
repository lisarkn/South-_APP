package net.southplus.client.ui.settings

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.southplus.client.AppGraph
import net.southplus.client.core.database.BlockRuleEntity
import net.southplus.client.core.settings.AppSettings
import net.southplus.client.core.update.UpdateChecker
import net.southplus.client.ui.ForumTopBar
import net.southplus.client.ui.components.uiMessage
import net.southplus.client.ui.graphViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.unit.sp
import net.southplus.client.ui.components.NavigationRow
import net.southplus.client.ui.components.SectionCard
import net.southplus.client.ui.components.SettingSectionTitle as SectionTitle
import net.southplus.client.ui.components.SettingSwitch as SwitchRow
import net.southplus.client.ui.components.ThemeSelector
import net.southplus.client.ui.theme.Spacing

class SettingsViewModel : ViewModel() {
    val settings = AppGraph.settings.settings

    val cacheBytes = MutableStateFlow<Long?>(null)

    val blockRules = AppGraph.db.blockDao().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { refreshCacheSize() }
    }

    fun refreshCacheSize() {
        viewModelScope.launch { cacheBytes.value = AppGraph.db.cacheDao().totalBytes() }
    }

    fun set(action: suspend (SettingsReceiver) -> Unit) = viewModelScope.launch { action(SettingsReceiver) }

    object SettingsReceiver {
        suspend fun theme(mode: Int) = AppGraph.settings.setThemeMode(mode)
        suspend fun lowTraffic(v: Boolean) = AppGraph.settings.setLowTraffic(v)
        suspend fun imageWifiOnly(v: Boolean) = AppGraph.settings.setImageWifiOnly(v)
        suspend fun blurOffsite(v: Boolean) = AppGraph.settings.setBlurOffsite(v)
        suspend fun autoBuyFree(v: Boolean) = AppGraph.settings.setAutoBuyFree(v)
        suspend fun autoCheckIn(v: Boolean) = AppGraph.settings.setAutoCheckIn(v)
        suspend fun fontScale(v: Float) = AppGraph.settings.setReaderFontScale(v)
        suspend fun remoteSelectors(v: Boolean) = AppGraph.settings.setRemoteSelectors(v)
    }

    fun setFontScale(value: Float) {
        viewModelScope.launch { AppGraph.settings.setReaderFontScale(value) }
    }

    fun clearCache() {
        viewModelScope.launch {
            AppGraph.db.cacheDao().clear()
            refreshCacheSize()
        }
    }

    fun addBlockRule(type: String, value: String) {
        viewModelScope.launch { AppGraph.db.blockDao().add(BlockRuleEntity(type = type, value = value)) }
    }

    fun removeBlockRule(id: Long) {
        viewModelScope.launch { AppGraph.db.blockDao().delete(id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: androidx.navigation.NavHostController) {
    val vm: SettingsViewModel = graphViewModel { SettingsViewModel() }
    val settings by vm.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val cacheBytes by vm.cacheBytes.collectAsStateWithLifecycle()
    val rules by vm.blockRules.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var addRuleOpen by remember { mutableStateOf(false) }

    Scaffold(topBar = { ForumTopBar("设置", nav, actions = {}) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            // ---- 网络与连接 ----
            NavigationRow(
                title = "网络与连接",
                subtitle = "镜像切换、加密 DNS 与网络诊断",
                icon = Icons.Outlined.Wifi,
                onClick = { nav.navigate(net.southplus.client.ui.Routes.NETWORK) },
            )

            // ---- 外观 ----
            SectionTitle("外观与阅读")
            SectionCard {
                ThemeSelector(settings.themeMode) { mode -> vm.set { it.theme(mode) } }
                ReaderFontSlider(settings.readerFontScale) { value ->
                    vm.setFontScale(value)
                }
            }

            // ---- 流量与内容 ----
            SectionTitle("流量与内容")
            SectionCard {
                SwitchRow("低流量模式", settings.lowTraffic, "优先使用本地缓存，减少网络请求") { v -> vm.set { it.lowTraffic(v) } }
                SwitchRow("站外图片按需加载", settings.imageWifiOnly, "移动网络下点击后再加载图片") { v -> vm.set { it.imageWifiOnly(v) } }
                SwitchRow("模糊站外图片", settings.blurOffsiteImages, "在阅读页长按图片可查看原图") { v -> vm.set { it.blurOffsite(v) } }
            }

            // ---- 账号自动化 ----
            SectionTitle("账号自动化")
            SectionCard {
                SwitchRow("自动签到", settings.autoCheckIn, "启动应用时自动完成论坛任务的每日/周签到（需已登录）") { v -> vm.set { it.autoCheckIn(v) } }
                SwitchRow("自动购买免费帖子", settings.autoBuyFree, "仅限 0 SP币；付费内容仍需确认") { v -> vm.set { it.autoBuyFree(v) } }
            }

            // ---- 屏蔽 ----
            SectionTitle("屏蔽（本地过滤）")
            SectionCard {
                if (rules.isEmpty()) {
                    Text(
                        "暂无屏蔽规则",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                rules.forEach { rule ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            (if (rule.type == "USER") "用户：" else "关键词：") + rule.value,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { vm.removeBlockRule(rule.id) }) { Text("删除") }
                    }
                }
                FilledTonalButton(onClick = { addRuleOpen = true }) { Text("添加屏蔽规则") }
            }

            // ---- 存储 ----
            SectionTitle("存储")
            SectionCard {
                Text(
                    "页面缓存：${cacheBytes?.let { "%.1f MB".format(it / 1024.0 / 1024.0) } ?: "统计中"}（最多保留 500 MB）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    OutlinedButton(onClick = { vm.clearCache() }) { Text("清除缓存") }
                }
            }

            // ---- 解析配置热修 ----
            SectionTitle("解析配置热修")
            SectionCard {
                SwitchRow(
                    "从远程更新选择器配置（默认关闭）",
                    settings.remoteSelectors,
                ) { v -> scope.launch { vm.set { it.remoteSelectors(v) } } }
                Text(
                    "镜像清单每 24h 低频校验（jsDelivr CDN）；选择器热修用于站点改版后的紧急修复。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- 更新与关于 ----
            SectionTitle("更新与关于")
            SectionCard {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    OutlinedButton(onClick = { nav.navigate(net.southplus.client.ui.Routes.DIAG) }) { Text("网络诊断") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val release = UpdateChecker.latest(AppGraph.http.rawClient)
                            val current = context.versionName()
                            when {
                                release == null -> android.widget.Toast.makeText(context, "无法获取更新信息（网络不可达）", android.widget.Toast.LENGTH_SHORT).show()
                                UpdateChecker.isNewer(release.version, current) ->
                                    android.widget.Toast.makeText(context, "发现新版本 ${release.version}，将打开发布页", android.widget.Toast.LENGTH_LONG).show()
                                else -> android.widget.Toast.makeText(context, "已是最新版本 v$current", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            release?.let { net.southplus.client.core.links.LinkOpener.open(context, it.pageUrl, settings) }
                        }
                    }) { Text("检查更新") }
                }
                Text("版本 ${context.versionName()}", style = MaterialTheme.typography.labelMedium)
                Text(
                    "免责声明：本应用为社区第三方工具，与南+官方无关；仅供已注册用户以移动端方式访问其有权访问的内容；" +
                        "所有请求由用户设备以用户自己的账号发起，内容版权归原发布者及站点所有；使用本应用产生的一切账号后果由用户自行承担。" +
                        "客户端对站点实施串行限速（1.5–3.0s 随机间隔）与激进本地缓存，不做任何自动化高频操作。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (addRuleOpen) {
        var value by remember { mutableStateOf("") }
        var isUser by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { addRuleOpen = false },
            title = { Text("添加屏蔽规则") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = isUser, onClick = { isUser = true }); Text("按用户名") }
                        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = !isUser, onClick = { isUser = false }); Text("按关键词") }
                    }
                    OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(if (isUser) "用户名" else "关键词") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(enabled = value.isNotBlank(), onClick = {
                    if (value.isNotBlank()) {
                        vm.addBlockRule(if (isUser) "USER" else "KEYWORD", value.trim())
                    }
                    addRuleOpen = false
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { addRuleOpen = false }) { Text("取消") } },
        )
    }
}

private fun Context.versionName(): String = runCatching {
    packageManager.getPackageInfo(packageName, 0).versionName
}.getOrNull() ?: "0.0"

@Composable
private fun ReaderFontSlider(savedValue: Float, onCommit: (Float) -> Unit) {
    var preview by remember(savedValue) { mutableStateOf(savedValue) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("阅读页正文字号", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("×${"%.1f".format(preview)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Text(
        "把热爱写进日常，让阅读慢下来。",
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.s),
        fontSize = (16 * preview).sp,
        lineHeight = (26 * preview).sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(value = preview, onValueChange = { preview = it },
        onValueChangeFinished = { if (preview != savedValue) onCommit(preview) },
        valueRange = 0.8f..1.6f, steps = 3)
}

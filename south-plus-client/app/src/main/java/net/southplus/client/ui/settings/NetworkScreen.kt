package net.southplus.client.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Troubleshoot
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.southplus.client.AppGraph
import net.southplus.client.core.network.ConnectionResult
import net.southplus.client.core.network.ConnectionTester
import net.southplus.client.core.network.DohController
import net.southplus.client.core.network.Mirror
import net.southplus.client.core.network.MirrorManager
import net.southplus.client.ui.ForumTopBar
import net.southplus.client.ui.Routes
import net.southplus.client.ui.components.NavigationRow
import net.southplus.client.ui.components.SectionCard
import net.southplus.client.ui.components.SettingSectionTitle as SectionTitle
import net.southplus.client.ui.graphViewModel
import net.southplus.client.ui.theme.Spacing

internal data class NetworkUiState(
    val busy: Boolean = false,
    val progress: String? = null,
    val message: String? = null,
    val currentResult: ConnectionResult? = null,
    val mirrorResults: Map<String, ConnectionResult> = emptyMap(),
    val providerResults: Map<String, ConnectionResult> = emptyMap(),
)

class NetworkViewModel : ViewModel() {
    val mirrors = MutableStateFlow(AppGraph.mirrors.all())
    val currentMirror = AppGraph.mirrors.currentState
    val dohState = AppGraph.doh.state
    internal val ui = MutableStateFlow(NetworkUiState())
    private val tester = ConnectionTester(AppGraph.http.imageClient)
    private var operation: Job? = null

    init {
        viewModelScope.launch {
            currentMirror.drop(1).collect { mirror ->
                // A background fetch can change the mirror while a test is in flight.
                operation?.cancel()
                ui.update { it.copy(currentResult = it.mirrorResults[mirror.host], providerResults = emptyMap()) }
            }
        }
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (ui.value.busy) return
        ui.update { it.copy(busy = true, message = null) }
        operation = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("NetworkScreen", "网络操作失败", e)
                val reason = e.message?.takeIf { it.isNotBlank() }
                    ?: e.javaClass.simpleName.ifBlank { e.javaClass.name }
                ui.update { it.copy(message = reason) }
            } finally {
                ui.update { it.copy(busy = false, progress = null) }
            }
        }
    }

    fun cancel() { operation?.cancel() }

    fun selectMirror(host: String) {
        if (ui.value.busy) return
        AppGraph.mirrors.select(host)
    }

    fun customMirror(host: String) = runOperation {
        val normalized = MirrorManager.normalizeHost(host) ?: error("请输入有效的 HTTPS 镜像域名")
        AppGraph.settings.setCustomMirror(normalized)
        AppGraph.mirrors.addOrUpdate(Mirror(normalized, "自定义"))
        AppGraph.refreshMirrorRoutes()
        mirrors.value = AppGraph.mirrors.all()
        AppGraph.mirrors.select(normalized)
    }

    fun setDohActive(enabled: Boolean) = runOperation {
        AppGraph.settings.setDohAutoEnable(enabled)
        AppGraph.doh.setActive(enabled)
        ui.update { NetworkUiState(busy = it.busy) }
    }

    fun chooseProvider(value: String) = runOperation {
        val url = DohController.normalizeProvider(value) ?: error("请输入有效的 HTTPS DoH 地址")
        AppGraph.settings.setDohProviderUrl(url)
        AppGraph.doh.updateConfig(dohState.value.config.copy(providerUrl = url))
        ui.update { NetworkUiState(busy = it.busy, providerResults = it.providerResults) }
    }

    fun preferIpv6(enabled: Boolean) = runOperation {
        AppGraph.settings.setDohPreferIpv6(enabled)
        AppGraph.doh.updateConfig(dohState.value.config.copy(preferIpv6 = enabled))
        ui.update { NetworkUiState(busy = it.busy) }
    }

    fun testCurrent() = runOperation {
        val host = currentMirror.value.host
        ui.update { it.copy(progress = "正在请求 $host 的论坛首页", currentResult = null) }
        val result = tester.test("https://$host/index.php", AppGraph.doh.snapshot())
        ui.update { it.copy(currentResult = result, mirrorResults = it.mirrorResults + (host to result)) }
    }

    fun testMirrors() = runOperation {
        val list = AppGraph.mirrors.all()
        val dns = AppGraph.doh.snapshot()
        mirrors.value = list
        ui.update { it.copy(mirrorResults = emptyMap(), currentResult = null) }
        for ((index, mirror) in list.withIndex()) {
            ui.update { it.copy(progress = "检测镜像 ${index + 1}/${list.size} · ${mirror.label}") }
            val result = tester.test("https://${mirror.host}/index.php", dns)
            ui.update { it.copy(
                mirrorResults = it.mirrorResults + (mirror.host to result),
                currentResult = if (mirror.host == currentMirror.value.host) result else it.currentResult,
            ) }
        }
        ui.update { it.copy(message = if (it.mirrorResults.values.any { r -> r.ok })
            "检测完成，可选择已验证的镜像，或使用最快可用镜像。"
            else "当前 DNS 下没有检测到可用镜像。请查看失败原因；DoH 无法修复线路阻断或站点故障。") }
    }

    fun useFastest() {
        if (ui.value.busy) return
        ui.value.mirrorResults.filterValues { it.ok }.minByOrNull { it.value.ms }?.let { selectMirror(it.key) }
    }

    fun testProviders() = runOperation {
        val host = currentMirror.value.host
        val cfg = dohState.value.config
        val urls = (DohController.PRESETS.map { it.second } + cfg.providerUrl).distinct()
        ui.update { it.copy(providerResults = emptyMap()) }
        for ((index, url) in urls.withIndex()) {
            ui.update { it.copy(progress = "检测 DoH ${index + 1}/${urls.size} · ${DohController.labelOf(url)} → $host") }
            val result = tester.test("https://$host/index.php", AppGraph.doh.createResolver(cfg.copy(providerUrl = url)))
            ui.update { it.copy(providerResults = it.providerResults + (url to result)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(nav: androidx.navigation.NavHostController) {
    val vm: NetworkViewModel = graphViewModel { NetworkViewModel() }
    val mirrors by vm.mirrors.collectAsStateWithLifecycle()
    val current by vm.currentMirror.collectAsStateWithLifecycle()
    val doh by vm.dohState.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val urls = (DohController.PRESETS.map { it.second } + doh.config.providerUrl).distinct()
    var advanced by rememberSaveable { mutableStateOf(false) }
    var customMirror by rememberSaveable { mutableStateOf("") }
    var customDoh by rememberSaveable { mutableStateOf("") }

    Scaffold(topBar = { ForumTopBar("网络与连接", nav, actions = {}) }) { padding ->
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            SectionTitle("当前连接")
            SectionCard {
                Text(current.label, style = MaterialTheme.typography.titleMedium)
                Text(current.host, style = MaterialTheme.typography.bodyMedium)
                Hint("DNS：${if (doh.active) "DoH · ${DohController.labelOf(doh.config.providerUrl)}" else "系统 DNS"}")
                ui.currentResult?.let { ResultText(it) } ?: Hint("尚未检测当前配置")
                Button(onClick = vm::testCurrent, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("检测当前连接")
                }
                if (ui.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(ui.progress ?: "正在保存配置…", Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall)
                    if (ui.progress != null) TextButton(onClick = vm::cancel) { Text("取消检测") }
                }
                ui.message?.let { Hint(it) }
            }

            SectionTitle("镜像")
            SectionCard {
                Hint("使用下方选定的 DNS 请求各镜像首页。检测不会自动切换镜像。")
                Column(Modifier.selectableGroup()) {
                    mirrors.forEach { mirror ->
                        ChoiceRow(
                            title = mirror.label, description = mirror.host,
                            selected = current.host == mirror.host, enabled = !ui.busy,
                            onSelect = { vm.selectMirror(mirror.host) },
                            result = ui.mirrorResults[mirror.host],
                        )
                    }
                }
                OutlinedButton(onClick = vm::testMirrors, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("检测全部镜像")
                }
                if (ui.mirrorResults.values.any { it.ok }) {
                    Button(onClick = vm::useFastest, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("使用最快可用镜像")
                    }
                }
                val normalized = MirrorManager.normalizeHost(customMirror)
                OutlinedTextField(
                    value = customMirror, onValueChange = { customMirror = it },
                    label = { Text("自定义镜像域名") }, singleLine = true, enabled = !ui.busy,
                    isError = customMirror.isNotBlank() && normalized == null,
                    supportingText = { Text("填写域名或 https://域名，不含路径") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { vm.customMirror(customMirror) },
                    enabled = !ui.busy && normalized != null, modifier = Modifier.fillMaxWidth()) { Text("添加并使用") }
            }

            SectionTitle("DNS 解析")
            SectionCard {
                Column(Modifier.selectableGroup()) {
                    ChoiceRow("系统 DNS", "使用当前网络提供的解析服务", !doh.active, !ui.busy, { vm.setDohActive(false) })
                    ChoiceRow("DoH（加密 DNS）", "使用选定的 HTTPS 解析服务；选择会保存", doh.active, !ui.busy, { vm.setDohActive(true) })
                }
                Hint("DoH 只改变域名解析，不是代理。目标线路不通时，解析成功也无法打开页面。DoH 失败会明确报错，可手动切回系统 DNS。")
                if (doh.active) {
                    Text("解析服务", style = MaterialTheme.typography.titleSmall)
                    Hint("检测目标：${current.host}。以论坛首页实际加载成功为准。")
                    OutlinedButton(onClick = vm::testProviders, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("检测各 DoH 服务")
                    }
                    Column(Modifier.selectableGroup()) {
                        urls.forEach { url ->
                            ChoiceRow(DohController.labelOf(url), url, doh.config.providerUrl == url,
                                !ui.busy, { vm.chooseProvider(url) }, ui.providerResults[url])
                        }
                    }
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置") }
                    if (advanced) {
                        val normalizedDoh = DohController.normalizeProvider(customDoh)
                        OutlinedTextField(
                            value = customDoh, onValueChange = { customDoh = it },
                            label = { Text("自定义 DoH 地址") }, singleLine = true, enabled = !ui.busy,
                            isError = customDoh.isNotBlank() && normalizedDoh == null,
                            supportingText = { Text("完整 HTTPS 地址，例如 https://dns.example/dns-query") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(onClick = { vm.chooseProvider(customDoh) }, enabled = !ui.busy && normalizedDoh != null,
                            modifier = Modifier.fillMaxWidth()) { Text("保存解析服务") }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("查询并优先尝试 IPv6")
                                Hint("关闭时 DoH 只查询 IPv4")
                            }
                            Switch(checked = doh.config.preferIpv6, onCheckedChange = vm::preferIpv6, enabled = !ui.busy)
                        }
                    }
                }
            }

            NavigationRow(title = "网络诊断", subtitle = "查看请求记录和详细错误",
                icon = Icons.Outlined.Troubleshoot, onClick = { nav.navigate(Routes.DIAG) })
        }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    result: ConnectionResult? = null,
) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Hint(description)
            result?.let { ResultText(it) }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ResultText(result: ConnectionResult) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("${result.status.label} · ${result.ms} ms", style = MaterialTheme.typography.bodyMedium,
            color = if (result.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Hint(result.detail)
    }
}

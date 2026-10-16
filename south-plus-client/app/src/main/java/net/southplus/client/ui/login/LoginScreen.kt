package net.southplus.client.ui.login

import net.southplus.client.core.util.runCatchingCancellable as runCatching
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import net.southplus.client.ui.components.SectionCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.southplus.client.AppGraph
import net.southplus.client.core.session.LoginResult
import net.southplus.client.core.session.SessionManager
import net.southplus.client.ui.ForumTopBar
import net.southplus.client.ui.components.CaptchaImage
import net.southplus.client.ui.components.uiMessage
import net.southplus.client.ui.graphViewModel
import net.southplus.client.ui.theme.Spacing

class LoginViewModel : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val captchaBytes: ByteArray? = null,
        val captchaLoading: Boolean = false,
        val error: String? = null,
        val submitting: Boolean = false,
        val success: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private var captchaUrl: String? = null

    init {
        loadForm()
    }

    fun loadForm(force: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { AppGraph.session.loadLoginForm(force) }
                .onSuccess { form ->
                    captchaUrl = form.form.captchaImageUrl
                    _state.update { it.copy(loading = false) }
                    captchaUrl?.let { loadCaptcha(it) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.uiMessage()) } }
        }
    }

    /** 拉取验证码字节流。ck.php 每次请求都会生成新码覆盖 session，禁止缓存/重复请求。 */
    fun loadCaptcha(url: String) {
        viewModelScope.launch {
            _state.update { it.copy(captchaLoading = true) }
            runCatching { AppGraph.session.fetchCaptcha(url) }
                .onSuccess { bytes -> _state.update { it.copy(captchaBytes = bytes, captchaLoading = false) } }
                .onFailure { e ->
                    _state.update { it.copy(captchaLoading = false, error = "验证码加载失败: ${e.uiMessage()}") }
                }
        }
    }

    fun refreshCaptcha() {
        captchaUrl?.let { loadCaptcha(it) }
    }

    fun submit(identity: String, password: String, captcha: String, remember: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            val result = AppGraph.session.login(identity, password, captcha, remember)
            when (result) {
                is LoginResult.Success -> {
                    _state.update { it.copy(submitting = false, success = true) }
                }
                is LoginResult.Failure -> {
                    _state.update { it.copy(submitting = false, error = result.message) }
                    // 失败整表单重提：刷新验证码（方案 §2.4-2）
                    loadForm(force = true)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(nav: NavHostController) {
    val vm: LoginViewModel = graphViewModel { LoginViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()

    var identity by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var rememberPwd by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val canSubmit = !state.loading && !state.submitting && identity.isNotBlank() && password.isNotBlank()
    val submit = {
        if (canSubmit) {
            keyboard?.hide()
            vm.submit(identity, password, captcha, rememberPwd)
        }
    }

    LaunchedEffect(state.success) {
        if (state.success) nav.popBackStack()
    }

    Scaffold(topBar = { ForumTopBar("登录", nav) }) { padding ->
        Column(
            Modifier
                .padding(padding).consumeWindowInsets(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            // 品牌区
            Column(
                Modifier.fillMaxWidth().padding(vertical = Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Text("SOUTH PLUS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("欢迎回到南+", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "登录账号，继续交流与分享。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            SectionCard {
            OutlinedTextField(
                value = identity,
                onValueChange = { identity = it },
                label = { Text("用户名 / UID / Email") },
                leadingIcon = { Icon(Icons.Outlined.PersonOutline, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (passwordVisible) "隐藏密码" else "显示密码",
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            CaptchaImage(
                bytes = state.captchaBytes,
                loading = state.captchaLoading,
                onClick = { vm.refreshCaptcha() },
            )
            OutlinedTextField(
                value = captcha,
                onValueChange = { captcha = it },
                label = { Text("验证码") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rememberPwd, onCheckedChange = { rememberPwd = it })
                Text("记住密码（本地加密存储）", style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = submit,
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(if (state.submitting) "登录中…" else "登录并继续") }
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            }
            Text(
                "账号信息仅在本地加密保存。第三方客户端与南+官方无关，使用前请了解站点社区规则。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { vm.loadForm(force = true) }) { Text("无法登录？刷新表单/验证码") }
        }
    }
}

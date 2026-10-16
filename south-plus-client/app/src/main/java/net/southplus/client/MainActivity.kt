package net.southplus.client

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import net.southplus.client.ui.AppRoot
import net.southplus.client.ui.theme.SouthPlusTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by AppGraph.settings.themeMode.collectAsStateWithLifecycle(initialValue = 0)
            val initialization by AppGraph.initialization.collectAsStateWithLifecycle()
            // 应用内主题三态（0 跟随 / 1 浅色 / 2 深色）可能与系统不同：
            // 系统栏图标对比度必须跟随应用实际生效的模式，否则强制深色时状态栏图标不可见。
            val systemDark = isSystemInDarkTheme()
            val appDark = when (themeMode) {
                1 -> false
                2 -> true
                else -> systemDark
            }
            LaunchedEffect(appDark) {
                val style = if (appDark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            SouthPlusTheme(themeMode = themeMode) {
                Surface(Modifier.fillMaxSize()) {
                    when (val status = initialization) {
                        AppGraph.Initialization.Ready -> AppRoot()
                        AppGraph.Initialization.Loading -> net.southplus.client.ui.components.LoadingPane(Modifier.safeDrawingPadding())
                        is AppGraph.Initialization.Failed -> net.southplus.client.ui.components.ErrorPane(
                            modifier = Modifier.safeDrawingPadding(),
                            message = status.message, onRetry = { AppGraph.init(applicationContext) },
                        )
                    }
                }
            }
        }
    }
}

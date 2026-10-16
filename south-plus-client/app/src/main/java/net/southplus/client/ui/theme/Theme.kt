package net.southplus.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 视觉方向：「晨雾 Mist」— 冷调雾灰中性底 + 青碧单一强调。
 * 中性色不带暖偏移（雾白 / 冷调近黑），强调色为青碧（只用于可操作 / 被选中 / 未读）；
 * 层级靠字重、发丝分隔线与 M3 表面色阶（surfaceContainer 系）表达，全局无投影。
 * 提案与备选强调色见 /design/THEME-PROPOSAL.md。
 */

// ---------- 扩展语义色（精华星标 / 认证标识 / 在线状态） ----------

private val DigestGoldLight = Color(0xFF8A6A08)
private val DigestGoldDark = Color(0xFFF2C14E)
private val CertRedLight = Color(0xFFB3261E)
private val CertRedDark = Color(0xFFFF897D)
private val OnlineGreenLight = Color(0xFF15803D)
private val OnlineGreenDark = Color(0xFF4ADE80)

@Immutable
data class ExtendedColors(
    val digestGold: Color,
    val certRed: Color,
    val onlineGreen: Color,
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(DigestGoldLight, CertRedLight, OnlineGreenLight)
}

val MaterialTheme.extended: ExtendedColors
    @Composable get() = LocalExtendedColors.current

// ---------- 深色：冷调近黑 + 青碧 ----------

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D4DA),
    onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F52),
    onPrimaryContainer = Color(0xFF9CF1F4),
    secondary = Color(0xFFC2C6DD),
    onSecondary = Color(0xFF2B3042),
    secondaryContainer = Color(0xFF414759),
    onSecondaryContainer = Color(0xFFDEE1FB),
    tertiary = DigestGoldDark,
    onTertiary = Color(0xFF3D2E00),
    tertiaryContainer = Color(0xFF5A4600),
    onTertiaryContainer = Color(0xFFFFE08D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111216),
    onBackground = Color(0xFFE2E2EA),
    surface = Color(0xFF111216),
    onSurface = Color(0xFFE2E2EA),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC6C6D0),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF2B2C33),
    surfaceDim = Color(0xFF111216),
    surfaceBright = Color(0xFF3B3C42),
    surfaceContainerLowest = Color(0xFF0C0D10),
    surfaceContainerLow = Color(0xFF191A1E),
    surfaceContainer = Color(0xFF1D1E23),
    surfaceContainerHigh = Color(0xFF27282E),
    surfaceContainerHighest = Color(0xFF32333A),
    inverseSurface = Color(0xFFE2E2EA),
    inverseOnSurface = Color(0xFF2F3036),
    inversePrimary = Color(0xFF00797D),
)

// ---------- 浅色：雾白 + 青碧 ----------

private val LightColors = lightColorScheme(
    primary = Color(0xFF00797D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF1F4),
    onPrimaryContainer = Color(0xFF00494D),
    secondary = Color(0xFF595D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDEE1FB),
    onSecondaryContainer = Color(0xFF161B2C),
    tertiary = DigestGoldLight,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF2E3AC),
    onTertiaryContainer = Color(0xFF281D00),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF8F9FB),
    onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFF8F9FB),
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFDCDEE6),
    surfaceDim = Color(0xFFD9DADF),
    surfaceBright = Color(0xFFF8F9FB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCFCFE),
    surfaceContainer = Color(0xFFF2F3F6),
    surfaceContainerHigh = Color(0xFFEDEEF2),
    surfaceContainerHighest = Color(0xFFE7E8ED),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    inversePrimary = Color(0xFF80D4DA),
)

/** 深色模式（方案 §6.10：跟随系统 / 手动），themeMode: 0 系统 / 1 浅色 / 2 深色 */
@Composable
fun SouthPlusTheme(
    themeMode: Int = 0,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val extended = if (darkTheme) {
        ExtendedColors(DigestGoldDark, CertRedDark, OnlineGreenDark)
    } else {
        ExtendedColors(DigestGoldLight, CertRedLight, OnlineGreenLight)
    }
    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

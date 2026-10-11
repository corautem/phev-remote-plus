package io.github.anwo91.phevlink.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// PHEV Link brand — a calm EV teal with an amber charge accent. These are the app's identity, used by
// default; "Match my wallpaper" (Material You) is opt-in in Settings.
private val Teal = Color(0xFF0E7C6D)
private val TealBright = Color(0xFF45D3BB)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9FF2E2),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF46635C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9E9E0),
    onSecondaryContainer = Color(0xFF04201A),
    tertiary = Color(0xFFA85C12),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC2),
    onTertiaryContainer = Color(0xFF301400),
    background = Color(0xFFF4F8F6),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFFBFDFC),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFD9E5E0),
    onSurfaceVariant = Color(0xFF3C4945),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F6F4),
    surfaceContainer = Color(0xFFEBF2EF),
    surfaceContainerHigh = Color(0xFFE5ECE9),
    surfaceContainerHighest = Color(0xFFDFE7E4),
    outline = Color(0xFF6C7A75),
    outlineVariant = Color(0xFFBFC9C4),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = TealBright,
    onPrimary = Color(0xFF003730),
    primaryContainer = Color(0xFF005045),
    onPrimaryContainer = Color(0xFFB8F0E4),
    secondary = Color(0xFFB0CCC4),
    onSecondary = Color(0xFF1B342F),
    secondaryContainer = Color(0xFF324B45),
    onSecondaryContainer = Color(0xFFCCE8E0),
    tertiary = Color(0xFFFFB77C),
    onTertiary = Color(0xFF4D2600),
    tertiaryContainer = Color(0xFF6D3A00),
    onTertiaryContainer = Color(0xFFFFDCC2),
    background = Color(0xFF0D1417),
    onBackground = Color(0xFFDDE4E1),
    surface = Color(0xFF0D1417),
    onSurface = Color(0xFFDDE4E1),
    surfaceVariant = Color(0xFF3C4945),
    onSurfaceVariant = Color(0xFFBEC9C4),
    surfaceContainerLowest = Color(0xFF080E10),
    surfaceContainerLow = Color(0xFF151D20),
    surfaceContainer = Color(0xFF182327),
    surfaceContainerHigh = Color(0xFF212D32),
    surfaceContainerHighest = Color(0xFF2C383D),
    outline = Color(0xFF889390),
    outlineVariant = Color(0xFF3C4945),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Status colours that read well in both themes. */
@Immutable
data class StatusColors(val good: Color, val warn: Color, val bad: Color, val idle: Color, val charge: Color)

val LocalStatus = staticCompositionLocalOf {
    StatusColors(Color(0xFF1E8E3E), Color(0xFFC4581C), Color(0xFFB3261E), Color(0xFF7A8A92), Color(0xFF1E88E5))
}

@Composable
fun PhevTheme(dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    val status = if (dark) {
        StatusColors(Color(0xFF7BDA8F), Color(0xFFFFB070), Color(0xFFFF8A80), Color(0xFF93A4AD), Color(0xFF90CAF9))
    } else {
        StatusColors(Color(0xFF1E8E3E), Color(0xFFC4581C), Color(0xFFB3261E), Color(0xFF6B7B83), Color(0xFF1565C0))
    }
    val base = Typography()
    val typography = base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalStatus provides status) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}

val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)

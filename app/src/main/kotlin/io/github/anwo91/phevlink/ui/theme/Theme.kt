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

private val Teal = Color(0xFF0F8A7A)
private val TealLight = Color(0xFF3FD0B8)
private val Night = Color(0xFF0D1417)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F0E4),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635E),
    tertiary = Color(0xFFB4651E),
    background = Color(0xFFF4F6F7),
    surface = Color(0xFFF9FBFB),
    surfaceContainer = Color(0xFFEDF1F1),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005045),
    onPrimaryContainer = Color(0xFFB8F0E4),
    secondary = Color(0xFFB1CCC5),
    tertiary = Color(0xFFFFB77C),
    background = Night,
    surface = Night,
    surfaceContainer = Color(0xFF162126),
    error = Color(0xFFFF8A80),
)

/** Status colours that read well in both themes. */
@Immutable
data class StatusColors(val good: Color, val warn: Color, val bad: Color, val idle: Color, val charge: Color)

val LocalStatus = staticCompositionLocalOf {
    StatusColors(Color(0xFF1E8E3E), Color(0xFFC4581C), Color(0xFFB3261E), Color(0xFF7A8A92), Color(0xFF1E88E5))
}

@Composable
fun PhevTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
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
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalStatus provides status) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}

val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)

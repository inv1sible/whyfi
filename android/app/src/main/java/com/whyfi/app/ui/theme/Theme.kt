package com.whyfi.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.whyfi.app.data.ThemePreference

// Same teal accent as the PWA (frontend/src/index.css --accent) so the two
// clients feel like one product.
private val AccentTeal = Color(0xFF2DD4BF)
private val AccentTealDark = Color(0xFF0D9488)

private val WhyfiDarkColors = darkColorScheme(
    primary = AccentTeal,
    onPrimary = Color(0xFF06251F),
    secondary = AccentTeal,
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF1A2540),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF16213A),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF2C3A58),
    error = Color(0xFFF87171),
)

private val WhyfiLightColors = lightColorScheme(
    primary = AccentTealDark,
    onPrimary = Color(0xFFECFDF9),
    secondary = AccentTealDark,
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF4F6FB),
    onSurfaceVariant = Color(0xFF5B6B83),
    outline = Color(0xFFDDE3EE),
    error = Color(0xFFDC2626),
)

@Composable
fun WhyfiTheme(themePreference: ThemePreference, content: @Composable () -> Unit) {
    val useDark = when (themePreference) {
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (useDark) WhyfiDarkColors else WhyfiLightColors,
        content = content,
    )
}

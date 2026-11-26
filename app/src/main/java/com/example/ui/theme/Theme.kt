package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LensColorScheme = darkColorScheme(
    primary = LensColors.Cyan,
    onPrimary = LensColors.OnCyan,
    primaryContainer = LensColors.CyanContainer,
    onPrimaryContainer = Color(0xFFCFFAFE),
    secondary = LensColors.Lavender,
    onSecondary = Color(0xFF1E1B4B),
    tertiary = LensColors.Pink,
    onTertiary = Color(0xFF3B0A24),
    background = LensColors.Background,
    onBackground = LensColors.Text,
    surface = LensColors.Background,
    onSurface = LensColors.Text,
    surfaceVariant = LensColors.Surface,
    onSurfaceVariant = LensColors.TextMuted,
    surfaceContainerLowest = LensColors.Background,
    surfaceContainerLow = LensColors.Background,
    surfaceContainer = LensColors.Surface,
    surfaceContainerHigh = LensColors.SurfaceHigh,
    surfaceContainerHighest = LensColors.SurfaceHigh,
    outline = LensColors.Outline,
    outlineVariant = LensColors.Divider,
    error = LensColors.Error,
    onError = Color(0xFF450A0A),
    errorContainer = LensColors.ErrorContainer,
    onErrorContainer = LensColors.OnErrorContainer
)

/**
 * One fixed look. The wallpaper-based dynamic colours used before gave buttons, fields and sliders a different
 * colour on every phone, clashing with the colours set by hand.
 */
@Composable
fun MyApplicationTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LensColorScheme, typography = Typography, content = content)
}

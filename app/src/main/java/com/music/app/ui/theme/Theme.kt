package com.music.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = GreenPrimary,
    onPrimary = OnGreenPrimary,
    primaryContainer = GreenPrimaryVariant,
    onPrimaryContainer = OnGreenPrimary,
    secondary = GreenSecondary,
    onSecondary = OnGreenPrimary,
    tertiary = GreenTertiary,
    onTertiary = OnGreenPrimary,
    background = DarkBackground,
    onBackground = OnDarkBackground,
    surface = DarkSurface,
    onSurface = OnDarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = OnDarkSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    inverseSurface = OnDarkSurface,
    inverseOnSurface = DarkBackground,
    surfaceTint = GreenPrimary,
)

private val LightColorScheme = darkColorScheme(
    primary = GreenPrimary,
    onPrimary = OnGreenPrimary,
    primaryContainer = GreenPrimaryVariant,
    onPrimaryContainer = OnGreenPrimary,
    secondary = GreenSecondary,
    onSecondary = OnGreenPrimary,
    tertiary = GreenTertiary,
    onTertiary = OnGreenPrimary,
    background = DarkBackground,
    onBackground = OnDarkBackground,
    surface = DarkSurface,
    onSurface = OnDarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = OnDarkSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    inverseSurface = OnDarkSurface,
    inverseOnSurface = DarkBackground,
    surfaceTint = GreenPrimary,
)

@Composable
fun MusicTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

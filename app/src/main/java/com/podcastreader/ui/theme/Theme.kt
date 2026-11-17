package com.podcastreader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF003258),
    secondary = Color(0xFFCE93D8),
    background = Color(0xFF101216),
    surface = Color(0xFF181B22),
    surfaceVariant = Color(0xFF232733),
    onBackground = Color(0xFFE6E8EC),
    onSurface = Color(0xFFE6E8EC)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    secondary = Color(0xFF6A1B9A),
    background = Color(0xFFF8F9FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEF1F7),
    onBackground = Color(0xFF1A1C20),
    onSurface = Color(0xFF1A1C20)
)

@Composable
fun PodcastReaderTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}

package __APP_ID__.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF7C5CFF)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A2058),
    onPrimaryContainer = Color(0xFFE6DEFF),
    background = Color(0xFF0B0B10),
    onBackground = Color(0xFFEAEAF2),
    surface = Color(0xFF0B0B10),
    onSurface = Color(0xFFEAEAF2),
    surfaceVariant = Color(0xFF1A1A24),
    onSurfaceVariant = Color(0xFFB4B4C4),
    error = Color(0xFFFF6B6B),
)

private val LightScheme = lightColorScheme(primary = Accent)

/** Dark-first: dark unless explicitly asked otherwise (a Settings toggle comes later). */
@Composable
fun DarkTubeTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}

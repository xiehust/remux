package dev.remux.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Remux is dark-first (the PRD's MVP ships a dark theme). The dark scheme is the
// default; a light scheme exists for completeness but dark is used unless the
// caller explicitly opts out.
private val RemuxDark = darkColorScheme(
    primary = Color(0xFF7CF5C4),
    onPrimary = Color(0xFF00382A),
    secondary = Color(0xFF80CBC4),
    background = Color(0xFF0B0F0E),
    onBackground = Color(0xFFE3E6E4),
    surface = Color(0xFF14191A),
    onSurface = Color(0xFFE3E6E4),
    surfaceVariant = Color(0xFF1F2624),
    error = Color(0xFFFF6E6E),
)

private val RemuxLight = lightColorScheme(
    primary = Color(0xFF006B53),
    background = Color(0xFFF7FAF8),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun RemuxTheme(
    useDarkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) RemuxDark else RemuxLight,
        typography = Typography(),
        content = content,
    )
}

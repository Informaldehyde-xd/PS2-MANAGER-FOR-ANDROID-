package com.ps2manager.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// OPL-inspired palette: near-black navy background, deep blue surfaces, bright blue accent.
val Ps2Background = Color(0xFF070B14)
val Ps2Surface = Color(0xFF101A2E)
val Ps2SurfaceElevated = Color(0xFF16223D)
val Ps2Primary = Color(0xFF3B82F6)
val Ps2PrimaryVariant = Color(0xFF1E40AF)
val Ps2Accent = Color(0xFF60A5FA)
val Ps2OnBackground = Color(0xFFE5EAF3)
val Ps2OnSurfaceMuted = Color(0xFF8B98B4)
val Ps2Error = Color(0xFFEF4444)
val Ps2Success = Color(0xFF22C55E)

private val Ps2ColorScheme = darkColorScheme(
    primary = Ps2Primary,
    onPrimary = Color.White,
    secondary = Ps2Accent,
    onSecondary = Color.White,
    background = Ps2Background,
    onBackground = Ps2OnBackground,
    surface = Ps2Surface,
    onSurface = Ps2OnBackground,
    surfaceVariant = Ps2SurfaceElevated,
    onSurfaceVariant = Ps2OnSurfaceMuted,
    error = Ps2Error,
    outline = Ps2OnSurfaceMuted
)

@Composable
fun Ps2ManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Ps2ColorScheme,
        content = content
    )
}

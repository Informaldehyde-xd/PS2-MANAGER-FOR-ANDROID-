package com.ps2manager.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// "Control Deck" palette: near-black ink background, deep navy panels,
// bright cyan/electric-blue accents with a soft glow feel throughout the UI.
val Ps2Background = Color(0xFF020713)          // --ink
val Ps2Surface = Color(0xFF07152D)             // --panel
val Ps2SurfaceElevated = Color(0xFF0E2244)     // slightly brighter panel for elevated cards/rows
val Ps2Primary = Color(0xFF1679FF)             // --electric
val Ps2PrimaryVariant = Color(0xFF0E4FBF)
val Ps2Accent = Color(0xFF4FD1FF)              // --blue
val Ps2OnBackground = Color(0xFFEEFAFF)
val Ps2OnSurfaceMuted = Color(0xFF9BBBD4)
val Ps2Error = Color(0xFFEF4444)
val Ps2Success = Color(0xFF22C55E)

// Hairline borders / dividers, echoing --line: rgba(104, 193, 255, 0.18)
val Ps2Line = Color(0x2E68C1FF)
// Slightly stronger border used for active/selected control-deck cards
val Ps2LineActive = Color(0xC752CCFF)
// Soft glow tint used behind active cards/panels
val Ps2Glow = Color(0x241679FF)

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
    outline = Ps2Line
)

@Composable
fun Ps2ManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Ps2ColorScheme,
        content = content
    )
}

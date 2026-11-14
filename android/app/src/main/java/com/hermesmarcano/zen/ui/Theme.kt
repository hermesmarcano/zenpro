package com.hermesmarcano.zen.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** ZEN es oscura siempre: serio y sobrio, sin colores de "app de hábitos". El ámbar es el único acento. */
private val ZenColors = darkColorScheme(
    primary = Color(0xFFE8B04A),
    onPrimary = Color(0xFF1A1200),
    primaryContainer = Color(0xFF3A2D10),
    onPrimaryContainer = Color(0xFFF6DDA6),
    secondary = Color(0xFFB9B4A8),
    onSecondary = Color(0xFF16150F),
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFECE9E3),
    surface = Color(0xFF17171A),
    onSurface = Color(0xFFECE9E3),
    surfaceVariant = Color(0xFF232327),
    onSurfaceVariant = Color(0xFFB4B1AA),
    outline = Color(0xFF4A4A52),
    outlineVariant = Color(0xFF2E2E34),
    error = Color(0xFFFF7B6E),
    onError = Color(0xFF2A0603),
)

@Composable
fun ZenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ZenColors, content = content)
}

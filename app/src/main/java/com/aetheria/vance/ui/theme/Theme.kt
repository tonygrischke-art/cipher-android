package com.aetheria.vance.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cipher brand colors
val CipherPurple = Color(0xFF6C63FF)
val CipherPurpleDark = Color(0xFF4A42D4)
val CipherCyan = Color(0xFF03DAC6)
val CipherAmber = Color(0xFFFFA000)
val CipherGreen = Color(0xFF4CAF50)
val CipherRed = Color(0xFFF44336)
val CipherSurface = Color(0xFF1A1A2E)
val CipherSurfaceVariant = Color(0xFF252540)
val CipherBackground = Color(0xFF12121F)

private val CipherColorScheme = darkColorScheme(
    primary = CipherPurple,
    onPrimary = Color.White,
    primaryContainer = CipherPurpleDark,
    secondary = CipherCyan,
    onSecondary = Color.Black,
    tertiary = CipherAmber,
    onTertiary = Color.Black,
    surface = CipherSurface,
    surfaceVariant = CipherSurfaceVariant,
    onSurface = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFFB0B0C0),
    background = CipherBackground,
    onBackground = Color(0xFFE0E0E0),
    error = CipherRed,
    onError = Color.White
)

@Composable
fun CipherTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CipherColorScheme,
        typography = Typography(),
        content = content
    )
}

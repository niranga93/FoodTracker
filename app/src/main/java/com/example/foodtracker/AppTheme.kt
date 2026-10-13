package com.example.foodtracker

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F0D5),
    onPrimaryContainer = Color(0xFF0B2E0E),
    secondary = Color(0xFFEF6C00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE3C7),
    onSecondaryContainer = Color(0xFF3A1B00),
    background = Color(0xFFF4F8F2),
    onBackground = Color(0xFF181D17),
    surface = Color(0xFFF4F8F2),
    onSurface = Color(0xFF181D17),
    surfaceVariant = Color(0xFFDDE6DA),
    onSurfaceVariant = Color(0xFF4A5248),
    outline = Color(0xFF727970),
    error = Color(0xFFC62828)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF0B2E0E),
    primaryContainer = Color(0xFF1F4D22),
    onPrimaryContainer = Color(0xFFD7F0D5),
    secondary = Color(0xFFFFB74D),
    onSecondary = Color(0xFF3A1B00),
    secondaryContainer = Color(0xFF5C3300),
    onSecondaryContainer = Color(0xFFFFE3C7),
    background = Color(0xFF10150F),
    onBackground = Color(0xFFE0E4DC),
    surface = Color(0xFF10150F),
    onSurface = Color(0xFFE0E4DC),
    surfaceVariant = Color(0xFF2B342A),
    onSurfaceVariant = Color(0xFFC0C9BD),
    outline = Color(0xFF8A9388),
    error = Color(0xFFFFB4AB)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        content = content
    )
}

@Composable
fun cardContainer(): Color =
    if (isSystemInDarkTheme()) Color(0xFF1B241A) else Color.White
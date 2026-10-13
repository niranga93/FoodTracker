package com.example.nourio

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Nourio palette: green = eaten, ember = burned, sky = balance and water.
val NourioInk = Color(0xFF0E1A13)
val NourioMint = Color(0xFF3DDC97)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0A7A50),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F5E6),
    onPrimaryContainer = Color(0xFF06311F),
    secondary = Color(0xFFE8590C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE3D3),
    onSecondaryContainer = Color(0xFF4A1A02),
    tertiary = Color(0xFF1C8FE8),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD9EDFF),
    onTertiaryContainer = Color(0xFF06284A),
    background = Color(0xFFF5F7F4),
    onBackground = Color(0xFF101A14),
    surface = Color.White,
    onSurface = Color(0xFF101A14),
    surfaceVariant = Color(0xFFE9EFEA),
    onSurfaceVariant = Color(0xFF566A5E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9FBF8),
    surfaceContainer = Color(0xFFF1F5F1),
    surfaceContainerHigh = Color(0xFFEBF1EC),
    surfaceContainerHighest = Color(0xFFE4ECE6),
    outline = Color(0xFFD3DDD6),
    outlineVariant = Color(0xFFE4EBE6),
    error = Color(0xFFD92D20),
    errorContainer = Color(0xFFFDE4E1)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3DDC97),
    onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF0F3D29),
    onPrimaryContainer = Color(0xFFBDF5D8),
    secondary = Color(0xFFFF9A66),
    onSecondary = Color(0xFF4A1A02),
    secondaryContainer = Color(0xFF5A2A10),
    onSecondaryContainer = Color(0xFFFFDCCB),
    tertiary = Color(0xFF7CC4FF),
    onTertiary = Color(0xFF05264A),
    tertiaryContainer = Color(0xFF0E3A63),
    onTertiaryContainer = Color(0xFFD2E9FF),
    background = Color(0xFF0C130F),
    onBackground = Color(0xFFE6EFE9),
    surface = Color(0xFF131C16),
    onSurface = Color(0xFFE6EFE9),
    surfaceVariant = Color(0xFF1C2820),
    onSurfaceVariant = Color(0xFF9DB0A3),
    surfaceContainerLowest = Color(0xFF090F0B),
    surfaceContainerLow = Color(0xFF101812),
    surfaceContainer = Color(0xFF151F18),
    surfaceContainerHigh = Color(0xFF1A261E),
    surfaceContainerHighest = Color(0xFF202E25),
    outline = Color(0xFF2C3A31),
    outlineVariant = Color(0xFF223027),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF5C1A16)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

// Only the styles Nourio tunes; every other style keeps the Material default.
private val AppTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}

@Composable
fun cardContainer(): Color =
    if (isSystemInDarkTheme()) Color(0xFF131C16) else Color.White

/** Hairline used around cards instead of a shadow. */
@Composable
fun cardOutline(): Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)

/** Bottom navigation: a mint pill behind the selected tab. */
@Composable
fun appNavColors(): NavigationBarItemColors = NavigationBarItemDefaults.colors(
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
)

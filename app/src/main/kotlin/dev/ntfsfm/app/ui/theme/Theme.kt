package dev.ntfsfm.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// A vibrant indigo/teal/coral palette rather than wallpaper colours: the app should feel bright.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E0FF),
    onPrimaryContainer = Color(0xFF1A1773),
    secondary = Color(0xFF0D9488),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCF5EE),
    onSecondaryContainer = Color(0xFF00382F),
    tertiary = Color(0xFFE5484D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD8),
    onTertiaryContainer = Color(0xFF5C0A10),
    background = Color(0xFFF7F6FF),
    onBackground = Color(0xFF1B1B26),
    surface = Color(0xFFF7F6FF),
    onSurface = Color(0xFF1B1B26),
    surfaceVariant = Color(0xFFE4E2F5),
    onSurfaceVariant = Color(0xFF474659),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F0FD),
    surfaceContainer = Color(0xFFEBEAFA),
    surfaceContainerHigh = Color(0xFFE5E4F6),
    surfaceContainerHighest = Color(0xFFDFDEF2),
    outline = Color(0xFF77768A),
    outlineVariant = Color(0xFFC8C6DB),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB4B2FF),
    onPrimary = Color(0xFF221C8F),
    primaryContainer = Color(0xFF3A33C2),
    onPrimaryContainer = Color(0xFFE0E0FF),
    secondary = Color(0xFF5EDBC9),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF005046),
    onSecondaryContainer = Color(0xFFCCF5EE),
    tertiary = Color(0xFFFFB3AF),
    onTertiary = Color(0xFF68000F),
    tertiaryContainer = Color(0xFF9E2A2F),
    onTertiaryContainer = Color(0xFFFFDAD8),
    background = Color(0xFF12121C),
    onBackground = Color(0xFFE5E3F3),
    surface = Color(0xFF12121C),
    onSurface = Color(0xFFE5E3F3),
    surfaceVariant = Color(0xFF474659),
    onSurfaceVariant = Color(0xFFC8C6DB),
    surfaceContainerLowest = Color(0xFF0D0D16),
    surfaceContainerLow = Color(0xFF1A1A25),
    surfaceContainer = Color(0xFF1F1E2A),
    surfaceContainerHigh = Color(0xFF292835),
    surfaceContainerHighest = Color(0xFF343340),
    outline = Color(0xFF918FA4),
    outlineVariant = Color(0xFF474659),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun NtfsFileManagerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        shapes = AppShapes,
        content = content,
    )
}

package dev.ntfsfm.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ntfsfm.app.R

// One confident blue accent, violet and teal as supporting colours, soft cool neutrals.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF3B5BFD),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF),
    onPrimaryContainer = Color(0xFF0B1A6B),
    secondary = Color(0xFF0FA3A3),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDF3F0),
    onSecondaryContainer = Color(0xFF003A39),
    tertiary = Color(0xFFF06A4E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDD5),
    onTertiaryContainer = Color(0xFF5A1405),
    background = Color(0xFFF2F5FA),
    onBackground = Color(0xFF151A2D),
    surface = Color(0xFFF7F9FC),
    onSurface = Color(0xFF151A2D),
    surfaceVariant = Color(0xFFE3E7F0),
    onSurfaceVariant = Color(0xFF5B6275),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F6FB),
    surfaceContainer = Color(0xFFEEF1F7),
    surfaceContainerHigh = Color(0xFFF9FAFD),
    surfaceContainerHighest = Color(0xFFE2E6EF),
    outline = Color(0xFF8C93A6),
    outlineVariant = Color(0xFFD7DCE6),
    error = Color(0xFFE5484D),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8EA2FF),
    onPrimary = Color(0xFF0B1A6B),
    primaryContainer = Color(0xFF283FC4),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFF5FD8D2),
    onSecondary = Color(0xFF003A39),
    secondaryContainer = Color(0xFF005553),
    onSecondaryContainer = Color(0xFFCDF3F0),
    tertiary = Color(0xFFFFA48F),
    onTertiary = Color(0xFF5A1405),
    tertiaryContainer = Color(0xFF8A2E19),
    onTertiaryContainer = Color(0xFFFFDDD5),
    background = Color(0xFF0A0D18),
    onBackground = Color(0xFFE6E9F4),
    surface = Color(0xFF10141F),
    onSurface = Color(0xFFE6E9F4),
    surfaceVariant = Color(0xFF2A3042),
    onSurfaceVariant = Color(0xFFA4ACC2),
    surfaceContainerLowest = Color(0xFF080A12),
    surfaceContainerLow = Color(0xFF131826),
    surfaceContainer = Color(0xFF181D2C),
    surfaceContainerHigh = Color(0xFF1C2232),
    surfaceContainerHighest = Color(0xFF262C3D),
    outline = Color(0xFF6E768C),
    outlineVariant = Color(0xFF2A3042),
    error = Color(0xFFFF8A8E),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@OptIn(ExperimentalTextApi::class)
private fun manrope(weight: Int) =
    Font(R.font.manrope, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

private fun style(size: Int, line: Int, weight: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = Manrope,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

private val AppTypography = Typography(
    displaySmall = style(36, 44, 800, -0.5),
    headlineLarge = style(32, 40, 800, -0.5),
    headlineMedium = style(28, 36, 800, -0.3),
    headlineSmall = style(24, 32, 700, -0.2),
    titleLarge = style(20, 28, 700),
    titleMedium = style(16, 24, 700),
    titleSmall = style(14, 20, 700),
    bodyLarge = style(16, 24, 500),
    bodyMedium = style(14, 20, 500),
    bodySmall = style(12, 16, 500),
    labelLarge = style(14, 20, 700),
    labelMedium = style(12, 16, 600),
    labelSmall = style(11, 16, 600, 0.2),
)

/** The effective theme, which may differ from the system's once the user picks one. */
val LocalDarkTheme = staticCompositionLocalOf { false }

/** Flips light/dark; provided by MainActivity. */
val LocalToggleDarkTheme = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun NtfsFileManagerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}

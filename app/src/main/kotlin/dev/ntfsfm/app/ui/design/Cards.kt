package dev.ntfsfm.app.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.ntfsfm.app.ui.theme.LocalDarkTheme

/**
 * A solid card: white with a soft shadow in light mode, a raised dark surface with a
 * hairline edge in dark mode. [elevation] scales the shadow (bars floating over lists use more).
 */
@Composable
fun Modifier.card(shape: Shape = RectangleShape, elevation: Float = 1f, tint: Color = Color.Transparent, outline: Boolean = true): Modifier {
    val dark = LocalDarkTheme.current
    val scheme = MaterialTheme.colorScheme
    val base = if (dark) this else this.shadow((4 * elevation).dp, shape, ambientColor = Color(0xFF1B2A5C), spotColor = Color(0xFF1B2A5C).copy(alpha = 0.18f))
    val filled = base
        .clip(shape)
        .background(if (dark) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest)
        .background(tint)
    return if (dark && outline) filled.border(1.dp, scheme.outlineVariant.copy(alpha = 0.7f), shape) else filled
}

/** The flat page background every screen sits on. */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // No Surface sits above the screens, so give text its default colour here.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) { content() }
    }
}

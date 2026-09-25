package dev.codexops.client

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class AppWindowClass(
    val compactWidth: Boolean,
    val compactHeight: Boolean,
    val nearlySquare: Boolean,
) {
    val coverScreen: Boolean
        get() = compactWidth && (compactHeight || nearlySquare)
}

internal fun classifyWindow(width: Dp, height: Dp) =
    AppWindowClass(
        compactWidth = width < 600.dp,
        compactHeight = height < 480.dp,
        nearlySquare = height < 600.dp && height.value / width.value < 1.35f,
    )

internal val LocalAppWindowClass = staticCompositionLocalOf {
    AppWindowClass(compactWidth = true, compactHeight = false, nearlySquare = false)
}

@Composable
internal fun AdaptiveWindow(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalAppWindowClass provides classifyWindow(maxWidth, maxHeight),
            content = content,
        )
    }
}

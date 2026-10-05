package dev.codexops.client

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

@Composable
internal fun ImageViewer(bitmap: Bitmap, description: String, onDismiss: () -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    fun transform(targetZoom: Float, focus: Offset, pan: Offset = Offset.Zero) {
        if (viewport.width == 0 || viewport.height == 0) return
        val nextZoom = targetZoom.coerceIn(1f, 5f)
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        // Keep the image point under the fingers stationary while scaling.
        val nextOffset = (offset - (focus - center)) * (nextZoom / zoom) +
            (focus - center) + pan
        val fit = minOf(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
        val maxX = ((bitmap.width * fit * nextZoom - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((bitmap.height * fit * nextZoom - viewport.height) / 2f).coerceAtLeast(0f)
        offset = Offset(nextOffset.x.coerceIn(-maxX, maxX), nextOffset.y.coerceIn(-maxY, maxY))
        zoom = nextZoom
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onDismiss, Modifier.testTag("close-image")) { Text("Close") }
                    TextButton(
                        { transform(zoom / 1.5f, Offset(viewport.width / 2f, viewport.height / 2f)) },
                        enabled = zoom > 1f,
                    ) { Text("Zoom out") }
                    TextButton(
                        { transform(zoom * 1.5f, Offset(viewport.width / 2f, viewport.height / 2f)) },
                        enabled = zoom < 5f,
                    ) { Text("Zoom in") }
                    TextButton({ zoom = 1f; offset = Offset.Zero }) { Text("Fit") }
                }
                Box(
                    Modifier.fillMaxWidth().weight(1f).clipToBounds()
                        .onSizeChanged {
                            if (viewport != it) {
                                viewport = it
                                zoom = 1f
                                offset = Offset.Zero
                            }
                        }
                        .testTag("image-viewport")
                        .semantics { stateDescription = "${(zoom * 100).roundToInt()}%" }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { focus ->
                                if (zoom > 1f) { zoom = 1f; offset = Offset.Zero }
                                else transform(3f, focus)
                            })
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { centroid, pan, change, _ ->
                                transform(zoom * change, centroid, pan)
                            }
                        },
                ) {
                    Image(
                        bitmap.asImageBitmap(), description,
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = offset.x
                            translationY = offset.y
                        },
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}

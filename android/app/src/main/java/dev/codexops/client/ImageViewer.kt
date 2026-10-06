package dev.codexops.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportedUri by remember(bitmap) { mutableStateOf<Uri?>(null) }

    fun exportImage(share: Boolean) {
        if (exporting) return
        exporting = true
        scope.launch(Dispatchers.Main.immediate) {
            try {
                val uri = exportedUri ?: withContext(Dispatchers.IO) {
                    val root = File(context.cacheDir, "preview-images").apply { mkdirs() }
                    // Keep exports available after closing the viewer for clipboard/share consumers.
                    val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                    root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
                    val file = File(root, "image-${UUID.randomUUID()}.png")
                    try {
                        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    } catch (error: Exception) {
                        file.delete()
                        throw error
                    }
                }.also { exportedUri = it }
                val clip = ClipData.newUri(context.contentResolver, "Image", uri)
                if (share) {
                    context.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("image/png")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .apply { clipData = clip }
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        "Share image",
                    ))
                } else {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    Toast.makeText(context, "Image copied", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                Toast.makeText(context, "Could not ${if (share) "share" else "copy"} image.", Toast.LENGTH_SHORT).show()
            } finally {
                exporting = false
            }
        }
    }

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
                    TextButton({ exportImage(false) }, enabled = !exporting) { Text("Copy") }
                    TextButton({ exportImage(true) }, enabled = !exporting) { Text("Share") }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                            detectTapGestures(onLongPress = { exportImage(false) }, onDoubleTap = { focus ->
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

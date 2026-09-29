package dev.codexops.client

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.codexops.core.MediaLocation
import dev.codexops.core.MediaRef
import dev.codexops.core.AttachmentKind
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object BitmapMemoryCache : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

internal suspend fun decodedBitmap(key: String, bytes: ByteArray, maximum: Int): Bitmap =
    withContext(Dispatchers.Default) {
        val cacheKey = "$key/$maximum"
        BitmapMemoryCache.get(cacheKey)?.let { return@withContext it }
        val bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) {
                decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > maximum) {
                    val scale = maximum.toDouble() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        BitmapMemoryCache.put(cacheKey, bitmap)
        bitmap
    }

@Composable
internal fun DraftAttachmentPreview(attachment: DraftAttachment, remove: () -> Unit) {
    val cover = LocalAppWindowClass.current.coverScreen
    if (attachment.kind == AttachmentKind.FILE) {
        Surface(
            Modifier.width(if (cover) 150.dp else 180.dp).testTag("draft-file"),
            shape = MaterialTheme.shapes.small,
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Glyph(R.drawable.ic_file, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(attachment.displayName, maxLines = 2, fontSize = 12.sp)
                        Text(
                            formatBytes(attachment.byteSize),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(remove, Modifier.fillMaxWidth()) { Text("Remove", fontSize = 11.sp) }
            }
        }
        return
    }
    val bitmap by
        produceState<Bitmap?>(null, attachment.id) {
            value =
                runCatching {
                        decodedBitmap(
                            attachment.id,
                            withContext(Dispatchers.IO) {
                                File(attachment.localPath).readBytes()
                            },
                            320,
                        )
                    }
                    .getOrNull()
        }
    Surface(
        Modifier.width(if (cover) 96.dp else 112.dp).testTag("draft-attachment"),
        shape = MaterialTheme.shapes.small,
        tonalElevation = 2.dp,
    ) {
        Column {
            if (bitmap == null)
                Box(
                    Modifier.fillMaxWidth().height(if (cover) 64.dp else 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            else
                Image(
                    bitmap!!.asImageBitmap(),
                    attachment.displayName,
                    Modifier.fillMaxWidth().height(if (cover) 64.dp else 80.dp),
                    contentScale = ContentScale.Crop,
                )
            TextButton(remove, Modifier.fillMaxWidth()) { Text("Remove", fontSize = 11.sp) }
        }
    }
}

internal fun formatBytes(value: Long): String =
    when {
        value >= 1024 * 1024 -> "%.1f MiB".format(value.toDouble() / (1024 * 1024))
        value >= 1024 -> "%.1f KiB".format(value.toDouble() / 1024)
        else -> "$value B"
    }

@Composable
internal fun MediaGallery(media: List<MediaRef>, actions: ConversationActions) {
    if (media.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        media.forEach { MediaPreview(it, actions) }
    }
}

@Composable
private fun MediaPreview(media: MediaRef, actions: ConversationActions) {
    val context = LocalContext.current
    val cover = LocalAppWindowClass.current.coverScreen
    if (media.location == MediaLocation.EXTERNAL_URL) {
        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(media.value)))
                }
            }
        ) {
            Text("Open external image")
        }
        return
    }
    var expanded by rememberSaveable(media.key) { mutableStateOf(false) }
    val result by
        produceState<Result<Bitmap>?>(null, media.key, media.value, expanded) {
            value =
                runCatching {
                    decodedBitmap(
                        media.key,
                        actions.loadMedia(media),
                        if (expanded) 2048 else 1024,
                    )
                }
        }
    when {
        result == null ->
            Box(
                Modifier.fillMaxWidth().height(160.dp).testTag("message-image-loading"),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        result!!.isFailure ->
            Text(
                "Image unavailable",
                Modifier.padding(vertical = 12.dp).testTag("message-image-error"),
                color = MaterialTheme.colorScheme.error,
            )
        else -> {
            val bitmap = result!!.getOrThrow()
            Image(
                bitmap.asImageBitmap(),
                "Conversation image",
                Modifier.fillMaxWidth()
                    .heightIn(max = if (cover) 240.dp else 360.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { expanded = true }
                    .testTag("message-image"),
                contentScale = ContentScale.Fit,
            )
            if (expanded)
                Dialog(
                    onDismissRequest = { expanded = false },
                    properties = DialogProperties(usePlatformDefaultWidth = !cover),
                ) {
                    Surface(
                        (if (cover) Modifier.fillMaxSize().systemBarsPadding()
                            else Modifier.fillMaxWidth())
                            .clickable { expanded = false },
                        shape = if (cover)
                            androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
                        else MaterialTheme.shapes.medium,
                    ) {
                        Column {
                            Image(
                                bitmap.asImageBitmap(),
                                "Expanded conversation image",
                                Modifier.fillMaxWidth().weight(1f, fill = false)
                                    .heightIn(max = 720.dp),
                                contentScale = ContentScale.Fit,
                            )
                            TextButton(
                                { expanded = false },
                                Modifier.align(Alignment.End).testTag("close-image"),
                            ) {
                                Text("Close")
                            }
                        }
                    }
                }
        }
    }
}

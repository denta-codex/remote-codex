package dev.codexops.client

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

internal data class IncomingShare(val text: String, val streams: List<Uri>)

internal fun Intent.incomingShare(): IncomingShare? {
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
    val text = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        ?: getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
    val streams = if (action == Intent.ACTION_SEND_MULTIPLE)
        IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    else listOfNotNull(IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java))
    val clips = clipData?.let { clip ->
        (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }.orEmpty()
    return IncomingShare(text, (streams + clips).distinct())
}

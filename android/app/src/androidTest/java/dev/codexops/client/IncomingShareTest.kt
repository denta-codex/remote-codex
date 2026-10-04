package dev.codexops.client

import android.app.Application
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IncomingShareTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun await(check: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (!check() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue(check())
    }
    private fun intent(text: String) = Intent(app, MainActivity::class.java)
        .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

    @Test fun coldAndWarmSharesPreserveDraftWithoutResendingOnRecreation() {
        runBlocking {
            LocalStore(app).put("draft/new", "Existing draft")
            LocalStore(app).remove("journal/new")
        }
        val resolved = app.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(app.packageName), 0)
        assertTrue(resolved.any { it.activityInfo.name == MainActivity::class.java.name })
        ActivityScenario.launch<MainActivity>(intent("Shared link")).use { scenario ->
            lateinit var model: ClientModel
            scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
            await { !model.state.value.busy && model.state.value.draft == "Existing draft\n\nShared link" }
            scenario.recreate()
            scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
            assertEquals("Existing draft\n\nShared link", model.state.value.draft)
            scenario.onActivity { it.startActivity(intent("Second share").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            await { model.state.value.draft.endsWith("\n\nSecond share") && !model.state.value.busy }
            assertNull(model.state.value.journal)
            assertNull(model.state.value.thread)
            assertEquals("Existing draft\n\nShared link\n\nSecond share", runBlocking { LocalStore(app).get("draft/new") })
        }
    }

    @Test fun multipleSharedFilesAreCopiedAndClipUrisAreDeduplicated() {
        val resolver = app.contentResolver
        val image = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "share-test.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }))
        val document = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "share-test.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }))
        try {
            resolver.openOutputStream(image)!!.use { output ->
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
            }
            resolver.openOutputStream(document)!!.use { it.write("Shared document".toByteArray()) }
            runBlocking {
                LocalStore(app).remove("attachments/new")
                LocalStore(app).remove("journal/new")
                LocalStore(app).remove("draft/new")
            }
            val incoming = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(image, document))
                .putExtra(Intent.EXTRA_TEXT, "Review these")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newUri(resolver, "files", image).apply { addItem(ClipData.Item(document)) } }
            ActivityScenario.launch<MainActivity>(incoming).use { scenario ->
                lateinit var model: ClientModel
                scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
                await { !model.state.value.busy && model.state.value.attachments.size == 2 }
                assertEquals("Review these", model.state.value.draft)
                resolver.delete(image, null, null)
                resolver.delete(document, null, null)
                assertTrue(model.state.value.attachments.all { java.io.File(it.localPath).isFile })
                scenario.recreate()
                assertEquals(2, model.state.value.attachments.size)
                assertNull(model.state.value.journal)
            }
        } finally {
            // MediaStore can reject a second delete after the owner row is gone.
            runCatching { resolver.delete(image, null, null) }
            runCatching { resolver.delete(document, null, null) }
        }
    }

    @Test fun invalidStreamsAndPendingSendsDoNotOverwriteDrafts() {
        val model = ClientModel(app)
        try {
            runBlocking {
                LocalStore(app).put("draft/new", "Keep me")
                LocalStore(app).put("journal/new", "{\"stage\":\"uncertain\"}")
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                model.receiveShare(IncomingShare("Do not append", emptyList()))
            }
            await { !model.state.value.busy && model.state.value.error != null }
            assertEquals("Keep me", model.state.value.draft)
            assertNotNull(model.state.value.journal)
            runBlocking { LocalStore(app).remove("journal/new") }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                model.receiveShare(IncomingShare("Safe text", listOf(Uri.parse("file:///private.txt"))))
            }
            await { !model.state.value.busy && model.state.value.draft.endsWith("Safe text") }
            assertTrue(model.state.value.attachments.none { it.displayName == "private.txt" })
            assertNotNull(model.state.value.error)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                androidx.lifecycle.ViewModelStore().apply { put("test", model); clear() }
            }
        }
    }
}

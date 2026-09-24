package dev.codexops.client

import android.app.Application
import android.system.Os
import android.system.OsConstants
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in, read-only acceptance against Grace; credential arrives through an app-private FIFO. */
class LiveConnectionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun connectsToGraceAndLoadsTasks() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSmoke") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val pipe = File(app.filesDir, "live-credential")
        check(OsConstants.S_ISFIFO(Os.stat(pipe.path).st_mode))
        val local = LocalStore(app)
        val store = ViewModelStore()
        try {
            val token = pipe.bufferedReader().use { it.readText().trim() }
            pipe.delete()
            runBlocking { local.saveToken(token) }
            lateinit var model: ClientModel
            compose.runOnUiThread {
                model = ClientModel(app)
                store.put("live-smoke", model)
                compose.activity.setContent { RemoteTheme { App(model) } }
                model.foreground(true)
            }
            compose.waitUntil(25000) {
                model.state.value.error != null || model.state.value.tasks.isNotEmpty()
            }
            val state = model.state.value
            assertTrue(state.error ?: state.connection, state.ready && state.error == null)
        } finally {
            runBlocking { local.saveToken("") }
            compose.runOnUiThread { store.clear() }
            pipe.delete()
        }
    }
}

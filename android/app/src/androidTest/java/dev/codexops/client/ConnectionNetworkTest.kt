package dev.codexops.client

import android.app.Application
import android.net.ConnectivityManager
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionNetworkTest {
    @Test fun readsAppNetworkWithoutExtraPermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        // Managed emulators can have no default network; neither state requires a VPN.
        // Calling the real API also verifies the manifest grants the permission to read it.
        val observed = connectionNetwork(manager)
        assertTrue("Unexpected emulator network state: $observed",
            observed == ConnectionNetwork.Offline || observed == ConnectionNetwork.NoVpn)
    }

    @Test fun modelKeepsCredentialRejectionWhenVpnIsInactive() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val local = LocalStore(app)
        val originalToken = local.token()
        val server = MockWebServer()
        val store = ViewModelStore()
        try {
            server.enqueue(MockResponse().setResponseCode(401))
            server.start()
            local.saveToken("fixture-token")
            lateinit var model: ClientModel
            instrumentation.runOnMainSync {
                model = ClientModel(app, endpoint = "ws://127.0.0.1:${server.port}/codex/rpc", allowLoopbackTest = true)
                store.put("connection-test", model)
                model.connect()
            }
            val failed = withTimeout(15_000) { model.state.first { it.error != null } }
            assertEquals("Disconnected", failed.connection)
            assertEquals("Grace rejected the connection credential. Scan the setup QR again in Settings.", failed.error)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            local.saveToken(originalToken)
            server.shutdown()
        }
    }
}

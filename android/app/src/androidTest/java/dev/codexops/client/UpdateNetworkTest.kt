package dev.codexops.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateNetworkTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun manualCheckUsesAuthenticatedExtensionRoute() = runBlocking {
        val certificate = BuildConfig.RELEASE_CERTIFICATE_SHA256
        val version = BuildConfig.VERSION_CODE
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{
                      "schema":"dev.codexops.remote-codex.update/v1",
                      "channel":"stable",
                      "packageName":"dev.codexops.client",
                      "versionCode":$version,
                      "versionName":"${BuildConfig.VERSION_NAME}",
                      "publishedAt":"2026-09-24T18:00:00Z",
                      "apkUrl":"/remote-codex/v1/updates/releases/$version/remote-codex.apk",
                      "apkSize":1234,
                      "sha256":"${"b".repeat(64)}",
                      "signingCertificateSha256":"$certificate",
                      "releaseNotes":"Fixture"
                    }"""
                )
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val updater = AppUpdater(context, "ws://127.0.0.1:${server.port}/codex/rpc", true)

        assertEquals(version.toLong(), updater.check("fixture-token").versionCode)
        val request = server.takeRequest()
        assertEquals("/remote-codex/v1/updates/stable/latest.json", request.path)
        assertEquals("Bearer fixture-token", request.getHeader("Authorization"))
    }
}

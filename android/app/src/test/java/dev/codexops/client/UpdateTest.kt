package dev.codexops.client

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateTest {
    private val certificate = "a".repeat(64)
    private val hash = "b".repeat(64)

    private fun manifest(
        versionCode: Long = 6,
        packageName: String = "dev.codexops.client",
        apkUrl: String = "/remote-codex/v1/updates/releases/$versionCode/remote-codex.apk",
        sha256: String = hash,
        signer: String = certificate,
    ) =
        """{
          "schema":"dev.codexops.remote-codex.update/v1",
          "channel":"stable",
          "packageName":"$packageName",
          "versionCode":$versionCode,
          "versionName":"0.1.5",
          "publishedAt":"2026-09-24T18:00:00Z",
          "apkUrl":"$apkUrl",
          "apkSize":1234,
          "sha256":"$sha256",
          "signingCertificateSha256":"$signer",
          "releaseNotes":"Private updates"
        }"""

    @Test
    fun derivesOnlyTheNamespacedPrivateEndpoint() {
        assertEquals(
            "https://grace.example/remote-codex/v1/updates/stable/latest.json",
            updateManifestUri("wss://grace.example/codex/rpc", false).toString(),
        )
        assertThrows(IllegalStateException::class.java) {
            updateManifestUri("ws://grace.example/codex/rpc", true)
        }
    }

    @Test
    fun validatesAvailableAndCurrentManifests() {
        val parsed = parseAndValidateManifest(manifest(), certificate)
        assertEquals(6, parsed.versionCode)
        assertEquals(true, isUpdateAvailable(parsed, 5))
        assertEquals(false, isUpdateAvailable(parsed, 6))
        assertEquals(false, isUpdateAvailable(parsed, 7))
    }

    @Test
    fun rejectsMalformedOrUntrustedManifests() {
        val cases =
            listOf(
                "not json",
                manifest(packageName = "example.bad"),
                manifest(apkUrl = "/updates/remote-codex.apk"),
                manifest(sha256 = "short"),
                manifest(signer = "c".repeat(64)),
            )
        cases.forEach { content ->
            assertThrows(UpdateFailure::class.java) {
                parseAndValidateManifest(content, certificate)
            }
        }
    }

    @Test
    fun rejectsInterruptedOrChangedDownloads() {
        val parsed = parseAndValidateManifest(manifest(), certificate)
        assertThrows(UpdateFailure::class.java) { validateDownloadedArtifact(parsed, 100, hash) }
        assertThrows(UpdateFailure::class.java) {
            validateDownloadedArtifact(parsed, 1234, "c".repeat(64))
        }
        validateDownloadedArtifact(parsed, 1234, hash)
    }

    @Test
    fun rejectsWrongArchiveIdentityOrSigner() {
        val parsed = parseAndValidateManifest(manifest(), certificate)
        validateArchiveIdentity(parsed, "dev.codexops.client", 6, setOf(certificate))
        assertThrows(UpdateFailure::class.java) {
            validateArchiveIdentity(parsed, "example.bad", 6, setOf(certificate))
        }
        assertThrows(UpdateFailure::class.java) {
            validateArchiveIdentity(parsed, "dev.codexops.client", 7, setOf(certificate))
        }
        assertThrows(UpdateFailure::class.java) {
            validateArchiveIdentity(parsed, "dev.codexops.client", 6, setOf("c".repeat(64)))
        }
    }

    @Test
    fun mapsInstallerResultsWithoutUsingServerMessages() {
        assertEquals(null, installResultMessage(PackageInstaller.STATUS_SUCCESS))
        assertEquals(
            "Update installation was canceled.",
            installResultMessage(PackageInstaller.STATUS_FAILURE_ABORTED),
        )
        assertEquals(
            "There is not enough storage to install the update.",
            installResultMessage(PackageInstaller.STATUS_FAILURE_STORAGE),
        )
        assertEquals(
            "Android timed out while installing the update.",
            installResultMessage(PackageInstaller.STATUS_FAILURE_TIMEOUT),
        )
        assertEquals("Update installation failed (status 99).", installResultMessage(99))
    }
}

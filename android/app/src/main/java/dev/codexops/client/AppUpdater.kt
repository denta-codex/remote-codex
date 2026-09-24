package dev.codexops.client

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val UPDATE_SCHEMA = "dev.codexops.remote-codex.update/v1"
private const val UPDATE_MANIFEST_PATH = "/remote-codex/v1/updates/stable/latest.json"
private const val MAX_MANIFEST_BYTES = 64 * 1024
private const val MAX_APK_BYTES = 256L * 1024 * 1024
private const val INSTALL_ACTION = "dev.codexops.client.UPDATE_INSTALL_RESULT"

data class UpdateManifest(
    val schema: String,
    val channel: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val publishedAt: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String,
    val signingCertificateSha256: String,
    val releaseNotes: String,
)

enum class UpdateStage {
    Idle,
    Checking,
    Current,
    Available,
    Downloading,
    Installing,
    Error,
}
data class UpdateState(
    val stage: UpdateStage = UpdateStage.Idle,
    val manifest: UpdateManifest? = null,
    val progress: Int = 0,
    val message: String? = null,
)

class UpdateFailure(message: String) : Exception(message)

internal fun updateManifestUri(endpoint: String, allowLoopback: Boolean): URI {
    val source = URI(endpoint)
    val scheme =
        when (source.scheme) {
            "wss" -> "https"
            "ws" -> {
                check(allowLoopback && (source.host == "127.0.0.1" || source.host == "localhost"))
                "http"
            }
            else -> error("Unsupported update endpoint")
        }
    return URI(scheme, source.userInfo, source.host, source.port, UPDATE_MANIFEST_PATH, null, null)
}

internal fun parseAndValidateManifest(
    content: String,
    expectedCertificate: String,
): UpdateManifest {
    val manifest =
        try {
            val value = Json.parseToJsonElement(content).jsonObject
            val expected =
                setOf(
                    "schema",
                    "channel",
                    "packageName",
                    "versionCode",
                    "versionName",
                    "publishedAt",
                    "apkUrl",
                    "apkSize",
                    "sha256",
                    "signingCertificateSha256",
                    "releaseNotes",
                )
            check(value.keys == expected)
            fun text(name: String) = value.getValue(name).jsonPrimitive.contentOrNull ?: error(name)
            fun number(name: String) = value.getValue(name).jsonPrimitive.longOrNull ?: error(name)
            UpdateManifest(
                schema = text("schema"),
                channel = text("channel"),
                packageName = text("packageName"),
                versionCode = number("versionCode"),
                versionName = text("versionName"),
                publishedAt = text("publishedAt"),
                apkUrl = text("apkUrl"),
                apkSize = number("apkSize"),
                sha256 = text("sha256"),
                signingCertificateSha256 = text("signingCertificateSha256"),
                releaseNotes = text("releaseNotes"),
            )
        } catch (_: Exception) {
            throw UpdateFailure("The update manifest is invalid.")
        }
    val certificate = expectedCertificate.lowercase()
    if (
        manifest.schema != UPDATE_SCHEMA ||
            manifest.channel != "stable" ||
            manifest.packageName != "dev.codexops.client" ||
            manifest.versionCode < 1 ||
            manifest.versionName.isBlank() || manifest.versionName.length > 64 ||
            manifest.publishedAt.isBlank() ||
            manifest.apkSize !in 1..MAX_APK_BYTES ||
            !manifest.sha256.matches(Regex("[0-9a-f]{64}")) ||
            !manifest.signingCertificateSha256.matches(Regex("[0-9a-f]{64}")) ||
            manifest.signingCertificateSha256.lowercase() != certificate ||
            !certificate.matches(Regex("[0-9a-f]{64}")) ||
            manifest.apkUrl !=
                "/remote-codex/v1/updates/releases/${manifest.versionCode}/remote-codex.apk" ||
            manifest.releaseNotes.length > 16_384
    ) {
        throw UpdateFailure("The update manifest failed validation.")
    }
    return manifest
}

internal fun isUpdateAvailable(manifest: UpdateManifest, currentVersion: Long) =
    manifest.versionCode > currentVersion

internal fun validateArchiveIdentity(
    manifest: UpdateManifest,
    packageName: String,
    versionCode: Long,
    certificateDigests: Set<String>,
) {
    if (packageName != manifest.packageName)
        throw UpdateFailure("The downloaded APK has the wrong package name.")
    if (versionCode != manifest.versionCode)
        throw UpdateFailure("The downloaded APK has the wrong version.")
    if (
        certificateDigests.map { it.lowercase() }.toSet() !=
            setOf(manifest.signingCertificateSha256.lowercase())
    )
        throw UpdateFailure("The downloaded APK has an unexpected signing certificate.")
}

internal fun validateDownloadedArtifact(manifest: UpdateManifest, size: Long, sha256: String) {
    if (size != manifest.apkSize) throw UpdateFailure("The update download was interrupted.")
    if (sha256 != manifest.sha256) throw UpdateFailure("The update download failed its integrity check.")
}

class AppUpdater(
    private val context: Context,
    endpoint: String,
    allowLoopback: Boolean,
) {
    private val manifestUri = updateManifestUri(endpoint, allowLoopback)

    suspend fun check(token: String): UpdateManifest =
        withContext(Dispatchers.IO) {
            val connection = open(manifestUri, token)
            try {
                if (connection.responseCode != 200) throw UpdateFailure("The update service returned HTTP ${connection.responseCode}.")
                val bytes = readLimited(connection.inputStream, MAX_MANIFEST_BYTES.toLong())
                parseAndValidateManifest(
                    bytes.toString(Charsets.UTF_8),
                    BuildConfig.RELEASE_CERTIFICATE_SHA256,
                )
            } finally {
                connection.disconnect()
            }
        }

    suspend fun downloadAndVerify(
        manifest: UpdateManifest,
        token: String,
        progress: (Int) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            val apkUri = manifestUri.resolve(manifest.apkUrl)
            if (
                apkUri.scheme != manifestUri.scheme ||
                    apkUri.host != manifestUri.host ||
                    apkUri.port != manifestUri.port ||
                    apkUri.path != manifest.apkUrl
            ) throw UpdateFailure("The update download address is invalid.")
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val partial = File(directory, "remote-codex-${manifest.versionCode}.download.apk")
            val complete = File(directory, "remote-codex-${manifest.versionCode}.apk")
            partial.delete()
            complete.delete()
            val connection = open(apkUri, token)
            try {
                if (connection.responseCode != 200) throw UpdateFailure("The update download returned HTTP ${connection.responseCode}.")
                val declared = connection.contentLengthLong
                if (declared >= 0 && declared != manifest.apkSize) throw UpdateFailure("The update download has an unexpected size.")
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            if (count > manifest.apkSize || count > MAX_APK_BYTES)
                                throw UpdateFailure("The update download is larger than expected.")
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            progress(((count * 100) / manifest.apkSize).toInt().coerceIn(0, 100))
                        }
                        output.fd.sync()
                    }
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                validateDownloadedArtifact(manifest, count, actualHash)
                verifyArchive(partial, manifest)
                if (!partial.renameTo(complete)) throw UpdateFailure("The verified update could not be prepared.")
                complete
            } catch (e: Exception) {
                partial.delete()
                if (e is CancellationException || e is UpdateFailure) throw e
                throw UpdateFailure("The update could not be downloaded.")
            } finally {
                connection.disconnect()
            }
        }

    suspend fun install(file: File, manifest: UpdateManifest) =
        withContext(Dispatchers.IO) {
            val installer = context.packageManager.packageInstaller
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply {
                        setAppPackageName(manifest.packageName)
                        setSize(manifest.apkSize)
                        if (Build.VERSION.SDK_INT >= 31)
                            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                    }
            val sessionId = installer.createSession(params)
            var commitAttempted = false
            try {
                installer.openSession(sessionId).use { session ->
                    file.inputStream().use { input ->
                        session.openWrite("remote-codex.apk", 0, manifest.apkSize).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                    val result =
                        Intent(context, UpdateInstallReceiver::class.java).setAction(INSTALL_ACTION)
                    val pending =
                        PendingIntent.getBroadcast(
                            context,
                            sessionId,
                            result,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                        )
                    commitAttempted = true
                    session.commit(pending.intentSender)
                }
            } catch (e: Exception) {
                if (!commitAttempted) installer.abandonSession(sessionId)
                throw e
            }
        }

    private fun verifyArchive(file: File, manifest: UpdateManifest) {
        val info =
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ) ?: throw UpdateFailure("Android could not read the downloaded APK.")
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        val digests =
            signers
                .map { signer ->
                    MessageDigest.getInstance("SHA-256")
                        .digest(signer.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                }
                .toSet()
        validateArchiveIdentity(manifest, info.packageName, info.longVersionCode, digests)
    }

    private fun open(uri: URI, token: String): HttpURLConnection =
        (uri.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json, application/vnd.android.package-archive")
        }
}

private fun readLimited(input: InputStream, maximum: Long): ByteArray =
    input.use {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            count += read
            if (count > maximum) throw UpdateFailure("The update manifest is too large.")
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }

object UpdateInstallResults {
    val events = MutableSharedFlow<String?>(extraBufferCapacity = 1)

    fun record(context: Context, message: String?) {
        context.getSharedPreferences("update-install-result", Context.MODE_PRIVATE)
            .edit()
            .putString("message", message ?: "")
            .apply()
        events.tryEmit(message)
    }

    fun consume(context: Context): String? {
        val preferences = context.getSharedPreferences("update-install-result", Context.MODE_PRIVATE)
        if (!preferences.contains("message")) return null
        val message = preferences.getString("message", "").orEmpty()
        preferences.edit().remove("message").apply()
        return message.ifEmpty { "Update installed." }
    }
}

internal fun installResultMessage(status: Int): String? =
    when (status) {
        PackageInstaller.STATUS_SUCCESS -> null
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Update installation was canceled."
        PackageInstaller.STATUS_FAILURE_BLOCKED ->
            "Android blocked this update. Check installation permissions."
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "The update conflicts with the installed app."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "This update is not compatible with the device."
        PackageInstaller.STATUS_FAILURE_INVALID -> "Android rejected the update package."
        PackageInstaller.STATUS_FAILURE_STORAGE ->
            "There is not enough storage to install the update."
        PackageInstaller.STATUS_FAILURE_TIMEOUT -> "Android timed out while installing the update."
        else -> "Update installation failed (status $status)."
    }

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != INSTALL_ACTION) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation =
                    if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                try {
                    context.startActivity(confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    UpdateInstallResults.record(context, "Android could not open the update confirmation. Try again from Settings.")
                }
            }
            else -> UpdateInstallResults.record(context, installResultMessage(status))
        }
    }
}

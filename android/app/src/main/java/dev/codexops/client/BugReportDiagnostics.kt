package dev.codexops.client

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Choreographer
import android.view.inspector.WindowInspector
import dev.codexops.core.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal data class DiagnosticArtifact(val name: String, val bytes: ByteArray, val mime: String = "text/plain")
internal data class DiagnosticResult(val status: String, val message: String, val artifacts: List<DiagnosticArtifact> = emptyList())
internal fun interface DiagnosticCollector {
    suspend fun collect(capturedAt: Long): DiagnosticResult
}
internal typealias ScreenshotCapture = suspend () -> ByteArray

// A stalled Android binder/stream read must not hold the report sheet behind a coroutine join.
// Cancellation detaches the collector; two daemon workers bound the resources even if a native
// call ignores interruption. Subsequent queued captures can still time out independently.
private val diagnosticWorkers = Executors.newFixedThreadPool(2) { task ->
    Thread(task, "report-diagnostic").apply { isDaemon = true }
}

internal suspend fun <T> reportBlocking(block: () -> T): T = suspendCancellableCoroutine { continuation ->
    val future = diagnosticWorkers.submit {
        try {
            val result = block()
            if (continuation.isActive) continuation.resume(result)
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }
    continuation.invokeOnCancellation { future.cancel(true) }
}

internal suspend fun awaitReportFrame() = suspendCancellableCoroutine<Unit> { continuation ->
    val choreographer = Choreographer.getInstance()
    val frame = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
    choreographer.postFrameCallback(frame)
    continuation.invokeOnCancellation { choreographer.removeFrameCallback(frame) }
}

internal class ScreenshotCollector(private val excluded: Boolean, private val capture: ScreenshotCapture?) : DiagnosticCollector {
    override suspend fun collect(capturedAt: Long): DiagnosticResult {
        if (excluded) return DiagnosticResult("omitted", "Credential/setup screens are excluded from screenshots.")
        if (capture == null) return DiagnosticResult("unavailable", "No app window was available.")
        val bytes = withTimeout(1500) { capture.invoke() }
        return DiagnosticResult("captured", "App window captured before the report sheet.", listOf(DiagnosticArtifact("screenshot.png", bytes, "image/png")))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun captureBugReportScreenshot(activity: Activity): ByteArray {
    check(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE == 0) {
        "Protected window cannot be captured."
    }
    // The capture targets this app's activity window, never other apps or system windows.
    val view = WindowInspector.getGlobalWindowViews().lastOrNull { it.isShown && it.hasWindowFocus() }
        ?: activity.window.decorView
    val flags = (view.layoutParams as? android.view.WindowManager.LayoutParams)?.flags ?: 0
    check(flags and android.view.WindowManager.LayoutParams.FLAG_SECURE == 0) {
        "Protected window cannot be captured."
    }
    check(view.isAttachedToWindow && view.width > 0 && view.height > 0)
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val captured = suspendCancellableCoroutine<Bitmap> { continuation ->
        try {
            val complete: (Int) -> Unit = { result ->
                if (!continuation.isActive) bitmap.recycle()
                else if (result == PixelCopy.SUCCESS) continuation.resume(bitmap) { bitmap.recycle() }
                else {
                    bitmap.recycle()
                    continuation.resumeWithException(IllegalStateException("Window capture unavailable ($result)."))
                }
            }
            if (Build.VERSION.SDK_INT >= 34) {
                val request = PixelCopy.Request.Builder.ofWindow(view).setDestinationBitmap(bitmap).build()
                PixelCopy.request(request, activity.mainExecutor) { complete(it.status) }
            } else {
                check(view === activity.window.decorView) { "This dialog cannot be captured on this Android version." }
                PixelCopy.request(activity.window, bitmap, complete, Handler(Looper.getMainLooper()))
            }
        } catch (e: Exception) {
            bitmap.recycle()
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }
    return try {
        withContext(Dispatchers.Default) {
            ByteArrayOutputStream().use { output ->
                check(captured.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        }
    } finally { captured.recycle() }
}

internal fun reportDeviceInfo(context: Context): JsonObject {
    val config = context.resources.configuration
    val display = context.resources.displayMetrics
    return obj(
        "appVersion" to s(BuildConfig.VERSION_NAME), "appBuild" to JsonPrimitive(BuildConfig.VERSION_CODE),
        "manufacturer" to s(Build.MANUFACTURER), "model" to s(Build.MODEL),
        "androidVersion" to s(Build.VERSION.RELEASE), "androidSdk" to JsonPrimitive(Build.VERSION.SDK_INT),
        "widthPixels" to JsonPrimitive(display.widthPixels), "heightPixels" to JsonPrimitive(display.heightPixels),
        "densityDpi" to JsonPrimitive(display.densityDpi), "widthDp" to JsonPrimitive(config.screenWidthDp),
        "heightDp" to JsonPrimitive(config.screenHeightDp), "orientation" to JsonPrimitive(config.orientation),
        "fontScale" to JsonPrimitive(config.fontScale), "uiMode" to JsonPrimitive(config.uiMode),
        "locale" to s(config.locales.toLanguageTags()),
    )
}

internal fun redactReportText(value: String, credential: String): String {
    var result = if (credential.length >= 16) value.replace(credential, "[credential removed]") else value
    result = result.replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+"), "$1[credential removed]")
    return result.replace(Regex("(?i)((?:token|password|secret|authorization)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s\\\"',}]+"), "$1[credential removed]")
}

internal fun redactReportJson(value: JsonElement, credential: String): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { redactReportJson(it.value, credential) })
    is JsonArray -> JsonArray(value.map { redactReportJson(it, credential) })
    is JsonPrimitive -> if (value.isString) s(redactReportText(value.content, credential)) else value
}

internal fun boundedReportLogs(lines: Sequence<String>, capturedAt: Long, credential: String): String {
    val rows = ArrayDeque<String>()
    var bytes = 0
    for (line in lines) {
        val epoch = line.trimStart().substringBefore(' ').toDoubleOrNull() ?: continue
        val time = (epoch * 1000).toLong()
        if (time !in (capturedAt - 300_000)..capturedAt) continue
        // Never turn serialized requests, responses, or transcripts into diagnostic logs.
        if (Regex("\"(?:params|input|messages|content|transcript)\"\\s*:").containsMatchIn(line)) continue
        val safe = redactReportText(line.take(32_768), credential) + "\n"
        rows.addLast(safe)
        bytes += safe.toByteArray().size
        while (rows.size > 2000 || bytes > 512 * 1024) bytes -= rows.removeFirst().toByteArray().size
    }
    return rows.joinToString("")
}

internal class AppLogCollector(private val credential: String) : DiagnosticCollector {
    override suspend fun collect(capturedAt: Long): DiagnosticResult = reportBlocking {
        val process = ProcessBuilder("logcat", "-d", "-v", "epoch", "--uid=${android.os.Process.myUid()}", "-t", "2000")
            .redirectErrorStream(true).start()
        // A blocking pipe read must be interrupted by killing the producer, not just cancelling a coroutine.
        val killer = Thread {
            try { if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroyForcibly() }
            catch (_: InterruptedException) { process.destroyForcibly() }
        }.apply { isDaemon = true; name = "report-logcat-timeout"; start() }
        try {
            val text = process.inputStream.bufferedReader().use { boundedReportLogs(it.lineSequence(), capturedAt, credential) }
            val success = process.waitFor(100, TimeUnit.MILLISECONDS) && process.exitValue() == 0
            if (!success) DiagnosticResult("unavailable", "App log collection failed or exceeded its time limit.")
            else DiagnosticResult("captured", "App UID only; preceding five minutes, at most 2,000 lines / 512 KiB. ${if (text.isEmpty()) "No eligible log lines were available." else ""}",
                listOf(DiagnosticArtifact("app-logcat.txt", text.toByteArray())))
        } finally { process.destroyForcibly(); killer.interrupt() }
    }
}

internal class ExitInfoCollector(private val context: Context, private val credential: String) : DiagnosticCollector {
    override suspend fun collect(capturedAt: Long): DiagnosticResult = reportBlocking {
        if (Build.VERSION.SDK_INT < 30) return@reportBlocking DiagnosticResult("unavailable", "Process exit history requires Android 11 or newer.")
        val exit = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .filter { it.timestamp in (capturedAt - 86_400_000)..capturedAt && it.reason in setOf(
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ) }.maxByOrNull { it.timestamp }
            ?: return@reportBlocking DiagnosticResult("unavailable", "No relevant abnormal process exit was available within 24 hours.")
        val metadata = obj("timestamp" to JsonPrimitive(exit.timestamp), "process" to s(exit.processName),
            "pid" to JsonPrimitive(exit.pid), "reason" to JsonPrimitive(exit.reason), "status" to JsonPrimitive(exit.status),
            "description" to s(redactReportText(exit.description.orEmpty(), credential)),
            "pssKiB" to JsonPrimitive(exit.pss), "rssKiB" to JsonPrimitive(exit.rss))
        val artifacts = mutableListOf(DiagnosticArtifact("process-exit.json", metadata.toString().toByteArray(), "application/json"))
        var traceNote = "No trace was available."
        try {
            exit.traceInputStream?.use { input ->
                val bytes = ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(8192)
                    val limit = 2 * 1024 * 1024 + 1
                    while (output.size() < limit) {
                        val read = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                if (bytes.size > 2 * 1024 * 1024) traceNote = "Trace exceeded the 2 MiB capture limit and was omitted."
                else {
                    val native = exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                    val content = if (native) bytes else redactReportText(bytes.toString(Charsets.UTF_8), credential).toByteArray()
                    artifacts += DiagnosticArtifact(if (native) "native-crash.pb" else "anr-trace.txt", content,
                        if (native) "application/octet-stream" else "text/plain")
                    traceNote = "Historical trace attached; timestamp and process are in process-exit.json."
                }
            }
        } catch (_: Exception) { traceNote = "The historical trace was unavailable; exit metadata is retained." }
        DiagnosticResult("captured", traceNote, artifacts)
    }
}

/** Collectors have independent deadlines and never publish throwable messages or raw RPC data. */
internal suspend fun collectBugReportDiagnostics(collectors: Map<String, DiagnosticCollector>, capturedAt: Long): Map<String, Pair<Long, DiagnosticResult>> = supervisorScope {
    collectors.mapValues { (_, collector) -> async {
        val result = try {
            withTimeout(2500) { collector.collect(capturedAt) }
        } catch (_: TimeoutCancellationException) {
            DiagnosticResult("unavailable", "Capture exceeded its time limit.")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { DiagnosticResult("unavailable", "Capture failed (${e.javaClass.simpleName}); other report evidence is retained.") }
        System.currentTimeMillis() to result
    } }.mapValues { it.value.await() }
}

package dev.codexops.client

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.squareup.seismic.ShakeDetector
import dev.codexops.core.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class ReportShakeGate {
    private var last: Long? = null
    fun accept(now: Long, resumed: Boolean, enabled: Boolean, reportOpen: Boolean): Boolean {
        if (!resumed || !enabled || reportOpen || last?.let { now - it < 3000 } == true) return false
        last = now
        return true
    }
}

/** Android 14+ reports this app's screenshots without granting access to the saved image. */
internal fun screenshotPromptAllowed(sdk: Int, started: Boolean, state: BugReportState): Boolean =
    sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && started && state.loaded && state.screenshotEnabled &&
        !state.visible && !state.capturing && !state.busy

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun watchScreenshots(activity: Activity, owner: androidx.lifecycle.LifecycleOwner, onScreenshot: () -> Unit): () -> Unit {
    val callback = Activity.ScreenCaptureCallback { onScreenshot() }
    var registered = false
    fun update() {
        // The platform delivers callbacks only while the activity is visible; follow that window.
        val visible = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (visible && !registered) { activity.registerScreenCaptureCallback(activity.mainExecutor, callback); registered = true }
        else if (!visible && registered) { activity.unregisterScreenCaptureCallback(callback); registered = false }
    }
    val observer = LifecycleEventObserver { _, _ -> update() }
    owner.lifecycle.addObserver(observer)
    update()
    return {
        owner.lifecycle.removeObserver(observer)
        if (registered) activity.unregisterScreenCaptureCallback(callback)
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
internal fun BugReportMenu(model: ClientModel) {
    val report by model.reports.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("app-menu").semantics { contentDescription = "More options" }) {
            Text("⋮", fontSize = 26.sp)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (report.capturing) "Capturing report…" else "Report a bug") },
                enabled = report.loaded && !report.capturing && (!report.busy || report.draft != null),
                modifier = Modifier.testTag("report-bug"), onClick = {
                    menu = false
                    model.reports.open {
                        awaitReportFrame() // Let the overflow menu close before copying the app window.
                        captureBugReportScreenshot(requireNotNull(context.activity()))
                    }
                })
            report.lastTask?.let { id ->
                DropdownMenuItem(text = { Text("Last bug report") }, onClick = { menu = false; model.openTask(id) })
            }
        }
    }
}

@Composable
internal fun BugReportHost(model: ClientModel, screen: ScreenState, snackbar: SnackbarHostState) {
    val report by model.reports.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(report)
    val gate = remember { ReportShakeGate() }
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(owner, report.screenshotEnabled, report.loaded) {
        val activity = context.activity()
        val stop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && activity != null &&
            report.loaded && report.screenshotEnabled) watchScreenshots(activity, owner) {
            if (!screenshotPromptAllowed(Build.VERSION.SDK_INT, owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED), current))
                return@watchScreenshots
            prompt?.cancel() // A newer screenshot replaces the pending prompt and its copy.
            prompt = scope.launch {
                // Copy the app window now so the report matches the screenshot, not the screen at tap time.
                val copy = runCatching { withTimeout(1500) { captureBugReportScreenshot(activity) } }.getOrNull()
                val result = snackbar.showSnackbar("Screenshot taken", actionLabel = "Report bug", duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed)
                    model.reports.open(copy?.let { bytes -> { bytes } } ?: {
                        awaitReportFrame()
                        captureBugReportScreenshot(activity)
                    })
            }
        } else null
        onDispose { stop?.invoke(); prompt?.cancel() }
    }
    DisposableEffect(owner, report.shakeEnabled, report.loaded) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val detector = ShakeDetector {
            if (gate.accept(SystemClock.elapsedRealtime(), owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                    current.loaded && current.shakeEnabled, current.visible || current.capturing || current.busy)) {
                model.reports.open { captureBugReportScreenshot(requireNotNull(context.activity())) }
            }
        }
        fun update() {
            if (report.loaded && report.shakeEnabled && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                detector.start(sensors, SensorManager.SENSOR_DELAY_GAME)
            else detector.stop()
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        owner.lifecycle.addObserver(observer)
        update()
        onDispose { owner.lifecycle.removeObserver(observer); detector.stop() }
    }
    if (report.visible) BugReportDialog(report, screen.ready, model.reports)
}

@Composable
private fun BugReportDialog(state: BugReportState, connected: Boolean, actions: BugReportController) {
    val draft = state.draft
    val locked = state.busy || draft?.journal?.isNotEmpty() == true
    var preview by remember { mutableStateOf<DraftAttachment?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { actions.addFiles(it) }
    Dialog(onDismissRequest = actions::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 640.dp).fillMaxHeight(.95f).imePadding().testTag("bug-report-sheet"), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Report a bug", fontSize = 20.sp)
                    TextButton(onClick = actions::close, modifier = Modifier.testTag("close-bug-report")) { Text("Close") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("bug-report-error")) } }
                    if (draft != null) {
                        item {
                            OutlinedTextField(value = draft.description, onValueChange = actions::describe, enabled = !locked,
                                label = { Text("What went wrong?") }, minLines = 3,
                                modifier = Modifier.fillMaxWidth().testTag("bug-description"))
                        }
                        item { Text("Saved on this phone. Review the captured evidence before starting a fix task.", fontSize = 12.sp) }
                        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(reportStage(draft.journal.str("stage"))) }
                        if (draft.journal.isNotEmpty()) item {
                            SelectionContainer { Text("Task: ${draft.journal.str("threadId").ifBlank { "Awaiting identity" }}\nWorkspace: ${draft.journal.str("cwd")}", fontSize = 12.sp) }
                        }
                        items(draft.attachments, key = { it.id }) { file ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(8.dp)) {
                                    if (file.kind == AttachmentKind.IMAGE) ReportImage(file, 180)
                                    Text(file.displayName, modifier = Modifier.testTag("report-artifact-${file.id}"))
                                    Text(formatBytes(file.byteSize), fontSize = 12.sp)
                                    Row {
                                        TextButton(onClick = { preview = file }) { Text("Preview") }
                                        TextButton(onClick = { actions.removeAttachment(file.id) }, enabled = !locked,
                                            modifier = Modifier.testTag("remove-report-${file.id}")) { Text("Remove") }
                                    }
                                }
                            }
                        }
                        item {
                            draft.diagnostics.forEach { (name, value) ->
                                Text("$name: ${(value as? kotlinx.serialization.json.JsonObject)?.str("message").orEmpty()}", fontSize = 12.sp)
                            }
                        }
                        item { OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !locked,
                            modifier = Modifier.fillMaxWidth()) { Text("Add images or files") } }
                    }
                }
                if (draft != null) {
                    Button(onClick = actions::submit,
                        enabled = !state.busy && draft.description.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("submit-bug-report")) {
                        Text(if (!connected) "Save report" else if (draft.journal.isNotEmpty()) "Check and continue" else "Start fix task")
                    }
                    if (draft.journal.isEmpty()) TextButton(onClick = actions::discard, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("discard-bug-report")) { Text("Discard report") }
                }
            }
        }
    }
    preview?.let { ReportArtifactPreview(it) { preview = null } }
}

private fun reportStage(stage: String): String = when (stage) {
    "creatingRoot", "rootReady", "creatingWorktree" -> "Preparing worktree…"
    "workspaceReady", "settingUp" -> "Preparing environment…"
    "creatingEvidence", "evidenceReady", "uploading" -> "Attaching evidence…"
    "creatingTask", "taskReady", "namingTask" -> "Creating fix task…"
    "named", "sending" -> "Starting investigation…"
    else -> "Saving report…"
}

@Composable
private fun ReportImage(file: DraftAttachment, height: Int) {
    val bitmap by produceState<Bitmap?>(null, file.id) {
        value = runCatching { decodedBitmap("report-${file.localPath}", withContext(Dispatchers.IO) { File(file.localPath).readBytes() }, 1200) }.getOrNull()
    }
    bitmap?.let { Image(it.asImageBitmap(), file.displayName, Modifier.fillMaxWidth().height(height.dp), contentScale = ContentScale.Fit) }
}

@Composable
private fun ReportArtifactPreview(file: DraftAttachment, close: () -> Unit) {
    val context = LocalContext.current
    val text by produceState<String?>(null, file.id) {
        if (file.mimeType.startsWith("text/") || file.mimeType == "application/json") value = withContext(Dispatchers.IO) {
            runCatching { File(file.localPath).bufferedReader().use { reader ->
                val chars = CharArray(64 * 1024)
                val length = reader.read(chars)
                if (length < 0) "(Empty file)" else String(chars, 0, length) + if (length == chars.size) "\n[Preview limited to 64 KiB]" else ""
            } }.getOrDefault("The saved artifact is unavailable.")
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text(file.displayName) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            if (file.kind == AttachmentKind.IMAGE) ReportImage(file, 360)
            else SelectionContainer { Text(text ?: "${file.mimeType} · ${formatBytes(file.byteSize)}") }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Close") } }, dismissButton = {
        TextButton(onClick = {
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(file.localPath))
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.onFailure { Toast.makeText(context, "No app can open this artifact.", Toast.LENGTH_SHORT).show() }
        }) { Text("Open") }
    })
}

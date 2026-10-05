package dev.codexops.client

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.codexops.core.*
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
internal fun VisualizationAwareMarkdown(text: String, scope: String, completed: Boolean, actions: ConversationActions) {
    val parts = remember(text) { visualizationParts(text) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        parts.forEachIndexed { index, part ->
            when (part) {
                is VisualizationPart.Markdown -> if (part.text.isNotBlank())
                    SelectionContainer { FileAwareMarkdown(part.text, actions) }
                is VisualizationPart.Visual -> key(scope, index, part.reference.path) {
                    val stateKey = remember(scope, index, part.reference.path) {
                        MessageDigest.getInstance("SHA-256")
                            .digest("$scope/$index/${part.reference.path}".toByteArray())
                            .joinToString("") { "%02x".format(it) }
                    }
                    InlineVisualization(part.reference, stateKey, completed, actions)
                }
            }
        }
    }
}

@Composable
private fun InlineVisualization(reference: VisualizationRef, stateKey: String, completed: Boolean, actions: ConversationActions) {
    var fragment by remember { mutableStateOf<String?>(null) }
    var savedState by remember { mutableStateOf("null") }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var followUp by remember { mutableStateOf<JsonObject?>(null) }
    var externalUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    // A completed marker can arrive before the file write finishes during streaming.
    LaunchedEffect(reference.path, stateKey, completed, attempt) {
        if (!completed) return@LaunchedEffect
        error = null
        fragment = null
        try {
            savedState = actions.visualizationState(stateKey)
            fragment = actions.loadVisualization(reference)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = "This visualization could not be loaded. Check the connection and that its file still exists."
        }
    }
    val content: @Composable (Boolean) -> Unit = { fullscreen ->
        when {
            error != null -> Column(Modifier.testTag("visualization-error")) {
                Text(error!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { attempt++ }) { Text("Retry") }
            }
            fragment == null -> Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(if (completed) "Loading visualization…" else "Preparing visualization…")
            }
            else -> VisualizationWebView(
                fragment = fragment!!,
                initialState = savedState,
                fullscreen = fullscreen,
                onState = { value, receipt ->
                    savedState = value
                    scope.launch {
                        try {
                            actions.saveVisualizationState(stateKey, value)
                            receipt(true)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) { receipt(false) }
                    }
                },
                onFollowUp = { followUp = it },
                onExternal = { externalUrl = it },
                onFailure = { error = "The visualization viewer stopped. Tap Retry to reload it." },
            )
        }
    }
    Column(Modifier.fillMaxWidth().testTag("visualization")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(reference.title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("expand-visualization")) {
                Text("Full screen")
            }
        }
        if (!expanded) content(false)
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("visualization-fullscreen")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(reference.title, Modifier.weight(1f))
                    TextButton(onClick = { expanded = false }, modifier = Modifier.testTag("close-visualization")) { Text("Close") }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { content(true) }
            }
        }
    }
    followUp?.let { request ->
        AlertDialog(
            onDismissRequest = { followUp = null },
            title = { Text(request.str("title").ifBlank { "Prepare a follow-up?" }) },
            text = { Text(request.str("prompt").take(2000)) },
            confirmButton = { TextButton(onClick = {
                actions.stageVisualizationFollowUp(request.str("prompt"))
                followUp = null
                expanded = false
            }) { Text("Add to message") } },
            dismissButton = { TextButton(onClick = { followUp = null }) { Text("Cancel") } },
        )
    }
    externalUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { externalUrl = null },
            title = { Text("Open link?") }, text = { Text(url) },
            confirmButton = { TextButton(onClick = {
                externalUrl = null
                runCatching { uriHandler.openUri(url) }
            }) { Text("Open") } },
            dismissButton = { TextButton(onClick = { externalUrl = null }) { Text("Cancel") } },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun VisualizationWebView(
    fragment: String,
    initialState: String,
    fullscreen: Boolean,
    onState: (String, (Boolean) -> Unit) -> Unit,
    onFollowUp: (JsonObject) -> Unit,
    onExternal: (String) -> Unit,
    onFailure: () -> Unit,
    resourceLoader: (String) -> WebResourceResponse = ::visualizationResource,
) {
    val context = LocalContext.current
    val document = remember { VisualizationDocument(context) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val background = "#%06x".format(MaterialTheme.colorScheme.background.toArgb() and 0xffffff)
    val foreground = "#%06x".format(MaterialTheme.colorScheme.onBackground.toArgb() and 0xffffff)
    // State updates must not reload the executing document. A new view restores the latest snapshot.
    val html = remember(fragment, dark, background, foreground) { document.render(fragment, initialState, dark, background, foreground) }
    val currentHtml by rememberUpdatedState(html)
    var height by remember { mutableIntStateOf(240) }
    val stateHandler by rememberUpdatedState(onState)
    val followUpHandler by rememberUpdatedState(onFollowUp)
    val externalHandler by rememberUpdatedState(onExternal)
    val failureHandler by rememberUpdatedState(onFailure)
    var port by remember { mutableStateOf<WebMessagePort?>(null) }
    var view by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            port?.close()
            view?.apply { stopLoading(); destroy() }
        }
    }
    AndroidView(
        modifier = (if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(height.coerceIn(80, 1200).dp))
            .testTag("visualization-webview"),
        factory = { ctx ->
            WebView(ctx).apply {
                view = this
                setBackgroundColor(Color.TRANSPARENT)
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    databaseEnabled = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    mediaPlaybackRequiresUserGesture = true
                    setGeolocationEnabled(false)
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                setDownloadListener { _, _, _, _, _ -> }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                    override fun onConsoleMessage(message: ConsoleMessage) = true
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        if (request.isForMainFrame && request.url.toString() == VisualizationPolicy.ORIGIN + "/")
                            return WebResourceResponse("text/html", "UTF-8", currentHtml.toByteArray(Charsets.UTF_8).inputStream())
                        return resourceLoader(request.url.toString())
                    }

                    override fun onReceivedError(web: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) failureHandler()
                    }

                    override fun onReceivedHttpError(web: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame) failureHandler()
                    }

                    override fun onPageFinished(web: WebView, url: String) {
                        if (url != VisualizationPolicy.ORIGIN + "/") return
                        port?.close()
                        val channels = web.createWebMessageChannel()
                        val nativePort = channels[0]
                        port = nativePort
                        nativePort.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                            override fun onMessage(source: WebMessagePort, message: WebMessage) {
                                val raw = message.data ?: return
                                if (raw.length > 100_000) return
                                val data = runCatching { wire.parseToJsonElement(raw) as JsonObject }.getOrNull() ?: return
                                when (data.str("type")) {
                                    "height" -> (data["height"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 48..10000 }?.let { height = it }
                                    "widget-state-write" -> {
                                        val value = data.str("state")
                                        val id = data["id"] as? JsonPrimitive ?: return
                                        if (id.longOrNull == null || !validWidgetState(value)) return
                                        stateHandler(value) { ok ->
                                            if (port === source) runCatching {
                                                source.postMessage(WebMessage(obj("type" to s("widget-state-result"), "id" to id, "ok" to JsonPrimitive(ok)).toString()))
                                            }
                                        }
                                    }
                                    "follow-up" -> if (data.str("prompt").length in 1..16384) followUpHandler(data)
                                    "open-external" -> {
                                        val uri = Uri.parse(data.str("href"))
                                        if (uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) externalHandler(uri.toString())
                                    }
                                }
                            }
                        })
                        web.postWebMessage(WebMessage("remote-codex-visualization", arrayOf(channels[1])), Uri.parse(VisualizationPolicy.ORIGIN))
                    }
                    override fun onRenderProcessGone(web: WebView, detail: RenderProcessGoneDetail): Boolean {
                        failureHandler()
                        return true
                    }
                }
            }
        },
        update = { web ->
            if (web.tag != html) {
                port?.close()
                port = null
                web.tag = html
                web.loadUrl(VisualizationPolicy.ORIGIN + "/")
            }
        },
    )
}

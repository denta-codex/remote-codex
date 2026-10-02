package dev.codexops.client

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal class VisualizationDocument(context: Context) {
    private val assets = context.assets
    private fun asset(name: String) = assets.open("visualize/$name").bufferedReader().use { it.readText() }
    private val css = asset("visualize.css")
    private val kit = asset("visualize.html")
    private val bridge = asset("standalone-host-bridge.js")
    private val shell = asset("android-shell.js")
    private val tweak = asset("tweak.js")
    private val calendar = asset("calendar.js")

    fun render(fragment: String, state: String, dark: Boolean, background: String, foreground: String): String {
        val globals = "{\"widgetState\":${state.takeIf(::validWidgetState) ?: "null"},\"statePersistence\":\"local\"}"
        val theme = if (dark) "dark" else "light"
        val inner = """
            <!doctype html><html lang="en" data-visualize-standalone><head>
            <meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="referrer" content="no-referrer">
            <meta http-equiv="Content-Security-Policy" content="${VisualizationPolicy.frameCsp}">
            <style>$css
            :root { color-scheme:$theme; --color-background-primary:$background; --color-text-primary:$foreground; }
            html>body { padding:0; } </style></head><body>
            <script type="application/json" id="codex-visualization-widget-state">${globals.replace("<", "\\u003c")}</script>
            ${script(bridge)}${script(tweak)}${script(calendar)}
            ${kit.replace("<!--__INLINE_VISUALIZATION_FRAGMENT__-->", fragment)}
            </body></html>
        """.trimIndent()
        return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="referrer" content="no-referrer">
            <meta http-equiv="Content-Security-Policy" content="${VisualizationPolicy.frameCsp.replace("frame-src 'none'", "frame-src 'self'")}">
            <style>html,body{margin:0;background:$background;color:$foreground;color-scheme:$theme}iframe{display:block;width:100%;height:240px;border:0}</style>
            </head><body><iframe id="visualization-frame" title="Interactive visualization" sandbox="allow-scripts" referrerpolicy="no-referrer" data-srcdoc="${attribute(inner)}"></iframe>
            ${script(shell)}</body></html>
        """.trimIndent()
    }

    private fun script(value: String) = "<script>${value.replace("</script", "<\\/script")}</script>"
    private fun attribute(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;")
}

/** Fetch only public CDN resources, without WebView cookies, credentials, or unvalidated redirects. */
internal fun visualizationResource(url: String): WebResourceResponse {
    fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArray(0).inputStream())
    var current = url
    repeat(5) {
        if (!VisualizationPolicy.allowsResource(current)) return blocked()
        val connection = runCatching { URL(current).openConnection() as HttpURLConnection }.getOrNull() ?: return blocked()
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val status = connection.responseCode
            if (status in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location") ?: return blocked()
                current = URL(URL(current), location).toString()
            } else {
                if (status != 200 || connection.contentLengthLong > 4 * 1024 * 1024) return blocked()
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 4 * 1024 * 1024) return blocked()
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val type = connection.contentType.orEmpty().substringBefore(';').ifBlank { "application/octet-stream" }
                return WebResourceResponse(type, "UTF-8", 200, "OK",
                    mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"), bytes.inputStream())
            }
        } catch (_: Exception) {
            return blocked()
        } finally {
            connection.disconnect()
        }
    }
    return blocked()
}

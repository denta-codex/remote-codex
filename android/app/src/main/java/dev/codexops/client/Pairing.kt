package dev.codexops.client

private const val PREFIX = "remote-codex-setup-v1:"
private val TOKEN = Regex("[0-9a-f]{64}")

/** A setup QR carries only the transport token; the server endpoint stays fixed in the app. */
internal fun parseSetupQr(value: String?): String? {
    if (value == null || !value.startsWith(PREFIX)) return null
    val token = value.removePrefix(PREFIX)
    return token.takeIf { TOKEN.matches(it) }
}

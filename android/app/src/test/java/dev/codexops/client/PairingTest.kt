package dev.codexops.client

import org.junit.Assert.*
import org.junit.Test

class PairingTest {
    private val token = "a".repeat(64)

    @Test fun acceptsVersionedTokenOnly() {
        assertEquals(token, parseSetupQr("remote-codex-setup-v1:$token"))
        assertNull(parseSetupQr(null))
        assertNull(parseSetupQr("remote-codex-setup-v1:short"))
        assertNull(parseSetupQr("remote-codex-setup-v2:$token"))
        assertNull(parseSetupQr("remote-codex-setup-v1:${token}extra"))
        assertNull(parseSetupQr("https://example.invalid/?token=$token"))
    }
}

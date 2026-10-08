package dev.codexops.client

import dev.codexops.core.ConnectionFailure
import dev.codexops.core.RpcRejected
import org.junit.Assert.*
import org.junit.Test

class ConnectionDiagnosticsTest {
    private fun message(network: ConnectionNetwork, transport: String = "UnknownHostException") =
        connectionFailureMessage(GraceHost, ConnectionFailure(null, transport), network)

    @Test fun missingVpnGivesActionForDnsAndOtherTransportFailures() {
        for (transport in listOf("UnknownHostException", "ConnectException", "SocketTimeoutException")) {
            assertEquals("No VPN connection is active for Remote Codex. Open Tailscale and connect, then return here and tap Reconnect.",
                message(ConnectionNetwork.NoVpn, transport))
        }
    }

    @Test fun activeVpnDoesNotClaimTailscaleIsConnected() {
        val dns = message(ConnectionNetwork.VpnActive)
        assertTrue(dns.contains("A VPN is active"))
        assertTrue(dns.contains("address could not be found"))
        assertTrue(dns.contains("Check that Tailscale is connected"))
        assertTrue(dns.contains("Tailscale DNS"))
        val timeout = message(ConnectionNetwork.VpnActive, "SocketTimeoutException")
        assertTrue(timeout.contains("Grace is unreachable"))
        assertTrue(timeout.contains("Grace is online"))
        assertFalse(timeout.contains("address could not be found"))
    }

    @Test fun offlineAndUnknownNetworkStatesDoNotClaimVpnIsOff() {
        assertEquals("No network connection is available. Connect to Wi-Fi or mobile data, then reconnect.",
            message(ConnectionNetwork.Offline))
        assertEquals("Cannot reach Grace. Check that Tailscale is connected, then reconnect.",
            message(ConnectionNetwork.Unknown))
    }

    @Test fun rejectionTakesPriorityOverNetworkHint() {
        for (network in ConnectionNetwork.entries) {
            assertEquals("Grace rejected the connection credential. Scan the setup QR again in Settings.",
                connectionFailureMessage(GraceHost, ConnectionFailure(401, "Closed"), network))
            assertEquals("Grace rejected the WebSocket connection (HTTP 403).",
                connectionFailureMessage(GraceHost, ConnectionFailure(403, "Closed"), network))
            assertEquals("Grace rejected connection setup (RPC -32600).",
                connectionFailureMessage(GraceHost, RpcRejected(-32600, "private server message"), network))
        }
    }

    @Test fun nonTailscaleEndpointsDoNotRequireVpn() {
        for (endpoint in listOf("ws://127.0.0.1:1234/codex/rpc", "wss://example.com/codex/rpc", "wss://grace.ts.net.example.com/codex/rpc")) {
            val host = GraceHost.copy(endpoint = endpoint)
            assertEquals("Cannot reach Grace (ConnectException). Check the network and reconnect.",
                connectionFailureMessage(host, ConnectionFailure(null, "ConnectException"), ConnectionNetwork.NoVpn))
        }
    }
}

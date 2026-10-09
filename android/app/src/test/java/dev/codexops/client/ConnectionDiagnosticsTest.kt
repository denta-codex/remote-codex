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
            assertEquals("Cannot connect to Grace. No VPN connection was detected. Open Tailscale and connect, then reconnect.",
                message(ConnectionNetwork.NoVpn, transport))
        }
    }

    @Test fun activeVpnDoesNotClaimTailscaleIsConnected() {
        for (network in listOf(ConnectionNetwork.VpnActive, ConnectionNetwork.Unknown)) {
            for (transport in listOf("UnknownHostException", "ConnectException", "SocketTimeoutException")) {
                assertEquals("Cannot connect to Grace. Check that Tailscale is connected, then reconnect.",
                    message(network, transport))
            }
        }
    }

    @Test fun offlineAndUnknownNetworkStatesDoNotClaimVpnIsOff() {
        assertEquals("Cannot connect to Grace. Connect to Wi-Fi or mobile data, then reconnect.",
            message(ConnectionNetwork.Offline))
        assertEquals("Cannot connect to Grace. Check that Tailscale is connected, then reconnect.",
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
            assertEquals("Cannot connect to Grace. Check your network connection, then reconnect.",
                connectionFailureMessage(host, ConnectionFailure(null, "ConnectException"), ConnectionNetwork.NoVpn))
        }
    }
}

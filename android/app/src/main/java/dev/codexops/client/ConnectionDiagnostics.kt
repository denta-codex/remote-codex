package dev.codexops.client

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.codexops.core.ConnectionFailure
import dev.codexops.core.RpcRejected
import java.net.URI

internal enum class ConnectionNetwork { Offline, NoVpn, VpnActive, Unknown }

/** The network available to this app. Android does not expose another VPN app's identity. */
internal fun connectionNetwork(manager: ConnectivityManager): ConnectionNetwork = try {
    val network = manager.activeNetwork
    if (network == null) ConnectionNetwork.Offline
    else {
        val capabilities = manager.getNetworkCapabilities(network)
        when {
            capabilities == null -> ConnectionNetwork.Unknown
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> ConnectionNetwork.VpnActive
            else -> ConnectionNetwork.NoVpn
        }
    }
} catch (_: SecurityException) {
    ConnectionNetwork.Unknown
}

internal fun connectionFailureMessage(
    host: HostIdentity,
    error: Exception,
    network: ConnectionNetwork,
): String = when {
    error is ConnectionFailure && error.httpStatus == 401 ->
        "${host.displayName} rejected the connection credential. Scan the setup QR again in Settings."
    error is ConnectionFailure && error.httpStatus != null ->
        "${host.displayName} rejected the WebSocket connection (HTTP ${error.httpStatus})."
    error is ConnectionFailure -> {
        val tailscaleHost = runCatching { URI(host.endpoint).host?.endsWith(".ts.net", ignoreCase = true) == true }
            .getOrDefault(false)
        when {
            network == ConnectionNetwork.Offline ->
                "Cannot connect to ${host.displayName}. Connect to Wi-Fi or mobile data, then reconnect."
            tailscaleHost && network == ConnectionNetwork.NoVpn ->
                "Cannot connect to ${host.displayName}. No VPN connection was detected. Open Tailscale and connect, then reconnect."
            tailscaleHost ->
                "Cannot connect to ${host.displayName}. Check that Tailscale is connected, then reconnect."
            else -> "Cannot connect to ${host.displayName}. Check your network connection, then reconnect."
        }
    }
    error is RpcRejected -> "${host.displayName} rejected connection setup (RPC ${error.code})."
    else -> "Cannot connect to ${host.displayName} (${error.javaClass.simpleName})."
}

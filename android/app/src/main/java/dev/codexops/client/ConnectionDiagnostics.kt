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
                "No network connection is available. Connect to Wi-Fi or mobile data, then reconnect."
            tailscaleHost && network == ConnectionNetwork.NoVpn ->
                "No VPN connection is active for Remote Codex. Open Tailscale and connect, then return here and tap Reconnect."
            tailscaleHost && network == ConnectionNetwork.VpnActive && error.transport == "UnknownHostException" ->
                "A VPN is active, but ${host.displayName}'s Tailscale address could not be found. Check that Tailscale is connected and using Tailscale DNS, then reconnect."
            tailscaleHost && network == ConnectionNetwork.VpnActive ->
                "A VPN is active, but ${host.displayName} is unreachable. Check that Tailscale is connected and ${host.displayName} is online, then reconnect."
            tailscaleHost ->
                "Cannot reach ${host.displayName}. Check that Tailscale is connected, then reconnect."
            else -> "Cannot reach ${host.displayName} (${error.transport}). Check the network and reconnect."
        }
    }
    error is RpcRejected -> "${host.displayName} rejected connection setup (RPC ${error.code})."
    else -> "Cannot connect to ${host.displayName} (${error.javaClass.simpleName})."
}

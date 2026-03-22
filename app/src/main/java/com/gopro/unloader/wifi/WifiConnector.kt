package com.gopro.unloader.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "WifiConnector"
private const val CONNECT_TIMEOUT_MS = 30_000L

sealed class WifiConnectResult {
    data class Connected(val network: Network) : WifiConnectResult()
    data class Failed(val reason: String) : WifiConnectResult()
}

class WifiConnector(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var legacyNetworkId: Int = -1

    /**
     * Programmatically connect to the GoPro WiFi network and bind all process traffic to it.
     *
     * On API 29+ this uses [WifiNetworkSpecifier] which shows a system confirmation dialog.
     * On API 26-28 this uses the legacy [WifiManager.addNetwork] API.
     */
    suspend fun connectToGoProWifi(ssid: String, password: String): WifiConnectResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectModern(ssid, password)
        } else {
            connectLegacy(ssid, password)
        }
    }

    /**
     * API 29+ (Android 10+): Use WifiNetworkSpecifier + NetworkRequest.
     * Shows a system bottom-sheet for user confirmation (one tap).
     * The network is automatically bound to this process.
     */
    private suspend fun connectModern(ssid: String, password: String): WifiConnectResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return WifiConnectResult.Failed("Modern API requires Android 10+")
        }

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val result = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.d(TAG, "Network available: $network")
                        connectivityManager.bindProcessToNetwork(network)
                        networkCallback = this
                        if (cont.isActive) cont.resume(WifiConnectResult.Connected(network))
                    }

                    override fun onUnavailable() {
                        Log.d(TAG, "Network unavailable (user rejected or timeout)")
                        if (cont.isActive) cont.resume(
                            WifiConnectResult.Failed("WiFi connection rejected or unavailable")
                        )
                    }
                }

                cont.invokeOnCancellation {
                    try {
                        connectivityManager.unregisterNetworkCallback(callback)
                    } catch (_: Exception) { }
                }

                connectivityManager.requestNetwork(request, callback)
            }
        }

        return result ?: WifiConnectResult.Failed("WiFi connection timed out")
    }

    /**
     * API 26-28 (Android 8-9): Use legacy WifiManager.addNetwork / enableNetwork.
     * No user prompt — connects silently.
     */
    @Suppress("DEPRECATION")
    private suspend fun connectLegacy(ssid: String, password: String): WifiConnectResult {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        if (!wifiManager.isWifiEnabled) {
            wifiManager.isWifiEnabled = true
            // Brief wait for WiFi radio to come up
            kotlinx.coroutines.delay(1_500)
        }

        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            preSharedKey = "\"$password\""
        }

        // Remove any previous config for this SSID
        wifiManager.configuredNetworks?.filter { it.SSID == "\"$ssid\"" }?.forEach {
            wifiManager.removeNetwork(it.networkId)
        }

        val netId = wifiManager.addNetwork(config)
        if (netId == -1) {
            return WifiConnectResult.Failed("addNetwork failed for SSID: $ssid")
        }
        legacyNetworkId = netId

        wifiManager.disconnect()
        wifiManager.enableNetwork(netId, true)
        wifiManager.reconnect()

        // Wait for the connection and bind process traffic
        val result = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.d(TAG, "Legacy network available: $network")
                        connectivityManager.bindProcessToNetwork(network)
                        networkCallback = this
                        if (cont.isActive) cont.resume(WifiConnectResult.Connected(network))
                    }
                }

                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()

                cont.invokeOnCancellation {
                    try {
                        connectivityManager.unregisterNetworkCallback(callback)
                    } catch (_: Exception) { }
                }

                connectivityManager.registerNetworkCallback(request, callback)
            }
        }

        return result ?: WifiConnectResult.Failed("WiFi connection timed out")
    }

    /**
     * Unbind process from the GoPro network and clean up.
     * Call this when done communicating with the camera.
     */
    fun disconnect() {
        connectivityManager.bindProcessToNetwork(null)
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) { }
        }
        networkCallback = null

        if (legacyNetworkId != -1) {
            @Suppress("DEPRECATION")
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.removeNetwork(legacyNetworkId)
            legacyNetworkId = -1
        }
    }
}

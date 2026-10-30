package com.princeniithompson.rapcha.service.floating

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log

object RetryConnectivity {
    fun isOnline(context: Context): Boolean {
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/**
 * Fires once each time a network with internet becomes available.
 * The callback is posted to the main thread.
 */
class RetryNetworkWatcher(
    context: Context,
    private val onNetworkBack: () -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            publish()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                publish()
            }
        }

        private fun publish() {
            if (!RetryConnectivity.isOnline(appContext)) return
            mainHandler.post { onNetworkBack() }
        }
    }

    fun start() {
        val connectivity = manager ?: return
        if (registered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivity.registerNetworkCallback(request, callback)
            registered = true
        } catch (t: Throwable) {
            Log.w(TAG, "Network callback not registered: ${t.javaClass.simpleName}")
        }
    }

    fun stop() {
        if (!registered) return
        try {
            manager?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        } finally {
            registered = false
        }
    }

    companion object {
        private const val TAG = "RetryNetworkWatcher"
    }
}

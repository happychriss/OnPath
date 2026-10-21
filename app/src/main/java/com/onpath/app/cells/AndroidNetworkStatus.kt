package com.onpath.app.cells

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.onpath.app.debug.Problems

/** [NetworkStatus] from Android's ConnectivityManager (no Google Play Services). */
class AndroidNetworkStatus(context: Context) : NetworkStatus {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun caps(): NetworkCapabilities? = try { cm.getNetworkCapabilities(cm.activeNetwork) } catch (e: Exception) {
        Problems.warn("cells", "Network state unreadable; treated as offline", e); null
    }

    override fun connected(): Boolean = caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    override fun unmetered(): Boolean = caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    /** Calls [onChange] whenever the default network changes (Wi-Fi back, offline …). */
    fun listen(onChange: () -> Unit) {
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onChange()
                override fun onLost(network: Network) = onChange()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChange()
            })
        } catch (e: Exception) {
            Problems.warn("cells", "Network changes not watched; map downloads retry on their own timer", e)
        }
    }
}

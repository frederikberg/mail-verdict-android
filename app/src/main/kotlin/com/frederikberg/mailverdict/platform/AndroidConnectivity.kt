package com.frederikberg.mailverdict.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.frederikberg.mailverdict.kit.live.Connectivity

/** The device's default network path, from the system's connectivity service. */
class AndroidConnectivity(
    context: Context,
) : Connectivity {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    override val isOnline: Boolean
        get() =
            manager
                .getNetworkCapabilities(manager.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    override fun observe(listener: (online: Boolean) -> Unit) {
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = listener(true)

                override fun onLost(network: Network) = listener(false)
            },
        )
    }
}

/** A path that is always there: fixture mode has no network to lose. */
object AlwaysOnline : Connectivity {
    override val isOnline: Boolean = true

    override fun observe(listener: (online: Boolean) -> Unit) = Unit
}

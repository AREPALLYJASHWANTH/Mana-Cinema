package com.manacinema.app.security

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.widget.Toast

/**
 * Blocks the app while a VPN / proxy tunnel is active.
 *
 *  1. [isVpnActive]   - one shot check (app start + every resume).
 *  2. [startMonitoring] - live monitoring; closes the app the moment a VPN
 *     is switched on while the user is inside the app.
 */
object VpnDetector {

    private const val BLOCK_MESSAGE = "Server Error..."

    /** True when any network (or interface) is a VPN tunnel. */
    fun isVpnActive(context: Context): Boolean =
        viaConnectivityManager(context) || viaNetworkInterfaces()

    private fun viaConnectivityManager(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                cm.allNetworks.any { network ->
                    val caps = cm.getNetworkCapabilities(network)
                    caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                @Suppress("DEPRECATION")
                info != null && info.type == ConnectivityManager.TYPE_VPN
            }
        } catch (e: Exception) {
            android.util.Log.e("VpnDetector", "ConnectivityManager check failed", e)
            false
        }
    }

    /** Fallback: tun0 / ppp0 / ipsec interfaces used by VPN clients. */
    private fun viaNetworkInterfaces(): Boolean {
        return try {
            java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                .any {
                    it.isUp && (it.name.startsWith("tun") ||
                        it.name.startsWith("ppp") ||
                        it.name.startsWith("ipsec"))
                }
        } catch (e: Exception) {
            false
        }
    }

    /** Shows the error toast and closes the app completely. */
    fun blockAndExit(activity: Activity) {
        try {
            Toast.makeText(activity.applicationContext, BLOCK_MESSAGE, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
        }
        // Post the kill so the Toast has a chance to display.
        activity.window?.decorView?.postDelayed({
            try {
                activity.finishAffinity()
            } catch (_: Exception) { }
            // Give finishAffinity a moment before hard kill.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    android.os.Process.killProcess(android.os.Process.myPid())
                } catch (_: Exception) { }
            }, 200)
        }, 800)
    }

    /**
     * Registers a live listener. Returns the callback so the caller can
     * unregister it in onDestroy (null when monitoring is unsupported).
     */
    fun startMonitoring(activity: Activity): ConnectivityManager.NetworkCallback? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, caps)
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    activity.runOnUiThread { blockAndExit(activity) }
                }
            }

            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                if (isVpnActive(activity)) {
                    activity.runOnUiThread { blockAndExit(activity) }
                }
            }
        }
        return try {
            cm.registerDefaultNetworkCallback(callback)
            callback
        } catch (e: Exception) {
            android.util.Log.e("VpnDetector", "registerDefaultNetworkCallback failed", e)
            null
        }
    }

    fun stopMonitoring(context: Context, callback: ConnectivityManager.NetworkCallback?) {
        if (callback == null) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }
}

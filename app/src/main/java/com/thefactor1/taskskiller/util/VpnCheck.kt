package com.thefactor1.taskskiller.util

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.SystemClock

/**
 * Whether a restarted VPN app really came back. Android shows every VPN as a
 * network with the VPN transport; a restart tears the old one down and the
 * app's reconnect brings up a new one, so "a VPN network we hadn't seen before"
 * is the sign it worked.
 */
object VpnCheck {

    /** The app offers a VPN service (declares [VpnService.SERVICE_INTERFACE]). */
    fun isVpnApp(context: Context, packageName: String): Boolean =
        context.packageManager
            .queryIntentServices(Intent(VpnService.SERVICE_INTERFACE).setPackage(packageName), 0)
            .isNotEmpty()

    /** Identities of the VPN networks up right now. */
    @Suppress("DEPRECATION") // allNetworks: still the simplest full listing on API 24+
    fun vpnNetworks(context: Context): Set<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptySet()
        return cm.allNetworks.filter { network ->
            cm.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } == true
        }.map { it.toString() }.toSet()
    }

    /**
     * Starts watching, off the calling thread, for a VPN network that is not in
     * [before], so the moment it appears is known even while the caller is
     * busy with something else (waiting to send a sleeping TV back to Home).
     */
    fun watch(context: Context, before: Set<String>): Watch = Watch(context.applicationContext, before).also { it.start() }

    class Watch internal constructor(private val context: Context, private val before: Set<String>) : Thread("vpn-watch") {
        @Volatile private var seenAt = 0L
        @Volatile private var stopped = false

        init { isDaemon = true }

        override fun run() {
            while (!stopped && seenAt == 0L) {
                if (isNew(before, vpnNetworks(context))) seenAt = SystemClock.elapsedRealtime()
                else SystemClock.sleep(POLL_MS)
            }
        }

        /**
         * How long after [since] (elapsedRealtime) the new VPN appeared, waiting
         * until [since] + [timeoutMs] at most; null if it never did. Stops the watch.
         */
        fun await(since: Long, timeoutMs: Long): Long? {
            val deadline = since + timeoutMs
            while (seenAt == 0L && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(POLL_MS)
            stopped = true
            return seenAt.takeIf { it != 0L }?.let { (it - since).coerceAtLeast(0L) }
        }
    }

    fun isNew(before: Set<String>, now: Set<String>): Boolean = (now - before).isNotEmpty()

    private const val POLL_MS = 500L
}

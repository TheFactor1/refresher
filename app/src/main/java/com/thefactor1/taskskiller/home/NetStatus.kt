package com.thefactor1.taskskiller.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.thefactor1.taskskiller.status.SystemStats.Level

/** The TV's connection for Home: Wi-Fi (and how strong) or Ethernet, whether it's online, and whether a VPN is on. */
object NetStatus {

    enum class Link { WIFI, ETHERNET, OTHER, NONE }

    data class Net(val link: Link, val bars: Int?, val online: Boolean, val vpn: Boolean)

    /** Offline is red; a weak signal or no VPN is amber; otherwise fine. */
    fun level(n: Net): Level = when {
        n.link == Link.NONE || !n.online -> Level.BAD
        !n.vpn || (n.link == Link.WIFI && (n.bars ?: 4) <= 1) -> Level.WARN
        else -> Level.OK
    }

    /** Wi-Fi bars 0..4 as a word. */
    fun strength(bars: Int) = when {
        bars >= 3 -> Strength.STRONG
        bars == 2 -> Strength.FAIR
        else -> Strength.WEAK
    }

    enum class Strength { STRONG, FAIR, WEAK }

    @Suppress("DEPRECATION") // allNetworks and connectionInfo: the simple way on API 24-30, and still answered after
    fun read(context: Context): Net {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Net(Link.NONE, null, false, false)
        val caps = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        val vpn = caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        val real = caps.filterNot { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        val link = when {
            real.any { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } -> Link.ETHERNET
            real.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } -> Link.WIFI
            real.isNotEmpty() -> Link.OTHER
            else -> Link.NONE
        }
        val online = real.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
        val bars = if (link == Link.WIFI) runCatching {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            WifiManager.calculateSignalLevel(wifi.connectionInfo.rssi, 5)
        }.getOrNull() else null
        return Net(link, bars, online, vpn)
    }
}

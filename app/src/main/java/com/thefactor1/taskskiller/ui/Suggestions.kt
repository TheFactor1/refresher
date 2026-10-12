package com.thefactor1.taskskiller.ui

import com.thefactor1.taskskiller.util.PackageUtil

/**
 * Apps that commonly hang or lose their connection on a TV box (VPNs and
 * streaming players), shown first in "Add an app". Matched by package name,
 * so only what's actually installed appears.
 */
object Suggestions {

    private val KNOWN = listOf(
        "com.nordvpn.android", "com.expressvpn.vpn", "com.surfshark.vpnclient.android", "com.privateinternetaccess.android",
        "ch.protonvpn.android", "com.wireguard.android", "de.blinkt.openvpn", "net.openvpn.openvpn",
        "net.mullvad.mullvadvpn", "com.tailscale.ipn",
        "ar.tvplayer.tv", "com.nathnetwork.xciptv", "se.hedekonsult.sparkle",
        "com.plexapp.android", "org.xbmc.kodi", "org.jellyfin.androidtv", "tv.emby.embyatv",
        "com.stremio.one"
    )

    data class Sections(val suggested: List<PackageUtil.InstalledApp>, val rest: List<PackageUtil.InstalledApp>)

    /** Suggested ones in [KNOWN]'s order; everything else (including them, again, A–Z) after. */
    fun sections(apps: List<PackageUtil.InstalledApp>): Sections {
        val byPackage = apps.associateBy { it.packageName }
        val suggested = KNOWN.mapNotNull { byPackage[it] }
        val rest = apps.sortedBy { it.label.lowercase() }
        return Sections(suggested, rest)
    }
}

package com.thefactor1.taskskiller.home

import com.thefactor1.taskskiller.status.SystemStats

/** The launcher's rules, kept pure so they can be tested. */
object HomeLogic {

    data class App(val packageName: String, val label: String)

    /** Favourites (in the user's order, if still installed), then every other visible app A to Z. Hidden apps are left out. */
    fun arrange(apps: List<App>, favourites: List<String>, hidden: Set<String>): Pair<List<App>, List<App>> {
        val byPkg = apps.associateBy { it.packageName }
        val favs = favourites.mapNotNull { byPkg[it] }.filter { it.packageName !in hidden }
        val favSet = favs.map { it.packageName }.toSet()
        val rest = apps.filter { it.packageName !in favSet && it.packageName !in hidden }.sortedBy { it.label.lowercase() }
        return favs to rest
    }

    /** Never stopped by Clean up: the system's own pieces and what keeps the TV working. */
    val ALWAYS_KEEP = setOf(
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.google.android.tvlauncher",
        "com.google.android.tvrecommendations", "com.android.tv.settings", "com.android.providers.tv",
        "com.google.android.leanbacklauncher", "com.google.android.inputmethod.latin", "com.google.android.katniss",
        "com.android.packageinstaller", "com.google.android.packageinstaller", "moe.shizuku.privileged.api"
    )

    /**
     * What Clean up stops: background apps above [thresholdKb], biggest first. Never the app on screen, never [keep]
     * (Refresher, VPNs, favourites if chosen), never [ALWAYS_KEEP].
     */
    fun cleanupTargets(apps: List<SystemStats.AppUse>, thresholdKb: Long, onScreen: String?, keep: Set<String>): List<SystemStats.AppUse> =
        apps.filter { it.pssKb >= thresholdKb && it.packageName != onScreen && it.packageName !in keep && it.packageName !in ALWAYS_KEEP }
            .sortedByDescending { it.pssKb }
}

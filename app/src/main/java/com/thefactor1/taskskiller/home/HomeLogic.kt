package com.thefactor1.taskskiller.home

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
}

package com.thefactor1.taskskiller.home

import android.content.Context
import android.content.SharedPreferences

/** The launcher's choices: favourites (in order), hidden apps, look, clock and clean-up. */
class HomePrefs private constructor(private val prefs: SharedPreferences) {

    enum class TileStyle { BANNERS, ICONS }
    enum class Clock { SYSTEM, H12, H24 }

    var favourites: List<String>
        get() = prefs.getString(K_FAVS, "").orEmpty().split(',').filter { it.isNotBlank() }
        set(v) = prefs.edit().putString(K_FAVS, v.distinct().joinToString(",")).apply()

    var hidden: Set<String>
        get() = prefs.getStringSet(K_HIDDEN, emptySet()).orEmpty().toSet()
        set(v) = prefs.edit().putStringSet(K_HIDDEN, v).apply()

    var tileStyle: TileStyle
        get() = runCatching { TileStyle.valueOf(prefs.getString(K_TILES, TileStyle.BANNERS.name)!!) }.getOrDefault(TileStyle.BANNERS)
        set(v) = prefs.edit().putString(K_TILES, v.name).apply()

    var clock: Clock
        get() = runCatching { Clock.valueOf(prefs.getString(K_CLOCK, Clock.SYSTEM.name)!!) }.getOrDefault(Clock.SYSTEM)
        set(v) = prefs.edit().putString(K_CLOCK, v.name).apply()

    var showHealth: Boolean
        get() = prefs.getBoolean(K_HEALTH, true)
        set(v) = prefs.edit().putBoolean(K_HEALTH, v).apply()

    /** Clean up stops background apps using more than this. */
    var cleanupMb: Int
        get() = prefs.getInt(K_CLEAN_MB, CLEANUP_STEPS[2])
        set(v) = prefs.edit().putInt(K_CLEAN_MB, v).apply()

    var cleanupSkipsFavourites: Boolean
        get() = prefs.getBoolean(K_CLEAN_FAVS, false)
        set(v) = prefs.edit().putBoolean(K_CLEAN_FAVS, v).apply()

    fun toggleFavourite(pkg: String) {
        favourites = if (pkg in favourites) favourites - pkg else favourites + pkg
    }

    /** Moves a favourite one place left (-1) or right (+1). */
    fun moveFavourite(pkg: String, dir: Int) {
        val list = favourites.toMutableList()
        val i = list.indexOf(pkg)
        val j = i + dir
        if (i < 0 || j !in list.indices) return
        list.removeAt(i); list.add(j, pkg)
        favourites = list
    }

    companion object {
        val CLEANUP_STEPS = intArrayOf(50, 100, 150, 200, 300)
        private const val NAME = "home"
        private const val K_FAVS = "favourites"
        private const val K_HIDDEN = "hidden"
        private const val K_TILES = "tiles"
        private const val K_CLOCK = "clock"
        private const val K_HEALTH = "health"
        private const val K_CLEAN_MB = "cleanup_mb"
        private const val K_CLEAN_FAVS = "cleanup_skips_favourites"

        fun get(context: Context) = HomePrefs(context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE))
    }
}

package com.thefactor1.taskskiller.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.thefactor1.taskskiller.kill.KillBackend

/**
 * Making Refresher Home the TV's home screen, and giving it back. Through Shizuku: first Android's own "home app"
 * setting (nothing is turned off); only if the TV takes Home back, and only after asking, the stock launcher is
 * turned off. Undo turns it back on and hands Home back to whatever had it before. Only known stock launchers are ever touched.
 */
object DefaultHome {

    /** The stock launchers this may turn off, and nothing else. */
    val STOCK = listOf(
        "com.google.android.apps.tv.launcherx", // Google TV (onn, Chromecast)
        "com.google.android.tvlauncher", // Android TV (Shield)
        "com.google.android.leanbacklauncher" // older Android TV
    )

    private const val PROJECTIVY = "com.spocky.projengmenu"
    private val OFF = setOf(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)

    enum class State { REFRESHER, OTHER }

    /** The package the Home button opens now, or null when Android would ask. */
    fun currentHome(context: Context): String? {
        val r = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return r?.activityInfo?.packageName?.takeIf { it != "android" }
    }

    fun isDefault(context: Context) = currentHome(context) == context.packageName

    /** Stock launchers installed and still turned on. */
    fun stockEnabled(context: Context): List<String> = STOCK.filter { enabled(context, it) == true }

    /** Stock launchers installed (on or off). */
    fun stockInstalled(context: Context): List<String> = STOCK.filter { enabled(context, it) != null }

    /** Whether Projectivy's "override Home" (an accessibility service) is on: it would still catch the Home button. */
    fun projectivyOverride(context: Context): Boolean =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().contains(PROJECTIVY)

    /** Asks Android to make [pkg] the home app. True if it now is. */
    fun setHome(context: Context, backend: KillBackend, pkg: String): Boolean {
        backend.shellOutput(context, arrayOf("cmd", "role", "add-role-holder", "android.app.role.HOME", pkg)) ?: return false
        return currentHome(context) == pkg
    }

    /** Makes Refresher the home app, remembering what had the Home button so Undo can hand it back. */
    fun makeDefault(context: Context, backend: KillBackend): Boolean {
        val before = currentHome(context)
        if (before != null && before != context.packageName) HomePrefs.get(context).previousHome = before
        return setHome(context, backend, context.packageName)
    }

    /** Turns off the stock launchers (only [STOCK]), remembering them for Undo. True if Refresher is then home. */
    fun turnOffStock(context: Context, backend: KillBackend): Boolean {
        val prefs = HomePrefs.get(context)
        for (pkg in stockEnabled(context)) {
            backend.shellOutput(context, arrayOf("pm", "disable-user", "--user", "0", pkg)) ?: return false
            if (enabled(context, pkg) == false) prefs.turnedOff = prefs.turnedOff + pkg
        }
        return makeDefault(context, backend)
    }

    /** Turns back on what Refresher turned off and hands Home to the stock launcher. True if Refresher no longer is home. */
    fun undo(context: Context, backend: KillBackend): Boolean {
        val prefs = HomePrefs.get(context)
        for (pkg in prefs.turnedOff) {
            backend.shellOutput(context, arrayOf("pm", "enable", "--user", "0", pkg)) ?: return false
        }
        prefs.turnedOff = prefs.turnedOff.filter { enabled(context, it) != true }.toSet()
        prefs.wantsDefault = false
        // Back to what had Home before (another launcher), else the TV's own.
        val back = prefs.previousHome?.takeIf { enabled(context, it) == true } ?: stockEnabled(context).firstOrNull()
        back?.let { setHome(context, backend, it) }
        if (!isDefault(context)) prefs.previousHome = null
        return !isDefault(context)
    }

    /** True, false, or null when [pkg] isn't installed. */
    private fun enabled(context: Context, pkg: String): Boolean? = try {
        context.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).enabled &&
            context.packageManager.getApplicationEnabledSetting(pkg) !in OFF
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}

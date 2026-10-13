package com.thefactor1.taskskiller.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

object PackageUtil {

    /** Letters, digits and underscores in dot-separated segments — nothing a shell reads. */
    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")

    /**
     * Whether [value] is a syntactically real package name.
     *
     * [com.thefactor1.taskskiller.kill.RootBackend] builds its commands by
     * interpolating the package name into a string that `su -c` hands to a
     * shell, so a name carrying `;` or `$(…)` would be executed rather than
     * treated as an argument. Names only ever come from PackageManager today,
     * but rules are persisted as JSON, so anything arriving from storage is
     * checked before it can reach a shell.
     */
    fun isPlausiblePackageName(value: String): Boolean =
        value.length in 1..255 && PACKAGE_NAME.matches(value)

    /**
     * TV apps expose a LEANBACK_LAUNCHER activity; sideloaded phone apps only
     * have the ordinary LAUNCHER one. Try the TV entry point first.
     */
    fun launchIntent(context: Context, packageName: String): Intent? {
        val pm = context.packageManager
        return pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
    }

    /**
     * `am start` arguments for opening [packageName] exactly as its launcher icon
     * does: same action, categories and component, new-task plus reset flags.
     * With an intent identical to the one that started its task, Android brings
     * the existing task forward with whatever screen it was on, instead of
     * stacking a fresh copy of the first screen on top (which left Refresher's
     * Back button walking through duplicates after a restart returned to it).
     */
    fun launchArgs(context: Context, packageName: String): List<String>? {
        val intent = launchIntent(context, packageName) ?: return null
        val component = intent.component ?: return null
        return buildList {
            add("am"); add("start")
            add("-a"); add(intent.action ?: Intent.ACTION_MAIN)
            intent.categories?.sorted()?.forEach { add("-c"); add(it) }
            add("-n"); add(component.flattenToShortString())
            add("-f"); add("0x" + Integer.toHexString(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
    }

    /** The component behind [launchIntent], formatted for `am start -n`. */
    fun launchComponent(context: Context, packageName: String): ComponentName? =
        launchIntent(context, packageName)?.component

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** The app's icon for list rows; null if it was uninstalled since the list was built. */
    fun icon(context: Context, packageName: String): Drawable? = try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun label(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    data class InstalledApp(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val hasLauncherEntry: Boolean
    )

    /**
     * Everything the user could plausibly want to restart. Launchable apps come
     * first because those are the ones with a visible entry on the TV home row.
     */
    fun installedApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val launchable = HashSet<String>()
        listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).forEach { category ->
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            pm.queryIntentActivities(intent, 0).forEach { launchable.add(it.activityInfo.packageName) }
        }

        return pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    hasLauncherEntry = launchable.contains(info.packageName)
                )
            }
            .sortedWith(
                compareByDescending<InstalledApp> { it.hasLauncherEntry }
                    .thenBy { it.label.lowercase() }
            )
            .toList()
    }
}

package com.thefactor1.taskskiller.ui

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.data.RuleStore

/**
 * Patch notes, kept in res/raw/whats_new.txt as "## <version>" headings each
 * followed by one note per line. Shown once after an update, and any time
 * from Setup.
 */
object WhatsNew {

    data class Release(val version: String, val notes: List<String>)

    fun parse(text: String): List<Release> {
        val releases = mutableListOf<Release>()
        var version: String? = null
        val notes = mutableListOf<String>()
        fun flush() {
            version?.let { if (notes.isNotEmpty()) releases += Release(it, notes.toList()) }
            notes.clear()
        }
        text.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("## ") -> { flush(); version = line.removePrefix("## ").trim() }
                line.isNotEmpty() && version != null -> notes += line
            }
        }
        flush()
        return releases
    }

    fun releases(context: Context): List<Release> =
        context.resources.openRawResource(R.raw.whats_new).bufferedReader().use { parse(it.readText()) }

    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    /** Every release in [shown], newest first, in one dialog. */
    fun show(activity: Activity, shown: List<Release> = releases(activity).take(3)) {
        if (shown.isEmpty()) return
        val body = shown.joinToString("\n\n") { r ->
            r.version + "\n" + r.notes.joinToString("\n") { "•  $it" }
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.whats_new_title, versionName(activity)))
            .setMessage(body)
            .setPositiveButton(R.string.whats_new_ok, null)
            .show()
    }

    /** The releases newer than [seen], newest first; all of them when [seen] isn't listed. */
    fun since(all: List<Release>, seen: String?): List<Release> {
        val i = all.indexOfFirst { it.version == seen }
        return if (i < 0) all else all.take(i)
    }

    /**
     * After an update, once: everything since the version last seen. A fresh
     * install only remembers the version; there is nothing "new" to someone
     * who just arrived.
     */
    fun showAfterUpdate(activity: Activity) {
        val prefs = activity.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = versionName(activity)
        val seen = prefs.getString(KEY_SEEN, null)
        if (seen == current) return
        prefs.edit().putString(KEY_SEEN, current).apply()
        // No record yet but apps already set up: an update from a version from
        // before these notes existed, not a fresh install.
        if (seen == null && RuleStore.get(activity).all().isEmpty()) return
        show(activity, since(releases(activity), seen))
    }

    private const val PREFS = "whats_new"
    private const val KEY_SEEN = "seen_version"
}

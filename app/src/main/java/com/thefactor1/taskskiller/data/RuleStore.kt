package com.thefactor1.taskskiller.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException

/**
 * Rules live in a JSON blob inside device-protected SharedPreferences, so the
 * boot receiver can read them before the user unlocks the device.
 *
 * Every mutation is a read-modify-write of that single blob, and the UI thread
 * and the restart worker both perform them, so the mutators are synchronized:
 * without that, a run recording its result would silently drop an edit the user
 * made at the same moment, or vice versa.
 */
class RuleStore private constructor(private val prefs: SharedPreferences) {

    @Synchronized
    fun all(): List<Rule> {
        val raw = prefs.getString(KEY_RULES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                try {
                    Rule.fromJson(array.getJSONObject(i))
                } catch (e: JSONException) {
                    null // Drop a single corrupt entry rather than losing every rule.
                }
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun enabled(): List<Rule> = all().filter { it.enabled }

    fun find(id: Long): Rule? = all().firstOrNull { it.id == id }

    @Synchronized
    fun upsert(rule: Rule) {
        val rules = all().toMutableList()
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) rules[index] = rule else rules.add(rule)
        write(rules)
    }

    @Synchronized
    fun delete(id: Long) = write(all().filterNot { it.id == id })

    fun nextId(): Long = (all().maxOfOrNull { it.id } ?: 0L) + 1L

    /**
     * Stamp an anchor on any rule that has neither run nor been anchored, and
     * return the current rule set. This is what makes the next-run time stable
     * across repeated schedule rebuilds; see [Rule.anchorAt].
     */
    @Synchronized
    fun backfillAnchors(now: Long): List<Rule> {
        val rules = all()
        if (rules.none { it.anchorAt <= 0L && it.lastRunAt <= 0L }) return rules
        val patched = rules.map {
            if (it.anchorAt <= 0L && it.lastRunAt <= 0L) it.copy(anchorAt = now) else it
        }
        write(patched)
        return patched
    }

    /** Global kill switch, so the user can pause everything without editing rules. */
    var masterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER, true)
        set(value) = prefs.edit().putBoolean(KEY_MASTER, value).apply()

    var preferredBackend: String
        get() = prefs.getString(KEY_BACKEND, BACKEND_AUTO) ?: BACKEND_AUTO
        set(value) = prefs.edit().putString(KEY_BACKEND, value).apply()

    /** Start Shizuku over the box's own network debugging after every reboot. */
    var autoStartShizuku: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_SHIZUKU, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START_SHIZUKU, value).apply()

    /**
     * Keep the copy of the rules outside the app (Backup). Off after "Remove
     * setup", so nothing writes it back after it was deleted; on again with
     * the next automatic setup.
     */
    var backupEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKUP_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BACKUP_ENABLED, value).apply()

    @Synchronized
    private fun write(rules: List<Rule>) {
        val array = JSONArray()
        rules.sortedBy { it.id }.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_RULES, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "taskskiller_rules"
        private const val KEY_RULES = "rules"
        private const val KEY_MASTER = "master_enabled"
        private const val KEY_BACKEND = "preferred_backend"
        private const val KEY_AUTO_START_SHIZUKU = "auto_start_shizuku"
        private const val KEY_BACKUP_ENABLED = "backup_enabled"

        const val BACKEND_AUTO = "auto"

        @Volatile
        private var instance: RuleStore? = null

        /** Tests get a fresh app (and fresh storage) each time; drop the old one's store. */
        @androidx.annotation.VisibleForTesting
        fun resetForTests() {
            instance = null
        }

        fun get(context: Context): RuleStore = instance ?: synchronized(this) {
            instance ?: RuleStore(
                context.applicationContext
                    .createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}

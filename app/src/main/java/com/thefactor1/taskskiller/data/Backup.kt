package com.thefactor1.taskskiller.data

import android.content.Context
import android.util.Base64
import android.util.Log
import com.thefactor1.taskskiller.kill.KillBackends
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A copy of the rules kept where an uninstall can't reach: a folder of the
 * shell user's (/data/local/tmp), written and read through Shizuku. Reinstalls
 * wiped the user's rules twice; with this, a fresh install offers them back
 * once Shizuku is set up again.
 *
 * Holds only what the rules say (which apps, how often, which options); run
 * history stays behind.
 */
object Backup {

    data class Contents(
        val rules: List<Rule>,
        val masterEnabled: Boolean,
        val autoStartShizuku: Boolean,
        val savedAt: Long
    )

    private const val TAG = "Backup"
    private const val DIR = "/data/local/tmp/refresher"
    private const val FILE = "$DIR/backup.json"
    private const val FORMAT = 1

    fun encode(contents: Contents): String = JSONObject().apply {
        put("format", FORMAT)
        put("savedAt", contents.savedAt)
        put("masterEnabled", contents.masterEnabled)
        put("autoStartShizuku", contents.autoStartShizuku)
        put("rules", JSONArray().apply {
            contents.rules.forEach { put(it.copy(anchorAt = 0L, lastRunAt = 0L, lastResult = "").toJson()) }
        })
    }.toString()

    /** Null for anything unreadable or from a newer format; unreadable rules are dropped one by one. */
    fun decode(text: String): Contents? = try {
        val json = JSONObject(text)
        if (json.optInt("format", 0) != FORMAT) {
            null
        } else {
            val array = json.getJSONArray("rules")
            val rules = (0 until array.length()).mapNotNull { i ->
                try {
                    Rule.fromJson(array.getJSONObject(i))
                } catch (e: JSONException) {
                    null
                }
            }
            Contents(
                rules = rules,
                masterEnabled = json.optBoolean("masterEnabled", true),
                autoStartShizuku = json.optBoolean("autoStartShizuku", true),
                savedAt = json.optLong("savedAt", 0L)
            )
        }
    } catch (e: JSONException) {
        null
    }

    /** Saves the current rules, off the calling thread. Quietly skipped without a shell (no Shizuku yet). */
    fun saveAsync(context: Context) {
        val app = context.applicationContext
        Thread {
            try {
                save(app)
            } catch (e: Exception) {
                Log.w(TAG, "Backup failed", e)
            }
        }.start()
    }

    private fun save(context: Context) {
        val store = RuleStore.get(context)
        val rules = store.all()
        // A fresh install has nothing yet: never overwrite a good backup with that.
        if (rules.isEmpty()) return
        val backend = KillBackends.resolve(context)
        val data = encode(Contents(rules, store.masterEnabled, store.autoStartShizuku, System.currentTimeMillis()))
        val b64 = Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)
        // Base64 is only letters, digits, + / and =, so it is safe as a bare shell word.
        val script = "umask 077 && mkdir -p $DIR && echo $b64 | base64 -d > $FILE.\$\$ && mv $FILE.\$\$ $FILE"
        val result = backend.shell(context, arrayOf("sh", "-c", script)) ?: return
        if (!result.success) Log.w(TAG, "Backup not written: ${result.detail}")
    }

    /** The saved copy, or null when there is none or no shell to read it with. Blocks. */
    fun load(context: Context): Contents? {
        val text = KillBackends.resolve(context).shellOutput(context, arrayOf("cat", FILE)) ?: return null
        return decode(text.trim())
    }

    /** Puts a saved copy back. The schedules restart from now. */
    fun restore(context: Context, contents: Contents) {
        val store = RuleStore.get(context)
        val now = System.currentTimeMillis()
        contents.rules.forEachIndexed { i, rule ->
            store.upsert(rule.copy(id = store.nextId().coerceAtLeast(i + 1L), anchorAt = now))
        }
        store.masterEnabled = contents.masterEnabled
        store.autoStartShizuku = contents.autoStartShizuku
    }
}

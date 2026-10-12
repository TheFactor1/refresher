package com.thefactor1.taskskiller.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A short rolling history of what actually happened, which is the only way to
 * tell a working setup from a silently-blocked one on a headless TV box.
 */
class RunLog private constructor(private val prefs: SharedPreferences) {

    data class Entry(val timestamp: Long, val label: String, val detail: String, val success: Boolean)

    fun entries(): List<Entry> {
        val raw = prefs.getString(KEY_LOG, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                Entry(
                    timestamp = item.optLong("ts"),
                    label = item.optString("label"),
                    detail = item.optString("detail"),
                    success = item.optBoolean("ok")
                )
            }.sortedByDescending { it.timestamp }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    @Synchronized
    fun add(label: String, detail: String, success: Boolean) {
        val trimmed = (entries() + Entry(System.currentTimeMillis(), label, detail, success))
            .sortedByDescending { it.timestamp }
            .take(MAX_ENTRIES)

        val array = JSONArray()
        trimmed.forEach { entry ->
            array.put(
                JSONObject()
                    .put("ts", entry.timestamp)
                    .put("label", entry.label)
                    .put("detail", entry.detail)
                    .put("ok", entry.success)
            )
        }
        prefs.edit().putString(KEY_LOG, array.toString()).apply()
    }

    fun clear() = prefs.edit().remove(KEY_LOG).apply()

    companion object {
        private const val PREFS_NAME = "taskskiller_log"
        private const val KEY_LOG = "entries"
        private const val MAX_ENTRIES = 50

        @Volatile
        private var instance: RunLog? = null

        /** Tests get a fresh app (and fresh storage) each time; drop the old one's store. */
        @androidx.annotation.VisibleForTesting
        fun resetForTests() {
            instance = null
        }

        fun get(context: Context): RunLog = instance ?: synchronized(this) {
            instance ?: RunLog(
                context.applicationContext
                    .createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}

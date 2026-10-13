package com.thefactor1.taskskiller.data

import com.thefactor1.taskskiller.util.PackageUtil
import org.json.JSONException
import org.json.JSONObject

/**
 * One "kill this app every N minutes, then bring it back" job.
 *
 * [intervalMinutes] is measured from the end of the previous run, not from a
 * wall-clock anchor, so a run that is delayed by Doze does not cause a burst of
 * catch-up restarts afterwards.
 */
data class Rule(
    val id: Long,
    val packageName: String,
    val label: String,
    val intervalMinutes: Int,
    val enabled: Boolean = true,
    /** Relaunch the app after killing it. Off means kill-only. */
    val relaunch: Boolean = true,
    /** Pause between the kill and the relaunch, to let the process fully die. */
    val relaunchDelayMs: Long = 3_000L,
    /** Turn the display on for the relaunch. Off keeps the TV asleep. */
    val wakeScreen: Boolean = false,
    /**
     * Skip the run when the display is on, so a restart never interrupts
     * viewing. On by default: restarting mid-film is the thing to avoid.
     */
    val skipWhileScreenOn: Boolean = true,
    /**
     * Also restart the app as the TV turns on (at most every
     * [WAKE_MIN_GAP_MINUTES]), so a viewing starts on a fresh connection.
     */
    val refreshOnWake: Boolean = false,
    /**
     * When the countdown started for a rule that has not run yet. Without a
     * persisted anchor the "first run" time would be recomputed as
     * `now + interval` every time the schedule was rebuilt, so simply opening
     * the app would push the first run out for ever.
     */
    val anchorAt: Long = 0L,
    val lastRunAt: Long = 0L,
    val lastResult: String = ""
) {
    val intervalMillis: Long get() = intervalMinutes * 60_000L

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_PACKAGE, packageName)
        put(KEY_LABEL, label)
        put(KEY_INTERVAL, intervalMinutes)
        put(KEY_ENABLED, enabled)
        put(KEY_RELAUNCH, relaunch)
        put(KEY_RELAUNCH_DELAY, relaunchDelayMs)
        put(KEY_WAKE_SCREEN, wakeScreen)
        put(KEY_SKIP_SCREEN_ON, skipWhileScreenOn)
        put(KEY_REFRESH_ON_WAKE, refreshOnWake)
        put(KEY_ANCHOR, anchorAt)
        put(KEY_LAST_RUN, lastRunAt)
        put(KEY_LAST_RESULT, lastResult)
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_PACKAGE = "package"
        private const val KEY_LABEL = "label"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_RELAUNCH = "relaunch"
        private const val KEY_RELAUNCH_DELAY = "relaunchDelay"
        private const val KEY_WAKE_SCREEN = "wakeScreen"
        private const val KEY_SKIP_SCREEN_ON = "skipScreenOn"
        private const val KEY_REFRESH_ON_WAKE = "refreshOnWake"
        private const val KEY_ANCHOR = "anchorAt"
        private const val KEY_LAST_RUN = "lastRun"
        private const val KEY_LAST_RESULT = "lastResult"

        /**
         * Below roughly this the system starts coalescing alarms hard enough
         * that the advertised interval stops being honest. A mains-powered TV
         * box rarely enters Doze at all, so short intervals usually do work —
         * but they are not guaranteed by the platform.
         */
        const val MIN_INTERVAL_MINUTES = 5
        const val MAX_INTERVAL_MINUTES = 24 * 60

        /** A TV turned off and on again soon after doesn't restart the app again. */
        const val WAKE_MIN_GAP_MINUTES = 30

        /**
         * Stored rules are JSON, so this input is not necessarily one we wrote,
         * and the package name reaches a root shell by interpolation in
         * RootBackend. A name that is not a real package name is refused here;
         * RuleStore already drops a single unreadable entry rather than the
         * whole set, so one bad rule cannot take the others down with it.
         */
        private fun requirePackageName(value: String): String {
            if (!PackageUtil.isPlausiblePackageName(value)) {
                throw JSONException("Refusing rule with implausible package name")
            }
            return value
        }

        fun fromJson(json: JSONObject): Rule = Rule(
            id = json.getLong(KEY_ID),
            packageName = requirePackageName(json.getString(KEY_PACKAGE)),
            label = json.optString(KEY_LABEL, json.getString(KEY_PACKAGE)),
            // Clamped on the way in: a corrupt or hand-edited interval of 0
            // would otherwise schedule a run every MIN_LEAD_MS for ever.
            intervalMinutes = json.optInt(KEY_INTERVAL, 60)
                .coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES),
            enabled = json.optBoolean(KEY_ENABLED, true),
            relaunch = json.optBoolean(KEY_RELAUNCH, true),
            relaunchDelayMs = json.optLong(KEY_RELAUNCH_DELAY, 3_000L),
            wakeScreen = json.optBoolean(KEY_WAKE_SCREEN, false),
            skipWhileScreenOn = json.optBoolean(KEY_SKIP_SCREEN_ON, true),
            refreshOnWake = json.optBoolean(KEY_REFRESH_ON_WAKE, false),
            anchorAt = json.optLong(KEY_ANCHOR, 0L),
            lastRunAt = json.optLong(KEY_LAST_RUN, 0L),
            lastResult = json.optString(KEY_LAST_RESULT, "")
        )
    }
}

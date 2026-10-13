package com.thefactor1.taskskiller.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.data.RuleStore
import com.thefactor1.taskskiller.schedule.RestartService

/**
 * The "Refresh now" tile: one press refreshes every enabled rule. It has no UI
 * of its own; it starts the runs and finishes before drawing anything, so the
 * viewer stays on whatever they were watching. Being a launcher entry, it can
 * also be bound to a remote button or a launcher's quick action.
 */
class RefreshNowActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rules = RuleStore.get(this).enabled()
        if (rules.isNotEmpty() && pressedTooSoon()) {
            // The tile is open to any app (launchers need that), so one that
            // kept starting it could cut the VPN over and over. A minute apart
            // is plenty for a person.
            Toast.makeText(applicationContext, R.string.refresh_now_too_soon, Toast.LENGTH_SHORT).show()
        } else if (rules.isEmpty()) {
            Toast.makeText(applicationContext, R.string.refresh_now_none, Toast.LENGTH_LONG).show()
        } else {
            // Nothing of ours is on screen to come back to, so the run returns
            // to whatever was in front before, not to Refresher.
            rules.forEach { RestartService.runRule(this, it.id, manual = true, returnToApp = false) }
            Toast.makeText(
                applicationContext,
                getString(R.string.refresh_now_started, rules.joinToString(", ") { it.label }),
                Toast.LENGTH_SHORT
            ).show()
        }

        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /** True if the tile ran less than [MIN_INTERVAL_MS] ago; otherwise records this press. */
    private fun pressedTooSoon(): Boolean {
        val prefs = createDeviceProtectedStorageContext().getSharedPreferences(PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST, 0L)
        if (now - last in 0 until MIN_INTERVAL_MS) return true
        prefs.edit().putLong(KEY_LAST, now).apply()
        return false
    }

    private companion object {
        const val PREFS = "refresh_now"
        const val KEY_LAST = "last_press"
        const val MIN_INTERVAL_MS = 60_000L
    }
}

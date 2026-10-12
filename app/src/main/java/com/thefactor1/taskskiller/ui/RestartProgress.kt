package com.thefactor1.taskskiller.ui

import android.os.SystemClock
import android.view.View
import android.widget.TextView
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.data.Rule
import com.thefactor1.taskskiller.data.RunLog
import com.thefactor1.taskskiller.schedule.RestartService
import com.thefactor1.taskskiller.status.RunKind
import com.thefactor1.taskskiller.status.StepWords

/**
 * "Restart now": starts the run and shows its progress in the corner card,
 * watching the log for the run's entry, then closes itself with the result.
 */
class RestartProgress(
    private val card: View,
    private val title: TextView,
    private val steps: TextView,
    private val onFinished: () -> Unit
) {
    private var watching: Rule? = null
    private var startedAt = 0L
    private var startedClock = 0L

    fun start(rule: Rule) {
        watching = rule
        startedAt = System.currentTimeMillis() - CLOCK_SLACK_MS
        startedClock = SystemClock.elapsedRealtime()
        title.text = card.context.getString(R.string.restarting, rule.label)
        steps.text = card.context.getString(R.string.restart_started)
        card.removeCallbacks(poll)
        card.removeCallbacks(hide)
        card.visibility = View.VISIBLE
        RestartService.runRule(card.context, rule.id, manual = true)
        card.postDelayed(poll, POLL_MS)
    }

    fun stop() {
        card.removeCallbacks(poll)
        card.removeCallbacks(hide)
        watching = null
        card.visibility = View.GONE
    }

    private val poll: Runnable = object : Runnable {
        override fun run() {
            val rule = watching ?: return
            val entry = RunLog.get(card.context).entries()
                .firstOrNull { it.timestamp >= startedAt && (it.label == rule.label || it.label == "Rule ${rule.id}") }
            when {
                entry != null -> finish(entry)
                SystemClock.elapsedRealtime() - startedClock > TIMEOUT_MS -> {
                    steps.text = card.context.getString(R.string.restart_slow)
                    close()
                }
                else -> card.postDelayed(this, POLL_MS)
            }
        }
    }

    private fun finish(entry: RunLog.Entry) {
        val ok = RunKind.of(entry) == RunKind.RESTARTED
        title.text = card.context.getString(if (ok) R.string.restart_done else R.string.restart_failed)
        steps.text = RunKind.steps(entry).joinToString("\n") { "· " + StepWords.friendly(it) }
        close()
    }

    private fun close() {
        watching = null
        onFinished()
        card.postDelayed(hide, SHOW_RESULT_MS)
    }

    private val hide = Runnable { card.visibility = View.GONE }

    private companion object {
        const val POLL_MS = 1_000L
        const val TIMEOUT_MS = 90_000L
        const val SHOW_RESULT_MS = 6_000L
        /** The log's clock is the service's; allow for it stamping a hair before we did. */
        const val CLOCK_SLACK_MS = 2_000L
    }
}

package com.thefactor1.taskskiller.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.data.Rule
import com.thefactor1.taskskiller.data.RuleStore
import com.thefactor1.taskskiller.data.RunLog
import com.thefactor1.taskskiller.databinding.ActivityRuleEditBinding
import com.thefactor1.taskskiller.databinding.RowSwitchBinding
import com.thefactor1.taskskiller.databinding.RowValueBinding
import com.thefactor1.taskskiller.data.Backup
import com.thefactor1.taskskiller.schedule.WakeWatch
import com.thefactor1.taskskiller.schedule.RestartScheduler
import com.thefactor1.taskskiller.status.RunKind
import com.thefactor1.taskskiller.util.Format
import com.thefactor1.taskskiller.util.PackageUtil

/**
 * One app's rule. Every setting is a row: left/right step a value, OK flips a
 * switch, and each change is saved the moment it's made (Android TV settings
 * have no Save button, and the old one lost edits to Back).
 */
class RuleEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRuleEditBinding
    private lateinit var store: RuleStore
    private lateinit var restart: RestartProgress
    private var ruleId = -1L
    private var removeArmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRuleEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = RuleStore.get(this)

        ruleId = intent.getLongExtra(EXTRA_RULE_ID, -1L)
        val rule = store.find(ruleId) ?: run { finish(); return }

        restart = RestartProgress(
            binding.restartCard.root, binding.restartCard.restartTitle, binding.restartCard.restartSteps
        ) { bind() }

        binding.appLabel.text = rule.label
        binding.appIcon.setImageDrawable(PackageUtil.icon(this, rule.packageName))

        title(binding.intervalRow, R.string.rule_interval, R.string.rule_interval_hint)
        title(binding.asleepRow, R.string.rule_asleep, R.string.rule_asleep_hint)
        title(binding.turnOnRow, R.string.rule_turn_on, R.string.rule_turn_on_hint)
        title(binding.reopenRow, R.string.rule_reopen, R.string.rule_reopen_hint)
        title(binding.delayRow, R.string.rule_delay, R.string.rule_delay_hint)
        title(binding.wakeRow, R.string.rule_wake, R.string.rule_wake_hint)

        stepper(binding.intervalRow) { r, dir ->
            val i = (closestInterval(r.intervalMinutes) + dir).coerceIn(0, INTERVALS.lastIndex)
            r.copy(intervalMinutes = INTERVALS[i])
        }
        stepper(binding.delayRow) { r, dir ->
            r.copy(relaunchDelayMs = (r.relaunchDelayMs + dir * DELAY_STEP_MS).coerceIn(0L, DELAY_MAX_MS))
        }
        toggle(binding.asleepRow) { it.copy(skipWhileScreenOn = !it.skipWhileScreenOn) }
        toggle(binding.turnOnRow) { it.copy(refreshOnWake = !it.refreshOnWake) }
        toggle(binding.reopenRow) { if (canReopen(it)) it.copy(relaunch = !it.relaunch) else it }
        toggle(binding.wakeRow) { if (it.relaunch) it.copy(wakeScreen = !it.wakeScreen) else it }

        binding.restartNowButton.setOnClickListener { current()?.let { restart.start(it) } }
        binding.pauseButton.setOnClickListener { update { it.copy(enabled = !it.enabled) } }
        binding.removeButton.setOnClickListener { remove() }
        binding.removeButton.setOnFocusChangeListener { _, focused -> if (!focused) disarmRemove() }

        bind()
        // After the first layout: a request made during onCreate doesn't stick on every TV.
        binding.root.post { binding.intervalRow.root.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        if (current() == null) finish() else bind()
    }

    override fun onDestroy() {
        if (::restart.isInitialized) restart.stop()
        super.onDestroy()
    }

    private fun current(): Rule? = store.find(ruleId)

    /**
     * Re-read, change, save, re-arm. Re-reading matters: a run that finishes
     * while this screen is open writes lastRunAt, and saving a stale copy over
     * it would put the next run in the past.
     */
    private fun update(change: (Rule) -> Rule) {
        val now = current() ?: return
        val changed = change(now)
        if (changed == now) return
        val saved = changed.copy(anchorAt = now.anchorAt.takeIf { it > 0L } ?: System.currentTimeMillis())
        store.upsert(saved)
        if (saved.enabled && store.masterEnabled) RestartScheduler.schedule(this, saved)
        else RestartScheduler.cancel(this, saved.id)
        WakeWatch.sync(this)
        Backup.saveAsync(this)
        bind()
        flashSaved()
    }

    private val unflashSaved = Runnable {
        binding.savedNote.setText(R.string.rule_autosave)
        binding.savedNote.setTextColor(ContextCompat.getColorStateList(this, R.color.row_text_secondary))
    }

    /** A moment of "✓ Saved" after each change, then back to the standing note. */
    private fun flashSaved() {
        binding.savedNote.setText(R.string.rule_saved)
        binding.savedNote.setTextColor(ContextCompat.getColor(this, R.color.ok))
        binding.savedNote.removeCallbacks(unflashSaved)
        binding.savedNote.postDelayed(unflashSaved, SAVED_FLASH_MS)
    }

    private fun bind() {
        val rule = current() ?: return
        val now = System.currentTimeMillis()

        binding.intervalRow.rowValue.text = Format.intervalWords(rule.intervalMinutes)
        binding.intervalRow.rowLess.alpha = if (rule.intervalMinutes <= INTERVALS.first()) DIM else 1f
        binding.intervalRow.rowMore.alpha = if (rule.intervalMinutes >= INTERVALS.last()) DIM else 1f
        binding.delayRow.rowValue.text = Format.seconds(rule.relaunchDelayMs)
        binding.delayRow.rowLess.alpha = if (rule.relaunchDelayMs <= 0L) DIM else 1f
        binding.delayRow.rowMore.alpha = if (rule.relaunchDelayMs >= DELAY_MAX_MS) DIM else 1f

        binding.asleepRow.rowSwitch.isChecked = rule.skipWhileScreenOn
        binding.turnOnRow.rowSwitch.isChecked = rule.refreshOnWake
        val reopenable = canReopen(rule)
        binding.reopenRow.rowSwitch.isChecked = rule.relaunch && reopenable
        binding.reopenRow.rowHint.setText(if (reopenable) R.string.rule_reopen_hint else R.string.rule_reopen_none)
        binding.reopenRow.rowSwitch.isEnabled = reopenable
        binding.wakeRow.rowSwitch.isChecked = rule.wakeScreen
        // Settings that only matter when the app is opened again fade without it.
        val reopening = rule.relaunch && reopenable
        listOf(binding.delayRow.root, binding.wakeRow.root).forEach { it.alpha = if (reopening) 1f else DIM }

        binding.pauseButton.setText(if (rule.enabled) R.string.rule_pause else R.string.rule_resume)

        val failed = lastRunFailed(rule)
        val (text, color) = when {
            !rule.enabled || !store.masterEnabled -> getString(R.string.card_paused) to R.color.idle
            failed -> getString(R.string.card_failed, Format.moment(this, rule.lastRunAt, now)) to R.color.bad
            rule.skipWhileScreenOn && RestartScheduler.isDue(rule, now) -> getString(R.string.rule_status_waiting) to R.color.warn
            rule.lastRunAt > 0 -> getString(
                R.string.rule_status_last, Format.moment(this, rule.lastRunAt, now),
                getString(R.string.next_at, Format.moment(this, RestartScheduler.nextRunAt(rule), now))
            ) to R.color.ok
            else -> getString(R.string.rule_status_next, Format.moment(this, RestartScheduler.nextRunAt(rule), now)) to R.color.text_secondary
        }
        binding.statusText.text = text
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))

        listOf(binding.intervalRow, binding.delayRow).forEach {
            it.root.contentDescription = "${it.rowTitle.text}: ${it.rowValue.text}"
        }
        listOf(binding.asleepRow, binding.turnOnRow, binding.reopenRow, binding.wakeRow).forEach {
            it.root.contentDescription = "${it.rowTitle.text}: ${if (it.rowSwitch.isChecked) "on" else "off"}"
        }
    }

    private fun lastRunFailed(rule: Rule): Boolean {
        val last = RunLog.get(this).entries().firstOrNull { it.label == rule.label && RunKind.of(it) != RunKind.SKIPPED }
        return last != null && RunKind.of(last) == RunKind.FAILED && rule.lastRunAt > 0
    }

    private fun canReopen(rule: Rule) = PackageUtil.launchIntent(this, rule.packageName) != null

    private fun remove() {
        if (!removeArmed) {
            removeArmed = true
            binding.removeButton.setText(R.string.rule_remove_confirm)
            binding.removeButton.removeCallbacks(disarm)
            binding.removeButton.postDelayed(disarm, CONFIRM_MS)
            return
        }
        RestartScheduler.cancel(this, ruleId)
        store.delete(ruleId)
        WakeWatch.sync(this)
        Backup.saveAsync(this)
        finish()
    }

    private val disarm = Runnable { disarmRemove() }

    private fun disarmRemove() {
        removeArmed = false
        binding.removeButton.setText(R.string.rule_remove)
    }

    private fun title(row: RowSwitchBinding, title: Int, hint: Int) {
        row.rowTitle.setText(title); row.rowHint.setText(hint)
    }

    private fun title(row: RowValueBinding, title: Int, hint: Int) {
        row.rowTitle.setText(title); row.rowHint.setText(hint)
    }

    private fun toggle(row: RowSwitchBinding, change: (Rule) -> Rule) {
        row.root.setOnClickListener { update(change) }
    }

    /** Left/right step the value; OK steps it up and wraps, for remotes without arrows in reach. */
    private fun stepper(row: RowValueBinding, change: (Rule, Int) -> Rule) {
        row.root.setOnKeyListener { _, keyCode, event ->
            val dir = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> return@setOnKeyListener false
            }
            if (event.action == KeyEvent.ACTION_DOWN) update { change(it, dir) }
            true
        }
        row.root.setOnClickListener {
            update { r ->
                val up = change(r, 1)
                if (up == r) change(r, -STEPS_TO_WRAP) else up
            }
        }
    }

    private fun closestInterval(minutes: Int): Int =
        INTERVALS.indices.minByOrNull { kotlin.math.abs(INTERVALS[it] - minutes) } ?: 0

    companion object {
        private const val SAVED_FLASH_MS = 2_000L
        const val EXTRA_RULE_ID = "rule_id"

        /** Choices rather than a free value: a D-pad steps one at a time. */
        val INTERVALS = intArrayOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 360, 480, 720, 1440)
        const val DELAY_STEP_MS = 500L
        const val DELAY_MAX_MS = 15_000L
        /** OK at the top of a range wraps back to the bottom. */
        private const val STEPS_TO_WRAP = 1_000
        private const val CONFIRM_MS = 4_000L
        private const val DIM = 0.4f
    }
}

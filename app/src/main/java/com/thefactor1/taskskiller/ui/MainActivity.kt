package com.thefactor1.taskskiller.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.data.Backup
import com.thefactor1.taskskiller.data.Rule
import com.thefactor1.taskskiller.data.RuleStore
import com.thefactor1.taskskiller.databinding.ActivityMainBinding
import com.thefactor1.taskskiller.kill.ShizukuBackend
import com.thefactor1.taskskiller.schedule.RestartScheduler
import com.thefactor1.taskskiller.setup.ShizukuStarter
import com.thefactor1.taskskiller.status.Health
import com.thefactor1.taskskiller.status.HealthReader
import com.thefactor1.taskskiller.status.RunKind
import com.thefactor1.taskskiller.status.StepWords
import com.thefactor1.taskskiller.util.Format
import java.util.concurrent.Executors

/**
 * Home. The headline answers "is it working?" with the proof under it; then
 * the apps, then the things restarts depend on. When something's wrong the
 * headline says what, and the one button that fixes it is focused.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cards: AppCardAdapter
    private lateinit var restart: RestartProgress
    private val worker = Executors.newSingleThreadExecutor()

    private var report: Health.Report? = null
    private var shownChips: List<Pair<Health.CheckId, Boolean>>? = null
    private var fixing = false
    private var fixMessage: String? = null
    private var firstFocusDone = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The service still runs without it; its notification is just hidden. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restart = RestartProgress(
            binding.restartCard.root, binding.restartCard.restartTitle, binding.restartCard.restartSteps
        ) { refresh() }

        cards = AppCardAdapter(
            onOpen = { openRule(it) },
            onAdd = { startActivity(Intent(this, AppPickerActivity::class.java)) },
            onRestart = { restart.start(it) }
        )
        binding.appsRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.appsRecycler.adapter = cards
        binding.appsRecycler.itemAnimator = null
        // Only the cards take focus; an empty list would otherwise swallow the D-pad.
        binding.appsRecycler.isFocusable = false

        binding.fixButton.setOnClickListener { report?.let { runFix(it.fix) } }
        binding.systemButton.setOnClickListener { startActivity(Intent(this, SystemActivity::class.java)) }
        binding.activityButton.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        binding.setupButton.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }

        requestNotificationPermissionIfNeeded()

        // Back from here always lands on the TV's home screen. Finishing alone
        // would show whatever task sits behind ours, and after a restart that is
        // the app just reopened (NordVPN's connect screen), not where you came from.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                try {
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: android.content.ActivityNotFoundException) {
                    // No launcher to go to: fall back to plain finishing below.
                }
                finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        com.thefactor1.taskskiller.schedule.HistorySampler.arm(this)
        // Alarms can be dropped by firmware task-killers, so re-arm on every visit.
        RestartScheduler.scheduleAll(this)
        refresh()
        offerRestore()
        WhatsNew.showAfterUpdate(this)
        binding.root.removeCallbacks(ticker)
        binding.root.postDelayed(ticker, TICK_MS)
    }

    /**
     * A fresh install with no apps yet: if an earlier install left a backup
     * (Shizuku has to be set up again first to read it), offer it back, once
     * per launch of the app.
     */
    private fun offerRestore() {
        if (restoreOffered || RuleStore.get(this).all().isNotEmpty()) return
        worker.execute {
            val saved = Backup.load(this)?.takeIf { it.rules.isNotEmpty() } ?: return@execute
            runOnUiThread {
                if (isFinishing || restoreOffered || RuleStore.get(this).all().isNotEmpty()) return@runOnUiThread
                restoreOffered = true
                val list = saved.rules.joinToString("\n") { "${it.label}: ${Format.intervalWords(it.intervalMinutes).lowercase()}" }
                AlertDialog.Builder(this)
                    .setTitle(R.string.restore_title)
                    .setMessage(getString(R.string.restore_message, Format.moment(this, saved.savedAt), list))
                    .setPositiveButton(R.string.restore_yes) { _, _ ->
                        Backup.restore(this, saved)
                        RestartScheduler.scheduleAll(this)
                        refresh()
                    }
                    .setNegativeButton(R.string.restore_no, null)
                    .show()
            }
        }
    }

    override fun onPause() {
        binding.root.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        restart.stop()
        worker.shutdown()
        super.onDestroy()
    }

    /** Keeps "next at" and the status current while Home is open. */
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            binding.root.postDelayed(this, TICK_MS)
        }
    }

    private fun refresh() {
        val store = RuleStore.get(this)
        val r = HealthReader.report(this)
        report = r
        bindHeadline(r)
        val inputs = HealthReader.read(this)
        cards.submit(store.all(), store.masterEnabled, failedLabels(inputs))
        bindChips(r)
        if (!firstFocusDone) {
            firstFocusDone = true
            binding.root.post { focusDefault() }
        }
    }

    /** The first thing to press: the fix when there is one, else the first app. */
    private fun focusDefault() {
        if (binding.fixRow.visibility == View.VISIBLE) {
            binding.fixButton.requestFocus()
        } else {
            binding.appsRecycler.getChildAt(0)?.requestFocus()
        }
    }

    private fun bindHeadline(r: Health.Report) {
        val (tint, ink, icon) = when (r.level) {
            Health.Level.OK -> Triple(R.color.ok_tint, R.color.ok, R.drawable.ic_status_ok)
            Health.Level.WARN -> Triple(R.color.warn_tint, R.color.warn, R.drawable.ic_status_alert)
            Health.Level.BAD -> Triple(R.color.bad_tint, R.color.bad, R.drawable.ic_status_alert)
            Health.Level.PAUSED -> Triple(R.color.idle_tint, R.color.text_secondary, R.drawable.ic_status_pause)
            Health.Level.EMPTY -> Triple(R.color.idle_tint, R.color.text_primary, R.drawable.ic_status_add)
        }
        binding.statusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, tint))
        binding.statusIcon.setImageResource(icon)
        binding.statusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, ink))

        binding.headline.setText(
            when (r.headline) {
                Health.Headline.WORKING -> R.string.headline_working
                Health.Headline.NO_APPS -> R.string.headline_no_apps
                Health.Headline.PAUSED_ALL -> R.string.headline_paused_all
                Health.Headline.PAUSED_EACH -> R.string.headline_paused_each
                Health.Headline.CANNOT_STOP -> R.string.headline_cannot_stop
                Health.Headline.SHIZUKU_MISSING -> R.string.headline_shizuku_missing
                Health.Headline.SHIZUKU_STOPPED -> R.string.headline_shizuku_stopped
                Health.Headline.SHIZUKU_NO_PERMISSION -> R.string.headline_shizuku_no_permission
                Health.Headline.LAST_RUN_FAILED -> R.string.headline_last_failed
                Health.Headline.MIGHT_MISS -> R.string.headline_might_miss
            }
        )
        binding.subline.text = subline(r)

        val fixLabel = when (r.fix) {
            Health.Fix.NONE -> null
            Health.Fix.ADD_APP -> R.string.fix_add_app
            Health.Fix.RESUME -> R.string.fix_resume
            Health.Fix.START_SHIZUKU -> R.string.fix_start_shizuku
            Health.Fix.GRANT_SHIZUKU -> R.string.fix_grant_shizuku
            Health.Fix.OPEN_SETUP -> R.string.fix_open_setup
            Health.Fix.OPEN_ACTIVITY -> R.string.fix_open_activity
        }
        val hadFix = binding.fixRow.visibility == View.VISIBLE
        binding.fixRow.visibility = if (fixLabel == null) View.GONE else View.VISIBLE
        if (fixLabel != null) {
            binding.fixButton.setText(fixLabel)
            binding.fixButton.isEnabled = !fixing
            binding.fixHint.text = fixMessage
                ?: if (r.fix == Health.Fix.START_SHIZUKU) getString(R.string.fix_start_shizuku_hint) else ""
        }
        // The problem was just fixed: don't leave focus on a button that's gone.
        if (hadFix && fixLabel == null && currentFocus == null) focusDefault()
    }

    private fun subline(r: Health.Report): String {
        val now = System.currentTimeMillis()
        val next = r.nextRule?.let { rule ->
            if (rule.skipWhileScreenOn && RestartScheduler.isDue(rule, now)) getString(R.string.next_when_asleep)
            else getString(R.string.next_at, Format.moment(this, r.nextAt, now))
        }
        val last = r.lastRun
        return when (r.headline) {
            Health.Headline.WORKING -> when {
                last != null && next != null ->
                    getString(R.string.sub_last_and_next, Format.moment(this, last.timestamp, now), last.label, next)
                last != null -> getString(R.string.sub_last_only, Format.moment(this, last.timestamp, now), last.label)
                next != null -> getString(R.string.sub_next_only, next)
                else -> ""
            }
            Health.Headline.NO_APPS -> getString(R.string.sub_no_apps)
            Health.Headline.PAUSED_ALL -> getString(R.string.sub_paused_all)
            Health.Headline.PAUSED_EACH -> getString(R.string.sub_paused_each)
            Health.Headline.CANNOT_STOP -> getString(R.string.sub_cannot_stop)
            Health.Headline.SHIZUKU_MISSING -> getString(R.string.sub_shizuku_missing)
            Health.Headline.SHIZUKU_STOPPED -> getString(R.string.sub_shizuku_stopped)
            Health.Headline.SHIZUKU_NO_PERMISSION -> getString(R.string.sub_shizuku_no_permission)
            Health.Headline.LAST_RUN_FAILED -> last?.let { entry ->
                val problem = RunKind.steps(entry).firstOrNull(StepWords::isProblem) ?: RunKind.steps(entry).lastOrNull() ?: ""
                getString(R.string.sub_last_failed, entry.label, Format.moment(this, entry.timestamp, now), StepWords.friendly(problem))
            } ?: ""
            Health.Headline.MIGHT_MISS -> r.checks.firstOrNull { !it.ok }?.let {
                getString(R.string.sub_might_miss, chipText(it))
            } ?: ""
        }
    }

    /** Rebuilt only when a check changes, so a focused chip keeps its focus across refreshes. */
    private fun bindChips(r: Health.Report) {
        val state = r.checks.map { it.id to it.ok }
        if (state == shownChips) return
        val focusedIndex = (0 until binding.chips.childCount).firstOrNull { binding.chips.getChildAt(it).isFocused }
        shownChips = state
        binding.chips.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val gap = resources.getDimensionPixelSize(R.dimen.space_s)
        r.checks.forEachIndexed { i, check ->
            val chip = inflater.inflate(R.layout.view_chip, binding.chips, false) as TextView
            chip.text = chipText(check)
            chip.maxLines = 1
            // A fifth of the row each: at large text sizes a label shrinks a little rather than wrap or clip.
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(chip, 10, 14, 1, TypedValue.COMPLEX_UNIT_SP)
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(dot(if (check.ok) R.color.ok else R.color.warn), null, null, null)
            chip.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
            chip.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = if (i == r.checks.lastIndex) 0 else gap
            }
            binding.chips.addView(chip)
        }
        focusedIndex?.let { binding.chips.getChildAt(it)?.requestFocus() }
    }

    private fun chipText(check: Health.Check): String = getString(
        when (check.id) {
            Health.CheckId.SHIZUKU -> if (check.ok) R.string.chip_shizuku_ok else R.string.chip_shizuku_bad
            Health.CheckId.AUTO_START -> if (check.ok) R.string.chip_auto_start_ok else R.string.chip_auto_start_bad
            Health.CheckId.EXACT_ALARMS -> if (check.ok) R.string.chip_exact_ok else R.string.chip_exact_bad
            Health.CheckId.BATTERY -> if (check.ok) R.string.chip_battery_ok else R.string.chip_battery_bad
            Health.CheckId.BACKGROUND_START -> if (check.ok) R.string.chip_background_ok else R.string.chip_background_bad
        }
    )

    private fun dot(color: Int): android.graphics.drawable.Drawable {
        val px = Math.round(9 * resources.displayMetrics.density)
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(ContextCompat.getColor(this@MainActivity, color))
            setSize(px, px)
        }
    }

    private fun runFix(fix: Health.Fix) {
        when (fix) {
            Health.Fix.NONE -> Unit
            Health.Fix.ADD_APP -> startActivity(Intent(this, AppPickerActivity::class.java))
            Health.Fix.RESUME -> {
                RuleStore.get(this).masterEnabled = true
                RestartScheduler.scheduleAll(this)
                refresh()
            }
            Health.Fix.START_SHIZUKU -> startShizuku()
            Health.Fix.GRANT_SHIZUKU -> ShizukuBackend.requestPermission()
            Health.Fix.OPEN_SETUP -> startActivity(Intent(this, SetupActivity::class.java))
            Health.Fix.OPEN_ACTIVITY -> startActivity(Intent(this, LogActivity::class.java))
        }
    }

    private fun startShizuku() {
        if (fixing) return
        fixing = true
        fixMessage = getString(R.string.fix_working)
        report?.let { bindHeadline(it) }
        worker.execute {
            val step = ShizukuStarter.startInBackground(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                fixing = false
                fixMessage = if (step.ok) null else getString(R.string.fix_failed, step.detail.ifBlank { "no answer" })
                if (step.ok && ShizukuBackend.isBinderAlive() && !ShizukuBackend.hasPermission()) {
                    ShizukuBackend.requestPermission()
                }
                refresh()
            }
        }
    }

    private fun openRule(rule: Rule) {
        startActivity(Intent(this, RuleEditActivity::class.java).putExtra(RuleEditActivity.EXTRA_RULE_ID, rule.id))
    }

    /** Labels whose latest real run (not a skip) didn't work, for the cards' red line. */
    private fun failedLabels(inputs: Health.Inputs): Set<String> =
        inputs.log.filter { RunKind.of(it) != RunKind.SKIPPED }
            .groupBy { it.label }
            .filterValues { runs -> RunKind.of(runs.first()) == RunKind.FAILED }
            .keys

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val TICK_MS = 30_000L

        /** Offered at most once per process, so "Not now" sticks until the app restarts. */
        var restoreOffered = false
    }
}

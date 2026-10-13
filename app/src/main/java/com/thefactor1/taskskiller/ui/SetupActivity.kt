package com.thefactor1.taskskiller.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.data.RuleStore
import com.thefactor1.taskskiller.databinding.ActivitySetupBinding
import com.thefactor1.taskskiller.databinding.RowCheckBinding
import com.thefactor1.taskskiller.kill.ShizukuBackend
import com.thefactor1.taskskiller.schedule.RestartScheduler
import com.thefactor1.taskskiller.setup.ShizukuStarter
import com.thefactor1.taskskiller.status.Health
import com.thefactor1.taskskiller.status.HealthReader
import com.thefactor1.taskskiller.status.StepWords
import java.util.concurrent.Executors

/**
 * Setup: the handful of things that must be true for restarts to work, in
 * plain words. Each row fixes itself when OK is pressed on it; "Set up
 * automatically" does them all at once over the TV's own network debugging.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.autoSetupButton.setOnClickListener { runAutoSetup() }
        binding.whatsNewRow.rowTitle.setText(R.string.whats_new_row)
        binding.whatsNewRow.rowHint.text = getString(R.string.whats_new_row_hint, WhatsNew.versionName(this))
        binding.whatsNewRow.checkIcon.visibility = View.GONE
        binding.whatsNewRow.checkAction.setText(R.string.whats_new_open)
        binding.whatsNewRow.checkAction.visibility = View.VISIBLE
        binding.whatsNewRow.root.setOnClickListener { WhatsNew.show(this) }
        binding.pauseAllRow.rowTitle.setText(R.string.setup_pause_all)
        binding.pauseAllRow.rowHint.setText(R.string.setup_pause_all_desc)
        binding.pauseAllRow.root.setOnClickListener {
            val store = RuleStore.get(this)
            store.masterEnabled = !store.masterEnabled
            RestartScheduler.scheduleAll(this)
            refresh()
        }
        binding.manualText.text = HtmlCompat.fromHtml(
            getString(R.string.adb_body).replace("\n", "<br>"), HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        refresh()
        val anyFailing = HealthReader.read(this).let(Health::checks).any { !it.ok }
        (if (anyFailing) binding.autoSetupButton else binding.shizukuRow.root).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        val store = RuleStore.get(this)
        val device = HealthReader.device(this)
        val checks = Health.checks(HealthReader.read(this)).associateBy { it.id }

        // Shizuku: what's wrong decides what OK does.
        val shizuku = binding.shizukuRow
        when {
            device.forceStopWithoutShizuku -> bindCheck(shizuku, true, getString(R.string.check_other_method, device.methodName), getString(R.string.check_shizuku_desc), null, null)
            !device.shizukuInstalled -> bindCheck(shizuku, false, getString(R.string.check_shizuku_missing), getString(R.string.check_shizuku_desc), R.string.check_get) { openShizukuDownload() }
            !device.shizukuRunning -> bindCheck(shizuku, false, getString(R.string.check_shizuku_stopped), getString(R.string.check_shizuku_desc), R.string.check_start) { startShizuku() }
            !device.shizukuPermitted -> bindCheck(shizuku, false, getString(R.string.check_shizuku_permission), getString(R.string.check_shizuku_desc), R.string.check_allow) {
                ShizukuBackend.requestPermission()
                Toast.makeText(this, R.string.grant_shizuku_sent, Toast.LENGTH_SHORT).show()
            }
            else -> bindCheck(shizuku, true, getString(R.string.check_shizuku), getString(R.string.check_shizuku_desc), null, null)
        }

        bindCheck(binding.autoStartRow, checks.getValue(Health.CheckId.AUTO_START).ok, getString(R.string.check_auto_start),
            getString(if (store.autoStartShizuku || device.forceStopWithoutShizuku) R.string.check_auto_start_desc else R.string.check_auto_start_off_desc), null) {
            store.autoStartShizuku = !store.autoStartShizuku
            refresh()
        }
        binding.autoStartRow.rowSwitch.visibility = if (device.forceStopWithoutShizuku) View.GONE else View.VISIBLE
        binding.autoStartRow.rowSwitch.isChecked = store.autoStartShizuku

        bindCheck(binding.exactRow, device.exactAlarms, getString(if (device.exactAlarms) R.string.check_exact else R.string.check_exact_bad), getString(R.string.check_exact_desc), R.string.check_fix) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, appUri()))
        }
        bindCheck(binding.batteryRow, device.batteryExempt, getString(if (device.batteryExempt) R.string.check_battery else R.string.check_battery_bad), getString(R.string.check_battery_desc), R.string.check_fix) {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, appUri()))
        }
        bindCheck(binding.backgroundRow, device.backgroundStarts, getString(if (device.backgroundStarts) R.string.check_background else R.string.check_background_bad), getString(R.string.check_background_desc), R.string.check_fix) {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, appUri()))
        }

        binding.pauseAllRow.rowSwitch.isChecked = !store.masterEnabled
        binding.pauseAllRow.root.contentDescription =
            "${getString(R.string.setup_pause_all)}: ${if (store.masterEnabled) "off" else "on"}"
        binding.autoSetupButton.isEnabled = !busy
    }

    /**
     * [action]: what OK does; [actionLabel]: the pill that says so, shown only
     * while the check fails (a passing row has nothing to fix).
     */
    private fun bindCheck(row: RowCheckBinding, ok: Boolean, title: String, hint: String, actionLabel: Int?, action: (() -> Unit)?) {
        row.rowTitle.text = title
        row.rowHint.text = hint
        row.checkIcon.setImageResource(if (ok) R.drawable.ic_status_ok else R.drawable.ic_status_alert)
        row.checkIcon.imageTintList = ContextCompat.getColorStateList(this, if (ok) R.color.check_ok_tint else R.color.check_warn_tint)
        row.checkIcon.isDuplicateParentStateEnabled = true
        val label = if (!ok && actionLabel != null) getString(actionLabel) else null
        row.checkAction.visibility = if (label == null) View.GONE else View.VISIBLE
        row.checkAction.text = label
        row.root.setOnClickListener(if (action != null && (!ok || actionLabel == null)) View.OnClickListener { action() } else null)
        row.root.contentDescription = listOfNotNull(title, hint, if (ok) "OK" else "needs fixing", label).joinToString(". ")
    }

    private fun startShizuku() {
        if (busy) return
        busy = true
        showResult(getString(R.string.fix_working))
        worker.execute {
            val step = ShizukuStarter.startInBackground(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                showResult(if (step.ok) getString(R.string.auto_setup_done) else getString(R.string.fix_failed, step.detail.ifBlank { "no answer" }))
                if (step.ok && ShizukuBackend.isBinderAlive() && !ShizukuBackend.hasPermission()) ShizukuBackend.requestPermission()
                refresh()
            }
        }
    }

    /** Network I/O and a prompt the viewer may take a while to answer, so it runs off the main thread. */
    private fun runAutoSetup() {
        if (busy) return
        busy = true
        binding.autoSetupButton.isEnabled = false
        showResult(getString(R.string.auto_setup_running))
        worker.execute {
            val steps = ShizukuStarter.runSetup(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                val connected = steps.firstOrNull()?.ok == true
                val allOk = steps.isNotEmpty() && steps.all { it.ok }
                val lines = steps.joinToString("\n") { step ->
                    val detail = if (step.detail.isBlank()) "" else ": ${step.detail}"
                    "${if (step.ok) "✓" else "✗"}  ${StepWords.friendly(step.label)}$detail"
                }
                showResult(getString(if (allOk) R.string.auto_setup_done else R.string.auto_setup_failed) + "\n" + lines)
                // A connection that worked once will work after a reboot too, so
                // that's the point to start Shizuku automatically from now on.
                if (connected && ShizukuStarter.isInstalled(this)) RuleStore.get(this).autoStartShizuku = true
                if (ShizukuBackend.isBinderAlive() && !ShizukuBackend.hasPermission()) ShizukuBackend.requestPermission()
                refresh()
                binding.autoSetupButton.requestFocus()
            }
        }
    }

    private fun showResult(text: String) {
        binding.setupResult.text = text
        binding.setupResult.visibility = View.VISIBLE
    }

    /** Play Store first; boxes without one may still have a browser for the releases page. */
    private fun openShizukuDownload() {
        listOf(ShizukuStarter.PLAY_STORE_URI, ShizukuStarter.DOWNLOAD_URL).forEach { uri ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                return
            } catch (e: ActivityNotFoundException) {
                // Try the next one.
            }
        }
        Toast.makeText(this, getString(R.string.shizuku_store_unavailable, ShizukuStarter.DOWNLOAD_URL), Toast.LENGTH_LONG).show()
    }

    private fun appUri(): Uri = Uri.parse("package:$packageName")

    /** Many TV builds ship without these settings screens at all. */
    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.setting_unavailable, Toast.LENGTH_LONG).show()
        }
    }
}

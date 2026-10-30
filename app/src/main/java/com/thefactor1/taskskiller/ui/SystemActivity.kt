package com.thefactor1.taskskiller.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.data.Rule
import com.thefactor1.taskskiller.data.RuleStore
import com.thefactor1.taskskiller.databinding.ActivitySystemBinding
import com.thefactor1.taskskiller.databinding.ItemSystemAppBinding
import com.thefactor1.taskskiller.databinding.ViewMeterBinding
import com.thefactor1.taskskiller.kill.KillBackends
import com.thefactor1.taskskiller.schedule.RestartScheduler
import com.thefactor1.taskskiller.schedule.HistorySampler
import com.thefactor1.taskskiller.status.SystemHistory
import com.thefactor1.taskskiller.status.SystemReader
import com.thefactor1.taskskiller.status.SystemStats
import com.thefactor1.taskskiller.status.SystemStats.Level
import com.thefactor1.taskskiller.util.PackageUtil
import java.util.concurrent.Executors

/**
 * System: how hard the TV is working (memory, swap, processor, storage) and which apps use the most memory, with
 * Stop / Add a stop-only rule for each. It reads only while it is open: the system every 3 s, the apps every 15 s.
 */
class SystemActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySystemBinding
    private val worker = Executors.newSingleThreadExecutor()
    private val adapter = AppAdapter(::onApp)
    private var reader: SystemReader? = null
    private var ticks = 0
    private var reading = false
    private var focused = false
    private var last: SystemStats.Snapshot? = null

    private val ticker = object : Runnable {
        override fun run() {
            refresh(withApps = ticks % APPS_EVERY == 0)
            ticks++
            binding.root.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySystemBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter
        binding.memMeter.meterLabel.setText(R.string.system_memory)
        binding.swapMeter.meterLabel.setText(R.string.system_swap)
        binding.cpuMeter.meterLabel.setText(R.string.system_cpu)
        binding.storageMeter.meterLabel.setText(R.string.system_storage)
        reader = SystemReader(this, KillBackends.resolve(this))
        render(null)
    }

    override fun onResume() {
        super.onResume()
        HistorySampler.arm(this)
        ticks = 0
        binding.root.post(ticker)
    }

    override fun onPause() {
        binding.root.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun refresh(withApps: Boolean) {
        if (reading) return
        reading = true
        val r = reader ?: return
        worker.execute {
            val snap = try { r.read(withApps) } catch (e: Exception) { null }
            val history = try { SystemHistory.load(this) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                reading = false
                if (!isFinishing && !isDestroyed) { render(snap); renderHistory(history) }
            }
        }
    }

    private fun render(s: SystemStats.Snapshot?) {
        if (s == null) {
            if (last == null) {
                binding.systemEmpty.visibility = View.VISIBLE
                binding.systemEmpty.setText(R.string.system_reading)
                verdict(Level.OK, getString(R.string.system_reading_short), muted = true)
                listOf(binding.memMeter, binding.swapMeter, binding.cpuMeter, binding.storageMeter).forEach { meter(it, "—", 0, Level.OK, "") }
            }
            return
        }
        last = s
        when (s.overall) {
            Level.OK -> verdict(Level.OK, getString(R.string.system_verdict_ok))
            Level.WARN -> verdict(Level.WARN, getString(R.string.system_verdict_warn))
            Level.BAD -> verdict(Level.BAD, getString(R.string.system_verdict_bad))
        }
        meter(binding.memMeter, getString(R.string.system_free, SystemStats.size(s.memAvailableKb)), s.memUsedPct, s.memLevel,
            getString(R.string.system_memory_hint, SystemStats.size(s.memTotalKb), s.memUsedPct))
        meter(binding.swapMeter, getString(R.string.system_of, SystemStats.size(s.swapTotalKb - s.swapFreeKb), SystemStats.size(s.swapTotalKb)),
            s.swapUsedPct, s.swapLevel, getString(if (s.swapLevel == Level.OK) R.string.system_swap_ok else R.string.system_swap_busy))
        val busy = s.cpuBusyPct
        meter(binding.cpuMeter, if (busy == null) "—" else getString(R.string.system_busy, busy),
            busy ?: (s.load1 * 100 / s.cores.coerceAtLeast(1)).toInt().coerceIn(0, 100), s.cpuLevel,
            getString(R.string.system_cpu_hint, String.format(java.util.Locale.US, "%.1f", s.load1), s.cores))
        meter(binding.storageMeter, getString(R.string.system_free, SystemStats.size(s.storageFreeBytes / 1024)), s.storageUsedPct, s.storageLevel,
            getString(R.string.system_storage_hint, SystemStats.size(s.storageTotalBytes / 1024)))

        adapter.submit(s.apps, s.onScreen)
        binding.systemEmpty.visibility = if (s.apps.isEmpty()) View.VISIBLE else View.GONE
        if (s.apps.isEmpty()) binding.systemEmpty.setText(R.string.system_no_apps)
        if (!focused && s.apps.isNotEmpty()) {
            focused = true
            binding.appList.post { binding.appList.getChildAt(0)?.requestFocus() }
        }
    }

    private fun renderHistory(samples: List<SystemHistory.Sample>) {
        binding.historyChart.show(samples, System.currentTimeMillis())
        val time = android.text.format.DateFormat.getTimeFormat(this)
        val peak = SystemHistory.peak(samples)
        binding.historyNote.text = when {
            samples.isEmpty() -> getString(R.string.system_history_empty)
            peak == null -> getString(R.string.system_history_calm)
            else -> {
                val at = time.format(java.util.Date(peak.sample.at))
                val head = if (peak.memory) getString(R.string.system_history_peak_mem, peak.sample.memUsedPct, at)
                    else getString(R.string.system_history_peak_cpu, peak.sample.cpuPct ?: 0, at)
                val hog = SystemHistory.hogNear(samples, peak.sample.at)
                if (hog?.hog != null && hog.hogKb != null)
                    getString(R.string.system_history_peak_app, head, PackageUtil.label(this, hog.hog), SystemStats.size(hog.hogKb))
                else head
            }
        }
    }

    private fun verdict(level: Level, text: String, muted: Boolean = false) {
        val (tint, ink) = if (muted) R.color.idle_tint to R.color.text_secondary else colors(level)
        binding.systemVerdict.text = text
        binding.systemVerdict.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, tint))
        binding.systemVerdict.setTextColor(ContextCompat.getColor(this, ink))
    }

    private fun meter(m: ViewMeterBinding, value: String, pct: Int, level: Level, hint: String) {
        m.meterValue.text = value
        m.meterBar.progress = pct
        m.meterBar.progressTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colors(level).second))
        m.meterHint.text = hint
        m.meterHint.visibility = if (hint.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun colors(level: Level): Pair<Int, Int> = when (level) {
        Level.OK -> R.color.ok_tint to R.color.ok
        Level.WARN -> R.color.warn_tint to R.color.warn
        Level.BAD -> R.color.bad_tint to R.color.bad
    }

    private fun onApp(app: SystemStats.AppUse) {
        val label = PackageUtil.label(this, app.packageName)
        val using = if (app.cpuPct != null && app.cpuPct >= 1) getString(R.string.system_using_cpu, SystemStats.size(app.pssKb), app.cpuPct.toInt())
            else getString(R.string.system_using, SystemStats.size(app.pssKb))
        if (app.packageName == last?.onScreen) {
            AlertDialog.Builder(this).setTitle(label).setMessage("$using\n\n${getString(R.string.system_on_screen_note)}")
                .setPositiveButton(R.string.ok, null).show()
            return
        }
        AlertDialog.Builder(this).setTitle(label).setMessage("$using\n\n${getString(R.string.system_stop_note)}")
            .setPositiveButton(R.string.system_stop_now) { _, _ -> stop(app.packageName, label) }
            .setNeutralButton(R.string.system_add_rule) { _, _ -> addRule(app.packageName, label) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun stop(pkg: String, label: String) {
        worker.execute {
            val result = KillBackends.resolve(this).kill(this, pkg)
            runOnUiThread {
                Toast.makeText(this, if (result.success) getString(R.string.system_stopped, label)
                    else getString(R.string.system_stop_failed, label, result.detail), Toast.LENGTH_LONG).show()
                ticks = 0
                refresh(withApps = true)
            }
        }
    }

    /** A stop-only rule that also runs while the TV is on: the on-screen guard keeps it off the app being watched. */
    private fun addRule(pkg: String, label: String) {
        val store = RuleStore.get(this)
        val rule = store.all().firstOrNull { it.packageName == pkg } ?: Rule(
            id = store.nextId(), packageName = pkg, label = label, intervalMinutes = RULE_INTERVAL_MINUTES,
            relaunch = false, skipWhileScreenOn = false, anchorAt = System.currentTimeMillis()
        ).also {
            store.upsert(it)
            if (store.masterEnabled) RestartScheduler.schedule(this, it)
        }
        startActivity(Intent(this, RuleEditActivity::class.java).putExtra(RuleEditActivity.EXTRA_RULE_ID, rule.id))
    }

    class AppAdapter(private val onPick: (SystemStats.AppUse) -> Unit) : RecyclerView.Adapter<AppAdapter.Holder>() {
        private var apps: List<SystemStats.AppUse> = emptyList()
        private var onScreen: String? = null

        init { setHasStableIds(true) }

        fun submit(list: List<SystemStats.AppUse>, front: String?) {
            apps = list; onScreen = front
            notifyDataSetChanged()
        }

        override fun getItemId(position: Int): Long = apps[position].packageName.hashCode().toLong()
        override fun getItemCount() = apps.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSystemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = apps[position]
            val b = holder.b
            val ctx = b.root.context
            b.appName.text = PackageUtil.label(ctx, app.packageName)
            val cpu = app.cpuPct?.takeIf { it >= 1 }?.toInt()
            val front = app.packageName == onScreen
            b.appDetail.text = when {
                front -> ctx.getString(R.string.system_detail_on_screen)
                cpu != null -> ctx.getString(R.string.system_detail_background_cpu, cpu)
                else -> ctx.getString(R.string.system_detail_background)
            }
            @ColorRes val dot = when {
                front -> R.color.ok
                cpu != null && cpu >= BUSY_CPU -> R.color.warn
                else -> R.color.idle
            }
            b.appDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, dot))
            b.appMemory.text = SystemStats.size(app.pssKb)
            b.root.setOnClickListener { onPick(app) }
        }

        class Holder(val b: ItemSystemAppBinding) : RecyclerView.ViewHolder(b.root)
    }

    companion object {
        private const val TICK_MS = 3_000L
        private const val APPS_EVERY = 5          // every 5th tick: 15 s
        private const val BUSY_CPU = 20
        private const val RULE_INTERVAL_MINUTES = 120
    }
}

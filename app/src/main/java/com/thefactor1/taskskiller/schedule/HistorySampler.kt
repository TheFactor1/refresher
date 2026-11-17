package com.thefactor1.taskskiller.schedule

import android.content.Context
import com.thefactor1.taskskiller.kill.ShizukuBackend
import com.thefactor1.taskskiller.status.SystemHistory
import com.thefactor1.taskskiller.status.SystemStats
import java.io.File

/**
 * One reading a minute for the System screen's "Last hour", taken only while Refresher is on screen (Home and System
 * call [sampleIfDue] from their own refresh loops). Nothing runs in the background: an every-minute alarm that ran
 * `dumpsys meminfo` (3.4 s of work on a Shield with full swap) made the whole TV lag. Memory comes from /proc/meminfo;
 * the processor and the biggest app need Shizuku, and the biggest app comes from `ps` (0.1 s).
 */
object HistorySampler {

    @Synchronized
    fun sampleIfDue(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(K_SAMPLED_AT, 0) < EVERY_MS) return
        prefs.edit().putLong(K_SAMPLED_AT, now).apply()

        val mem = SystemStats.parseMeminfo(File("/proc/meminfo").readText())
        val total = mem["MemTotal"] ?: return
        val memUsed = (((total - (mem["MemAvailable"] ?: total)) * 100) / total).toInt().coerceIn(0, 100)
        val swapTotal = mem["SwapTotal"] ?: 0L
        val swapUsed = if (swapTotal <= 0) 0 else (((swapTotal - (mem["SwapFree"] ?: swapTotal)) * 100) / swapTotal).toInt().coerceIn(0, 100)

        val shell = ShizukuBackend.isBinderAlive() && ShizukuBackend.hasPermission()
        var cpu: Int? = null
        if (shell) {
            val stat = ShizukuBackend.shellOutput(context, arrayOf("sh", "-c", "head -1 /proc/stat"))
            val times = stat?.let(SystemStats::parseCpuTimes)
            val before = if (prefs.contains(K_TOTAL)) SystemStats.CpuTimes(prefs.getLong(K_TOTAL, 0), prefs.getLong(K_IDLE, 0)) else null
            // Only a gap of about a minute means "busy over the last minute"; after a gap the old reading is stale.
            if (now - prefs.getLong(K_AT, 0) <= 3 * 60_000L) cpu = SystemStats.busyPct(before, times)
            times?.let { prefs.edit().putLong(K_TOTAL, it.total).putLong(K_IDLE, it.idle).putLong(K_AT, now).apply() }
        }

        var hog: String? = null
        var hogKb: Long? = null
        if (shell && (memUsed >= 70 || swapUsed >= 50)) {
            // Refresher is the app on screen whenever this runs, so only Refresher and Shizuku need hiding.
            val top = ShizukuBackend.shellOutput(context, arrayOf("ps", "-A", "-o", "RSS,NAME"))?.let {
                SystemStats.topApps(SystemStats.parsePs(it), emptyMap(),
                    hidden = setOf(context.packageName, "moe.shizuku.privileged.api"), limit = 1)
            }?.firstOrNull()
            hog = top?.packageName; hogKb = top?.pssKb
        }
        SystemHistory.append(context, SystemHistory.Sample(now, memUsed, swapUsed, cpu, hog, hogKb))
    }

    private const val PREFS = "history_sampler"
    private const val K_SAMPLED_AT = "sampled_at"
    private const val K_TOTAL = "cpu_total"
    private const val K_IDLE = "cpu_idle"
    private const val K_AT = "cpu_at"
    private const val EVERY_MS = 55_000L // a little under a minute, so Home's 30 s loop lands a reading every minute
}

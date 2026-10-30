package com.thefactor1.taskskiller.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.thefactor1.taskskiller.kill.ShizukuBackend
import com.thefactor1.taskskiller.status.SystemHistory
import com.thefactor1.taskskiller.status.SystemStats
import com.thefactor1.taskskiller.util.ScreenUtil
import java.io.File

/**
 * One reading a minute for the System screen's "Last hour". A non-waking alarm: while the TV sleeps nothing runs,
 * and the next reading comes when it wakes. Memory comes from /proc/meminfo (any app may read it); the processor and
 * the biggest app need Shizuku, and the biggest app (dumpsys, a little work for the TV) is read only when memory is
 * amber or red, at most every 5 minutes.
 */
class HistorySampler : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                if (ScreenUtil.isScreenOn(app)) sample(app)
            } catch (e: Exception) {
                // A missed minute is fine; the next alarm tries again.
            } finally {
                arm(app)
                pending.finish()
            }
        }.start()
    }

    private fun sample(context: Context) {
        val now = System.currentTimeMillis()
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
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val before = if (prefs.contains(K_TOTAL)) SystemStats.CpuTimes(prefs.getLong(K_TOTAL, 0), prefs.getLong(K_IDLE, 0)) else null
            // Only a gap of about a minute means "busy over the last minute"; after a sleep the old reading is stale.
            if (now - prefs.getLong(K_AT, 0) <= 3 * 60_000L) cpu = SystemStats.busyPct(before, times)
            times?.let { prefs.edit().putLong(K_TOTAL, it.total).putLong(K_IDLE, it.idle).putLong(K_AT, now).apply() }
        }

        var hog: String? = null
        var hogKb: Long? = null
        val strained = memUsed >= 70 || swapUsed >= 50
        if (shell && strained) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (now - prefs.getLong(K_HOG_AT, 0) >= 5 * 60_000L) {
                val pss = ShizukuBackend.shellOutput(context, arrayOf("sh", "-c", "dumpsys meminfo | sed -n '/Total PSS by process/,/Total PSS by OOM/p'"))
                val front = com.thefactor1.taskskiller.util.ForegroundApp.packageName(context, ShizukuBackend)
                val top = pss?.let { SystemStats.topApps(SystemStats.parsePss(it), emptyMap(),
                    hidden = setOfNotNull(context.packageName, "moe.shizuku.privileged.api", front), limit = 1) }?.firstOrNull()
                hog = top?.packageName; hogKb = top?.pssKb
                prefs.edit().putLong(K_HOG_AT, now).apply()
            }
        }
        SystemHistory.append(context, SystemHistory.Sample(now, memUsed, swapUsed, cpu, hog, hogKb))
    }

    companion object {
        private const val PREFS = "history_sampler"
        private const val K_TOTAL = "cpu_total"
        private const val K_IDLE = "cpu_idle"
        private const val K_AT = "cpu_at"
        private const val K_HOG_AT = "hog_at"
        private const val EVERY_MS = 60_000L

        /** Arms the next reading in about a minute. Safe to call any number of times: it replaces the one pending. */
        fun arm(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(context, 0, Intent(context, HistorySampler::class.java), flags)
            val at = System.currentTimeMillis() + EVERY_MS
            // RTC (not RTC_WAKEUP): never wakes a sleeping TV.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) am.set(AlarmManager.RTC, at, pi)
            else am.setExact(AlarmManager.RTC, at, pi)
        }
    }
}

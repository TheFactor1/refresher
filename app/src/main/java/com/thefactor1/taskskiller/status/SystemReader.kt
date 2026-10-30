package com.thefactor1.taskskiller.status

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.thefactor1.taskskiller.kill.KillBackend
import com.thefactor1.taskskiller.util.ForegroundApp

/**
 * Reads the System screen's numbers through the backend's shell (Shizuku). Only while that screen is open: the
 * cheap system numbers every few seconds, the per-app lists (dumpsys meminfo / cpuinfo, which cost the TV a little
 * work themselves) less often. Nothing is kept or sent anywhere.
 */
class SystemReader(private val context: Context, private val backend: KillBackend) {

    private var lastCpu: SystemStats.CpuTimes? = null
    private var lastApps: List<SystemStats.AppUse> = emptyList()

    fun read(withApps: Boolean): SystemStats.Snapshot? {
        override?.let { return it(context) }
        val raw = backend.shellOutput(context, arrayOf("sh", "-c", "cat /proc/meminfo; echo '#load'; cat /proc/loadavg; echo '#stat'; head -1 /proc/stat"))
            ?: return null
        val mem = SystemStats.parseMeminfo(raw.substringBefore("#load"))
        val load = SystemStats.parseLoad(raw.substringAfter("#load").substringBefore("#stat")) ?: 0.0
        val cpuNow = SystemStats.parseCpuTimes(raw.substringAfter("#stat"))
        val busy = SystemStats.busyPct(lastCpu, cpuNow)
        lastCpu = cpuNow
        val onScreen = ForegroundApp.packageName(context, backend)
        if (withApps || lastApps.isEmpty()) {
            val pss = backend.shellOutput(context, arrayOf("sh", "-c", "dumpsys meminfo | sed -n '/Total PSS by process/,/Total PSS by OOM/p'"))
            val cpu = backend.shellOutput(context, arrayOf("sh", "-c", "dumpsys cpuinfo | head -40"))
            if (pss != null) {
                lastApps = SystemStats.topApps(SystemStats.parsePss(pss), cpu?.let(SystemStats::parseCpuinfo) ?: emptyMap(),
                    hidden = setOf(context.packageName, "moe.shizuku.privileged.api"))
            }
        }
        val fs = StatFs(Environment.getDataDirectory().path)
        return SystemStats.Snapshot(
            memTotalKb = mem["MemTotal"] ?: 0, memAvailableKb = mem["MemAvailable"] ?: 0,
            swapTotalKb = mem["SwapTotal"] ?: 0, swapFreeKb = mem["SwapFree"] ?: 0,
            load1 = load, cores = Runtime.getRuntime().availableProcessors(), cpuBusyPct = busy,
            storageFreeBytes = fs.availableBytes, storageTotalBytes = fs.totalBytes,
            apps = lastApps, onScreen = onScreen
        )
    }

    companion object {
        /** Tests: a fixed snapshot instead of reading the TV. */
        var override: ((Context) -> SystemStats.Snapshot)? = null
    }
}

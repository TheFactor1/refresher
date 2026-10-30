package com.thefactor1.taskskiller.home

import android.content.Context
import com.thefactor1.taskskiller.kill.KillBackend
import com.thefactor1.taskskiller.status.SystemStats
import com.thefactor1.taskskiller.util.ForegroundApp
import com.thefactor1.taskskiller.util.PackageUtil
import com.thefactor1.taskskiller.util.VpnCheck

/** "Clean up now": stops background apps over the size limit, never one that is playing. Needs Shizuku (it reads every app's memory). */
object CleanUp {

    data class Result(val stopped: List<String>, val freedKb: Long, val failed: Int, val noShell: Boolean = false)

    fun run(context: Context, backend: KillBackend): Result {
        val pss = backend.shellOutput(context, arrayOf("sh", "-c", "dumpsys meminfo | sed -n '/Total PSS by process/,/Total PSS by OOM/p'"))
            ?: return Result(emptyList(), 0, 0, noShell = true)
        val prefs = HomePrefs.get(context)
        val apps = SystemStats.parsePss(pss).map { SystemStats.AppUse(it.key, it.value, null) }
        val keep = buildSet {
            add(context.packageName)
            apps.forEach { if (VpnCheck.isVpnApp(context, it.packageName)) add(it.packageName) }
            if (prefs.cleanupSkipsFavourites) addAll(prefs.favourites)
            // Music playing in the background stays playing.
            addAll(NowPlaying.playingPackages(context, backend))
        }
        val targets = HomeLogic.cleanupTargets(apps, prefs.cleanupMb * 1024L, ForegroundApp.packageName(context, backend), keep)
        val stopped = mutableListOf<String>()
        var freed = 0L
        var failed = 0
        for (t in targets) {
            if (backend.kill(context, t.packageName).success) { stopped += PackageUtil.label(context, t.packageName); freed += t.pssKb } else failed++
        }
        return Result(stopped, freed, failed)
    }
}

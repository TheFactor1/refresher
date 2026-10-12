package com.thefactor1.taskskiller.status

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.thefactor1.taskskiller.data.RuleStore
import com.thefactor1.taskskiller.data.RunLog
import com.thefactor1.taskskiller.kill.KillBackends
import com.thefactor1.taskskiller.kill.ShizukuBackend
import com.thefactor1.taskskiller.launch.AppLauncher
import com.thefactor1.taskskiller.schedule.RestartScheduler
import com.thefactor1.taskskiller.setup.ShizukuStarter

/** Reads [Health.Inputs] from the device. */
object HealthReader {

    /**
     * Replaces the device's answers about Shizuku, permissions and the kill
     * method (rules and the log still come from the stores). Screenshot tests
     * set it to draw a healthy box or a broken one; null in the app.
     */
    @Volatile
    var deviceOverride: ((Context) -> Device)? = null

    /** The parts of [Health.Inputs] that come from the system rather than Refresher's own storage. */
    data class Device(
        val canForceStop: Boolean,
        val shizukuInstalled: Boolean,
        val shizukuRunning: Boolean,
        val shizukuPermitted: Boolean,
        val forceStopWithoutShizuku: Boolean,
        val exactAlarms: Boolean,
        val batteryExempt: Boolean,
        val backgroundStarts: Boolean,
        /** "Shizuku", "Root"… for Setup's "Stops apps with" line. */
        val methodName: String
    )

    fun read(context: Context): Health.Inputs {
        val store = RuleStore.get(context)
        val device = device(context)
        return Health.Inputs(
            rules = store.all(),
            masterEnabled = store.masterEnabled,
            canForceStop = device.canForceStop,
            shizukuInstalled = device.shizukuInstalled,
            shizukuRunning = device.shizukuRunning,
            shizukuPermitted = device.shizukuPermitted,
            forceStopWithoutShizuku = device.forceStopWithoutShizuku,
            autoStartShizuku = store.autoStartShizuku,
            exactAlarms = device.exactAlarms,
            batteryExempt = device.batteryExempt,
            backgroundStarts = device.backgroundStarts,
            log = RunLog.get(context).entries()
        )
    }

    fun report(context: Context): Health.Report = Health.evaluate(read(context), RestartScheduler::nextRunAt)

    fun device(context: Context): Device {
        deviceOverride?.let { return it(context) }
        val backend = KillBackends.resolve(context)
        val running = ShizukuBackend.isBinderAlive()
        val usable = backend.isForceStop && backend.isAvailable(context)
        return Device(
            canForceStop = usable,
            shizukuInstalled = ShizukuStarter.isInstalled(context),
            shizukuRunning = running,
            shizukuPermitted = running && ShizukuBackend.hasPermission(),
            forceStopWithoutShizuku = usable && backend !== ShizukuBackend,
            exactAlarms = RestartScheduler.canScheduleExact(context),
            batteryExempt = isIgnoringBatteryOptimizations(context),
            backgroundStarts = backend.hasPrivilegedLaunch || !AppLauncher.backgroundStartsLikelyBlocked(context),
            methodName = backend.displayName
        )
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}

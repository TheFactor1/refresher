package com.thefactor1.taskskiller.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.data.Rule
import com.thefactor1.taskskiller.data.RuleStore
import com.thefactor1.taskskiller.data.RunLog
import com.thefactor1.taskskiller.setup.ShizukuStarter
import com.thefactor1.taskskiller.kill.ShizukuBackend
import com.thefactor1.taskskiller.kill.KillBackend
import com.thefactor1.taskskiller.kill.KillBackends
import com.thefactor1.taskskiller.launch.AppLauncher
import com.thefactor1.taskskiller.ui.MainActivity
import com.thefactor1.taskskiller.util.ForegroundApp
import com.thefactor1.taskskiller.kill.OpResult
import com.thefactor1.taskskiller.util.Format
import com.thefactor1.taskskiller.util.VpnCheck
import com.thefactor1.taskskiller.util.PackageUtil
import com.thefactor1.taskskiller.util.ScreenUtil
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Does the actual kill-wait-relaunch work.
 *
 * It runs as a foreground service for the duration of a job so the system does
 * not reap the process mid-restart, and holds a partial wake lock so the CPU
 * stays up while the display is off.
 */
class RestartService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private val pendingJobs = AtomicInteger(0)

    /**
     * The most recent start id, so a stop can be refused when a start arrived
     * after the running job began. The no-argument stopSelf() ignores that and
     * tears the service down anyway; onDestroy then calls executor.shutdown(),
     * which *drains* the queue rather than discarding it, so the newly queued
     * job would run on a destroyed Service — sleeping up to 75 s and calling
     * startActivity through a dead Context, with no foreground service holding
     * the process up.
     */
    @Volatile
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        // Must happen within a few seconds of being started, before any work.
        promoteToForeground()

        when (intent?.action) {
            ACTION_RUN_RULE -> {
                val ruleId = intent.getLongExtra(EXTRA_RULE_ID, -1L)
                val manual = intent.getBooleanExtra(EXTRA_MANUAL, false)
                if (ruleId >= 0) enqueue(ruleId, manual, intent.getBooleanExtra(EXTRA_RETURN_TO_APP, manual))
                else finishIfIdle()
            }
            else -> finishIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun enqueue(ruleId: Long, manual: Boolean, returnToApp: Boolean) {
        pendingJobs.incrementAndGet()
        executor.execute {
            val wakeLock = acquireWakeLock()
            try {
                execute(ruleId, manual, returnToApp)
            } catch (e: Exception) {
                Log.e(TAG, "Rule $ruleId failed", e)
                RunLog.get(this).add("Rule $ruleId", "Unexpected error: ${e.message}", false)
            } finally {
                releaseWakeLock(wakeLock)
                if (pendingJobs.decrementAndGet() <= 0) stopSelf(latestStartId)
            }
        }
    }

    private fun execute(ruleId: Long, manual: Boolean, returnToApp: Boolean) {
        val store = RuleStore.get(this)
        val rule = store.find(ruleId) ?: run {
            RestartScheduler.cancel(this, ruleId)
            return
        }

        if (!manual && (!store.masterEnabled || !rule.enabled)) {
            RestartScheduler.cancel(this, ruleId)
            return
        }

        if (!PackageUtil.isInstalled(this, rule.packageName)) {
            record(rule, "Skipped: ${rule.packageName} is not installed", success = false)
            reArm(ruleId, rule.copy(lastRunAt = System.currentTimeMillis()))
            return
        }

        // A scheduled run defers while someone is watching; a manual one never does.
        if (!manual && rule.skipWhileScreenOn && ScreenUtil.isScreenOn(this)) {
            record(rule, "Deferred: display is on", success = true, advanceClock = false)
            RestartScheduler.scheduleAt(
                this,
                rule.id,
                System.currentTimeMillis() + minOf(rule.intervalMillis, DEFER_RETRY_MS)
            )
            return
        }

        // Sampled before the kill: the question is whether anyone was watching.
        val displayWasOff = !ScreenUtil.isScreenOn(this)

        // Shizuku dies with every reboot and now and then on its own. A run that
        // finds it down starts it again first, rather than dropping to a method
        // that cannot stop a VPN.
        if (ShizukuBackend.isShizukuInstalled(this) && !ShizukuBackend.isBinderAlive() && store.autoStartShizuku) {
            ShizukuStarter.startInBackground(this)
            ShizukuStarter.awaitBinder()
        }

        val backend = KillBackends.resolve(this)

        // With Shizuku installed, it is the intended way to stop apps. If it still
        // can't be used (stopped, or Refresher's permission lost, e.g. by a
        // reinstall), the weak fallback would leave a VPN running and log a
        // "Refreshed" that never happened (Sep 26 - Oct 12 on the Shield), so the
        // run fails and says why.
        if (!backend.isForceStop && ShizukuBackend.isShizukuInstalled(this)) {
            record(rule, "Refresh failed: ${ShizukuBackend.unavailableReason(this)}. Open Refresher to fix it", false)
            reArm(ruleId, rule)
            return
        }

        // Never stop the app on screen on a schedule: wait and retry instead.
        val inFront = if (displayWasOff) null else ForegroundApp.packageName(this, backend)
        val playing = if (manual) emptySet() else com.thefactor1.taskskiller.home.NowPlaying.playingPackages(this, backend)
        if (OnScreenGuard.shouldDefer(manual, !displayWasOff, inFront, rule.packageName, playing)) {
            val why = if (rule.packageName in playing) "is playing" else "is on screen"
            record(rule, "Deferred: ${rule.label} $why", success = true, advanceClock = false)
            RestartScheduler.scheduleAt(this, rule.id, System.currentTimeMillis() + minOf(rule.intervalMillis, DEFER_RETRY_MS))
            return
        }

        // What was on screen, so the relaunch does not leave the viewer looking
        // at the relaunched app. Run now starts from our own editor, so without
        // a shell to ask, Refresher is the answer; the Refresh now tile starts
        // from someone else's screen, which cannot be known then.
        val onScreenBefore = if (displayWasOff) null
            else ForegroundApp.packageName(this, backend) ?: if (manual && returnToApp) packageName else null

        // For a VPN that was connected, note which VPN network is up now, so a
        // new one afterwards proves the restart brought it back.
        val vpnBefore = if (rule.relaunch && VpnCheck.isVpnApp(this, rule.packageName)) {
            VpnCheck.vpnNetworks(this).takeIf { it.isNotEmpty() }
        } else {
            null
        }

        val vpnWatch = vpnBefore?.let { VpnCheck.watch(this, it) }
        val killResult = backend.kill(this, rule.packageName)
        val parts = mutableListOf(
            if (killResult.success) "Refreshed via ${backend.displayName}"
            else "Refresh failed (${backend.displayName}): ${killResult.detail}"
        )
        if (killResult.success && killResult.detail.isNotBlank()) parts += killResult.detail
        var success = killResult.success

        if (rule.relaunch && PackageUtil.launchIntent(this, rule.packageName) == null) {
            // A kill-only app (no launcher entry): there is nothing to relaunch,
            // which is expected rather than a failure.
            parts += "not relaunched (no launcher entry)"
        } else if (rule.relaunch) {
            val launchedAt = relaunch(rule, backend, displayWasOff, onScreenBefore, parts)

            if (killResult.success && launchedAt != null && vpnWatch != null) {
                val back = vpnWatch.await(launchedAt, VPN_WAIT_MS)
                if (back != null) {
                    parts += "VPN back in ${Format.seconds(back)}"
                } else {
                    // One more go: a VPN that didn't reconnect is the failure
                    // this whole app exists to fix, so it is worth a second try.
                    val retryWatch = VpnCheck.watch(this, VpnCheck.vpnNetworks(this))
                    backend.kill(this, rule.packageName)
                    val againAt = relaunch(rule, backend, !ScreenUtil.isScreenOn(this), onScreenBefore, mutableListOf())
                    val second = if (againAt != null) retryWatch.await(againAt, VPN_WAIT_MS) else retryWatch.await(0L, 0L)
                    if (second != null) {
                        parts += "VPN back in ${Format.seconds(second)} after a second try"
                    } else {
                        parts += "VPN didn't come back"
                        success = false
                    }
                }
            }
        }

        vpnWatch?.await(0L, 0L) // stops it if the check above didn't run
        record(rule, parts.joinToString(" • "), success)
        reArm(ruleId, rule)
    }

    /**
     * Opens the app again and settles the screen: a sleeping TV goes back to
     * Home once the app has had time to start; with someone watching, whatever
     * they were on comes back in front. Returns when it was opened
     * (elapsedRealtime), or null if it couldn't be.
     */
    private fun relaunch(
        rule: Rule,
        backend: KillBackend,
        displayWasOff: Boolean,
        onScreenBefore: String?,
        parts: MutableList<String>
    ): Long? {
        SystemClock.sleep(rule.relaunchDelayMs.coerceIn(0L, MAX_RELAUNCH_DELAY_MS))
        if (rule.wakeScreen) ScreenUtil.wakeScreen(this, backend)
        // Someone may have opened another app during the wait; that is the one
        // to give back, not what was on screen before the stop.
        val target = if (displayWasOff) null else
            ForegroundApp.packageName(this, backend)?.takeIf { it != rule.packageName } ?: onScreenBefore
        val launchResult = AppLauncher.launch(this, backend, rule.packageName)
        val launchedAt = SystemClock.elapsedRealtime()
        parts += if (launchResult.success) {
            if (launchResult.detail.isBlank()) "relaunched" else "relaunched (${launchResult.detail})"
        } else {
            "relaunch failed: ${launchResult.detail}"
        }
        if (!launchResult.success) return null

        // Relaunched into a sleeping TV: once the app has had time to start
        // (a VPN reconnects meanwhile), put the launcher back on top so the
        // TV wakes up to the home screen instead of to that app. Skipped if
        // someone turned the TV on in the meantime.
        if (displayWasOff && !rule.wakeScreen) {
            SystemClock.sleep(HOME_RETURN_DELAY_MS)
            if (!ScreenUtil.isScreenOn(this)) {
                val home = AppLauncher.goHome(this, backend)
                parts += if (home.success) "returned to Home" else "could not return to Home: ${home.detail}"
            }
        } else if (!rule.wakeScreen && target != null && target != rule.packageName) {
            // Someone is using the TV. The relaunched app's own start-up does
            // the reconnecting, so it only needs a moment in front before
            // whatever they were looking at is put back. Only while the app is
            // actually on top: never pull someone out of what they just opened.
            // Some apps open a second screen of their own once they are up
            // (NordVPN's connection screen), so keep watching briefly.
            SystemClock.sleep(RETURN_DELAY_MS)
            val label = PackageUtil.label(this, target)
            var back: OpResult? = null
            val watchUntil = SystemClock.elapsedRealtime() + RETURN_WATCH_MS
            var returns = 0
            while (returns <= MAX_RETURN_RETRIES && SystemClock.elapsedRealtime() < watchUntil) {
                if (ForegroundApp.packageName(this, backend) == rule.packageName) {
                    back = AppLauncher.returnTo(this, backend, target)
                    returns++
                    if (!back.success) break
                }
                SystemClock.sleep(RETURN_POLL_MS)
            }
            if (back != null) {
                parts += if (back.success) "returned to $label" else "could not return to $label: ${back.detail}"
            }
        }
        return launchedAt
    }

    /**
     * Re-arm after *every* run, manual included.
     *
     * [record] has just advanced lastRunAt, but a manual run used to leave the
     * existing alarm untouched at its original trigger time, and the alarm path
     * does not consult [RestartScheduler.isDue] — so pressing "Refresh now"
     * shortly before a rule was due killed the app a second time when the stale
     * alarm landed. Nothing corrected it unless MainActivity happened to be
     * opened, which is exactly what the tile exists to avoid.
     */
    private fun reArm(ruleId: Long, fallback: Rule) {
        val store = RuleStore.get(this)
        val latest = store.find(ruleId) ?: fallback
        if (latest.enabled && store.masterEnabled) RestartScheduler.schedule(this, latest)
        else RestartScheduler.cancel(this, ruleId)
    }

    /** Persist the outcome on the rule and in the rolling log. */
    private fun record(rule: Rule, detail: String, success: Boolean, advanceClock: Boolean = true) {
        Log.i(TAG, "${rule.packageName}: $detail")
        val store = RuleStore.get(this)
        val current = store.find(rule.id)
        val updated = current?.copy(
            lastRunAt = if (advanceClock) System.currentTimeMillis() else current.lastRunAt,
            lastResult = detail
        )

        // A rule that keeps deferring records the identical outcome every few
        // minutes. Writing and logging each repeat would churn storage and
        // flush the real history out of the 50-entry log within a few hours,
        // so an unchanged outcome is recorded once and then stays quiet.
        if (current != null && updated == current) return

        if (updated != null) store.upsert(updated)
        RunLog.get(this).add(rule.label, detail, success)
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return try {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TasksKiller:restart").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire wake lock", e)
            null
        }
    }

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        try {
            if (wakeLock?.isHeld == true) wakeLock.release()
        } catch (e: Exception) {
            Log.w(TAG, "Wake lock release failed", e)
        }
    }

    private fun finishIfIdle() {
        if (pendingJobs.get() <= 0) stopSelf(latestStartId)
    }

    private fun promoteToForeground() {
        createChannel()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_restart)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
        )
    }

    companion object {
        private const val TAG = "RestartService"
        private const val CHANNEL_ID = "taskskiller_restarts"
        private const val NOTIFICATION_ID = 4711

        private const val ACTION_RUN_RULE = "com.thefactor1.taskskiller.action.RUN_RULE"
        private const val EXTRA_RULE_ID = "rule_id"
        private const val EXTRA_MANUAL = "manual"
        private const val EXTRA_RETURN_TO_APP = "return_to_app"

        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60_000L
        private const val MAX_RELAUNCH_DELAY_MS = 60_000L

        /** Time the relaunched app gets in front before the launcher is brought back. */
        private const val HOME_RETURN_DELAY_MS = 15_000L

        /** With the screen on, how long the relaunched app shows before the previous one returns. */
        private const val RETURN_DELAY_MS = 1_500L

        /** How long after returning to watch for the app popping back on top. */
        private const val RETURN_WATCH_MS = 8_000L
        private const val RETURN_POLL_MS = 500L
        private const val MAX_RETURN_RETRIES = 3

        /** How long a restarted VPN gets to show up again before it counts as failed. */
        private const val VPN_WAIT_MS = 30_000L

        /** How soon to look again after deferring a run because the TV was in use. */
        private const val DEFER_RETRY_MS = 5 * 60_000L

        /**
         * [returnToApp]: when the foreground app cannot be read, whether a
         * manual run should bring Refresher back afterwards (true for Run now
         * in the editor, false for the Refresh now tile).
         */
        fun runRule(context: Context, ruleId: Long, manual: Boolean = false, returnToApp: Boolean = manual) {
            val intent = Intent(context, RestartService::class.java).apply {
                action = ACTION_RUN_RULE
                putExtra(EXTRA_RULE_ID, ruleId)
                putExtra(EXTRA_MANUAL, manual)
                putExtra(EXTRA_RETURN_TO_APP, returnToApp)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Android 12+ only lifts the background foreground-service ban
                // for a broadcast delivered by an *exact* alarm. On the inexact
                // fallback path that exemption is absent and every run dies
                // here, so name the cause instead of logging a bare exception.
                Log.e(TAG, "Could not start restart service", e)
                val hint = if (!RestartScheduler.canScheduleExact(context)) {
                    " — exact alarms are not permitted, so the wake-up carried no " +
                        "foreground-service exemption. Grant them in Setup."
                } else {
                    ""
                }
                RunLog.get(context)
                    .add("Rule $ruleId", "Service start blocked: ${e.message}$hint", false)
            }
        }
    }
}

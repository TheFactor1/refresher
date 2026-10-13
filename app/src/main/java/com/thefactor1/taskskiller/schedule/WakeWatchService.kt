package com.thefactor1.taskskiller.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.data.Rule
import com.thefactor1.taskskiller.data.RuleStore
import com.thefactor1.taskskiller.ui.MainActivity

/**
 * Restarts the apps marked "When the TV turns on" as the screen comes on.
 *
 * Android only delivers SCREEN_ON to a receiver registered at run time, so
 * something has to stay running to hear it: this service, kept in the
 * foreground with a silent minimum-priority notification. It runs only while
 * at least one enabled rule asks for it ([WakeWatch.sync]).
 */
class WakeWatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            // A moment for the TV to settle (HDMI, Wi-Fi) before the restart.
            handler.removeCallbacks(onWake)
            handler.postDelayed(onWake, SETTLE_MS)
        }
    }

    private val onWake = Runnable {
        val store = RuleStore.get(this)
        if (!store.masterEnabled) return@Runnable
        val now = System.currentTimeMillis()
        store.enabled().filter { WakeWatch.isDueOnWake(it, now) }.forEach {
            Log.i(TAG, "${it.packageName}: refreshing as the TV turns on")
            RestartService.runRule(this, it.id, manual = true, returnToApp = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!WakeWatch.wanted(this)) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(onWake)
        try {
            unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.let { manager ->
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    manager.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            getString(R.string.wake_channel),
                            NotificationManager.IMPORTANCE_MIN
                        ).apply { setShowBadge(false) }
                    )
                }
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.wake_notification_title))
            .setContentText(getString(R.string.wake_notification_text))
            .setSmallIcon(R.drawable.ic_restart)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private companion object {
        const val TAG = "WakeWatchService"
        const val CHANNEL_ID = "taskskiller_wake"
        const val NOTIFICATION_ID = 4712
        const val SETTLE_MS = 2_000L
    }
}

/** Starts or stops [WakeWatchService] to match the rules. */
object WakeWatch {

    fun wanted(context: Context): Boolean {
        val store = RuleStore.get(context)
        return store.masterEnabled && store.enabled().any { it.refreshOnWake }
    }

    /** Due as the TV turns on: asked for, and not restarted in the last little while. */
    fun isDueOnWake(rule: Rule, now: Long): Boolean =
        rule.enabled && rule.refreshOnWake &&
            now - rule.lastRunAt >= Rule.WAKE_MIN_GAP_MINUTES * 60_000L

    fun sync(context: Context) {
        val intent = Intent(context, WakeWatchService::class.java)
        if (wanted(context)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                // Android 12+ refuses foreground services started from the
                // background outside its exemptions; the next app open or boot retries.
                Log.w("WakeWatch", "Could not start the wake watcher", e)
            }
        } else {
            context.stopService(intent)
        }
    }
}

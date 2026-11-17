package com.thefactor1.taskskiller.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.thefactor1.taskskiller.data.RuleStore
import com.thefactor1.taskskiller.setup.ShizukuStartReceiver

/**
 * Alarms do not survive a reboot, so every schedule is rebuilt here. Rules live
 * in device-protected storage so this also works at LOCKED_BOOT_COMPLETED,
 * before the user has unlocked the box.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Re-arming schedules after ${intent.action}")
                RestartScheduler.scheduleAll(context)
                // Shizuku's server dies with the reboot. Only after a full boot:
                // the adb key it needs lives in credential-encrypted storage.
                if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
                    RuleStore.get(context).autoStartShizuku
                ) {
                    ShizukuStartReceiver.schedule(context)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}

package com.thefactor1.taskskiller.setup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import com.thefactor1.taskskiller.R
import java.io.File

/** Answers from Android's installer for the Shizuku session ShizukuDownloader started. */
class ShizukuInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    confirm?.let { context.startActivity(it) }
                } catch (e: Exception) {
                    toast(context, context.getString(R.string.shizuku_install_failed, e.javaClass.simpleName))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                File(context.cacheDir, ShizukuDownloader.APK_NAME).delete()
                toast(context, context.getString(R.string.shizuku_installed))
            }
            else -> {
                File(context.cacheDir, ShizukuDownloader.APK_NAME).delete()
                // The viewer pressing Cancel lands here too (STATUS_FAILURE_ABORTED): say so plainly.
                val why = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) context.getString(R.string.shizuku_install_cancelled)
                    else intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                toast(context, context.getString(R.string.shizuku_install_failed, why))
            }
        }
    }

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}

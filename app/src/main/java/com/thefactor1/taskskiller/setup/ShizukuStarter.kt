package com.thefactor1.taskskiller.setup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.thefactor1.taskskiller.kill.ShizukuBackend

/**
 * Starts Shizuku and applies the settings Android TV has no screen for, through
 * [LocalAdb]. Used by the "Set up automatically" button and, after a reboot, by
 * [ShizukuStartReceiver]. Everything here blocks, so call it off the main thread.
 */
object ShizukuStarter {

    const val PACKAGE = "moe.shizuku.privileged.api"
    const val PLAY_STORE_URI = "market://details?id=$PACKAGE"
    const val DOWNLOAD_URL = "https://github.com/RikkaApps/Shizuku/releases"

    /** How long to wait for the freshly started server to hand us its binder. */
    private const val BINDER_WAIT_MS = 10_000L

    data class Step(val label: String, val ok: Boolean, val detail: String = "")

    fun isInstalled(context: Context): Boolean = startCommand(context) != null

    /** Printed by [startCommand] when the server was already up, so nothing was started. */
    private const val ALREADY_RUNNING = "shizuku-already-running"

    /**
     * Shizuku 13 ships its starter as a native library inside the APK; older
     * releases wrote start.sh to external storage instead. The shell picks
     * whichever exists, so the path follows Shizuku updates. An already-running
     * server is left alone rather than restarted, and says so.
     */
    fun startCommand(context: Context): String? {
        val info = try {
            context.packageManager.getApplicationInfo(PACKAGE, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val library = "${info.nativeLibraryDir}/libshizuku.so"
        val legacyScript = "/storage/emulated/0/Android/data/$PACKAGE/start.sh"
        return "if pidof shizuku_server >/dev/null; then echo $ALREADY_RUNNING; " +
            "elif [ -f '$library' ]; then '$library'; else sh '$legacyScript'; fi"
    }

    fun wasAlreadyRunning(step: Step): Boolean = step.ok && step.detail == DETAIL_ALREADY_RUNNING

    private const val DETAIL_ALREADY_RUNNING = "already running"

    /** The one-off grants from the manual ADB instructions, for this Android version. */
    fun settingsCommands(context: Context): List<String> {
        val pkg = context.packageName
        return buildList {
            add("appops set $pkg SYSTEM_ALERT_WINDOW allow")
            add("dumpsys deviceidle whitelist +$pkg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add("appops set $pkg SCHEDULE_EXACT_ALARM allow")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            }
        }
    }

    /** The whole "Set up automatically" sequence, one [Step] per action. */
    fun runSetup(context: Context): List<Step> {
        val steps = mutableListOf<Step>()
        val setupError = LocalAdb.session(context, LocalAdb.INTERACTIVE_TIMEOUT_MS) { shell ->
            val probe = shell.exec("echo ok")
            steps += Step("Connect to network debugging", probe.success, if (probe.success) "" else probe.output)
            if (!probe.success) return@session

            val start = startCommand(context)
            steps += if (start == null) {
                Step("Start Shizuku", false, "Shizuku is not installed; choose \"Get Shizuku\" first")
            } else {
                val result = shell.exec(start)
                when {
                    !result.success -> Step("Start Shizuku", false, result.output)
                    ALREADY_RUNNING in result.output -> Step("Start Shizuku", true, DETAIL_ALREADY_RUNNING)
                    awaitBinder() -> Step("Start Shizuku", true)
                    else -> Step("Start Shizuku", true, "started, but it has not answered Refresher yet")
                }
            }

            settingsCommands(context).forEach { command ->
                val result = shell.exec(command)
                steps += Step(command, result.success, if (result.success) "" else result.output)
            }
        }
        if (setupError != null) steps += Step("Connect to network debugging", false, setupError)
        return steps
    }

    /** Start the server only, with a short timeout: the key is already trusted by now. */
    fun startInBackground(context: Context): Step {
        val start = startCommand(context) ?: return Step("Start Shizuku", false, "Shizuku is not installed")
        var step = Step("Start Shizuku", false, "no result")
        val setupError = LocalAdb.session(context, LocalAdb.BACKGROUND_TIMEOUT_MS) { shell ->
            val result = shell.exec(start)
            step = when {
                !result.success -> Step("Start Shizuku", false, result.output)
                ALREADY_RUNNING in result.output -> Step("Start Shizuku", true, DETAIL_ALREADY_RUNNING)
                else -> Step("Start Shizuku", true)
            }
        }
        return if (setupError != null) Step("Start Shizuku", false, setupError) else step
    }

    fun awaitBinder(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + BINDER_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ShizukuBackend.isBinderAlive()) return true
            SystemClock.sleep(250)
        }
        return ShizukuBackend.isBinderAlive()
    }
}

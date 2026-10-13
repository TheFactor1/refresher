package com.thefactor1.taskskiller.kill

import android.content.Context
import android.os.Looper
import com.thefactor1.taskskiller.util.PackageUtil
import com.thefactor1.taskskiller.util.Shell

/** `su -c am force-stop`. The bluntest instrument, and the most reliable one. */
object RootBackend : KillBackend {
    override val id = "root"
    override val displayName = "Root (su)"
    override val rank = 10
    override val isForceStop = true
    override val hasPrivilegedLaunch = true

    // Cached because each probe pops a grant dialog on some superuser apps.
    @Volatile
    private var verified: Boolean? = null

    @Volatile
    private var probing = false

    override fun isAvailable(context: Context): Boolean {
        if (!Shell.suBinaryExists()) return false
        verified?.let { return it }

        // The probe blocks on `su`, which can sit for seconds waiting on a grant
        // dialog. Never do that on the main thread; the status panel picks the
        // answer up on its next refresh instead.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            probeAsync()
            return false
        }
        return probe()
    }

    override fun unavailableReason(context: Context): String = when {
        !Shell.suBinaryExists() -> "No su binary on this device"
        verified == null -> "Checking for root access…"
        else -> "Root access was not granted to Refresher"
    }

    private fun probe(): Boolean {
        val result = Shell.runAsRoot("id", timeoutSeconds = 10)
        val ok = result.success && result.output.contains("uid=0")
        verified = ok
        return ok
    }

    @Synchronized
    private fun probeAsync() {
        if (probing) return
        probing = true
        Thread {
            try {
                probe()
            } finally {
                probing = false
            }
        }.start()
    }

    override fun kill(context: Context, packageName: String): OpResult {
        // Belt and braces with the check in Rule.fromJson: this is the one
        // backend that interpolates into a shell, so it refuses outright rather
        // than trusting that everything upstream validated.
        if (!PackageUtil.isPlausiblePackageName(packageName)) {
            return OpResult.fail("Refusing to build a shell command for \"$packageName\"")
        }
        val result = Shell.runAsRoot("am force-stop $packageName")
        return if (result.success) OpResult.OK
        else OpResult.fail("am force-stop exited ${result.exitCode}: ${result.output}")
    }

    override fun shell(context: Context, command: Array<String>): OpResult {
        val result = Shell.runAsRoot(command.joinToString(" "))
        return if (result.success) OpResult.OK
        else OpResult.fail("exited ${result.exitCode}: ${result.output}")
    }

    override fun launch(context: Context, packageName: String): OpResult {
        if (!PackageUtil.isPlausiblePackageName(packageName)) {
            return OpResult.fail("Refusing to build a shell command for \"$packageName\"")
        }
        // Every word is an intent constant, a hex flag or the component of a
        // package whose name was just checked, so joining them is safe here.
        val args = PackageUtil.launchArgs(context, packageName)
            ?: return OpResult.fail("No launchable activity in $packageName")
        val result = Shell.runAsRoot(args.joinToString(" "))
        return if (result.success) OpResult.OK
        else OpResult.fail("am start exited ${result.exitCode}: ${result.output}")
    }
}

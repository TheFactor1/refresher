package com.thefactor1.taskskiller.kill

import android.content.Context
import android.content.pm.PackageManager
import com.thefactor1.taskskiller.util.PackageUtil
import com.thefactor1.taskskiller.util.readTextSafely
import rikka.shizuku.Shizuku
import java.io.InputStream

/**
 * Runs `am` as the shell user through Shizuku.
 *
 * Shizuku is a separate app the user starts once per boot with a single ADB
 * command; it then hands out shell-level privileges to apps that ask. This gets
 * a genuine force-stop on a stock, unrooted Android TV box.
 *
 * `Shizuku.newProcess` is marked as restricted API in the client library, so it
 * is reached reflectively — that also keeps this compiling across library
 * versions that move the signature around.
 */
object ShizukuBackend : KillBackend {
    override val id = "shizuku"
    override val displayName = "Shizuku (ADB, no root)"
    override val rank = 20
    override val isForceStop = true
    override val hasPrivilegedLaunch = true

    const val PERMISSION_REQUEST_CODE = 8171

    /** Shizuku is running and has granted us permission. */
    override fun isAvailable(context: Context): Boolean = isBinderAlive() && hasPermission()

    override fun unavailableReason(context: Context): String = when {
        !isShizukuInstalled(context) -> "Shizuku app is not installed"
        !isBinderAlive() -> "Shizuku is installed but not running — start it over ADB"
        !hasPermission() -> "Shizuku permission not granted to Refresher yet"
        else -> ""
    }

    fun isShizukuInstalled(context: Context): Boolean =
        PackageUtil.isInstalled(context, "moe.shizuku.privileged.api")

    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Throwable) {
            // The binder died between the check and the request; the status
            // panel will show it as unavailable on the next refresh.
        }
    }

    override fun kill(context: Context, packageName: String): OpResult {
        val result = exec(arrayOf("am", "force-stop", packageName))
        return if (result.success) OpResult.OK
        else OpResult.fail("am force-stop exited ${result.exitCode}: ${result.output}")
    }

    override fun launch(context: Context, packageName: String): OpResult {
        val args = PackageUtil.launchArgs(context, packageName)
            ?: return OpResult.fail("No launchable activity in $packageName")
        val result = exec(args.toTypedArray())
        return if (result.success) OpResult.OK
        else OpResult.fail("am start exited ${result.exitCode}: ${result.output}")
    }

    override fun shell(context: Context, command: Array<String>): OpResult {
        val result = exec(command)
        return if (result.success) OpResult.OK
        else OpResult.fail("exited ${result.exitCode}: ${result.output}")
    }

    override fun shellOutput(context: Context, command: Array<String>): String? =
        exec(command).takeIf { it.success }?.output

    private data class ExecResult(val exitCode: Int, val output: String) {
        val success: Boolean get() = exitCode == 0
    }

    private fun exec(command: Array<String>): ExecResult = try {
        val newProcess = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val process = newProcess.invoke(null, command, null, null)
            ?: return ExecResult(-1, "Shizuku returned no process")

        val type = process.javaClass
        val out = type.getMethod("getInputStream").invoke(process) as InputStream
        val err = type.getMethod("getErrorStream").invoke(process) as InputStream

        // Both pipes have to be drained *concurrently*, and only then may we
        // wait: reading one to EOF first deadlocks as soon as the command fills
        // the other pipe's buffer, because it blocks writing to a pipe nobody
        // is reading while we block on a stream it will never close.
        var stderr = ""
        val errorDrain = Thread { stderr = err.readTextSafely() }
        errorDrain.start()
        val stdout = out.readTextSafely()
        errorDrain.join()

        val exitCode = type.getMethod("waitFor").invoke(process) as Int
        runCatching { type.getMethod("destroy").invoke(process) }

        ExecResult(exitCode, listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString(" "))
    } catch (e: Throwable) {
        ExecResult(-1, e.cause?.message ?: e.message ?: e.javaClass.simpleName)
    }
}

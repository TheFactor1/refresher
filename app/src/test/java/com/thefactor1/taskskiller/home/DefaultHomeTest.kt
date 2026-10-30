package com.thefactor1.taskskiller.home

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.thefactor1.taskskiller.kill.KillBackend
import com.thefactor1.taskskiller.kill.OpResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DefaultHomeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pm get() = context.packageManager
    private val stock = "com.google.android.apps.tv.launcherx"
    private val other = "com.example.someapp"

    /** A shell that does what pm would, and ignores the role command (as a stubborn Google TV does). */
    private class FakeShell(private val pm: PackageManager, private val hasShell: Boolean = true) : KillBackend {
        val commands = mutableListOf<String>()
        override val id = "fake"; override val displayName = "fake"; override val rank = 0; override val isForceStop = true
        override fun isAvailable(context: Context) = hasShell
        override fun unavailableReason(context: Context) = ""
        override fun kill(context: Context, packageName: String) = OpResult(true, "")
        override fun shellOutput(context: Context, command: Array<String>): String? {
            if (!hasShell) return null
            commands += command.joinToString(" ")
            when {
                command.take(2) == listOf("pm", "disable-user") ->
                    pm.setApplicationEnabledSetting(command.last(), PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
                command.take(2) == listOf("pm", "enable") ->
                    pm.setApplicationEnabledSetting(command.last(), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0)
            }
            return ""
        }
    }

    @Before fun install() {
        listOf(stock, other).forEach { pkg ->
            shadowOf(pm).installPackage(PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply { packageName = pkg; enabled = true }
            })
        }
    }

    @Test fun onlyStockLaunchersAreTurnedOffAndRemembered() {
        val shell = FakeShell(pm)
        DefaultHome.turnOffStock(context, shell)
        assertTrue(shell.commands.contains("pm disable-user --user 0 $stock"))
        assertTrue("never anything else", shell.commands.none { it.startsWith("pm disable-user") && !it.endsWith(stock) })
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, pm.getApplicationEnabledSetting(stock))
        assertEquals(setOf(stock), HomePrefs.get(context).turnedOff)
    }

    @Test fun undoTurnsBackOnOnlyWhatRefresherTurnedOff() {
        val shell = FakeShell(pm)
        DefaultHome.turnOffStock(context, shell)
        shell.commands.clear()
        DefaultHome.undo(context, shell)
        assertEquals(listOf("pm enable --user 0 $stock"), shell.commands.filter { it.startsWith("pm ") })
        assertTrue(HomePrefs.get(context).turnedOff.isEmpty())
        assertFalse(HomePrefs.get(context).wantsDefault)
        assertTrue(DefaultHome.stockEnabled(context).contains(stock))
    }

    @Test fun withoutShizukuNothingRuns() {
        val shell = FakeShell(pm, hasShell = false)
        assertFalse(DefaultHome.makeDefault(context, shell))
        assertFalse(DefaultHome.turnOffStock(context, shell))
        assertTrue(DefaultHome.stockEnabled(context).contains(stock))
    }

    @Test fun theListIsOnlyStockLaunchers() {
        assertTrue(DefaultHome.STOCK.all { it.startsWith("com.google.android.") })
    }
}

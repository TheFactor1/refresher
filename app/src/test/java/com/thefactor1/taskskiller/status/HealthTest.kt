package com.thefactor1.taskskiller.status

import com.thefactor1.taskskiller.data.Rule
import com.thefactor1.taskskiller.data.RunLog
import com.thefactor1.taskskiller.status.Health.Fix
import com.thefactor1.taskskiller.status.Health.Headline
import com.thefactor1.taskskiller.status.Health.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every answer Home can give to "is it working?". */
class HealthTest {

    private val rule = Rule(id = 1, packageName = "com.nordvpn.android", label = "NordVPN", intervalMinutes = 360, anchorAt = 1)
    private val ok = RunLog.Entry(1_000, "NordVPN", "Refreshed via Shizuku • relaunched • returned to Home", true)

    private fun inputs(
        rules: List<Rule> = listOf(rule), master: Boolean = true, canForceStop: Boolean = true,
        installed: Boolean = true, running: Boolean = true, permitted: Boolean = true, other: Boolean = false,
        autoStart: Boolean = true, exact: Boolean = true, battery: Boolean = true, background: Boolean = true,
        log: List<RunLog.Entry> = listOf(ok)
    ) = Health.Inputs(rules, master, canForceStop, installed, running, permitted, other, autoStart, exact, battery, background, log)

    private fun eval(i: Health.Inputs) = Health.evaluate(i) { 5_000L }

    @Test fun working() {
        val r = eval(inputs())
        assertEquals(Level.OK, r.level); assertEquals(Headline.WORKING, r.headline); assertEquals(Fix.NONE, r.fix)
        assertEquals(ok, r.lastRun); assertEquals(rule, r.nextRule); assertEquals(5_000L, r.nextAt)
        assertTrue(r.checks.all { it.ok })
    }

    @Test fun noApps() = check(eval(inputs(rules = emptyList())), Level.EMPTY, Headline.NO_APPS, Fix.ADD_APP)
    @Test fun pausedAll() = check(eval(inputs(master = false)), Level.PAUSED, Headline.PAUSED_ALL, Fix.RESUME)
    @Test fun pausedEach() = check(eval(inputs(rules = listOf(rule.copy(enabled = false)))), Level.PAUSED, Headline.PAUSED_EACH, Fix.NONE)

    @Test fun shizukuMissing() = check(eval(inputs(canForceStop = false, installed = false, running = false, permitted = false)), Level.BAD, Headline.SHIZUKU_MISSING, Fix.OPEN_SETUP)
    @Test fun shizukuStopped() = check(eval(inputs(canForceStop = false, running = false, permitted = false)), Level.BAD, Headline.SHIZUKU_STOPPED, Fix.START_SHIZUKU)
    @Test fun shizukuNoPermission() = check(eval(inputs(canForceStop = false, permitted = false)), Level.BAD, Headline.SHIZUKU_NO_PERMISSION, Fix.GRANT_SHIZUKU)
    @Test fun cannotStopOtherwise() = check(eval(inputs(canForceStop = false)), Level.BAD, Headline.CANNOT_STOP, Fix.OPEN_SETUP)

    @Test fun lastRunFailed() {
        val failed = ok.copy(detail = "Refreshed via Shizuku • relaunch failed: blocked")
        check(eval(inputs(log = listOf(failed, ok))), Level.WARN, Headline.LAST_RUN_FAILED, Fix.OPEN_ACTIVITY)
    }

    @Test fun aSkipIsNotTheLastRun() {
        val skipped = ok.copy(detail = "Deferred: display is on")
        val r = eval(inputs(log = listOf(skipped, ok)))
        assertEquals(Headline.WORKING, r.headline)
        assertEquals(ok, r.lastRun)
    }

    @Test fun mightMissWhenAPermissionIsOff() {
        listOf(inputs(exact = false), inputs(battery = false), inputs(background = false), inputs(autoStart = false)).forEach {
            check(eval(it), Level.WARN, Headline.MIGHT_MISS, Fix.OPEN_SETUP)
        }
    }

    @Test fun rootOrDeviceOwnerDontNeedShizuku() {
        val r = eval(inputs(running = false, permitted = false, other = true, autoStart = false))
        assertEquals(Headline.WORKING, r.headline)
        assertTrue(r.checks.all { it.ok })
    }

    @Test fun pausedHasNoNextRun() {
        val r = eval(inputs(master = false))
        assertNull(r.nextRule)
    }

    @Test fun theNextRunIsTheSoonest() {
        val later = rule.copy(id = 2, label = "Later")
        val r = Health.evaluate(inputs(rules = listOf(later, rule))) { if (it.id == 1L) 10L else 20L }
        assertEquals(1L, r.nextRule!!.id)
    }

    @Test fun runKinds() {
        assertEquals(RunKind.RESTARTED, RunKind.of(ok))
        assertEquals(RunKind.SKIPPED, RunKind.of(ok.copy(detail = "Deferred: display is on")))
        assertEquals(RunKind.FAILED, RunKind.of(ok.copy(success = false, detail = "Refresh failed (Shizuku): denied")))
        assertEquals(RunKind.FAILED, RunKind.of(ok.copy(detail = "Refreshed via Shizuku • relaunch failed: x")))
        assertEquals(RunKind.FAILED, RunKind.of(ok.copy(success = false, detail = "Skipped: x is not installed")))
        assertEquals(listOf("Refreshed via Shizuku", "relaunched", "returned to Home"), RunKind.steps(ok))
    }

    private fun check(r: Health.Report, level: Level, headline: Headline, fix: Fix) {
        assertEquals(level, r.level); assertEquals(headline, r.headline); assertEquals(fix, r.fix)
    }
}

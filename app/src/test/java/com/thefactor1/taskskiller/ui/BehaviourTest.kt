package com.thefactor1.taskskiller.ui

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.data.RuleStore
import com.thefactor1.taskskiller.data.RunLog
import com.thefactor1.taskskiller.schedule.RestartService
import com.thefactor1.taskskiller.status.HealthReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every control does what it says, and what it changes is saved. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = TV)
class BehaviourTest {

    private val store get() = RuleStore.get(Fixture.context)

    @Before fun setUp() = Fixture.reset()
    @After fun tearDown() { HealthReader.deviceOverride = null }

    // ---------- Rule ----------

    private fun ruleScreen(id: Long = 1L): RuleEditActivity =
        Robolectric.buildActivity(RuleEditActivity::class.java, Intent(Fixture.context, RuleEditActivity::class.java)
            .putExtra(RuleEditActivity.EXTRA_RULE_ID, id)).setup().visible().get().also { idle(100) }

    @Test fun intervalStepsWithLeftAndRightAndSaves() {
        Fixture.rule(Fixture.NORD, 1, intervalMinutes = 360)
        val a = ruleScreen()
        val row = a.findViewById<View>(R.id.intervalRow)
        assertTrue(row.isFocused)
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(480, store.find(1)!!.intervalMinutes)
        assertEquals("8 hours", row.findViewById<TextView>(R.id.rowValue).text.toString())
        press(a, KeyEvent.KEYCODE_DPAD_LEFT); press(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(240, store.find(1)!!.intervalMinutes)
        // Left/right on a value row change it; they must not also move focus away.
        assertTrue(row.isFocused)
    }

    @Test fun intervalStopsAtTheEnds() {
        Fixture.rule(Fixture.NORD, 1, intervalMinutes = 1440)
        val a = ruleScreen()
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(1440, store.find(1)!!.intervalMinutes)
        assertEquals("1 day", a.findViewById<View>(R.id.intervalRow).findViewById<TextView>(R.id.rowValue).text.toString())
    }

    @Test fun okOnASwitchRowFlipsItAndSaves() {
        Fixture.rule(Fixture.NORD, 1, skip = true)
        val a = ruleScreen()
        a.findViewById<View>(R.id.asleepRow).performClick(); idle()
        assertFalse(store.find(1)!!.skipWhileScreenOn)
        a.findViewById<View>(R.id.reopenRow).performClick(); idle()
        assertFalse(store.find(1)!!.relaunch)
        // Waking the screen only means something when the app is reopened: OK does nothing now.
        a.findViewById<View>(R.id.wakeRow).performClick(); idle()
        assertFalse(store.find(1)!!.wakeScreen)
    }

    @Test fun delayStepsInHalfSeconds() {
        Fixture.rule(Fixture.NORD, 1)
        val a = ruleScreen()
        a.findViewById<View>(R.id.delayRow).requestFocus()
        press(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(2_500L, store.find(1)!!.relaunchDelayMs)
        assertEquals("2.5 seconds", a.findViewById<View>(R.id.delayRow).findViewById<TextView>(R.id.rowValue).text.toString())
    }

    @Test fun editsKeepTheLastRun() {
        val before = Fixture.rule(Fixture.NORD, 1)
        val a = ruleScreen()
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        val after = store.find(1)!!
        assertEquals(before.lastRunAt, after.lastRunAt)
        assertEquals(before.anchorAt, after.anchorAt)
    }

    @Test fun pauseAndResume() {
        Fixture.rule(Fixture.NORD, 1)
        val a = ruleScreen()
        val pause = a.findViewById<TextView>(R.id.pauseButton)
        pause.performClick(); idle()
        assertFalse(store.find(1)!!.enabled)
        assertEquals("Resume this app", pause.text.toString())
        assertEquals("Paused", a.findViewById<TextView>(R.id.statusText).text.toString())
        pause.performClick(); idle()
        assertTrue(store.find(1)!!.enabled)
    }

    @Test fun removeNeedsASecondPress() {
        Fixture.rule(Fixture.NORD, 1)
        val a = ruleScreen()
        val remove = a.findViewById<TextView>(R.id.removeButton)
        remove.requestFocus()
        remove.performClick(); idle()
        assertNotNull("one press must not delete", store.find(1))
        assertEquals("Press again to remove", remove.text.toString())
        remove.performClick(); idle()
        assertNull(store.find(1))
        assertTrue(a.isFinishing)
    }

    @Test fun removeDisarmsWhenFocusLeaves() {
        Fixture.rule(Fixture.NORD, 1)
        val a = ruleScreen()
        val remove = a.findViewById<TextView>(R.id.removeButton)
        remove.requestFocus(); remove.performClick(); idle()
        a.findViewById<View>(R.id.pauseButton).requestFocus(); idle()
        assertEquals("Remove", remove.text.toString())
        remove.requestFocus(); remove.performClick(); idle()
        assertNotNull(store.find(1))
    }

    @Test fun restartNowShowsProgressThenTheResult() {
        Fixture.rule(Fixture.NORD, 1)
        val a = ruleScreen()
        a.findViewById<View>(R.id.restartNowButton).performClick(); idle()
        val started = shadowOf(Fixture.context as android.app.Application).nextStartedService
        assertEquals(RestartService::class.java.name, started.component!!.className)
        val card = a.findViewById<View>(R.id.restartCard)
        assertTrue(card.isShown)
        assertEquals("Restarting NordVPN", a.findViewById<TextView>(R.id.restartTitle).text.toString())
        RunLog.get(Fixture.context).add("NordVPN", Fixture.OK_DETAIL, true)
        idle(1_500)
        assertEquals("Restarted", a.findViewById<TextView>(R.id.restartTitle).text.toString())
        assertEquals("· Stopped\n· Opened again\n· Back to the home screen", a.findViewById<TextView>(R.id.restartSteps).text.toString())
        idle(7_000)
        assertFalse(card.isShown)
    }

    @Test fun aMissingRuleClosesTheScreen() {
        val a = Robolectric.buildActivity(RuleEditActivity::class.java, Intent(Fixture.context, RuleEditActivity::class.java)
            .putExtra(RuleEditActivity.EXTRA_RULE_ID, 99L)).setup().get()
        assertTrue(a.isFinishing)
    }

    // ---------- Add an app ----------

    private fun picker(): AppPickerActivity {
        val a = Robolectric.buildActivity(AppPickerActivity::class.java).setup().visible().get()
        val deadline = System.currentTimeMillis() + 5_000
        while (a.findViewById<View>(R.id.loadingText).isShown && System.currentTimeMillis() < deadline) { Thread.sleep(20); idle(20) }
        idle(200)
        return a
    }

    private fun tile(a: Activity, label: String): View {
        val grid = a.findViewById<RecyclerView>(R.id.appGrid)
        return (0 until grid.childCount).map { grid.getChildAt(it) }
            .first { it.findViewById<TextView>(R.id.tileLabel)?.text?.toString() == label }
    }

    @Test fun pickingAnAppSavesItWithDefaultsAndOpensItsRule() {
        val a = picker()
        tile(a, "NordVPN").performClick(); idle()
        val rule = store.all().single()
        assertEquals("com.nordvpn.android", rule.packageName)
        assertEquals(360, rule.intervalMinutes)
        assertTrue(rule.skipWhileScreenOn && rule.relaunch && !rule.wakeScreen && rule.enabled)
        val next = shadowOf(a).nextStartedActivity
        assertEquals(RuleEditActivity::class.java.name, next.component!!.className)
        assertEquals(rule.id, next.getLongExtra(RuleEditActivity.EXTRA_RULE_ID, -1))
        assertTrue(a.isFinishing)
    }

    @Test fun pickingAnAppAlreadyAddedOpensItsRuleInsteadOfADuplicate() {
        Fixture.rule(Fixture.NORD, 7)
        val a = picker()
        val t = tile(a, "NordVPN")
        assertEquals("Already added", t.findViewById<TextView>(R.id.tileNote).text.toString())
        t.performClick(); idle()
        assertEquals(1, store.all().size)
        assertEquals(7L, shadowOf(a).nextStartedActivity.getLongExtra(RuleEditActivity.EXTRA_RULE_ID, -1))
    }

    @Test fun suggestionsComeFirstAndSystemAppsAreHidden() {
        val a = picker()
        val grid = a.findViewById<RecyclerView>(R.id.appGrid)
        assertEquals("Often needs a restart", (grid.getChildAt(0) as TextView).text.toString())
        assertEquals("NordVPN", grid.getChildAt(1).findViewById<TextView>(R.id.tileLabel).text.toString())
        val labels = (0 until grid.adapter!!.itemCount).mapNotNull { grid.findViewHolderForAdapterPosition(it)?.itemView?.findViewById<TextView>(R.id.tileLabel)?.text?.toString() }
        assertFalse("system app without a launcher entry is hidden", "Settings" in labels)
        a.findViewById<View>(R.id.systemToggle).performClick(); idle(200)
        assertEquals("Hide system apps", a.findViewById<TextView>(R.id.systemToggle).text.toString())
    }

    // ---------- Home ----------

    private fun home(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().get().also { idle() }

    @Test fun homeSaysWorkingAndFocusesTheFirstApp() {
        Fixture.rule(Fixture.NORD, 1)
        Fixture.typicalLog()
        val a = home()
        assertEquals("Everything's working", a.findViewById<TextView>(R.id.headline).text.toString())
        assertTrue(a.findViewById<TextView>(R.id.subline).text.toString().startsWith("Last restart "))
        assertFalse(a.findViewById<View>(R.id.fixRow).isShown)
        assertTrue(a.findViewById<RecyclerView>(R.id.appsRecycler).getChildAt(0).isFocused)
    }

    @Test fun resumeFixTurnsRestartsBackOn() {
        Fixture.rule(Fixture.NORD, 1)
        store.masterEnabled = false
        val a = home()
        assertEquals("Restarts are paused", a.findViewById<TextView>(R.id.headline).text.toString())
        val fix = a.findViewById<TextView>(R.id.fixButton)
        assertTrue(fix.isFocused)
        fix.performClick(); idle()
        assertTrue(store.masterEnabled)
        assertEquals("Everything's working", a.findViewById<TextView>(R.id.headline).text.toString())
        assertFalse(a.findViewById<View>(R.id.fixRow).isShown)
        assertNotNull("focus moves on when the fix disappears", a.window.decorView.findFocus())
    }

    @Test fun cardsOpenTheirRuleAndAddOpensThePicker() {
        Fixture.rule(Fixture.NORD, 1)
        val a = home()
        val cards = a.findViewById<RecyclerView>(R.id.appsRecycler)
        cards.getChildAt(0).performClick()
        assertEquals(1L, shadowOf(a).nextStartedActivity.getLongExtra(RuleEditActivity.EXTRA_RULE_ID, -1))
        cards.getChildAt(1).performClick()
        assertEquals(AppPickerActivity::class.java.name, shadowOf(a).nextStartedActivity.component!!.className)
    }

    @Test fun playOnACardRestartsIt() {
        Fixture.rule(Fixture.NORD, 1)
        val a = home()
        a.findViewById<RecyclerView>(R.id.appsRecycler).getChildAt(0).requestFocus()
        press(a, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        val started = shadowOf(Fixture.context as android.app.Application).nextStartedService
        assertEquals(RestartService::class.java.name, started.component!!.className)
        assertTrue(a.findViewById<View>(R.id.restartCard).isShown)
    }

    @Test fun chipsOpenSetupAndActivity() {
        Fixture.rule(Fixture.NORD, 1)
        val a = home()
        val chips = a.findViewById<ViewGroup>(R.id.chips)
        assertEquals(6, chips.childCount)
        chips.getChildAt(0).performClick()
        assertEquals(SetupActivity::class.java.name, shadowOf(a).nextStartedActivity.component!!.className)
        chips.getChildAt(5).performClick()
        assertEquals(LogActivity::class.java.name, shadowOf(a).nextStartedActivity.component!!.className)
    }

    @Test fun aFailedLastRunIsTheHeadline() {
        Fixture.rule(Fixture.NORD, 1, lastResult = Fixture.FAIL_DETAIL)
        Fixture.log(Fixture.LogEntry(30 * Fixture.MIN, "NordVPN", Fixture.FAIL_DETAIL, true))
        val a = home()
        assertEquals("The last restart didn't work", a.findViewById<TextView>(R.id.headline).text.toString())
        assertTrue(a.findViewById<TextView>(R.id.subline).text.toString().endsWith("Couldn't open it again: activity start was blocked"))
        assertEquals("See what happened", a.findViewById<TextView>(R.id.fixButton).text.toString())
    }

    // ---------- Setup ----------

    @Test fun setupRowsDoTheirJob() {
        Fixture.rule(Fixture.NORD, 1)
        val a = Robolectric.buildActivity(SetupActivity::class.java).setup().visible().get().also { idle() }
        a.findViewById<View>(R.id.pauseAllRow).performClick(); idle()
        assertFalse(store.masterEnabled)
        a.findViewById<View>(R.id.pauseAllRow).performClick(); idle()
        assertTrue(store.masterEnabled)
        val before = store.autoStartShizuku
        a.findViewById<View>(R.id.autoStartRow).performClick(); idle()
        assertEquals(!before, store.autoStartShizuku)
    }

    @Test fun setupShowsTheFixOnlyOnFailingChecks() {
        HealthReader.deviceOverride = { Fixture.healthy().copy(batteryExempt = false) }
        val a = Robolectric.buildActivity(SetupActivity::class.java).setup().visible().get().also { idle() }
        assertTrue(a.findViewById<View>(R.id.batteryRow).findViewById<View>(R.id.checkAction).isShown)
        assertEquals("Battery saving can stop Refresher", a.findViewById<View>(R.id.batteryRow).findViewById<TextView>(R.id.rowTitle).text.toString())
        assertFalse(a.findViewById<View>(R.id.exactRow).findViewById<View>(R.id.checkAction).isShown)
        assertTrue("a failing check focuses Set up automatically", a.findViewById<View>(R.id.autoSetupButton).isFocused)
    }

    // ---------- Activity ----------

    @Test fun activityShowsTheFocusedRunsSteps() {
        Fixture.typicalLog()
        val a = Robolectric.buildActivity(LogActivity::class.java).setup().visible().get().also { idle(300) }
        val steps = a.findViewById<ViewGroup>(R.id.detailSteps)
        assertEquals(3, steps.childCount)
        assertEquals("Restarted", a.findViewById<TextView>(R.id.detailResult).text.toString())
        press(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Skipped because the screen was on", a.findViewById<TextView>(R.id.detailResult).text.toString())
        a.findViewById<View>(R.id.clearButton).performClick(); idle()
        assertTrue(RunLog.get(Fixture.context).entries().isEmpty())
        assertFalse(a.findViewById<View>(R.id.detailPanel).isShown)
        assertTrue(a.findViewById<View>(R.id.emptyText).isShown)
    }

    // ---------- helpers ----------

    private fun press(a: Activity, key: Int) = Remote.press(a, key)

    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
}

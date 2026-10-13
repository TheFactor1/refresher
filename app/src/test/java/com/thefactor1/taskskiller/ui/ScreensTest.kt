package com.thefactor1.taskskiller.ui

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.takahirom.roborazzi.captureRoboImage
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.data.RuleStore
import com.thefactor1.taskskiller.status.HealthReader
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every screen, at TV size (960×540 dp: a 1080p Shield at xhdpi, or a 720p TV),
 * at the default text size and at the TV's largest. Each one is checked for
 * margins (overscan-safe area, shared gutters), alignment, cut-off text and
 * overlaps, and photographed when run with -Pshots.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = TV)
class ScreensTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "text {0}")
        fun scales() = listOf(arrayOf<Any>(1.0f), arrayOf<Any>(1.3f))
    }

    private val gutter = 56

    @Before
    fun setUp() {
        RuntimeEnvironment.setFontScale(fontScale)
        Fixture.reset()
    }

    @After
    fun tearDown() {
        HealthReader.deviceOverride = null
    }

    private fun tag(name: String) = if (fontScale == 1f) name else "$name-large"

    // ---------- Home ----------

    @Test fun homeWorking() {
        Fixture.rule(Fixture.NORD, 1)
        Fixture.typicalLog()
        val a = launch(MainActivity::class.java)
        checkHome(a, "home-working")
    }

    @Test fun homeSeveralApps() {
        Fixture.rule(Fixture.NORD, 1)
        Fixture.rule(Fixture.TIVI, 2, intervalMinutes = 720, lastRunAgo = null)
        Fixture.rule(Fixture.PLEX, 3, enabled = false)
        Fixture.rule(Fixture.LONG, 4, intervalMinutes = 90, skip = false, lastResult = Fixture.FAIL_DETAIL)
        Fixture.log(Fixture.LogEntry(40 * Fixture.MIN, Fixture.LONG.label, Fixture.FAIL_DETAIL, true), Fixture.LogEntry(5 * Fixture.HOUR + 12 * Fixture.MIN, Fixture.NORD.label))
        val a = launch(MainActivity::class.java)
        checkHome(a, "home-several", allowEllipsis = { it.id == R.id.cardLabel && it.text.startsWith("A Remarkably") })
    }

    @Test fun homeShizukuStopped() {
        HealthReader.deviceOverride = { Fixture.shizukuStopped() }
        Fixture.rule(Fixture.NORD, 1)
        Fixture.typicalLog()
        val a = launch(MainActivity::class.java)
        assertTrue("fix button shows", a.findViewById<View>(R.id.fixRow).isShown)
        assertTrue("fix button has focus", a.findViewById<View>(R.id.fixButton).isFocused)
        checkHome(a, "home-shizuku-stopped")
    }

    @Test fun homeMightMiss() {
        HealthReader.deviceOverride = { Fixture.healthy().copy(batteryExempt = false, exactAlarms = false) }
        Fixture.rule(Fixture.NORD, 1)
        Fixture.typicalLog()
        checkHome(launch(MainActivity::class.java), "home-might-miss")
    }

    @Test fun homeEmpty() {
        checkHome(launch(MainActivity::class.java), "home-empty")
    }

    @Test fun homePaused() {
        Fixture.rule(Fixture.NORD, 1)
        RuleStore.get(Fixture.context).masterEnabled = false
        checkHome(launch(MainActivity::class.java), "home-paused")
    }

    private fun checkHome(a: Activity, name: String, allowEllipsis: (TextView) -> Boolean = { false }) {
        val root = a.findViewById<View>(R.id.root)
        val cards = a.findViewById<RecyclerView>(R.id.appsRecycler)
        val problems = common(root, allowEllipsis) +
            LayoutCheck.leftAt(a.findViewById(R.id.statusBadge), gutter) +
            LayoutCheck.sameLeft("home column", a.findViewById(R.id.statusBadge), a.findViewById(R.id.appsHeader),
                cards.getChildAt(0), a.findViewById(R.id.needsHeader), a.findViewById<android.view.ViewGroup>(R.id.chips).getChildAt(0)) +
            LayoutCheck.sameHeight("app cards", (0 until cards.childCount).map { cards.getChildAt(it) }) +
            (if (a.findViewById<View>(R.id.fixRow).isShown) LayoutCheck.sameLeft("headline and fix", a.findViewById(R.id.headline), a.findViewById(R.id.fixButton)) else emptyList())
        shoot(a, name)
        assertNoProblems(name, problems)
    }

    // ---------- Rule ----------

    @Test fun rule() {
        val r = Fixture.rule(Fixture.NORD, 1)
        Fixture.typicalLog()
        val a = launch(RuleEditActivity::class.java) { putExtra(RuleEditActivity.EXTRA_RULE_ID, r.id) }
        checkRule(a, "rule")
    }

    @Test fun ruleNotReopened() {
        val r = Fixture.rule(Fixture.NORD, 1, relaunch = false, enabled = false, lastRunAgo = null)
        val a = launch(RuleEditActivity::class.java) { putExtra(RuleEditActivity.EXTRA_RULE_ID, r.id) }
        checkRule(a, "rule-paused")
    }

    private fun checkRule(a: Activity, name: String) {
        val root = a.findViewById<View>(R.id.root)
        val rows = listOf(R.id.intervalRow, R.id.asleepRow, R.id.reopenRow, R.id.delayRow, R.id.wakeRow).map { a.findViewById<View>(it) }
        val buttons = listOf(R.id.restartNowButton, R.id.pauseButton, R.id.removeButton).map { a.findViewById<View>(it) }
        val problems = common(root) +
            LayoutCheck.leftAt(a.findViewById(R.id.appIcon), gutter) +
            LayoutCheck.sameLeft("rows", *rows.toTypedArray()) + LayoutCheck.leftAt(rows[0], gutter) +
            LayoutCheck.sameRight("rows", *rows.toTypedArray()) +
            LayoutCheck.sameLeft("buttons", *buttons.toTypedArray()) + LayoutCheck.sameRight("buttons", *buttons.toTypedArray()) +
            LayoutCheck.sameLeft("status over buttons", a.findViewById(R.id.statusDot), buttons[0]) +
            rightAtGutter(buttons[0])
        assertTrue("first row focused", rows[0].isFocused)
        shoot(a, name)
        assertNoProblems(name, problems)
    }

    // ---------- Add an app ----------

    @Test fun picker() {
        Fixture.rule(Fixture.NORD, 1)
        val a = launch(AppPickerActivity::class.java)
        waitFor { !a.findViewById<View>(R.id.loadingText).isShown }
        val grid = a.findViewById<RecyclerView>(R.id.appGrid)
        val tiles = (0 until grid.childCount).map { grid.getChildAt(it) }.filter { it.id == View.NO_ID && it !is TextView }
        val problems = common(a.window.decorView.findViewById(android.R.id.content)) { it.id == R.id.tileLabel && it.lineCount == 2 } +
            LayoutCheck.leftAt(tiles.first(), gutter) +
            LayoutCheck.sameHeight("tiles", tiles) +
            rightAtGutter(a.findViewById(R.id.systemToggle))
        shoot(a, "picker")
        assertNoProblems("picker", problems)
    }

    // ---------- Activity ----------

    @Test fun activity() {
        Fixture.rule(Fixture.NORD, 1)
        Fixture.log(
            Fixture.LogEntry(40 * Fixture.MIN, Fixture.TIVI.label, Fixture.FAIL_DETAIL, true),
            Fixture.LogEntry(5 * Fixture.HOUR + 12 * Fixture.MIN, Fixture.NORD.label),
            Fixture.LogEntry(11 * Fixture.HOUR + 12 * Fixture.MIN, Fixture.NORD.label, "Deferred: display is on", true),
            Fixture.LogEntry(17 * Fixture.HOUR + 12 * Fixture.MIN, Fixture.NORD.label),
            Fixture.LogEntry(23 * Fixture.HOUR + 12 * Fixture.MIN, Fixture.NORD.label),
            Fixture.LogEntry(29 * Fixture.HOUR + 12 * Fixture.MIN, Fixture.NORD.label, "Deferred: display is on", true),
            Fixture.LogEntry(35 * Fixture.HOUR + 12 * Fixture.MIN, Fixture.NORD.label)
        )
        val a = launch(LogActivity::class.java)
        idleFor(400)
        val list = a.findViewById<RecyclerView>(R.id.logList)
        val rows = (0 until list.childCount).map { list.getChildAt(it) }.filter { it !is TextView }
        val heads = (0 until list.childCount).map { list.getChildAt(it) }.filterIsInstance<TextView>()
        val problems = common(a.window.decorView.findViewById(android.R.id.content)) +
            LayoutCheck.sameLeft("activity column", *(rows + heads).toTypedArray()) +
            LayoutCheck.leftAt(rows.first(), gutter) +
            LayoutCheck.sameRight("rows", *rows.toTypedArray()) +
            LayoutCheck.sameLeft("run text", *rows.map { it.findViewById<View>(R.id.logText) }.toTypedArray()) +
            LayoutCheck.sameLeft("run dots", *rows.map { it.findViewById<View>(R.id.logDot) }.toTypedArray()) +
            rightAtGutter(a.findViewById(R.id.detailPanel)) + rightAtGutter(a.findViewById(R.id.clearButton))
        shoot(a, "activity")
        assertNoProblems("activity", problems)
    }

    @Test fun activityEmpty() {
        val a = launch(LogActivity::class.java)
        shoot(a, "activity-empty")
        assertNoProblems("activity-empty", common(a.window.decorView.findViewById(android.R.id.content)))
    }

    // ---------- Setup ----------

    @Test fun setupHealthy() = checkSetup("setup-healthy")

    @Test fun setupNeedsFixing() {
        HealthReader.deviceOverride = { Fixture.shizukuStopped().copy(batteryExempt = false) }
        checkSetup("setup-needs-fixing")
    }

    private fun checkSetup(name: String) {
        Fixture.rule(Fixture.NORD, 1)
        val a = launch(SetupActivity::class.java)
        val rows = listOf(R.id.shizukuRow, R.id.autoStartRow, R.id.exactRow, R.id.batteryRow, R.id.backgroundRow, R.id.pauseAllRow).map { a.findViewById<View>(it) }
        val problems = common(a.window.decorView.findViewById(android.R.id.content)) +
            LayoutCheck.sameLeft("setup rows", *rows.toTypedArray()) + LayoutCheck.leftAt(rows.first(), gutter) +
            LayoutCheck.sameRight("setup rows", *rows.toTypedArray()) + rightAtGutter(rows.first()) +
            rightAtGutter(a.findViewById(R.id.autoCard))
        shoot(a, name)
        assertNoProblems(name, problems)
    }

    // ---------- helpers ----------

    private fun common(root: View, allowEllipsis: (TextView) -> Boolean = { false }) =
        LayoutCheck.safeArea(root) + LayoutCheck.textFits(root, allowEllipsis) + LayoutCheck.noOverlaps(root)

    private fun rightAtGutter(v: View): List<String> {
        val screen = v.rootView.width
        val right = LayoutCheck.leftOf(v) + v.width
        val expected = screen - Math.round(gutter * v.resources.displayMetrics.density)
        return if (kotlin.math.abs(right - expected) <= 1) emptyList()
        else listOf("${LayoutCheck.describe(v)} ends at ${right}px, not the right gutter (${expected}px)")
    }

    private fun <T : Activity> launch(cls: Class<T>, extras: Intent.() -> Unit = {}): T {
        val intent = Intent(Fixture.context, cls).apply(extras)
        val a = Robolectric.buildActivity(cls, intent).setup().visible().get()
        idleFor(100)
        return a
    }

    /** For screens that load on a background thread. */
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            idleFor(20)
        }
        idleFor(200)
    }

    private fun idleFor(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
    }

    private fun shoot(a: Activity, name: String) {
        if (System.getProperty("roborazzi.test.record") == "true") {
            a.window.decorView.captureRoboImage("build/outputs/roborazzi/${tag(name)}.png")
        }
    }

    private fun assertNoProblems(name: String, problems: List<String>) {
        assertTrue("${tag(name)}:\n  " + problems.joinToString("\n  "), problems.isEmpty())
    }
}

/** 960×540 dp TV, landscape, xhdpi (a 1080p Shield). */
const val TV = "w960dp-h540dp-land-television-xhdpi"

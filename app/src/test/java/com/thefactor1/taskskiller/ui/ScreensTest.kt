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

    // ---------- System ----------

    private fun systemSnapshot(struggling: Boolean) = com.thefactor1.taskskiller.status.SystemStats.Snapshot(
        memTotalKb = 3016708, memAvailableKb = if (struggling) 782972 else 1149184,
        swapTotalKb = 524284, swapFreeKb = if (struggling) 78816 else 400000,
        load1 = if (struggling) 5.2 else 1.4, cores = 4, cpuBusyPct = if (struggling) 81 else 9,
        storageFreeBytes = 3_600_000_000, storageTotalBytes = 12_000_000_000,
        apps = listOf(
            com.thefactor1.taskskiller.status.SystemStats.AppUse(Fixture.TIVI.pkg, 436_337, 63.0),
            com.thefactor1.taskskiller.status.SystemStats.AppUse(Fixture.PLEX.pkg, 294_912, 38.0),
            com.thefactor1.taskskiller.status.SystemStats.AppUse(Fixture.NORD.pkg, 147_330, 13.0),
            com.thefactor1.taskskiller.status.SystemStats.AppUse(Fixture.PROJECTIVY.pkg, 124_967, 0.4),
            com.thefactor1.taskskiller.status.SystemStats.AppUse(Fixture.KODI.pkg, 118_356, null),
            com.thefactor1.taskskiller.status.SystemStats.AppUse(Fixture.YOUTUBE.pkg, 85_106, 2.0)
        ),
        onScreen = Fixture.TIVI.pkg
    )

    /** A realistic hour: steady until a spike 18 min ago (a big app named), a 9-minute sleep gap, then recovery. */
    private fun systemHour(struggling: Boolean): List<com.thefactor1.taskskiller.status.SystemHistory.Sample> {
        val now = System.currentTimeMillis()
        return (0..59).filter { it !in 30..38 }.map { m ->
            val minAgo = 59 - m
            val spike = struggling && minAgo in 14..22
            com.thefactor1.taskskiller.status.SystemHistory.Sample(
                at = now - minAgo * 60_000L,
                memUsedPct = if (spike) 88 + (minAgo % 3) else 58 + (m % 5),
                swapUsedPct = if (spike) 85 else 30,
                cpuPct = if (spike) 70 + (m % 7) * 3 else 8 + (m % 6) * 2,
                hog = if (spike && minAgo == 18) Fixture.PLEX.pkg else null,
                hogKb = if (spike && minAgo == 18) 294_912L else null)
        }
    }

    @Test fun systemStruggling() = checkSystem("system-struggling", struggling = true)

    @Test fun systemSmooth() = checkSystem("system-smooth", struggling = false)

    private fun checkSystem(name: String, struggling: Boolean) {
        com.thefactor1.taskskiller.status.SystemReader.override = { systemSnapshot(struggling) }
        com.thefactor1.taskskiller.status.SystemHistory.override = { systemHour(struggling) }
        try {
            val a = launch(SystemActivity::class.java)
            idleFor(400)
            val list = a.findViewById<RecyclerView>(R.id.appList)
            val rows = (0 until list.childCount).map { list.getChildAt(it) }
            val meters = listOf(R.id.memMeter, R.id.swapMeter, R.id.cpuMeter, R.id.storageMeter).map { a.findViewById<View>(it) }
            val problems = common(a.window.decorView.findViewById(android.R.id.content)) +
                LayoutCheck.leftAt(a.findViewById(R.id.appsHeader), gutter) +
                LayoutCheck.sameLeft("system column", a.findViewById(R.id.appsHeader), *rows.toTypedArray()) +
                LayoutCheck.sameRight("app rows", *rows.toTypedArray()) +
                LayoutCheck.sameHeight("app rows", rows) +
                LayoutCheck.sameLeft("meters", *meters.toTypedArray()) +
                LayoutCheck.sameRight("meters", *meters.toTypedArray()) +
                rightAtGutter(a.findViewById(R.id.meterPanel)) + rightAtGutter(a.findViewById(R.id.systemVerdict)) +
                (if (rows.size < 3) listOf("only ${rows.size} app rows fit on screen") else emptyList()) +
                LayoutCheck.sameLeft("history and list", a.findViewById(R.id.historyCard), a.findViewById(R.id.appsHeader)) +
                LayoutCheck.sameRight("history and rows", a.findViewById(R.id.historyCard), *rows.toTypedArray()) +
                (if (rows.firstOrNull()?.hasFocus() != true) listOf("first app row is not focused") else emptyList())
            shoot(a, name)
            assertNoProblems(name, problems)
        } finally {
            com.thefactor1.taskskiller.status.SystemReader.override = null
            com.thefactor1.taskskiller.status.SystemHistory.override = null
        }
    }

    // ---------- Home (the launcher) ----------

    @Test fun launcherHome() {
        HomeActivity.meminfo = { "MemTotal: 2000000 kB\nMemAvailable: 820000 kB\n" }
        com.thefactor1.taskskiller.home.HomePrefs.get(RuntimeEnvironment.getApplication()).favourites =
            listOf(Fixture.TIVI.pkg, Fixture.PLEX.pkg, Fixture.YOUTUBE.pkg)
        try {
            val a = launch(HomeActivity::class.java)
            idleFor(600)
            val dock = a.findViewById<android.view.ViewGroup>(R.id.dockApps)
            val cards = (0 until dock.childCount).map { dock.getChildAt(it).findViewById<View>(R.id.tileCard) }
            val chips = listOf(R.id.healthChip, R.id.cleanChip, R.id.settingsChip).map { a.findViewById<View>(it) }
            val meters = listOf(R.id.memMeter, R.id.cpuMeter, R.id.diskMeter).map { a.findViewById<View>(it) }
            val problems = common(a.window.decorView.findViewById(android.R.id.content), allowEllipsis = { it.id == R.id.tileIconName }) +
                LayoutCheck.leftAt(a.findViewById(R.id.homeGreeting), gutter) +
                LayoutCheck.sameHeight("dock tiles", cards) +
                LayoutCheck.sameHeight("chips", chips) +
                LayoutCheck.sameHeight("meters", meters) +
                rightAtGutter(a.findViewById(R.id.settingsChip)) +
                centred(a, a.findViewById(R.id.homeClock)) + centred(a, a.findViewById(R.id.dock)) + centred(a, a.findViewById(R.id.meters)) +
                (if (cards.size != 3) listOf("dock shows ${cards.size} favourites, not 3") else emptyList()) +
                (if (cards.firstOrNull()?.hasFocus() != true) listOf("first favourite is not focused") else emptyList()) +
                liftedInside(a, cards, 1.08f, a.findViewById(R.id.dock)) +
                liftedInside(a, listOf(a.findViewById(R.id.allApps)), 1.08f, a.findViewById(R.id.dock))
            shoot(a, "launcher-home")
            assertNoProblems("launcher-home", problems)
        } finally {
            HomeActivity.meminfo = { java.io.File("/proc/meminfo").readText() }
        }
    }

    @Test fun launcherAllApps() {
        com.thefactor1.taskskiller.home.HomePrefs.get(RuntimeEnvironment.getApplication()).favourites = listOf(Fixture.PLEX.pkg)
        val a = launch(AllAppsActivity::class.java)
        idleFor(600)
        val grid = a.findViewById<RecyclerView>(R.id.appsGrid)
        val cards = (0 until grid.childCount).map { grid.getChildAt(it).findViewById<View>(R.id.tileCard) }
        val area = a.findViewById<View>(R.id.appsArea)
        val row = grid.getChildAt(0)?.height ?: 0
        val problems = common(a.window.decorView.findViewById(android.R.id.content), allowEllipsis = { it.id == R.id.tileIconName }) +
            LayoutCheck.leftAt(a.findViewById(R.id.appsTitle), gutter) + rightAtGutter(a.findViewById(R.id.appsCount)) +
            LayoutCheck.sameHeight("app tiles", cards) +
            (if (row == 0 || grid.height % row != 0) listOf("grid ${grid.height}px is not whole rows of ${row}px") else emptyList()) +
            (if (grid.height > area.height) listOf("grid is taller than its space") else emptyList()) +
            (if (cards.firstOrNull()?.hasFocus() != true) listOf("first app is not focused") else emptyList()) +
            liftedInside(a, cards, 1.08f, grid)
        shoot(a, "launcher-all-apps")
        assertNoProblems("launcher-all-apps", problems)
    }

    /** [v] sits in the middle of the screen, to within a pixel or two. */
    private fun centred(a: android.app.Activity, v: View): List<String> {
        val p = IntArray(2).also { v.getLocationOnScreen(it) }
        val mid = p[0] + v.width / 2; val screen = a.window.decorView.width / 2
        return if (kotlin.math.abs(mid - screen) > 2) listOf("${a.resources.getResourceEntryName(v.id)} is off-centre by ${mid - screen}px") else emptyList()
    }

    @Test fun launcherSettings() {
        val a = launch(HomeSettingsActivity::class.java)
        val rows = listOf(R.id.tilesRow, R.id.clockRow, R.id.healthRow, R.id.cleanRow, R.id.skipFavsRow).map { a.findViewById<View>(it) }
        val problems = common(a.window.decorView.findViewById(android.R.id.content)) +
            LayoutCheck.sameLeft("settings rows", *rows.toTypedArray()) + LayoutCheck.leftAt(rows.first(), gutter) +
            LayoutCheck.sameRight("settings rows", *rows.toTypedArray()) + rightAtGutter(rows.first())
        shoot(a, "launcher-settings")
        assertNoProblems("launcher-settings", problems)
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

    /** A focused tile grows by [scale]: even then it must stay inside the screen's safe area and its grid (never cut off). */
    private fun liftedInside(a: android.app.Activity, cards: List<View>, scale: Float, container: View): List<String> {
        val root = a.window.decorView
        val d = root.resources.displayMetrics.density
        val safeX = root.width * 0.05f
        val grid = container
        val g = IntArray(2).also { grid.getLocationOnScreen(it) }
        return cards.flatMap { c ->
            // From the parent, so a tile that's already lifted isn't counted twice.
            val p = IntArray(2).also { (c.parent as View).getLocationOnScreen(it) }.let { intArrayOf(it[0] + c.left, it[1] + c.top) }
            val dx = c.width * (scale - 1) / 2; val dy = c.height * (scale - 1) / 2
            val l = p[0] - dx; val r = p[0] + c.width + dx; val t = p[1] - dy; val b = p[1] + c.height + dy
            val name = c.contentDescription
            listOfNotNull(
                if (l < safeX || r > root.width - safeX) "$name lifted spans ${(l / d).toInt()}–${(r / d).toInt()}dp, outside the side safe area" else null,
                if (l < g[0] || r > g[0] + grid.width || t < g[1] || b > g[1] + grid.height) "$name lifted is clipped by its container" else null
            )
        }
    }

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

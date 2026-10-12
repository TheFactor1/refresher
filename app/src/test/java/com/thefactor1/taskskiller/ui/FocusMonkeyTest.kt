package com.thefactor1.taskskiller.ui

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.status.HealthReader
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * The bug test for the remote: hundreds of random D-pad presses on every screen.
 * Nothing may crash, focus must never be lost (a TV with nothing focused is
 * stuck), and focus must stay on screen and inside the safe area.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = TV)
class FocusMonkeyTest {

    private val arrows = intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

    @Before fun setUp() {
        Fixture.reset()
        Fixture.rule(Fixture.NORD, 1)
        Fixture.rule(Fixture.TIVI, 2, lastRunAgo = null)
        Fixture.rule(Fixture.PLEX, 3, enabled = false)
        Fixture.typicalLog()
    }

    @After fun tearDown() { HealthReader.deviceOverride = null }

    @Test fun home() = monkey(MainActivity::class.java)
    @Test fun homeBroken() { HealthReader.deviceOverride = { Fixture.shizukuStopped() }; monkey(MainActivity::class.java) }
    @Test fun rule() = monkey(RuleEditActivity::class.java) { putExtra(RuleEditActivity.EXTRA_RULE_ID, 1L) }
    @Test fun picker() = monkey(AppPickerActivity::class.java, settleMs = 1_500)
    @Test fun activity() = monkey(LogActivity::class.java)
    @Test fun setup() = monkey(SetupActivity::class.java)
    @Test fun setupBroken() { HealthReader.deviceOverride = { Fixture.shizukuStopped().copy(exactAlarms = false) }; monkey(SetupActivity::class.java) }

    private fun <T : Activity> monkey(cls: Class<T>, settleMs: Long = 200, extras: Intent.() -> Unit = {}) {
        val a = Robolectric.buildActivity(cls, Intent(Fixture.context, cls).apply(extras)).setup().visible().get()
        settle(settleMs)
        val rng = Random(cls.name.hashCode())
        val root = a.window.decorView
        val content = a.findViewById<View>(android.R.id.content)
        val visited = HashSet<Int>()
        repeat(PRESSES) { i ->
            val key = arrows[rng.nextInt(arrows.size)]
            Remote.press(a, key, idleMs = 300)
            val focused = root.findFocus()
            assertNotNull("${cls.simpleName}: focus lost after press $i (${KeyEvent.keyCodeToString(key)})", focused)
            assertTrue("${cls.simpleName}: focused ${LayoutCheck.describe(focused!!)} is not on screen", focused.isShown)
            val r = android.graphics.Rect()
            assertTrue("${cls.simpleName}: focused ${LayoutCheck.describe(focused)} is scrolled off screen", focused.getGlobalVisibleRect(r))
            val w = content.width
            assertTrue("${cls.simpleName}: focused ${LayoutCheck.describe(focused)} pokes past the side ($r, width $w)", r.left >= 0 && r.right <= w)
            visited += System.identityHashCode(focused)
        }
        // A screen whose focus never moved would pass vacuously.
        assertTrue("${cls.simpleName}: focus only ever reached ${visited.size} place(s)", visited.size >= 2)
        if (cls == RuleEditActivity::class.java) assertTrue(a.findViewById<View>(R.id.intervalRow).isShown)
    }

    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
        }
    }

    private companion object {
        const val PRESSES = 300
    }
}

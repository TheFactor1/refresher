package com.thefactor1.taskskiller.ui

import android.app.Activity
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import org.robolectric.Shadows.shadowOf

/**
 * A remote control for tests. Robolectric hands keys to the activity, but on a
 * device an arrow nobody consumed then moves focus (ViewRootImpl's focus
 * navigation: the focused view's focusSearch, then requestFocus). This does both.
 */
object Remote {
    fun press(a: Activity, key: Int, idleMs: Long = 50) {
        val down = KeyEvent(KeyEvent.ACTION_DOWN, key)
        val handled = a.dispatchKeyEvent(down)
        if (!handled) {
            val dir = when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                else -> null
            }
            if (dir != null) {
                val focused = a.window.decorView.findFocus()
                val next = focused?.focusSearch(dir)
                if (next != null && next !== focused) next.requestFocus(dir)
            }
        }
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(idleMs))
    }
}

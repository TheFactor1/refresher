package com.thefactor1.taskskiller.schedule

/**
 * A scheduled run never stops the app someone is watching: with the display on and that app in front, it waits
 * and tries again. "Run now" (manual) always goes ahead.
 */
object OnScreenGuard {
    fun shouldDefer(manual: Boolean, displayOn: Boolean, onScreen: String?, target: String): Boolean =
        !manual && displayOn && onScreen != null && onScreen == target
}

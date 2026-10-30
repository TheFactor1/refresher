package com.thefactor1.taskskiller.schedule

/**
 * A scheduled run never stops the app someone is watching or listening to: with the display on and that app in
 * front, or with it playing in the background (music), it waits and tries again. "Run now" (manual) always goes ahead.
 */
object OnScreenGuard {
    fun shouldDefer(manual: Boolean, displayOn: Boolean, onScreen: String?, target: String, playing: Set<String> = emptySet()): Boolean =
        !manual && ((displayOn && onScreen != null && onScreen == target) || target in playing)
}

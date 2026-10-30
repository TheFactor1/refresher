package com.thefactor1.taskskiller.ui

import android.view.View

/**
 * How a focused app tile looks: it lifts toward the viewer with a soft glow in the theme's colour (the white
 * edge comes from the tile's foreground). The tile is clipped to its rounded card, so the banner fills it cleanly.
 */
object TileFocus {
    private const val LIFT = 1.1f
    private const val MS = 150L

    fun setUp(card: View, glow: Int) {
        card.clipToOutline = true
        card.outlineSpotShadowColor = glow
        card.outlineAmbientShadowColor = glow
    }

    /** Lifts [v] by [scale]; past the standard lift it also rises a little, like the dock's selected app. */
    fun lift(v: View, up: Boolean, scale: Float = LIFT) {
        val d = v.resources.displayMetrics.density
        val rise = if (scale > LIFT) -6 * d else 0f
        v.animate().scaleX(if (up) scale else 1f).scaleY(if (up) scale else 1f).translationZ(if (up) 16 * d else 0f)
            .translationY(if (up) rise else 0f).setDuration(MS).start()
    }
}

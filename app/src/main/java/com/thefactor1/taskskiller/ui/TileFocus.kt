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

    fun lift(v: View, up: Boolean) {
        val z = v.resources.displayMetrics.density * 14
        v.animate().scaleX(if (up) LIFT else 1f).scaleY(if (up) LIFT else 1f).translationZ(if (up) z else 0f).setDuration(MS).start()
    }
}

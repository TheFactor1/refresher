package com.thefactor1.taskskiller.home

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

/** Home's colour themes: a deep base, two soft glows (top left, bottom right) and one accent. */
enum class HomeTheme(val base: Int, val glowA: Int, val glowB: Int, val accent: Int) {
    MIDNIGHT(0xFF070B14.toInt(), 0x4D466EFF, 0x33965AFF, 0xFF8AB4FF.toInt()),
    AURORA(0xFF04100E.toInt(), 0x4228C8AA, 0x2E3C78FF, 0xFF7FE3C8.toInt()),
    EMBER(0xFF110A08.toInt(), 0x3DFF8C46, 0x2EC83C64, 0xFFFFB98F.toInt());

    /** The accent at [alpha], for the clock's glow. */
    fun accentAt(alpha: Int) = Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent))

    /** The whole backdrop: three cheap layers, drawn once (no images, no blur). */
    fun backdrop(width: Int): Drawable {
        fun glow(color: Int, cx: Float, cy: Float) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(cx, cy)
            gradientRadius = width * 0.62f
            colors = intArrayOf(color, Color.TRANSPARENT)
        }
        return LayerDrawable(arrayOf(ColorDrawable(base), glow(glowA, 0.18f, 0f), glow(glowB, 1f, 1f)))
    }

    companion object {
        fun of(t: HomePrefs.Theme) = when (t) {
            HomePrefs.Theme.MIDNIGHT -> MIDNIGHT
            HomePrefs.Theme.AURORA -> AURORA
            HomePrefs.Theme.EMBER -> EMBER
        }
    }
}

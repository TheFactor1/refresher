package com.thefactor1.taskskiller.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.min

/** The ring around Home's clock, in the selected app's colour, with a soft glow. The clock sits inside it. */
class HaloView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private val d = resources.displayMetrics.density
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 9f * d }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d }

    var color: Int = 0xFF8AB4FF.toInt()
        set(v) { field = v; invalidate() }

    init { setWillNotDraw(false) }

    override fun onDraw(c: Canvas) {
        // Same size as before 0.7.3 (when a dot's glow set the inset); well clear of the glow being clipped.
        val r = min(width, height) / 2f - 13f * d
        val cx = width / 2f; val cy = height / 2f
        glow.color = Color.argb(0x38, Color.red(color), Color.green(color), Color.blue(color))
        ring.color = color
        c.drawCircle(cx, cy, r, glow)
        c.drawCircle(cx, cy, r, ring)
    }
}

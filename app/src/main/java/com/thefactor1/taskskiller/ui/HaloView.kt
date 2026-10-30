package com.thefactor1.taskskiller.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The ring around Home's clock: how much memory is free, as an arc with a glowing dot at its end, in the selected
 * app's colour (amber or red when memory is short). The clock sits inside it. Brightens when focused (OK opens System).
 */
class HaloView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0x1AFFFFFF }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 9f * d; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dotGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private var shown = 0f
    private var anim: ValueAnimator? = null

    /** Free memory, 0..1. */
    var fraction = 0f
        private set

    var color: Int = 0xFF8AB4FF.toInt()
        set(v) { field = v; invalidate() }

    init { isFocusable = true; isClickable = true; setWillNotDraw(false) }

    /** Sets the arc, sweeping to it the first time and easing after that. */
    fun setFraction(f: Float, animate: Boolean) {
        fraction = f.coerceIn(0f, 1f)
        anim?.cancel()
        if (!animate) { shown = fraction; invalidate(); return }
        anim = ValueAnimator.ofFloat(shown, fraction).apply {
            duration = 700
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val pad = 8f * d
        val r = min(width, height) / 2f - pad
        val cx = width / 2f; val cy = height / 2f
        box.set(cx - r, cy - r, cx + r, cy + r)
        track.color = if (isFocused) 0x59FFFFFF else 0x1AFFFFFF
        track.strokeWidth = (if (isFocused) 2.5f else 1.5f) * d
        c.drawCircle(cx, cy, r, track)
        val sweep = 360f * shown
        if (sweep <= 0f) return
        glow.color = Color.argb(0x38, Color.red(color), Color.green(color), Color.blue(color))
        arc.color = color
        c.drawArc(box, -90f, sweep, false, glow)
        c.drawArc(box, -90f, sweep, false, arc)
        val a = Math.toRadians((-90f + sweep).toDouble())
        val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat()
        dotGlow.color = Color.argb(0x55, Color.red(color), Color.green(color), Color.blue(color))
        c.drawCircle(x, y, 11f * d, dotGlow)
        c.drawCircle(x, y, 4.5f * d, dot)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}

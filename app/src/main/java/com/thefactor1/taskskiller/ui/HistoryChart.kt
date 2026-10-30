package com.thefactor1.taskskiller.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.status.SystemHistory

/**
 * The last hour as two flat lines: memory in use (white, softly filled) and processor busy (green), 0-100% over
 * 60 minutes, with faint guides at 50% and 100%. A gap in the readings (the TV asleep) breaks the line.
 */
class HistoryChart @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var samples: List<SystemHistory.Sample> = emptyList()
    private var now: Long = System.currentTimeMillis()
    private val d = resources.displayMetrics.density

    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.hairline); strokeWidth = 1f * d; style = Paint.Style.STROKE
    }
    private val memLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary); strokeWidth = 2f * d; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val memFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary); alpha = 28; style = Paint.Style.FILL
    }
    private val cpuLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ok); strokeWidth = 2f * d; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    fun show(list: List<SystemHistory.Sample>, at: Long) {
        samples = list; now = at
        contentDescription = if (list.isEmpty()) null else context.getString(R.string.system_history_desc, list.size)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val top = 2f * d; val bottom = h - 2f * d
        fun y(pct: Int) = bottom - (bottom - top) * pct.coerceIn(0, 100) / 100f
        fun x(t: Long) = w * (1f - (now - t).toFloat() / SystemHistory.WINDOW_MS).coerceIn(0f, 1f)
        canvas.drawLine(0f, y(100), w, y(100), guide)
        canvas.drawLine(0f, y(50), w, y(50), guide)
        canvas.drawLine(0f, bottom, w, bottom, guide)
        if (samples.isEmpty()) return

        // Runs of readings with no gap over 3 minutes: a sleeping TV leaves a break, not a slope.
        val runs = mutableListOf<MutableList<SystemHistory.Sample>>()
        samples.forEach { s -> if (runs.isEmpty() || s.at - runs.last().last().at > GAP_MS) runs += mutableListOf(s) else runs.last() += s }
        for (run in runs) {
            if (run.size == 1) {
                canvas.drawCircle(x(run[0].at), y(run[0].memUsedPct), 2f * d, memLine)
                continue
            }
            val fill = Path().apply {
                moveTo(x(run.first().at), bottom)
                run.forEach { lineTo(x(it.at), y(it.memUsedPct)) }
                lineTo(x(run.last().at), bottom); close()
            }
            canvas.drawPath(fill, memFill)
            canvas.drawPath(Path().apply { run.forEachIndexed { i, s -> if (i == 0) moveTo(x(s.at), y(s.memUsedPct)) else lineTo(x(s.at), y(s.memUsedPct)) } }, memLine)
            val cpu = Path(); var open = false
            run.forEach { s ->
                val c = s.cpuPct
                if (c == null) { open = false } else if (!open) { cpu.moveTo(x(s.at), y(c)); open = true } else cpu.lineTo(x(s.at), y(c))
            }
            canvas.drawPath(cpu, cpuLine)
        }
    }

    companion object {
        private const val GAP_MS = 3 * 60_000L
    }
}

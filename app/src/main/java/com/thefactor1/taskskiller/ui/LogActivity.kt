package com.thefactor1.taskskiller.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.data.RunLog
import com.thefactor1.taskskiller.databinding.ActivityLogBinding
import com.thefactor1.taskskiller.databinding.ItemLogRowBinding
import com.thefactor1.taskskiller.databinding.ViewStepBinding
import com.thefactor1.taskskiller.status.RunKind
import com.thefactor1.taskskiller.status.StepWords
import com.thefactor1.taskskiller.util.Format
import java.util.Date

/**
 * Activity: one line per run, grouped by day, in plain words. The run with
 * focus shows its steps in the panel on the right.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private val adapter = LogListAdapter(::showDetail)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.logList.layoutManager = CenteringLayoutManager(this)
        binding.logList.adapter = adapter
        binding.logList.itemAnimator = null
        binding.logList.isFocusable = false
        binding.clearButton.setOnClickListener {
            RunLog.get(this).clear()
            load()
            binding.clearButton.requestFocus()
        }
        load()
        binding.logList.post {
            binding.logList.findViewHolderForAdapterPosition(adapter.firstRun())?.itemView?.requestFocus()
                ?: binding.clearButton.requestFocus()
        }
    }

    private fun load() {
        val entries = RunLog.get(this).entries()
        adapter.submit(LogGroups.group(entries, System.currentTimeMillis()) { dayLabel(it) })
        val empty = entries.isEmpty()
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        binding.detailPanel.visibility = if (empty) View.INVISIBLE else View.VISIBLE
        binding.clearButton.visibility = if (empty) View.GONE else View.VISIBLE
        entries.firstOrNull()?.let(::showDetail)
    }

    private fun dayLabel(millis: Long): String = when (Format.dayOffset(millis)) {
        0 -> getString(R.string.activity_today)
        -1 -> getString(R.string.activity_yesterday)
        else -> DateFormat.getLongDateFormat(this).format(Date(millis))
    }

    private fun showDetail(entry: RunLog.Entry) {
        val time = DateFormat.getTimeFormat(this).format(Date(entry.timestamp))
        binding.detailTitle.text = getString(R.string.detail_title, time, entry.label)
        val kind = RunKind.of(entry)
        binding.detailResult.text = getString(
            when (kind) {
                RunKind.RESTARTED -> R.string.restart_done
                RunKind.SKIPPED -> R.string.detail_skipped
                RunKind.FAILED -> R.string.restart_failed
            }
        )
        binding.detailSteps.removeAllViews()
        RunKind.steps(entry).forEach { step ->
            val row = ViewStepBinding.inflate(LayoutInflater.from(this), binding.detailSteps, true)
            row.stepText.text = StepWords.friendly(step)
            val color = when {
                StepWords.isProblem(step) -> R.color.bad
                kind == RunKind.SKIPPED -> R.color.idle
                else -> R.color.ok
            }
            row.stepDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
        }
    }

    class LogListAdapter(private val onFocus: (RunLog.Entry) -> Unit) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<LogGroups.Item> = emptyList()

        fun submit(items: List<LogGroups.Item>) {
            this.items = items
            notifyDataSetChanged()
        }

        fun firstRun() = items.indexOfFirst { it is LogGroups.Item.Run }.coerceAtLeast(0)

        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (items[position] is LogGroups.Item.Day) DAY else RUN

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == DAY) {
                object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_log_day, parent, false)) {}
            } else {
                RunHolder(ItemLogRowBinding.inflate(inflater, parent, false)).also {
                    // Every row's time column is as wide as the widest time ("12:59 PM"), so the
                    // dots and text line up whatever the hour and the TV's text size.
                    val t = it.binding.logTime
                    t.minWidth = WIDEST_TIMES.maxOf { ms -> t.paint.measureText(DateFormat.getTimeFormat(parent.context).format(Date(ms))) }
                        .let { w -> Math.ceil(w.toDouble()).toInt() + t.paddingStart + t.paddingEnd }
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is LogGroups.Item.Day -> (holder.itemView as TextView).text = item.label
                is LogGroups.Item.Run -> {
                    val b = (holder as RunHolder).binding
                    val context = b.root.context
                    val entry = item.entry
                    val kind = RunKind.of(entry)
                    b.logTime.text = DateFormat.getTimeFormat(context).format(Date(entry.timestamp))
                    b.logText.text = context.getString(
                        when (kind) {
                            RunKind.RESTARTED -> R.string.activity_restarted
                            RunKind.SKIPPED -> R.string.activity_skipped
                            RunKind.FAILED -> R.string.activity_failed
                        },
                        entry.label
                    )
                    b.logNote.text = if (kind == RunKind.SKIPPED) context.getString(R.string.activity_skipped_why) else ""
                    val color = when (kind) {
                        RunKind.RESTARTED -> R.color.ok_deep
                        RunKind.SKIPPED -> R.color.idle
                        RunKind.FAILED -> R.color.bad_deep
                    }
                    b.logDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, color))
                    b.root.setOnFocusChangeListener { _, focused -> if (focused) onFocus(entry) }
                }
            }
        }

        class RunHolder(val binding: ItemLogRowBinding) : RecyclerView.ViewHolder(binding.root)

        private companion object {
            const val DAY = 0
            const val RUN = 1
            /** 12:58 and 23:58 today: the widest the clock gets in 12 h and 24 h formats. */
            val WIDEST_TIMES: List<Long> = listOf(12, 23).map { h ->
                java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, 58) }.timeInMillis
            }
        }
    }
}

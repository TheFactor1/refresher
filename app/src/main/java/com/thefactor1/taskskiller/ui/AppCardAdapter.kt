package com.thefactor1.taskskiller.ui

import android.content.res.ColorStateList
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.data.Rule
import com.thefactor1.taskskiller.databinding.ItemAppCardBinding
import com.thefactor1.taskskiller.schedule.RestartScheduler
import com.thefactor1.taskskiller.status.RunKind
import com.thefactor1.taskskiller.util.Format
import com.thefactor1.taskskiller.util.PackageUtil

/** Home's row of apps, ending in "Add an app". */
class AppCardAdapter(
    private val onOpen: (Rule) -> Unit,
    private val onAdd: () -> Unit,
    private val onRestart: (Rule) -> Unit
) : RecyclerView.Adapter<AppCardAdapter.ViewHolder>() {

    private var rules: List<Rule> = emptyList()
    private var masterEnabled = true
    /** Whether each rule's latest run (by label) failed, from the log. */
    private var failed: Set<String> = emptySet()

    init {
        setHasStableIds(true)
    }

    fun submit(rules: List<Rule>, masterEnabled: Boolean, failedLabels: Set<String>) {
        if (rules == this.rules && masterEnabled == this.masterEnabled && failedLabels == failed) {
            notifyItemRangeChanged(0, rules.size) // countdowns only
            return
        }
        this.rules = rules
        this.masterEnabled = masterEnabled
        this.failed = failedLabels
        notifyDataSetChanged()
    }

    override fun getItemCount() = rules.size + 1
    override fun getItemId(position: Int) = if (position < rules.size) rules[position].id else ADD_ID

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemAppCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val b = holder.binding
        val context = b.root.context
        if (position >= rules.size) {
            // Same three lines as a rule's card, so the row stays one height.
            b.root.setBackgroundResource(R.drawable.bg_card_add)
            b.cardIcon.setImageResource(R.drawable.ic_status_add)
            b.cardIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_secondary))
            b.cardLabel.text = context.getString(R.string.add_app)
            b.cardSchedule.text = context.getString(R.string.add_app_hint)
            b.cardStatusRow.visibility = View.INVISIBLE
            b.root.contentDescription = context.getString(R.string.add_app)
            b.root.setOnClickListener { onAdd() }
            b.root.setOnKeyListener(null)
            return
        }

        val rule = rules[position]
        val now = System.currentTimeMillis()
        b.root.setBackgroundResource(R.drawable.bg_card)
        b.cardIcon.imageTintList = null
        b.cardIcon.setImageDrawable(PackageUtil.icon(context, rule.packageName))
        b.cardLabel.text = rule.label
        val every = Format.intervalWords(rule.intervalMinutes)
        b.cardSchedule.text = context.getString(
            if (rule.skipWhileScreenOn) R.string.card_every_asleep else R.string.card_every, every
        )
        b.cardStatusRow.visibility = View.VISIBLE

        val live = masterEnabled && rule.enabled
        val (text, color) = when {
            !live -> context.getString(R.string.card_paused) to R.color.idle
            rule.label in failed && rule.lastRunAt > 0 ->
                context.getString(R.string.card_failed, Format.moment(context, rule.lastRunAt, now)) to R.color.bad
            rule.skipWhileScreenOn && RestartScheduler.isDue(rule, now) ->
                context.getString(R.string.card_waiting) to R.color.warn
            rule.lastRunAt > 0 ->
                context.getString(R.string.card_restarted, Format.moment(context, rule.lastRunAt, now)) to R.color.ok
            else ->
                context.getString(R.string.card_not_run, Format.moment(context, RestartScheduler.nextRunAt(rule), now)) to R.color.text_secondary
        }
        b.cardStatus.text = text
        val c = ContextCompat.getColor(context, color)
        b.cardStatus.setTextColor(c)
        b.cardDot.backgroundTintList = ColorStateList.valueOf(c)
        b.root.contentDescription = "${rule.label}. ${b.cardSchedule.text}. $text"
        b.root.setOnClickListener { onOpen(rule) }
        // The remote's play key restarts the focused app without opening it.
        b.root.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode in PLAY_KEYS) {
                onRestart(rule); true
            } else {
                false
            }
        }
    }

    class ViewHolder(val binding: ItemAppCardBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val ADD_ID = -1L
        val PLAY_KEYS = setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY)
    }
}

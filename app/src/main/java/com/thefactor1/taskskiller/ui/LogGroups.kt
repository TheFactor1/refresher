package com.thefactor1.taskskiller.ui

import com.thefactor1.taskskiller.data.RunLog
import com.thefactor1.taskskiller.util.Format

/** Activity's list: a heading for each day, then that day's runs, newest first. */
object LogGroups {

    sealed class Item {
        data class Day(val label: String) : Item()
        data class Run(val entry: RunLog.Entry) : Item()
    }

    fun group(entries: List<RunLog.Entry>, now: Long, dayLabel: (Long) -> String): List<Item> {
        val out = mutableListOf<Item>()
        var lastDay: Int? = null
        entries.sortedByDescending { it.timestamp }.forEach { entry ->
            val day = Format.dayOffset(entry.timestamp, now)
            if (day != lastDay) {
                out += Item.Day(dayLabel(entry.timestamp))
                lastDay = day
            }
            out += Item.Run(entry)
        }
        return out
    }
}

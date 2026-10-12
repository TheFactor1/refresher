package com.thefactor1.taskskiller.util

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import java.util.Date

object Format {

    /** "45 min", "2 h", "1 h 30 min". */
    fun interval(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> "$minutes min"
            rest == 0 -> "$hours h"
            else -> "$hours h $rest min"
        }
    }

    /** Clock time for today, date + time for anything further out. */
    fun timestamp(context: Context, millis: Long): String {
        if (millis <= 0L) return "—"
        val date = Date(millis)
        return if (DateUtils.isToday(millis)) {
            DateFormat.getTimeFormat(context).format(date)
        } else {
            "${DateFormat.getDateFormat(context).format(date)} ${DateFormat.getTimeFormat(context).format(date)}"
        }
    }

    /** "5 minutes", "1 hour", "1 hour 30 minutes", "6 hours", "1 day": the rule screen and cards. */
    fun intervalWords(minutes: Int): String {
        if (minutes > 0 && minutes % (24 * 60) == 0) return plural(minutes / (24 * 60), "day")
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> plural(minutes, "minute")
            rest == 0 -> plural(hours, "hour")
            else -> "${plural(hours, "hour")} ${plural(rest, "minute")}"
        }
    }

    /** "No wait", "3 seconds", "1.5 seconds". */
    fun seconds(millis: Long): String {
        if (millis <= 0L) return "No wait"
        val whole = millis % 1000L == 0L
        val value = if (whole) (millis / 1000L).toString() else String.format(java.util.Locale.US, "%.1f", millis / 1000.0)
        return if (value == "1") "1 second" else "$value seconds"
    }

    /**
     * A moment near now, as people say it: "15:02" today, "yesterday 21:02",
     * "tomorrow 03:02", otherwise the date. The clock follows the TV's 12/24 h setting.
     */
    fun moment(context: Context, millis: Long, now: Long = System.currentTimeMillis()): String {
        if (millis <= 0L) return "—"
        val time = DateFormat.getTimeFormat(context).format(Date(millis))
        return when (dayOffset(millis, now)) {
            0 -> time
            -1 -> "yesterday $time"
            1 -> "tomorrow $time"
            else -> "${DateFormat.getDateFormat(context).format(Date(millis))} $time"
        }
    }

    /** Whole calendar days from [now]'s day to [millis]'s day, in the TV's time zone. */
    fun dayOffset(millis: Long, now: Long = System.currentTimeMillis()): Int {
        val a = java.util.Calendar.getInstance().apply { timeInMillis = now; truncate() }
        val b = java.util.Calendar.getInstance().apply { timeInMillis = millis; truncate() }
        return Math.round((b.timeInMillis - a.timeInMillis) / 86_400_000.0).toInt()
    }

    private fun java.util.Calendar.truncate() {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }

    private fun plural(n: Int, unit: String) = if (n == 1) "1 $unit" else "$n ${unit}s"

    fun relative(millis: Long): String =
        if (millis <= 0L) "—"
        else DateUtils.getRelativeTimeSpanString(
            millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        ).toString()
}

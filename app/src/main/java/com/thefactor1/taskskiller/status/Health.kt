package com.thefactor1.taskskiller.status

import com.thefactor1.taskskiller.data.Rule
import com.thefactor1.taskskiller.data.RunLog

/**
 * The answer to "is it working?", which is the first thing every screen shows.
 *
 * Pure: it takes a snapshot of the device ([Inputs]) and returns what to say, so
 * every state the home screen can be in is covered by plain JVM tests. Reading
 * the snapshot from the device is [HealthReader]'s job.
 */
object Health {

    enum class Level { OK, WARN, BAD, PAUSED, EMPTY }

    /** What the one button under the headline does. */
    enum class Fix { NONE, ADD_APP, RESUME, START_SHIZUKU, GRANT_SHIZUKU, OPEN_SETUP, OPEN_ACTIVITY }

    /** One of the things Refresher depends on, shown as a chip on Home and a row in Setup. */
    enum class CheckId { SHIZUKU, AUTO_START, EXACT_ALARMS, BATTERY, BACKGROUND_START }

    data class Check(val id: CheckId, val ok: Boolean)

    data class Inputs(
        val rules: List<Rule>,
        val masterEnabled: Boolean,
        /** The kill method in use can force-stop other apps (Shizuku, device owner or root). */
        val canForceStop: Boolean,
        val shizukuInstalled: Boolean,
        val shizukuRunning: Boolean,
        val shizukuPermitted: Boolean,
        /** Force-stop comes from device owner or root, so Shizuku is not needed at all. */
        val forceStopWithoutShizuku: Boolean,
        val autoStartShizuku: Boolean,
        val exactAlarms: Boolean,
        val batteryExempt: Boolean,
        /** False when relaunches from the background are likely to be dropped. */
        val backgroundStarts: Boolean,
        /** Newest first, as [RunLog.entries] returns them. */
        val log: List<RunLog.Entry>
    )

    data class Report(
        val level: Level,
        /** "Everything's working", "Restarts are paused"… */
        val headline: Headline,
        val fix: Fix,
        val checks: List<Check>,
        /** The run the headline is about, when it is about one. */
        val lastRun: RunLog.Entry?,
        /** The rule that runs next, and when. */
        val nextRule: Rule?,
        val nextAt: Long
    )

    /** Which sentence to show; the wording itself lives in string resources. */
    enum class Headline {
        WORKING, NO_APPS, PAUSED_ALL, PAUSED_EACH, CANNOT_STOP, SHIZUKU_MISSING, SHIZUKU_STOPPED,
        SHIZUKU_NO_PERMISSION, LAST_RUN_FAILED, MIGHT_MISS
    }

    fun evaluate(inputs: Inputs, nextRunAt: (Rule) -> Long): Report {
        val checks = checks(inputs)
        val enabled = inputs.rules.filter { it.enabled }
        val next = if (inputs.masterEnabled) enabled.minByOrNull(nextRunAt) else null
        val nextAt = next?.let(nextRunAt) ?: 0L
        val lastRun = inputs.log.firstOrNull { RunKind.of(it) != RunKind.SKIPPED }

        fun report(level: Level, headline: Headline, fix: Fix, run: RunLog.Entry? = lastRun) =
            Report(level, headline, fix, checks, run, next, nextAt)

        return when {
            inputs.rules.isEmpty() -> report(Level.EMPTY, Headline.NO_APPS, Fix.ADD_APP, null)
            !inputs.masterEnabled -> report(Level.PAUSED, Headline.PAUSED_ALL, Fix.RESUME)
            enabled.isEmpty() -> report(Level.PAUSED, Headline.PAUSED_EACH, Fix.NONE)
            !inputs.canForceStop -> when {
                !inputs.shizukuInstalled -> report(Level.BAD, Headline.SHIZUKU_MISSING, Fix.OPEN_SETUP)
                !inputs.shizukuRunning -> report(Level.BAD, Headline.SHIZUKU_STOPPED, Fix.START_SHIZUKU)
                !inputs.shizukuPermitted -> report(Level.BAD, Headline.SHIZUKU_NO_PERMISSION, Fix.GRANT_SHIZUKU)
                else -> report(Level.BAD, Headline.CANNOT_STOP, Fix.OPEN_SETUP)
            }
            lastRun != null && RunKind.of(lastRun) == RunKind.FAILED ->
                report(Level.WARN, Headline.LAST_RUN_FAILED, Fix.OPEN_ACTIVITY)
            checks.any { !it.ok } -> report(Level.WARN, Headline.MIGHT_MISS, Fix.OPEN_SETUP)
            else -> report(Level.OK, Headline.WORKING, Fix.NONE)
        }
    }

    /**
     * Auto-start only matters while Shizuku is the thing doing the stopping;
     * with device owner or root it is reported as fine rather than nagging.
     */
    fun checks(inputs: Inputs): List<Check> = listOf(
        Check(CheckId.SHIZUKU, inputs.forceStopWithoutShizuku || (inputs.shizukuRunning && inputs.shizukuPermitted)),
        Check(CheckId.AUTO_START, inputs.forceStopWithoutShizuku || inputs.autoStartShizuku),
        Check(CheckId.EXACT_ALARMS, inputs.exactAlarms),
        Check(CheckId.BATTERY, inputs.batteryExempt),
        Check(CheckId.BACKGROUND_START, inputs.backgroundStarts)
    )
}

/** What a log entry was, read from what [com.thefactor1.taskskiller.schedule.RestartService] wrote. */
enum class RunKind {
    RESTARTED, SKIPPED, FAILED;

    companion object {
        fun of(entry: RunLog.Entry): RunKind = when {
            entry.detail.startsWith("Deferred") -> SKIPPED
            !entry.success -> FAILED
            entry.detail.contains("failed", ignoreCase = true) -> FAILED
            else -> RESTARTED
        }

        /**
         * The run's steps, in order: the service joins them with " • "
         * ("Refreshed via Shizuku • relaunched • returned to Home").
         */
        fun steps(entry: RunLog.Entry): List<String> =
            entry.detail.split(" • ").map { it.trim() }.filter { it.isNotEmpty() }
    }
}

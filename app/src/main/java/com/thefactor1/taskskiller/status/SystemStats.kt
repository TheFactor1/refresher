package com.thefactor1.taskskiller.status

/**
 * What the System screen shows: memory, swap, processor and storage, plus the apps using the most memory. All
 * parsing is pure so it can be tested against real Shield output; SystemReader does the reading.
 */
object SystemStats {

    enum class Level { OK, WARN, BAD }

    data class Snapshot(
        val memTotalKb: Long,
        val memAvailableKb: Long,
        val swapTotalKb: Long,
        val swapFreeKb: Long,
        val load1: Double,
        val cores: Int,
        /** Share of the last interval the processors were busy, 0..100; null until two samples exist. */
        val cpuBusyPct: Int?,
        val storageFreeBytes: Long,
        val storageTotalBytes: Long,
        val apps: List<AppUse>,
        val onScreen: String?
    ) {
        val memUsedPct: Int get() = pct(memTotalKb - memAvailableKb, memTotalKb)
        val swapUsedPct: Int get() = pct(swapTotalKb - swapFreeKb, swapTotalKb)
        val storageUsedPct: Int get() = pct(storageTotalBytes - storageFreeBytes, storageTotalBytes)

        val memLevel: Level get() = when {
            memAvailableKb * 100 < memTotalKb * 12 -> Level.BAD
            memAvailableKb * 100 < memTotalKb * 30 -> Level.WARN
            else -> Level.OK
        }
        val swapLevel: Level get() = when {
            swapTotalKb == 0L -> Level.OK
            swapUsedPct >= 80 -> Level.BAD
            swapUsedPct >= 50 -> Level.WARN
            else -> Level.OK
        }
        val cpuLevel: Level get() {
            val perCore = load1 / cores.coerceAtLeast(1)
            return when {
                perCore >= 1.2 -> Level.BAD
                perCore >= 0.7 -> Level.WARN
                else -> Level.OK
            }
        }
        val storageLevel: Level get() = when {
            storageTotalBytes == 0L -> Level.OK
            storageFreeBytes * 100 < storageTotalBytes * 5 -> Level.BAD
            storageFreeBytes * 100 < storageTotalBytes * 15 -> Level.WARN
            else -> Level.OK
        }
        /** The worst of the four. */
        val overall: Level get() = listOf(memLevel, swapLevel, cpuLevel, storageLevel).maxBy { it.ordinal }
    }

    data class AppUse(val packageName: String, val pssKb: Long, val cpuPct: Double?)

    /** Two readings of the first /proc/stat line. */
    data class CpuTimes(val total: Long, val idle: Long)

    private fun pct(part: Long, whole: Long): Int = if (whole <= 0) 0 else ((part * 100) / whole).toInt().coerceIn(0, 100)

    /** /proc/meminfo: key -> kB. */
    fun parseMeminfo(text: String): Map<String, Long> =
        Regex("""^(\w+):\s+(\d+) kB""", RegexOption.MULTILINE).findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2].toLong() }

    /** /proc/loadavg: the one-minute load. */
    fun parseLoad(text: String): Double? = text.trim().split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull()

    /** The first line of /proc/stat ("cpu  user nice system idle iowait irq softirq ..."). */
    fun parseCpuTimes(text: String): CpuTimes? {
        val line = text.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
        val n = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (n.size < 5) return null
        return CpuTimes(total = n.sum(), idle = n[3] + n[4])
    }

    /** Busy share between two /proc/stat readings, or null if they don't move forward. */
    fun busyPct(before: CpuTimes?, after: CpuTimes?): Int? {
        if (before == null || after == null) return null
        val total = after.total - before.total
        val idle = after.idle - before.idle
        if (total <= 0) return null
        return (((total - idle) * 100) / total).toInt().coerceIn(0, 100)
    }

    private val PSS = Regex("""^\s*([\d,]+)K: ([A-Za-z][\w.]*\.[\w.]+)(?::[\w.:]+)? \(pid""", RegexOption.MULTILINE)
    private val CPU = Regex("""^\s*([\d.]+)% \d+/([A-Za-z][\w.]*\.[\w.]+)(?::[\w.:]+)?:""", RegexOption.MULTILINE)
    /** System and hardware processes that look like package names but are not apps someone can stop. */
    private val NOT_APPS = listOf("android.hardware.", "vendor.", "android.process.", "com.android.systemui", "com.android.phone")

    private fun isApp(name: String) = NOT_APPS.none { name.startsWith(it) }

    /** "Total PSS by process" from `dumpsys meminfo`: package -> kB, a package's processes added together. */
    fun parsePss(text: String): Map<String, Long> {
        val out = linkedMapOf<String, Long>()
        val section = text.substringAfter("Total PSS by process", text).substringBefore("Total PSS by OOM")
        for (m in PSS.findAll(section)) {
            val pkg = m.groupValues[2]
            if (!isApp(pkg)) continue
            out[pkg] = (out[pkg] ?: 0L) + m.groupValues[1].replace(",", "").toLong()
        }
        return out
    }

    /** `dumpsys cpuinfo`: package -> recent CPU %, a package's processes added together. */
    fun parseCpuinfo(text: String): Map<String, Double> {
        val out = linkedMapOf<String, Double>()
        for (m in CPU.findAll(text)) {
            val pkg = m.groupValues[2]
            if (!isApp(pkg)) continue
            out[pkg] = (out[pkg] ?: 0.0) + (m.groupValues[1].toDoubleOrNull() ?: 0.0)
        }
        return out
    }

    /** The apps using the most memory, biggest first, with their CPU where known; [hidden] are left out. */
    fun topApps(pss: Map<String, Long>, cpu: Map<String, Double>, hidden: Set<String>, limit: Int = 8): List<AppUse> =
        pss.filterKeys { it !in hidden }.entries.sortedByDescending { it.value }.take(limit)
            .map { AppUse(it.key, it.value, cpu[it.key]) }

    /** "1.1 GB" / "288 MB". */
    fun size(kb: Long): String = if (kb >= 1024 * 1024) String.format(java.util.Locale.US, "%.1f GB", kb / 1048576.0)
        else "${(kb + 512) / 1024} MB"
}

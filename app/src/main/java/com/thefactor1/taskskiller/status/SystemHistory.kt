package com.thefactor1.taskskiller.status

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The System screen's "Last hour": one reading a minute while the TV is awake, kept for an hour in one small file on
 * the TV and pruned as it goes. Nothing leaves the TV.
 */
object SystemHistory {

    data class Sample(
        val at: Long,
        val memUsedPct: Int,
        val swapUsedPct: Int,
        /** Processor busy 0..100 since the previous reading; null without Shizuku or a previous reading. */
        val cpuPct: Int?,
        /** The biggest background app at this moment, read only when memory was amber or red. */
        val hog: String? = null,
        val hogKb: Long? = null
    )

    /** The peak worth naming under the chart. */
    data class Peak(val sample: Sample, val memory: Boolean)

    const val WINDOW_MS = 60 * 60_000L
    private const val FILE = "system_history.json"

    /** Tests: fixed samples instead of the file. */
    var override: (() -> List<Sample>)? = null

    fun prune(samples: List<Sample>, now: Long): List<Sample> =
        samples.filter { it.at in (now - WINDOW_MS)..now }.sortedBy { it.at }

    /** The worst moment of the hour: memory first (what slows a TV most), then the processor; null if all was fine. */
    fun peak(samples: List<Sample>): Peak? {
        val mem = samples.maxByOrNull { it.memUsedPct }
        if (mem != null && mem.memUsedPct >= MEM_NOTEWORTHY) return Peak(mem, memory = true)
        val cpu = samples.filter { it.cpuPct != null }.maxByOrNull { it.cpuPct!! }
        if (cpu != null && cpu.cpuPct!! >= CPU_NOTEWORTHY) return Peak(cpu, memory = false)
        return null
    }

    /** The app named near a moment: the nearest reading (within 5 min) that recorded one. */
    fun hogNear(samples: List<Sample>, at: Long): Sample? =
        samples.filter { it.hog != null && kotlin.math.abs(it.at - at) <= 5 * 60_000L }.minByOrNull { kotlin.math.abs(it.at - at) }

    fun load(context: Context, now: Long = System.currentTimeMillis()): List<Sample> {
        override?.let { return prune(it(), now) }
        return prune(read(file(context)), now)
    }

    fun append(context: Context, sample: Sample) {
        val f = file(context)
        val kept = prune(read(f) + sample, sample.at)
        val arr = JSONArray()
        kept.forEach { s ->
            arr.put(JSONObject().apply {
                put("t", s.at); put("m", s.memUsedPct); put("s", s.swapUsedPct)
                s.cpuPct?.let { put("c", it) }
                s.hog?.let { put("h", it) }
                s.hogKb?.let { put("hk", it) }
            })
        }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(f)
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun read(f: File): List<Sample> = try {
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Sample(o.getLong("t"), o.getInt("m"), o.getInt("s"),
                if (o.has("c")) o.getInt("c") else null,
                if (o.has("h")) o.getString("h") else null,
                if (o.has("hk")) o.getLong("hk") else null)
        }
    } catch (e: Exception) {
        emptyList()
    }

    private const val MEM_NOTEWORTHY = 70
    private const val CPU_NOTEWORTHY = 70
}

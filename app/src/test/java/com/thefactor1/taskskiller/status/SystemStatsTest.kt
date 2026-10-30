package com.thefactor1.taskskiller.status

import com.thefactor1.taskskiller.schedule.OnScreenGuard
import com.thefactor1.taskskiller.status.SystemStats.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The System screen's parsing, against output in the exact shape a Shield (Android 11) prints; app names made up. */
class SystemStatsTest {

    private val meminfo = """
        MemTotal:        3016708 kB
        MemFree:          292860 kB
        MemAvailable:    1013944 kB
        Buffers:            7104 kB
        Cached:           749652 kB
        SwapCached:        23800 kB
        SwapTotal:        524284 kB
        SwapFree:          87904 kB
    """.trimIndent()

    private val pss = """
        Total PSS by process:
            436,337K: com.example.tvplayer (pid 4892 state 2 oom 0 / activities)
            175,006K: system (pid 3706 state 0 oom -900)
            147,330K: com.example.vpn (pid 29601 state 4 oom 100)
            124,501K: vendor.nvidia.hardware.graphics.composer@2.0-service (pid 3385)
            118,356K: com.example.streams (pid 7040 state 18 oom 900)
            110,272K: com.example.streams:server_process (pid 7161 state 4 oom 200)
             80,491K: surfaceflinger (pid 3413)
             45,636K: com.android.systemui (pid 3910 state 0 oom -800)
             12,004K: android.hardware.audio@6.0-service-msd (pid 3355)
              9,120K: moe.shizuku.privileged.api (pid 801 state 4 oom 200)
        Total PSS by OOM adjustment:
            999,999K: com.example.notcounted (pid 1 state 0 oom 0)
    """.trimIndent()

    private val cpuinfo = """
        Load: 2.98 / 3.1 / 2.75
        CPU usage from 116676ms to 2934ms ago (2026-10-30 13:17:44.705 to 2026-10-30 13:19:38.447):
          63% 4892/com.example.tvplayer: 50% user + 12% kernel / faults: 304781 minor 141 major
          18% 3355/android.hardware.audio@6.0-service-msd: 17% user + 1.3% kernel / faults: 927 minor
          13% 29601/com.example.vpn: 3.8% user + 9.6% kernel / faults: 44107 minor 27 major
          2.5% 7161/com.example.streams:server_process: 2% user + 0.5% kernel
          1.5% 7040/com.example.streams: 1% user + 0.5% kernel
          0.2% 2869/logd: 0.1% user + 0.1% kernel / faults: 440 minor
    """.trimIndent()

    @Test fun meminfoAndLoad() {
        val m = SystemStats.parseMeminfo(meminfo)
        assertEquals(3016708L, m["MemTotal"]); assertEquals(1013944L, m["MemAvailable"])
        assertEquals(524284L, m["SwapTotal"]); assertEquals(87904L, m["SwapFree"])
        assertEquals(2.98, SystemStats.parseLoad("2.98 3.10 2.75 4/1830 8627\n")!!, 0.001)
        assertNull(SystemStats.parseLoad(""))
    }

    @Test fun cpuBusyFromTwoStatReadings() {
        val a = SystemStats.parseCpuTimes("cpu  2967854 63486 1439897 33157746 222598 125888 81373 0 0 0")!!
        val b = SystemStats.parseCpuTimes("cpu  2967954 63486 1439997 33158046 222698 125888 81373 0 0 0")!!
        // 600 more jiffies, of which idle+iowait grew by 400: 33% busy.
        assertEquals(33, SystemStats.busyPct(a, b))
        assertNull("one reading is not enough", SystemStats.busyPct(null, b))
        assertNull("no time passed", SystemStats.busyPct(b, b))
    }

    @Test fun pssAddsAPackagesProcessesAndSkipsSystemParts() {
        val p = SystemStats.parsePss(pss)
        assertEquals(436337L, p["com.example.tvplayer"])
        assertEquals("app + its :server process", 118356L + 110272L, p["com.example.streams"])
        assertFalse("system_server", p.containsKey("system"))
        assertFalse("hardware services", p.keys.any { it.startsWith("vendor.") || it.startsWith("android.hardware.") })
        assertFalse("system UI can't be stopped", p.containsKey("com.android.systemui"))
        assertFalse("only the by-process section counts", p.containsKey("com.example.notcounted"))
    }

    @Test fun cpuinfoPerPackage() {
        val c = SystemStats.parseCpuinfo(cpuinfo)
        assertEquals(63.0, c["com.example.tvplayer"]!!, 0.01)
        assertEquals(4.0, c["com.example.streams"]!!, 0.01)
        assertFalse(c.containsKey("logd"))
        assertFalse(c.keys.any { it.startsWith("android.hardware.") })
    }

    @Test fun topAppsBiggestFirstWithoutRefresherOrShizuku() {
        val top = SystemStats.topApps(SystemStats.parsePss(pss), SystemStats.parseCpuinfo(cpuinfo),
            hidden = setOf("moe.shizuku.privileged.api"), limit = 3)
        assertEquals(listOf("com.example.tvplayer", "com.example.streams", "com.example.vpn"), top.map { it.packageName })
        assertEquals(13.0, top[2].cpuPct!!, 0.01)
    }

    @Test fun levelsMatchTheShieldThatWasStruggling() {
        // 2026-10-30: ~780 MB of 3 GB available, swap 445 of 512 MB, load 5.2 on 4 cores.
        val slow = snapshot(memAvail = 782972, swapFree = 78816, load = 5.2)
        assertEquals(Level.WARN, slow.memLevel)
        assertEquals(Level.BAD, slow.swapLevel)
        assertEquals(Level.BAD, slow.cpuLevel)
        assertEquals(Level.BAD, slow.overall)
        val fine = snapshot(memAvail = 1149184, swapFree = 400000, load = 1.4)
        assertEquals(Level.OK, fine.overall)
    }

    @Test fun sizesReadPlainly() {
        assertEquals("288 MB", SystemStats.size(294_912))
        assertEquals("1.1 GB", SystemStats.size(1_149_184))
    }

    @Test fun neverStopTheAppOnScreenOnASchedule() {
        assertTrue(OnScreenGuard.shouldDefer(manual = false, displayOn = true, onScreen = "com.example.tvplayer", target = "com.example.tvplayer"))
        assertFalse("another app is on screen", OnScreenGuard.shouldDefer(false, true, "com.example.vpn", "com.example.tvplayer"))
        assertFalse("display off", OnScreenGuard.shouldDefer(false, false, "com.example.tvplayer", "com.example.tvplayer"))
        assertFalse("Run now always goes ahead", OnScreenGuard.shouldDefer(true, true, "com.example.tvplayer", "com.example.tvplayer"))
        assertFalse("can't tell what's on screen", OnScreenGuard.shouldDefer(false, true, null, "com.example.tvplayer"))
        assertTrue("playing in the background", OnScreenGuard.shouldDefer(false, false, "com.example.vpn", "com.example.music", setOf("com.example.music")))
        assertFalse("Run now goes ahead even when playing", OnScreenGuard.shouldDefer(true, true, null, "com.example.music", setOf("com.example.music")))
    }

    private fun snapshot(memAvail: Long, swapFree: Long, load: Double) = SystemStats.Snapshot(
        memTotalKb = 3016708, memAvailableKb = memAvail, swapTotalKb = 524284, swapFreeKb = swapFree,
        load1 = load, cores = 4, cpuBusyPct = null, storageFreeBytes = 3_600_000_000, storageTotalBytes = 12_000_000_000,
        apps = emptyList(), onScreen = null
    )
}

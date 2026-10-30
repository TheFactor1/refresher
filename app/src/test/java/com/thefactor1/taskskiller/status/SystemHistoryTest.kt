package com.thefactor1.taskskiller.status

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thefactor1.taskskiller.schedule.HistorySampler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SystemHistoryTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private fun s(minAgo: Int, mem: Int, cpu: Int? = 20, hog: String? = null, kb: Long? = null) =
        SystemHistory.Sample(now - minAgo * 60_000L, mem, 30, cpu, hog, kb)

    @After fun tearDown() { SystemHistory.override = null }

    @Test fun keepsOnlyTheLastHourInOrder() {
        val kept = SystemHistory.prune(listOf(s(5, 50), s(90, 99), s(61, 98), s(1, 40)), now)
        assertEquals(listOf(now - 5 * 60_000L, now - 60_000L), kept.map { it.at })
    }

    @Test fun peakNamesMemoryFirstThenProcessor() {
        val p = SystemHistory.peak(listOf(s(30, 60, cpu = 95), s(20, 92), s(10, 70)))!!
        assertTrue(p.memory); assertEquals(92, p.sample.memUsedPct)
        val c = SystemHistory.peak(listOf(s(30, 50, cpu = 88), s(20, 55, cpu = 10)))!!
        assertTrue(!c.memory); assertEquals(88, c.sample.cpuPct)
        assertNull("a calm hour names nothing", SystemHistory.peak(listOf(s(30, 50, cpu = 20), s(20, 55, cpu = 30))))
    }

    @Test fun theAppNamedIsTheNearestRecordedWithinFiveMinutes() {
        val list = listOf(s(30, 70, hog = "com.example.far", kb = 1), s(21, 80, hog = "com.example.near", kb = 280_000), s(20, 92))
        assertEquals("com.example.near", SystemHistory.hogNear(list, now - 20 * 60_000L)?.hog)
        assertNull(SystemHistory.hogNear(list, now - 5 * 60_000L))
    }

    @Test fun appendAndLoadRoundTripAndPrune() {
        SystemHistory.append(ctx, s(70, 99))
        SystemHistory.append(ctx, s(2, 81, cpu = null, hog = "com.example.big", kb = 300_000))
        SystemHistory.append(ctx, s(1, 64, cpu = 33))
        val back = SystemHistory.load(ctx, now)
        assertEquals(listOf(81, 64), back.map { it.memUsedPct })
        assertNull(back[0].cpuPct); assertEquals("com.example.big", back[0].hog); assertEquals(33, back[1].cpuPct)
    }

    @Test fun theSamplerNeverWakesASleepingTv() {
        HistorySampler.arm(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val next = shadowOf(am).nextScheduledAlarm!!
        assertEquals(AlarmManager.RTC, next.type)
        HistorySampler.arm(ctx)
        assertEquals("re-arming replaces, never stacks", 1, shadowOf(am).scheduledAlarms.size)
    }
}

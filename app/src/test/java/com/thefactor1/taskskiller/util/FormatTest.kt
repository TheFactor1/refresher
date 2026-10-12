package com.thefactor1.taskskiller.util

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FormatTest {
    @Test fun intervalWords() {
        assertEquals("5 minutes", Format.intervalWords(5))
        assertEquals("1 hour", Format.intervalWords(60))
        assertEquals("1 hour 30 minutes", Format.intervalWords(90))
        assertEquals("6 hours", Format.intervalWords(360))
        assertEquals("1 day", Format.intervalWords(1440))
        assertEquals("1 minute", Format.intervalWords(1))
    }

    @Test fun seconds() {
        assertEquals("No wait", Format.seconds(0))
        assertEquals("1 second", Format.seconds(1_000))
        assertEquals("2.5 seconds", Format.seconds(2_500))
        assertEquals("15 seconds", Format.seconds(15_000))
    }

    @Test fun momentSaysYesterdayAndTomorrow() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val noon = Calendar.getInstance().apply { set(2026, 9, 12, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val h = 3_600_000L
        assertEquals(0, Format.dayOffset(noon + 3 * h, noon))
        assertEquals(-1, Format.dayOffset(noon - 13 * h, noon))
        assertEquals(1, Format.dayOffset(noon + 13 * h, noon))
        assertEquals(true, Format.moment(ctx, noon - 13 * h, noon).startsWith("yesterday "))
        assertEquals(true, Format.moment(ctx, noon + 13 * h, noon).startsWith("tomorrow "))
        assertEquals("—", Format.moment(ctx, 0, noon))
    }
}

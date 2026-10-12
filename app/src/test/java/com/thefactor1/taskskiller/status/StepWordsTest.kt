package com.thefactor1.taskskiller.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepWordsTest {
    @Test fun friendly() {
        assertEquals("Stopped", StepWords.friendly("Refreshed via Shizuku (ADB, no root)"))
        assertEquals("Opened again", StepWords.friendly("relaunched"))
        assertEquals("Opened again (via shell)", StepWords.friendly("relaunched (via shell)"))
        assertEquals("Back to the home screen", StepWords.friendly("returned to Home"))
        assertEquals("Back to Live Now", StepWords.friendly("returned to Live Now"))
        assertEquals("Couldn't open it again: blocked", StepWords.friendly("relaunch failed: blocked"))
        assertEquals("Couldn't stop it: denied", StepWords.friendly("Refresh failed (Shizuku): denied"))
        assertEquals("Couldn't go back to the home screen", StepWords.friendly("could not return to Home: no launcher"))
        assertEquals("Skipped: the screen was on", StepWords.friendly("Deferred: display is on"))
        assertEquals("Not opened again: it has no home-screen entry", StepWords.friendly("not relaunched (no launcher entry)"))
        assertEquals("Unexpected error: boom", StepWords.friendly("unexpected error: boom"))
    }

    @Test fun problems() {
        assertTrue(StepWords.isProblem("relaunch failed: x"))
        assertTrue(StepWords.isProblem("could not return to Home: x"))
        assertTrue(StepWords.isProblem("Skipped: com.x is not installed"))
        assertFalse(StepWords.isProblem("relaunched"))
        assertFalse(StepWords.isProblem("Refreshed via Shizuku"))
    }
}

package com.thefactor1.taskskiller.data

import com.thefactor1.taskskiller.schedule.WakeWatch
import com.thefactor1.taskskiller.status.StepWords
import com.thefactor1.taskskiller.util.VpnCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupTest {

    private val nord = Rule(
        id = 7, packageName = "com.nordvpn.android", label = "NordVPN", intervalMinutes = 720,
        refreshOnWake = true, anchorAt = 123L, lastRunAt = 456L, lastResult = "Refreshed via Shizuku"
    )

    @Test
    fun roundTripKeepsSettingsAndDropsHistory() {
        val text = Backup.encode(Backup.Contents(listOf(nord), masterEnabled = false, autoStartShizuku = true, savedAt = 99L))
        val back = Backup.decode(text)!!
        assertEquals(99L, back.savedAt)
        assertFalse(back.masterEnabled)
        assertTrue(back.autoStartShizuku)
        val rule = back.rules.single()
        assertEquals("com.nordvpn.android", rule.packageName)
        assertEquals(720, rule.intervalMinutes)
        assertTrue(rule.refreshOnWake)
        assertEquals(0L, rule.lastRunAt)
        assertEquals("", rule.lastResult)
    }

    @Test
    fun refusesJunkAndBadPackageNames() {
        assertNull(Backup.decode("not json"))
        assertNull(Backup.decode("""{"format":2,"rules":[]}"""))
        val bad = """{"format":1,"rules":[{"id":1,"package":"x; rm -rf /","interval":60},""" +
            """{"id":2,"package":"com.nordvpn.android","interval":60}]}"""
        assertEquals(listOf("com.nordvpn.android"), Backup.decode(bad)!!.rules.map { it.packageName })
    }

    @Test
    fun oldRulesDefaultToNoWakeRefresh() {
        val old = Rule.fromJson(org.json.JSONObject("""{"id":1,"package":"com.nordvpn.android","interval":720}"""))
        assertFalse(old.refreshOnWake)
    }

    @Test
    fun wakeRefreshWaitsHalfAnHourBetweenRuns() {
        val now = 10_000_000_000L
        assertTrue(WakeWatch.isDueOnWake(nord.copy(lastRunAt = now - 31 * 60_000L), now))
        assertFalse(WakeWatch.isDueOnWake(nord.copy(lastRunAt = now - 5 * 60_000L), now))
        assertFalse(WakeWatch.isDueOnWake(nord.copy(refreshOnWake = false, lastRunAt = 0L), now))
        assertFalse(WakeWatch.isDueOnWake(nord.copy(enabled = false, lastRunAt = 0L), now))
    }

    @Test
    fun aVpnCountsAsBackOnlyWhenItIsANewNetwork() {
        assertFalse(VpnCheck.isNew(setOf("101"), setOf("101")))
        assertFalse(VpnCheck.isNew(setOf("101"), emptySet()))
        assertTrue(VpnCheck.isNew(setOf("101"), setOf("102")))
        assertTrue(VpnCheck.isNew(emptySet(), setOf("102")))
    }

    @Test
    fun vpnStepsReadWell() {
        assertEquals("VPN reconnected in 4.8 seconds", StepWords.friendly("VPN back in 4.8 seconds"))
        assertTrue(StepWords.isProblem("VPN didn't come back"))
        assertFalse(StepWords.isProblem("VPN back in 4.8 seconds"))
    }
}

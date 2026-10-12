package com.thefactor1.taskskiller.ui

import com.thefactor1.taskskiller.data.RunLog
import com.thefactor1.taskskiller.util.PackageUtil
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupsAndSuggestionsTest {

    @Test fun groupsByDayNewestFirst() {
        val now = java.util.Calendar.getInstance().apply { set(2026, 9, 12, 12, 0, 0) }.timeInMillis
        val h = 3_600_000L
        val e = { ago: Long -> RunLog.Entry(now - ago, "NordVPN", "x", true) }
        val items = LogGroups.group(listOf(e(30 * h), e(1 * h), e(5 * h), e(13 * h)), now) { ts -> if (ts > now - 12 * h) "Today" else "Earlier" }
        assertEquals(
            listOf("Day:Today", "Run:1", "Run:5", "Day:Earlier", "Run:13", "Run:30"),
            items.map {
                when (it) {
                    is LogGroups.Item.Day -> "Day:${it.label}"
                    is LogGroups.Item.Run -> "Run:${(now - it.entry.timestamp) / h}"
                }
            }
        )
    }

    @Test fun suggestionsOnlyWhatsInstalledInOrder() {
        val app = { pkg: String, label: String -> PackageUtil.InstalledApp(pkg, label, false, true) }
        val apps = listOf(app("org.xbmc.kodi", "Kodi"), app("com.x", "Zed"), app("com.nordvpn.android", "NordVPN"), app("com.a", "alpha"))
        val s = Suggestions.sections(apps)
        assertEquals(listOf("NordVPN", "Kodi"), s.suggested.map { it.label })
        assertEquals(listOf("alpha", "Kodi", "NordVPN", "Zed"), s.rest.map { it.label })
    }
}

package com.thefactor1.taskskiller.ui

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WhatsNewTest {

    @Test
    fun parsesHeadingsAndNotes() {
        val r = WhatsNew.parse("## 1.1\nTwo\nThree\n\n## 1.0\n  One  \n")
        assertEquals(listOf("1.1", "1.0"), r.map { it.version })
        assertEquals(listOf("Two", "Three"), r[0].notes)
        assertEquals(listOf("One"), r[1].notes)
    }

    @Test
    fun shippedNotesStartWithThisVersion() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val releases = WhatsNew.releases(context)
        assertEquals(WhatsNew.versionName(context), releases.first().version)
        assertTrue(releases.all { it.notes.isNotEmpty() })
    }

    @Test
    fun sinceShowsEverythingNewerThanTheLastSeen() {
        val all = WhatsNew.parse("## 3\nc\n## 2\nb\n## 1\na\n")
        assertEquals(listOf("3", "2"), WhatsNew.since(all, "1").map { it.version })
        assertEquals(listOf("3", "2", "1"), WhatsNew.since(all, null).map { it.version })
        assertEquals(emptyList<String>(), WhatsNew.since(all, "3").map { it.version })
    }
}

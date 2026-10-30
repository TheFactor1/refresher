package com.thefactor1.taskskiller.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingTest {

    private val dump = """
        MEDIA SESSION SERVICE (dumpsys media_session)
          Sessions Stack - have 2 sessions:
            Music session tag=main
              ownerPid=1, ownerUid=10050, userId=0
              package=com.example.music
              active=true
              state=PlaybackState {state=3, position=1554, buffered position=0, speed=1.0, updated=2800434, actions=379, custom actions=[], active item id=-1, error=null}
              metadata: size=5, description=A Song, An Artist, An Album
            Video session tag=video
              ownerPid=2, ownerUid=10060, userId=0
              package=com.example.video
              active=true
              state=PlaybackState {state=2, position=10, buffered position=0, speed=0.0, updated=1, actions=1, custom actions=[], active item id=-1, error=null}
              metadata: size=0, description=null, null, null
    """.trimIndent()

    @Test fun parsesEachSession() {
        val s = NowPlaying.parse(dump)
        assertEquals(2, s.size)
        assertEquals(NowPlaying.Session("com.example.music", 3, "A Song", "An Artist"), s[0])
        assertEquals(NowPlaying.Session("com.example.video", 2, null, null), s[1])
        assertTrue(s[0].playing)
    }

    @Test fun picksWhatsPlayingThenWhatWasJustPaused() {
        val s = NowPlaying.parse(dump)
        assertEquals("com.example.music", NowPlaying.pick(s, null)?.packageName)
        val paused = s.map { it.copy(state = NowPlaying.STATE_PAUSED) }
        assertNull("a paused app nobody paused here stays hidden", NowPlaying.pick(paused, null))
        assertEquals("com.example.video", NowPlaying.pick(paused, "com.example.video")?.packageName)
    }

    @Test fun emptyDumpMeansNothing() {
        assertTrue(NowPlaying.parse("").isEmpty())
        assertTrue(NowPlaying.parse("No sessions").isEmpty())
    }
}

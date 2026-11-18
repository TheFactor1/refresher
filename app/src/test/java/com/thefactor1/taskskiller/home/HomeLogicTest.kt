package com.thefactor1.taskskiller.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeLogicTest {
    private val a = HomeLogic.App("com.example.a", "alpha")
    private val b = HomeLogic.App("com.example.b", "Bravo")
    private val c = HomeLogic.App("com.example.c", "charlie")
    private val d = HomeLogic.App("com.example.d", "Delta")

    @Test fun favouritesFirstInTheirOrderThenAToZWithoutHidden() {
        val (favs, rest) = HomeLogic.arrange(listOf(d, c, b, a), favourites = listOf("com.example.c", "gone.app", "com.example.a"), hidden = setOf("com.example.d"))
        assertEquals(listOf("charlie", "alpha"), favs.map { it.label })
        assertEquals("uninstalled favourites and hidden apps drop out; A-Z ignores case", listOf("Bravo"), rest.map { it.label })
    }

    @Test fun favouritesToggleAndMove() {
        val prefs = HomePrefs.get(ApplicationProvider.getApplicationContext<Context>())
        prefs.favourites = emptyList()
        listOf("x", "y", "z").forEach { prefs.toggleFavourite(it) }
        prefs.moveFavourite("z", -1)
        assertEquals(listOf("x", "z", "y"), prefs.favourites)
        prefs.moveFavourite("x", -1)
        assertEquals("can't move past the start", listOf("x", "z", "y"), prefs.favourites)
        prefs.toggleFavourite("z")
        assertEquals(listOf("x", "y"), prefs.favourites)
    }
}

package com.thefactor1.taskskiller.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thefactor1.taskskiller.util.PackageUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LaunchArgsTest {

    /** Same intent as the launcher icon, so Android brings the task back instead of stacking a new Home. */
    @Test
    fun relaunchLooksExactlyLikeTheLauncherIcon() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val args = PackageUtil.launchArgs(context, context.packageName)!!
        assertEquals(listOf("am", "start", "-a", "android.intent.action.MAIN"), args.take(4))
        assertTrue(args.windowed(2).any { it == listOf("-c", "android.intent.category.LEANBACK_LAUNCHER") })
        assertTrue(args.windowed(2).any { it[0] == "-n" && it[1].endsWith("/.ui.MainActivity") })
        assertEquals(listOf("-f", "0x10200000"), args.takeLast(2))
    }
}

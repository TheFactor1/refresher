package com.thefactor1.taskskiller.setup

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShizukuDownloaderTest {

    private val release = """
        {"tag_name":"v13.6.0","assets":[
          {"name":"shizuku-v13.6.0-release.apk.sha256","browser_download_url":"https://github.com/x/sum"},
          {"name":"mapping.txt","browser_download_url":"https://github.com/x/mapping.txt"},
          {"name":"shizuku-v13.6.0.r1086.2650830c-release.apk","browser_download_url":"https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk"}
        ]}
    """.trimIndent()

    @Test
    fun picksTheApkAssetOnly() {
        val (name, url) = ShizukuDownloader.pickApk(release)!!
        assertEquals("shizuku-v13.6.0.r1086.2650830c-release.apk", name)
        assertTrue(url.startsWith("https://github.com/RikkaApps/Shizuku/releases/download/"))
    }

    @Test
    fun noApkOrJunkGivesNothing() {
        assertNull(ShizukuDownloader.pickApk("""{"assets":[{"name":"notes.txt","browser_download_url":"https://x/y"}]}"""))
        assertNull(ShizukuDownloader.pickApk("""{"assets":[{"name":"a.apk","browser_download_url":"http://plain/a.apk"}]}"""))
        assertNull(ShizukuDownloader.pickApk("not json"))
        assertNull(ShizukuDownloader.pickApk("""{"message":"API rate limit exceeded"}"""))
    }

    @Test
    fun sha256IsLowerHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ShizukuDownloader.sha256("abc".toByteArray()))
    }

    @Test
    fun onlyShizukuSignedByTheExpectedKeyPasses() {
        val cert = "rikka-cert".toByteArray()
        val good = ShizukuDownloader.sha256(cert)
        assertTrue(ShizukuDownloader.isRikka(ShizukuStarter.PACKAGE, listOf(cert), good))
        assertFalse("other package", ShizukuDownloader.isRikka("com.evil.shizuku", listOf(cert), good))
        assertFalse("other signer", ShizukuDownloader.isRikka(ShizukuStarter.PACKAGE, listOf("other".toByteArray()), good))
        assertFalse("extra signer", ShizukuDownloader.isRikka(ShizukuStarter.PACKAGE, listOf(cert, "other".toByteArray()), good))
        assertFalse("unsigned", ShizukuDownloader.isRikka(ShizukuStarter.PACKAGE, emptyList(), good))
        assertFalse("no package", ShizukuDownloader.isRikka(null, listOf(cert), good))
        assertEquals(64, ShizukuDownloader.RIKKA_CERT_SHA256.length)
    }

    @Test
    fun installReceiverIsPrivate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getReceiverInfo(ComponentName(context, ShizukuInstallReceiver::class.java), PackageManager.GET_META_DATA)
        assertFalse(info.exported)
    }
}

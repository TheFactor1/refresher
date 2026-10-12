package com.thefactor1.taskskiller.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import com.thefactor1.taskskiller.data.Rule
import com.thefactor1.taskskiller.data.RuleStore
import com.thefactor1.taskskiller.data.RunLog
import com.thefactor1.taskskiller.status.HealthReader
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.Shadows.shadowOf

/**
 * Test data: apps installed on a pretend Shield, rules, a run log, and the
 * device's answers about Shizuku and permissions. Everything a screen reads.
 */
object Fixture {

    val context: Context get() = ApplicationProvider.getApplicationContext()

    const val MIN = 60_000L
    const val HOUR = 60 * MIN

    data class App(val pkg: String, val label: String, val color: Int, val launchable: Boolean = true, val system: Boolean = false)

    val NORD = App("com.nordvpn.android", "NordVPN", Color.rgb(70, 135, 255))
    val TIVI = App("ar.tvplayer.tv", "TiviMate", Color.rgb(58, 63, 71))
    val PLEX = App("com.plexapp.android", "Plex", Color.rgb(229, 160, 13))
    val KODI = App("org.xbmc.kodi", "Kodi", Color.rgb(23, 178, 231))
    val JELLY = App("org.jellyfin.androidtv", "Jellyfin", Color.rgb(170, 92, 195))
    val YOUTUBE = App("com.google.android.youtube.tv", "YouTube", Color.rgb(255, 0, 0), system = true)
    val PROJECTIVY = App("com.spocky.projengmenu", "Projectivy Launcher", Color.rgb(40, 40, 40))
    val SPOTIFY = App("com.spotify.tv.android", "Spotify", Color.rgb(30, 215, 96))
    val SETTINGS = App("com.android.tv.settings", "Settings", Color.rgb(90, 90, 90), launchable = false, system = true)
    val LONG = App("com.example.longname", "A Remarkably Long Streaming App Name", Color.rgb(200, 80, 80))

    val ALL = listOf(NORD, TIVI, PLEX, KODI, JELLY, YOUTUBE, PROJECTIVY, SPOTIFY, SETTINGS, LONG)

    /** Fresh stores for a fresh app, and the given apps installed. */
    fun reset(apps: List<App> = ALL) {
        RuleStore.resetForTests()
        RunLog.resetForTests()
        // A TV is driven by a remote, never touch: without this nothing takes focus.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        HealthReader.deviceOverride = { healthy() }
        RuleStore.get(context).autoStartShizuku = true
        val spm = shadowOf(context.packageManager)
        apps.forEach { app ->
            val info = ApplicationInfo().apply {
                packageName = app.pkg
                nonLocalizedLabel = app.label
                flags = if (app.system) ApplicationInfo.FLAG_SYSTEM else 0
            }
            spm.installPackage(PackageInfo().apply { packageName = app.pkg; applicationInfo = info })
            spm.setApplicationIcon(app.pkg, icon(app))
            if (app.launchable) {
                listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).forEach { category ->
                    val resolve = ResolveInfo().apply {
                        activityInfo = ActivityInfo().apply {
                            packageName = app.pkg
                            name = app.pkg + ".Main"
                            applicationInfo = info
                            exported = true
                        }
                    }
                    spm.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(app.pkg), resolve)
                    spm.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(category), resolve)
                }
            }
        }
    }

    /** A rounded square in the app's colour with its first letter: stands in for a real icon. */
    private fun icon(app: App): BitmapDrawable {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = app.color }
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 22f, 22f, p)
        p.color = Color.WHITE
        p.textSize = 54f
        p.isFakeBoldText = true
        p.textAlign = Paint.Align.CENTER
        c.drawText(app.label.take(1), size / 2f, size / 2f + 19f, p)
        return BitmapDrawable(context.resources, bmp)
    }

    fun healthy() = HealthReader.Device(
        canForceStop = true, shizukuInstalled = true, shizukuRunning = true, shizukuPermitted = true,
        forceStopWithoutShizuku = false, exactAlarms = true, batteryExempt = true, backgroundStarts = true,
        methodName = "Shizuku (ADB, no root)"
    )

    fun shizukuStopped() = healthy().copy(canForceStop = false, shizukuRunning = false, shizukuPermitted = false)

    fun rule(app: App, id: Long, intervalMinutes: Int = 360, lastRunAgo: Long? = 5 * HOUR + 12 * MIN, enabled: Boolean = true,
             relaunch: Boolean = true, skip: Boolean = true, lastResult: String = OK_DETAIL): Rule {
        val now = System.currentTimeMillis()
        val rule = Rule(
            id = id, packageName = app.pkg, label = app.label, intervalMinutes = intervalMinutes, enabled = enabled,
            relaunch = relaunch, skipWhileScreenOn = skip, anchorAt = now - 30 * MIN,
            lastRunAt = lastRunAgo?.let { now - it } ?: 0L, lastResult = if (lastRunAgo == null) "" else lastResult
        )
        RuleStore.get(context).upsert(rule)
        return rule
    }

    const val OK_DETAIL = "Refreshed via Shizuku (ADB, no root) • relaunched • returned to Home"
    const val FAIL_DETAIL = "Refreshed via Shizuku (ADB, no root) • relaunch failed: activity start was blocked"

    /** Log entries, given as (time ago, label, detail, ok). Written straight to the log's storage. */
    fun log(vararg entries: LogEntry) {
        val now = System.currentTimeMillis()
        val array = JSONArray()
        entries.sortedBy { it.ago }.forEach {
            array.put(JSONObject().put("ts", now - it.ago).put("label", it.label).put("detail", it.detail).put("ok", it.ok))
        }
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("taskskiller_log", Context.MODE_PRIVATE)
            .edit().putString("entries", array.toString()).commit()
        RunLog.resetForTests()
    }

    data class LogEntry(val ago: Long, val label: String, val detail: String = OK_DETAIL, val ok: Boolean = true)

    /** A plausible day and a half: restarts every 6 h, one skipped while the screen was on. */
    fun typicalLog(label: String = NORD.label) = log(
        LogEntry(5 * HOUR + 12 * MIN, label),
        LogEntry(11 * HOUR + 12 * MIN, label, "Deferred: display is on", true),
        LogEntry(17 * HOUR + 12 * MIN, label),
        LogEntry(23 * HOUR + 12 * MIN, label),
        LogEntry(29 * HOUR + 12 * MIN, label, "Deferred: display is on", true),
        LogEntry(35 * HOUR + 12 * MIN, label)
    )
}

package com.thefactor1.taskskiller.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.home.HomeLogic
import com.thefactor1.taskskiller.home.HomePrefs
import com.thefactor1.taskskiller.kill.KillBackends
import java.util.concurrent.Executors

/** What Home and All apps do with an app: open it, and its options (favourite, move, hide, stop, info). */
object AppActions {

    private val worker = Executors.newSingleThreadExecutor()

    fun open(activity: Activity, app: HomeLogic.App) {
        val intent = com.thefactor1.taskskiller.util.PackageUtil.launchIntent(activity, app.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = intent != null && runCatching { activity.startActivity(intent) }.isSuccess
        if (!ok) Toast.makeText(activity, activity.getString(R.string.home_cant_open, app.label), Toast.LENGTH_SHORT).show()
    }

    /** The options dialog; [changed] runs after anything that changes what Home shows. */
    fun options(activity: Activity, app: HomeLogic.App, changed: () -> Unit) {
        val prefs = HomePrefs.get(activity)
        val fav = app.packageName in prefs.favourites
        val items = buildList<Pair<Int, () -> Unit>> {
            add((if (fav) R.string.home_unfavourite else R.string.home_favourite) to { prefs.toggleFavourite(app.packageName); changed() })
            if (fav) {
                add(R.string.home_move_left to { prefs.moveFavourite(app.packageName, -1); changed() })
                add(R.string.home_move_right to { prefs.moveFavourite(app.packageName, 1); changed() })
            }
            add(R.string.home_hide to { prefs.hidden = prefs.hidden + app.packageName; changed() })
            add(R.string.home_stop to { stop(activity, app) })
            add(R.string.home_app_info to {
                runCatching { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))) }
                Unit
            })
        }
        AlertDialog.Builder(activity).setTitle(app.label)
            .setItems(items.map { activity.getString(it.first) }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun stop(activity: Activity, app: HomeLogic.App) {
        val ctx = activity.applicationContext
        worker.execute {
            val r = KillBackends.resolve(ctx).kill(ctx, app.packageName)
            activity.runOnUiThread {
                Toast.makeText(ctx, if (r.success) ctx.getString(R.string.system_stopped, app.label) else ctx.getString(R.string.system_stop_failed, app.label, r.detail), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

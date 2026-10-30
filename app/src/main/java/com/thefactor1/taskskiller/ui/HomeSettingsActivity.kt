package com.thefactor1.taskskiller.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.databinding.ActivityHomeSettingsBinding
import com.thefactor1.taskskiller.databinding.RowValueBinding
import com.thefactor1.taskskiller.home.DefaultHome
import com.thefactor1.taskskiller.home.HomePrefs
import com.thefactor1.taskskiller.kill.KillBackends
import com.thefactor1.taskskiller.util.PackageUtil
import android.widget.Toast
import java.util.concurrent.Executors

/** Home's settings: theme, tile style, clock, what's playing, the health pill, Clean up's size and favourites, hidden apps, shortcuts. */
class HomeSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeSettingsBinding
    private lateinit var prefs: HomePrefs
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = HomePrefs.get(this)

        binding.defaultRow.rowTitle.setText(R.string.home_set_default)
        binding.defaultRow.root.setOnClickListener { if (!busy) { if (DefaultHome.isDefault(this)) askUndo() else makeDefault() } }

        binding.themeRow.rowTitle.setText(R.string.home_set_theme)
        binding.themeRow.rowHint.setText(R.string.home_set_theme_hint)
        stepper(binding.themeRow) { dir ->
            val all = HomePrefs.Theme.values()
            prefs.theme = all[(prefs.theme.ordinal + dir).mod(all.size)]
        }

        binding.tilesRow.rowTitle.setText(R.string.home_set_tiles)
        binding.tilesRow.rowHint.setText(R.string.home_set_tiles_hint)
        stepper(binding.tilesRow) { dir ->
            val all = HomePrefs.TileStyle.values()
            prefs.tileStyle = all[(prefs.tileStyle.ordinal + dir).mod(all.size)]
        }

        binding.clockRow.rowTitle.setText(R.string.home_set_clock)
        binding.clockRow.rowHint.setText(R.string.home_set_clock_hint)
        stepper(binding.clockRow) { dir ->
            val all = HomePrefs.Clock.values()
            prefs.clock = all[(prefs.clock.ordinal + dir).mod(all.size)]
        }

        binding.healthRow.rowTitle.setText(R.string.home_set_health)
        binding.healthRow.rowHint.setText(R.string.home_set_health_hint)
        binding.healthRow.root.setOnClickListener { prefs.showHealth = !prefs.showHealth; show() }

        binding.playingRow.rowTitle.setText(R.string.home_set_playing)
        binding.playingRow.rowHint.setText(R.string.home_set_playing_hint)
        binding.playingRow.root.setOnClickListener { prefs.showNowPlaying = !prefs.showNowPlaying; show() }

        binding.cleanRow.rowTitle.setText(R.string.home_set_clean)
        binding.cleanRow.rowHint.setText(R.string.home_set_clean_hint)
        stepper(binding.cleanRow) { dir ->
            val steps = HomePrefs.CLEANUP_STEPS
            val i = steps.indexOfFirst { it >= prefs.cleanupMb }.coerceAtLeast(0)
            prefs.cleanupMb = steps[(i + dir).mod(steps.size)]
        }

        binding.skipFavsRow.rowTitle.setText(R.string.home_set_skip_favs)
        binding.skipFavsRow.rowHint.setText(R.string.home_set_skip_favs_hint)
        binding.skipFavsRow.root.setOnClickListener { prefs.cleanupSkipsFavourites = !prefs.cleanupSkipsFavourites; show() }

        binding.hiddenRow.rowTitle.setText(R.string.home_set_hidden)
        binding.hiddenRow.root.setOnClickListener { unhide() }

        binding.rulesRow.rowTitle.setText(R.string.home_set_rules)
        binding.rulesRow.rowHint.setText(R.string.home_set_rules_hint)
        binding.rulesRow.root.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }

        binding.androidRow.rowTitle.setText(R.string.home_set_android)
        binding.androidRow.rowHint.setText(R.string.home_set_android_hint)
        binding.androidRow.root.setOnClickListener { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }

        show()
        binding.defaultRow.root.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        show()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun show() {
        showDefault()
        binding.themeRow.rowValue.setText(when (prefs.theme) {
            HomePrefs.Theme.MIDNIGHT -> R.string.home_theme_midnight
            HomePrefs.Theme.AURORA -> R.string.home_theme_aurora
            HomePrefs.Theme.EMBER -> R.string.home_theme_ember
        })
        binding.playingRow.rowSwitch.isChecked = prefs.showNowPlaying
        binding.tilesRow.rowValue.setText(if (prefs.tileStyle == HomePrefs.TileStyle.BANNERS) R.string.home_tiles_banners else R.string.home_tiles_icons)
        binding.clockRow.rowValue.setText(when (prefs.clock) {
            HomePrefs.Clock.SYSTEM -> R.string.home_clock_system
            HomePrefs.Clock.H12 -> R.string.home_clock_12
            HomePrefs.Clock.H24 -> R.string.home_clock_24
        })
        binding.healthRow.rowSwitch.isChecked = prefs.showHealth
        binding.cleanRow.rowValue.text = getString(R.string.home_clean_mb, prefs.cleanupMb)
        binding.skipFavsRow.rowSwitch.isChecked = prefs.cleanupSkipsFavourites
        val n = prefs.hidden.size
        binding.hiddenRow.rowHint.text = if (n == 0) getString(R.string.home_set_hidden_none) else resources.getQuantityString(R.plurals.home_hidden_count, n, n)
    }

    private fun showDefault() {
        binding.defaultRow.rowHint.text = when {
            busy -> getString(R.string.home_default_working)
            DefaultHome.isDefault(this) && DefaultHome.projectivyOverride(this) -> getString(R.string.home_default_projectivy)
            DefaultHome.isDefault(this) -> getString(R.string.home_default_is)
            prefs.wantsDefault -> getString(R.string.home_default_lost)
            else -> DefaultHome.currentHome(this)?.let { getString(R.string.home_default_not, PackageUtil.label(this, it)) }
                ?: getString(R.string.home_default_not_unknown)
        }
    }

    /** Android's own home-app setting first; if the TV won't let go, offer to turn its launcher off. */
    private fun makeDefault() {
        busy = true; showDefault()
        worker.execute {
            val backend = KillBackends.resolve(this)
            val noShell = backend.shellOutput(this, arrayOf("true")) == null
            val ok = !noShell && DefaultHome.makeDefault(this, backend)
            runOnUiThread {
                busy = false
                if (ok) prefs.wantsDefault = true
                show()
                when {
                    noShell -> toast(R.string.home_default_needs_shizuku)
                    ok -> toast(R.string.home_default_done)
                    else -> askTurnOff()
                }
            }
        }
    }

    private fun askTurnOff() {
        val names = DefaultHome.stockEnabled(this).joinToString(", ") { PackageUtil.label(this, it) }
        if (names.isEmpty()) { toast(R.string.home_default_failed); return }
        AlertDialog.Builder(this).setTitle(R.string.home_default_stubborn_title)
            .setMessage(getString(R.string.home_default_stubborn, names))
            .setPositiveButton(R.string.home_default_turn_off) { _, _ ->
                busy = true; showDefault()
                worker.execute {
                    val ok = DefaultHome.turnOffStock(this, KillBackends.resolve(this))
                    runOnUiThread {
                        busy = false
                        if (ok) prefs.wantsDefault = true
                        show()
                        toast(if (ok) R.string.home_default_done else R.string.home_default_failed)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun askUndo() {
        val extra = if (prefs.turnedOff.isNotEmpty()) getString(R.string.home_default_undo_reenable) else ""
        AlertDialog.Builder(this).setTitle(R.string.home_default_undo_title)
            .setMessage(getString(R.string.home_default_undo,
                prefs.previousHome?.let { PackageUtil.label(this, it) } ?: getString(R.string.home_default_undo_tv), extra))
            .setPositiveButton(R.string.home_default_give_back) { _, _ ->
                busy = true; showDefault()
                worker.execute {
                    val ok = DefaultHome.undo(this, KillBackends.resolve(this))
                    runOnUiThread {
                        busy = false
                        show()
                        toast(if (ok) R.string.home_default_undone else R.string.home_default_failed)
                    }
                }
            }
            .setNegativeButton(R.string.home_default_keep, null).show()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    /** Left/right step the value; OK steps forward and wraps. */
    private fun stepper(row: RowValueBinding, step: (Int) -> Unit) {
        row.root.setOnKeyListener { _, keyCode, event ->
            val dir = when (keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> return@setOnKeyListener false }
            if (event.action == KeyEvent.ACTION_DOWN) { step(dir); show() }
            true
        }
        row.root.setOnClickListener { step(1); show() }
    }

    private fun unhide() {
        val hidden = prefs.hidden.toList().sortedBy { PackageUtil.label(this, it).lowercase() }
        if (hidden.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.home_set_hidden_none).setPositiveButton(R.string.ok, null).show(); return
        }
        AlertDialog.Builder(this).setTitle(R.string.home_unhide_title)
            .setItems(hidden.map { PackageUtil.label(this, it) }.toTypedArray()) { _, which ->
                prefs.hidden = prefs.hidden - hidden[which]; show()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }
}

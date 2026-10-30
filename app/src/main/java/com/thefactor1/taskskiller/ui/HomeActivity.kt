package com.thefactor1.taskskiller.ui

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.databinding.ActivityHomeBinding
import com.thefactor1.taskskiller.databinding.ItemHomeTileBinding
import com.thefactor1.taskskiller.home.Backdrop
import com.thefactor1.taskskiller.home.CleanUp
import com.thefactor1.taskskiller.home.HomeLogic
import com.thefactor1.taskskiller.home.HomePrefs
import com.thefactor1.taskskiller.home.HomeTheme
import com.thefactor1.taskskiller.home.NowPlaying
import com.thefactor1.taskskiller.home.TileImages
import com.thefactor1.taskskiller.kill.KillBackends
import com.thefactor1.taskskiller.schedule.HistorySampler
import com.thefactor1.taskskiller.status.SystemHistory
import com.thefactor1.taskskiller.status.SystemStats
import com.thefactor1.taskskiller.status.SystemStats.Level
import com.thefactor1.taskskiller.util.PackageUtil
import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors

/**
 * Refresher Home, the launcher, "cinema": the selected app's art fills the screen, the clock sits in a halo that
 * shows free memory, and a dock holds favourites and All apps. One screen, nothing scrolls. Built to stay light:
 * plain views and drawables, six small tile images (the backdrop art is a 32x18 copy), the app list read only
 * when apps change, readings every 30 s only while Home is showing, and nothing animating while it sits still.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var prefs: HomePrefs
    private lateinit var theme: HomeTheme
    private lateinit var backdrop: Backdrop
    /** The selected app's colour (the theme's accent for greys and All apps). */
    private var focusColor = 0
    private var level = Level.OK
    private var haloShown = false
    private val worker = Executors.newSingleThreadExecutor()
    private var apps: List<HomeLogic.App>? = null
    private var appsDirty = true
    private var shownKey: String? = null
    private var focusedOnce = false
    /** The app the viewer just paused from Home, so its pill stays to press play again. */
    private var pausedHere: String? = null

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = showClock()
    }

    /** Installs, updates and removals: the only times the app list is read again. */
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            appsDirty = true
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) loadApps()
        }
    }

    private val readings = object : Runnable {
        override fun run() {
            readHealth()
            binding.root.postDelayed(this, READ_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = HomePrefs.get(this)

        binding.cpuStat.statLabel.setText(R.string.home_meter_cpu)
        binding.cpuStat.statLabel.gravity = android.view.Gravity.END
        binding.cpuStat.statValue.gravity = android.view.Gravity.END
        binding.diskStat.statLabel.setText(R.string.home_meter_storage)

        binding.halo.setOnClickListener { startActivity(Intent(this, SystemActivity::class.java)) }
        binding.cleanChip.setOnClickListener { cleanUp() }
        binding.settingsChip.setOnClickListener { startActivity(Intent(this, HomeSettingsActivity::class.java)) }
        binding.allApps.setOnClickListener { startActivity(Intent(this, AllAppsActivity::class.java)) }
        binding.allApps.setOnFocusChangeListener { v, has ->
            TileFocus.lift(v, has, DOCK_LIFT)
            if (has) select(getString(R.string.home_all_apps), null, null)
            dim()
        }
        binding.nowPlaying.setOnClickListener { playPause() }

        registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })

        // Home has nowhere to go back to.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() {} })
    }

    override fun onResume() {
        super.onResume()
        HistorySampler.arm(this)
        applyTheme()
        registerReceiver(tick, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        showClock()
        val health = if (prefs.showHealth) View.VISIBLE else View.INVISIBLE
        binding.halo.visibility = health
        binding.haloText.visibility = health
        binding.cpuStat.root.visibility = health
        binding.diskStat.root.visibility = health
        binding.root.post(readings)
        if (appsDirty || apps == null) loadApps() else render()
    }

    /** Home eases in when you come back to it. */
    override fun onStart() {
        super.onStart()
        binding.homeRoot.alpha = 0f
        binding.homeRoot.translationY = resources.displayMetrics.density * 14
        binding.homeRoot.animate().alpha(1f).translationY(0f).setDuration(ENTER_MS).start()
    }

    override fun onPause() {
        runCatching { unregisterReceiver(tick) }
        binding.root.removeCallbacks(readings)
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packages) }
        worker.shutdown()
        super.onDestroy()
    }

    /** While an app is on screen, drop the tile images Home isn't showing; they come back in a blink. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) TileImages.trim()
    }

    private fun applyTheme() {
        val t = HomeTheme.of(prefs.theme)
        if (::theme.isInitialized && t == theme) return
        theme = t
        shownKey = null
        backdrop = Backdrop(resources, t)
        binding.homeFrame.background = backdrop.drawable
        window.statusBarColor = t.base
        tint(t.accent)
    }

    /** The selected app's colour on the halo, its text and the clock's glow. */
    private fun tint(color: Int) {
        focusColor = color
        val ring = when (level) {
            Level.OK -> color
            Level.WARN -> ContextCompat.getColor(this, R.color.warn)
            Level.BAD -> ContextCompat.getColor(this, R.color.bad)
        }
        binding.halo.color = ring
        // Half way to white, so it reads over any backdrop.
        binding.haloText.setTextColor(androidx.core.graphics.ColorUtils.blendARGB(ring, android.graphics.Color.WHITE, 0.5f))
        binding.homeClock.setShadowLayer(56f, 0f, 0f, android.graphics.Color.argb(0x73, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color)))
    }

    /** Something in the dock was selected: its name below, its art and colour everywhere else. */
    private fun select(label: String, img: TileImages.Img?, hint: String?) {
        binding.dockCaption.text = hint ?: getString(R.string.home_open_hint, label)
        val color = img?.color ?: theme.accent
        backdrop.show(img?.art, color)
        tint(color)
    }

    /** While the dock has focus, the apps not selected step back a little. */
    private fun dim() {
        binding.dock.post {
            val inDock = binding.dock.hasFocus()
            for (i in 0 until binding.dockApps.childCount) {
                val card = binding.dockApps.getChildAt(i).findViewById<View>(R.id.tileCard)
                card.animate().alpha(if (!inDock || card.isFocused) 1f else DIMMED).setDuration(160).start()
            }
        }
    }

    private fun showClock() {
        val now = Date()
        val pattern = when (prefs.clock) {
            HomePrefs.Clock.H24 -> "H:mm"
            HomePrefs.Clock.H12 -> "h:mm"
            HomePrefs.Clock.SYSTEM -> if (DateFormat.is24HourFormat(this)) "H:mm" else "h:mm"
        }
        binding.homeClock.text = DateFormat.format(pattern, now)
        val locale = resources.configuration.locales[0]
        binding.homeDate.text = (DateFormat.format("EEE", now).toString() + " · " +
            DateFormat.format(DateFormat.getBestDateTimePattern(locale, "MMMd"), now)).uppercase(locale)
        binding.homeGreeting.setText(greeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)))
    }

    /**
     * Memory from /proc/meminfo (one small file, no shell), the processor from Refresher's own minute readings,
     * storage from the file system; what's playing through Shizuku when it's running.
     */
    private fun readHealth() {
        val showHealth = prefs.showHealth
        val showPlaying = prefs.showNowPlaying
        worker.execute {
            val mem = runCatching { SystemStats.parseMeminfo(meminfo()) }.getOrNull()
            val total = mem?.get("MemTotal"); val avail = mem?.get("MemAvailable")
            val cpu = if (showHealth) runCatching { SystemHistory.load(this).lastOrNull() }.getOrNull()
                ?.takeIf { System.currentTimeMillis() - it.at < CPU_FRESH_MS }?.cpuPct else null
            val disk = if (showHealth) runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull() else null
            val playing = if (showPlaying) runCatching { NowPlaying.read(this, KillBackends.resolve(this)) }.getOrNull() else null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (total != null && avail != null && total > 0) {
                    level = when { avail * 100 < total * 12 -> Level.BAD; avail * 100 < total * 30 -> Level.WARN; else -> Level.OK }
                    val pct = (avail * 100 / total).toInt()
                    binding.halo.setFraction(pct / 100f, animate = !haloShown)
                    haloShown = true
                    binding.haloText.text = getString(R.string.home_halo_free, pct)
                    binding.halo.contentDescription = getString(R.string.home_halo_free, pct)
                    tint(focusColor)
                }
                binding.cpuStat.statValue.text = if (cpu != null) getString(cpuWord(cpu)) else getString(R.string.home_meter_unknown)
                if (disk != null && disk.totalBytes > 0) binding.diskStat.statValue.text = getString(R.string.home_storage_free, SystemStats.size(disk.availableBytes / 1024))
                showPlaying(playing?.let { NowPlaying.pick(it, pausedHere) })
            }
        }
    }

    private fun showPlaying(s: NowPlaying.Session?) {
        if (s == null) { binding.nowPlaying.visibility = View.GONE; return }
        val app = PackageUtil.label(this, s.packageName)
        val what = listOfNotNull(s.title, s.artist).joinToString(" – ")
        binding.nowPlayingText.text = if (what.isEmpty()) getString(R.string.home_playing_app, app) else getString(R.string.home_playing, app, what)
        binding.nowPlayingIcon.setImageResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.nowPlaying.contentDescription = getString(if (s.playing) R.string.home_pause else R.string.home_play)
        binding.nowPlaying.tag = s
        binding.nowPlaying.visibility = View.VISIBLE
    }

    private fun playPause() {
        val s = binding.nowPlaying.tag as? NowPlaying.Session ?: return
        pausedHere = s.packageName
        worker.execute {
            NowPlaying.playPause(this, KillBackends.resolve(this))
            Thread.sleep(PLAY_SETTLE_MS)
            runOnUiThread { readHealth() }
        }
    }

    private fun loadApps() {
        appsDirty = false
        worker.execute {
            val list = PackageUtil.installedApps(this).filter { it.hasLauncherEntry }.map { HomeLogic.App(it.packageName, it.label) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                apps = list
                shownKey = null
                render()
            }
        }
    }

    /** Fills the dock again only when something changed (apps, favourites, hidden, tile style). */
    private fun render() {
        val list = apps ?: return
        val key = "${prefs.favourites}|${prefs.hidden.sorted()}|${prefs.tileStyle}"
        if (key == shownKey) return
        shownKey = key
        val (favs, rest) = HomeLogic.arrange(list, prefs.favourites, prefs.hidden)
        // No favourites yet: the first apps A to Z keep the dock from looking empty.
        val dock = (favs.ifEmpty { rest }).take(DOCK_SIZE)
        binding.dockApps.removeAllViews()
        dock.forEach { app -> binding.dockApps.addView(dockTile(app, favs.isEmpty())) }
        binding.dockDivider.visibility = if (dock.isEmpty()) View.GONE else View.VISIBLE
        if (!focusedOnce) {
            focusedOnce = true
            (binding.dockApps.getChildAt(0)?.findViewById<View>(R.id.tileCard) ?: binding.allApps).requestFocus()
        }
    }

    private fun dockTile(app: HomeLogic.App, noFavourites: Boolean): View {
        val b = ItemHomeTileBinding.inflate(layoutInflater, binding.dockApps, false)
        val pad = resources.getDimensionPixelSize(R.dimen.home_dock_gap) / 2
        b.root.setPadding(pad, pad, pad, pad)
        b.root.layoutParams = b.root.layoutParams.apply { width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT }
        b.tileCard.layoutParams = b.tileCard.layoutParams.apply {
            width = resources.getDimensionPixelSize(R.dimen.home_dock_tile_w); height = resources.getDimensionPixelSize(R.dimen.home_dock_tile_h)
        }
        TileFocus.setUp(b.tileCard, theme.accent)
        // The dock's caption names the focused app; an icon tile here is too short for its own label.
        b.tileIconName.visibility = View.GONE
        b.tileCard.contentDescription = app.label
        b.tileCard.setOnClickListener { AppActions.open(this, app) }
        b.tileCard.setOnLongClickListener { AppActions.options(this, app) { shownKey = null; render() }; true }
        b.tileCard.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) { AppActions.options(this, app) { shownKey = null; render() }; true } else false
        }
        b.tileCard.setOnFocusChangeListener { v, has ->
            TileFocus.lift(v, has, DOCK_LIFT)
            if (has) select(app.label, TileImages.cached(app.packageName), if (noFavourites) getString(R.string.home_dock_hint, app.label) else null)
            dim()
        }
        TileImages.load(this, app.packageName, prefs.tileStyle) { img ->
            img.color?.let { b.tileCard.outlineSpotShadowColor = it; b.tileCard.outlineAmbientShadowColor = it }
            if (b.tileCard.isFocused) select(app.label, img, if (noFavourites) getString(R.string.home_dock_hint, app.label) else null)
            b.tileBanner.setImageDrawable(if (img.banner) img.d else null)
            b.tileIcon.setImageDrawable(if (img.banner) null else img.d)
            b.tileBanner.visibility = if (img.banner) View.VISIBLE else View.GONE
            b.tileIconBox.visibility = if (img.banner || img.d == null) View.GONE else View.VISIBLE
        }
        return b.root
    }

    private fun cleanUp() {
        binding.cleanChip.isEnabled = false
        binding.cleanChip.setText(R.string.home_cleaning)
        worker.execute {
            val r = runCatching { CleanUp.run(this, KillBackends.resolve(this)) }.getOrNull()
            runOnUiThread {
                binding.cleanChip.isEnabled = true
                binding.cleanChip.setText(R.string.home_clean)
                val msg = when {
                    r == null || r.noShell -> getString(R.string.home_clean_needs_shizuku)
                    r.stopped.isEmpty() -> getString(R.string.home_clean_nothing, prefs.cleanupMb)
                    else -> getString(R.string.home_clean_done, SystemStats.size(r.freedKb), r.stopped.joinToString(", "))
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                readHealth()
            }
        }
    }

    companion object {
        private const val DOCK_SIZE = 6
        private const val DOCK_LIFT = 1.12f
        private const val DIMMED = 0.62f
        private const val ENTER_MS = 260L
        private const val READ_MS = 30_000L
        private const val CPU_FRESH_MS = 3 * 60_000L
        private const val PLAY_SETTLE_MS = 600L

        /** Test hook: where memory is read from. */
        internal var meminfo: () -> String = { File("/proc/meminfo").readText() }

        /** The line above the clock. */
        fun greeting(hour: Int) = when (hour) {
            in 5..11 -> R.string.home_greet_morning
            in 12..16 -> R.string.home_greet_afternoon
            in 17..21 -> R.string.home_greet_evening
            else -> R.string.home_greet_night
        }

        fun cpuWord(pct: Int) = when {
            pct < 35 -> R.string.home_cpu_calm
            pct < 70 -> R.string.home_cpu_busy
            else -> R.string.home_cpu_hard
        }
    }
}

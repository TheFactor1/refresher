package com.thefactor1.taskskiller.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.util.LruCache
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.databinding.ActivityHomeBinding
import com.thefactor1.taskskiller.databinding.ItemHomeTileBinding
import com.thefactor1.taskskiller.home.CleanUp
import com.thefactor1.taskskiller.home.HomeLogic
import com.thefactor1.taskskiller.home.HomePrefs
import com.thefactor1.taskskiller.kill.KillBackends
import com.thefactor1.taskskiller.schedule.HistorySampler
import com.thefactor1.taskskiller.status.SystemReader
import com.thefactor1.taskskiller.status.SystemStats
import com.thefactor1.taskskiller.status.SystemStats.Level
import com.thefactor1.taskskiller.util.PackageUtil
import java.io.File
import java.util.Date
import java.util.concurrent.Executors

/**
 * Refresher Home, the launcher: a clock, the TV's health, Clean up, and the apps (favourites first). Built to stay
 * light: plain views, banners loaded only when shown and kept in a small cache, health read every 15 s while visible.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var prefs: HomePrefs
    private val worker = Executors.newSingleThreadExecutor()
    private val images = Executors.newSingleThreadExecutor()
    private val adapter = TileAdapter()
    private var reader: SystemReader? = null
    private var apps: List<HomeLogic.App> = emptyList()
    private var focusedOnce = false

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = showClock()
    }

    private val health = object : Runnable {
        override fun run() {
            readHealth()
            binding.root.postDelayed(this, HEALTH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = HomePrefs.get(this)
        reader = SystemReader(this, KillBackends.resolve(this))

        val grid = GridLayoutManager(this, COLUMNS)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (adapter.isHeader(position)) COLUMNS else 1
        }
        binding.homeGrid.layoutManager = grid
        binding.homeGrid.adapter = adapter
        binding.homeGrid.itemAnimator = null

        binding.healthChip.setOnClickListener { startActivity(Intent(this, SystemActivity::class.java)) }
        binding.cleanChip.setOnClickListener { cleanUp() }
        binding.refresherChip.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        binding.settingsChip.setOnClickListener { startActivity(Intent(this, HomeSettingsActivity::class.java)) }

        // Home has nowhere to go back to.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() {} })
    }

    override fun onResume() {
        super.onResume()
        HistorySampler.arm(this)
        registerReceiver(tick, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        showClock()
        binding.healthChip.visibility = if (prefs.showHealth) View.VISIBLE else View.GONE
        if (prefs.showHealth) binding.root.post(health)
        loadApps()
    }

    override fun onPause() {
        runCatching { unregisterReceiver(tick) }
        binding.root.removeCallbacks(health)
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdown(); images.shutdown()
        super.onDestroy()
    }

    private fun showClock() {
        val now = Date()
        val pattern = when (prefs.clock) {
            HomePrefs.Clock.H24 -> "H:mm"
            HomePrefs.Clock.H12 -> "h:mm"
            HomePrefs.Clock.SYSTEM -> if (DateFormat.is24HourFormat(this)) "H:mm" else "h:mm"
        }
        binding.homeClock.text = DateFormat.format(pattern, now)
        binding.homeDate.text = DateFormat.format(DateFormat.getBestDateTimePattern(resources.configuration.locales[0], "EEEEMMMMd"), now)
    }

    private fun readHealth() {
        val r = reader ?: return
        worker.execute {
            val snap = runCatching { r.read(withApps = false) }.getOrNull()
            // Without Shizuku there is still memory: /proc/meminfo is readable by any app.
            val memOnly = if (snap == null) runCatching { SystemStats.parseMeminfo(File("/proc/meminfo").readText()) }.getOrNull() else null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    snap != null -> showHealth(snap.overall, SystemStats.size(snap.memAvailableKb))
                    memOnly != null -> {
                        val total = memOnly["MemTotal"] ?: 0L; val avail = memOnly["MemAvailable"] ?: 0L
                        val level = when { avail * 100 < total * 12 -> Level.BAD; avail * 100 < total * 30 -> Level.WARN; else -> Level.OK }
                        showHealth(level, SystemStats.size(avail))
                    }
                }
            }
        }
    }

    private fun showHealth(level: Level, free: String) {
        val (dot, text) = when (level) {
            Level.OK -> R.color.ok to R.string.system_verdict_ok
            Level.WARN -> R.color.warn to R.string.system_verdict_warn
            Level.BAD -> R.color.bad to R.string.system_verdict_bad
        }
        binding.healthDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, dot))
        binding.healthText.text = getString(R.string.home_health, getString(text), free)
    }

    private fun loadApps() {
        worker.execute {
            val list = PackageUtil.installedApps(this).filter { it.hasLauncherEntry }.map { HomeLogic.App(it.packageName, it.label) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                apps = list
                render()
            }
        }
    }

    private fun render() {
        val (favs, rest) = HomeLogic.arrange(apps, prefs.favourites, prefs.hidden)
        adapter.submit(favs, rest, prefs.tileStyle)
        if (!focusedOnce && (favs.isNotEmpty() || rest.isNotEmpty())) {
            focusedOnce = true
            binding.homeGrid.post { binding.homeGrid.findViewHolderForAdapterPosition(1)?.itemView?.findViewById<View>(R.id.tileCard)?.requestFocus() }
        }
    }

    private fun open(app: HomeLogic.App) {
        val intent = PackageUtil.launchIntent(this, app.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent == null) {
            Toast.makeText(this, getString(R.string.home_cant_open, app.label), Toast.LENGTH_SHORT).show(); return
        }
        runCatching { startActivity(intent) }.onFailure { Toast.makeText(this, getString(R.string.home_cant_open, app.label), Toast.LENGTH_SHORT).show() }
    }

    private fun options(app: HomeLogic.App) {
        val fav = app.packageName in prefs.favourites
        val items = buildList {
            add((if (fav) R.string.home_unfavourite else R.string.home_favourite) to { prefs.toggleFavourite(app.packageName); render() })
            if (fav) {
                add(R.string.home_move_left to { prefs.moveFavourite(app.packageName, -1); render() })
                add(R.string.home_move_right to { prefs.moveFavourite(app.packageName, 1); render() })
            }
            add(R.string.home_hide to { prefs.hidden = prefs.hidden + app.packageName; render() })
            add(R.string.home_stop to { stop(app) })
            add(R.string.home_app_info to {
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${app.packageName}"))) }
                Unit
            })
        }
        AlertDialog.Builder(this).setTitle(app.label)
            .setItems(items.map { getString(it.first) }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun stop(app: HomeLogic.App) {
        worker.execute {
            val r = KillBackends.resolve(this).kill(this, app.packageName)
            runOnUiThread {
                Toast.makeText(this, if (r.success) getString(R.string.system_stopped, app.label) else getString(R.string.system_stop_failed, app.label, r.detail), Toast.LENGTH_SHORT).show()
            }
        }
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

    /** Headers ("Favourites", "Apps") span the row; every other cell is an app. */
    inner class TileAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Any> = emptyList()
        private var style = HomePrefs.TileStyle.BANNERS
        private val cache = LruCache<String, Img>(CACHE_ENTRIES)

        init { setHasStableIds(true) }

        fun submit(favs: List<HomeLogic.App>, rest: List<HomeLogic.App>, tiles: HomePrefs.TileStyle) {
            if (tiles != style) cache.evictAll()
            style = tiles
            rows = buildList {
                if (favs.isNotEmpty()) { add(getString(R.string.home_favourites)); addAll(favs) }
                if (rest.isNotEmpty()) { add(getString(R.string.home_apps)); addAll(rest) }
            }
            notifyDataSetChanged()
        }

        fun isHeader(position: Int) = rows.getOrNull(position) is String
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (isHeader(position)) 0 else 1
        override fun getItemId(position: Int): Long = when (val r = rows[position]) {
            is HomeLogic.App -> r.packageName.hashCode().toLong()
            else -> ("h:" + r).hashCode().toLong()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_home_header, parent, false)) {}
            else TileHolder(ItemHomeTileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            if (row is String) { (holder.itemView as TextView).text = row; return }
            val app = row as HomeLogic.App
            val b = (holder as TileHolder).b
            b.tileName.text = app.label
            b.tileCard.contentDescription = app.label
            b.tileCard.setOnClickListener { open(app) }
            b.tileCard.setOnLongClickListener { options(app); true }
            b.tileCard.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) { options(app); true } else false
            }
            b.tileBanner.tag = app.packageName
            b.tileBanner.setImageDrawable(null); b.tileIcon.setImageDrawable(null)
            b.tileBanner.visibility = View.INVISIBLE; b.tileIcon.visibility = View.GONE
            val hit = cache.get(app.packageName)
            if (hit != null) show(b, hit.d, hit.banner) else load(b, app.packageName)
        }

        private fun load(b: ItemHomeTileBinding, pkg: String) {
            val wantBanner = style == HomePrefs.TileStyle.BANNERS
            images.execute {
                val pm = packageManager
                val banner: Drawable? = if (!wantBanner) null else runCatching {
                    PackageUtil.launchIntent(this@HomeActivity, pkg)?.let { pm.getActivityBanner(it) } ?: pm.getApplicationBanner(pkg)
                }.getOrNull()
                val icon: Drawable? = if (banner != null) null else runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                runOnUiThread {
                    val img = Img(banner ?: icon, banner != null)
                    cache.put(pkg, img)
                    if (b.tileBanner.tag == pkg) show(b, img.d, img.banner)
                }
            }
        }

        private fun show(b: ItemHomeTileBinding, d: Drawable?, banner: Boolean) {
            b.tileBanner.setImageDrawable(if (banner) d else null)
            b.tileIcon.setImageDrawable(if (banner) null else d)
            b.tileBanner.visibility = if (banner) View.VISIBLE else View.GONE
            b.tileIcon.visibility = if (banner) View.GONE else View.VISIBLE
        }
    }

    /** A loaded tile image: the TV banner, or the icon when the app has none (or icons are chosen). */
    data class Img(val d: Drawable?, val banner: Boolean)

    class TileHolder(val b: ItemHomeTileBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            val r = b.root.resources.getDimension(R.dimen.radius_card) - b.root.resources.getDimension(R.dimen.focus_ring)
            b.tileBanner.clipToOutline = true
            b.tileBanner.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
    }

    companion object {
        private const val COLUMNS = 5
        private const val HEALTH_MS = 15_000L
        private const val CACHE_ENTRIES = 48
    }
}

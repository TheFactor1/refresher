package com.thefactor1.taskskiller.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import com.thefactor1.taskskiller.status.SystemStats
import com.thefactor1.taskskiller.status.SystemStats.Level
import com.thefactor1.taskskiller.util.PackageUtil
import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors

/**
 * Refresher Home, the launcher: a clock, the TV's health, Clean up, and the apps (favourites first, bigger).
 * Built to stay light: plain views, tile-sized thumbnails in a small cache, the app list read only when apps change,
 * and health from /proc/meminfo every 30 s while visible (no shell, no dumpsys).
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var prefs: HomePrefs
    private val worker = Executors.newSingleThreadExecutor()
    private val images = Executors.newSingleThreadExecutor()
    private val adapter = TileAdapter()
    private var apps: List<HomeLogic.App>? = null
    private var appsDirty = true
    private var shownKey: String? = null
    private var focusedOnce = false
    private val wash = GradientDrawable()
    private var washColor = 0
    private var washAnim: ValueAnimator? = null

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

        washColor = ContextCompat.getColor(this, R.color.home_wash)
        wash.gradientType = GradientDrawable.RADIAL_GRADIENT
        wash.setGradientCenter(0.18f, 0f)
        wash.gradientRadius = resources.displayMetrics.widthPixels * 0.85f
        wash.colors = intArrayOf(washColor, Color.TRANSPARENT)
        binding.homeFrame.background = android.graphics.drawable.LayerDrawable(
            arrayOf(android.graphics.drawable.ColorDrawable(ContextCompat.getColor(this, R.color.background)), wash))

        val grid = GridLayoutManager(this, SPANS)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = when (adapter.getItemViewType(position)) {
                TYPE_HEADER -> SPANS
                TYPE_FAV -> SPANS / FAV_COLUMNS
                else -> SPANS / APP_COLUMNS
            }
        }
        binding.homeGrid.layoutManager = grid
        binding.homeGrid.adapter = adapter
        binding.homeGrid.itemAnimator = null
        binding.homeGrid.setItemViewCacheSize(4)

        binding.healthChip.setOnClickListener { startActivity(Intent(this, SystemActivity::class.java)) }
        binding.cleanChip.setOnClickListener { cleanUp() }
        binding.refresherChip.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        binding.settingsChip.setOnClickListener { startActivity(Intent(this, HomeSettingsActivity::class.java)) }

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
        registerReceiver(tick, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        showClock()
        binding.healthChip.visibility = if (prefs.showHealth) View.VISIBLE else View.GONE
        if (prefs.showHealth) binding.root.post(health)
        if (appsDirty || apps == null) loadApps() else render()
    }

    override fun onPause() {
        runCatching { unregisterReceiver(tick) }
        binding.root.removeCallbacks(health)
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packages) }
        washAnim?.cancel()
        worker.shutdown(); images.shutdown()
        super.onDestroy()
    }

    /** While an app is on screen, drop the thumbnails Home isn't showing; they come back in a blink. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) adapter.trim()
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
        binding.homeGreeting.setText(greeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)))
    }

    /** Memory is what slows a TV down, and /proc/meminfo is readable by any app: one small file, no shell. */
    private fun readHealth() {
        worker.execute {
            val mem = runCatching { SystemStats.parseMeminfo(meminfo()) }.getOrNull() ?: return@execute
            val total = mem["MemTotal"] ?: return@execute
            val avail = mem["MemAvailable"] ?: return@execute
            val level = when { avail * 100 < total * 12 -> Level.BAD; avail * 100 < total * 30 -> Level.WARN; else -> Level.OK }
            runOnUiThread { if (!isFinishing && !isDestroyed) showHealth(level, SystemStats.size(avail)) }
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

    /** Lays the tiles out again only when something changed (apps, favourites, hidden, tile style). */
    private fun render() {
        val list = apps ?: return
        val key = "${prefs.favourites}|${prefs.hidden.sorted()}|${prefs.tileStyle}"
        if (key == shownKey) return
        shownKey = key
        val (favs, rest) = HomeLogic.arrange(list, prefs.favourites, prefs.hidden)
        adapter.submit(favs, rest, prefs.tileStyle)
        if (!focusedOnce && (favs.isNotEmpty() || rest.isNotEmpty())) {
            focusedOnce = true
            binding.homeGrid.post { binding.homeGrid.findViewHolderForAdapterPosition(1)?.itemView?.findViewById<View>(R.id.tileCard)?.requestFocus() }
        }
    }

    /** Eases the backdrop toward the focused app's colour. */
    private fun tintTo(color: Int) {
        if (color == washColor) return
        washAnim?.cancel()
        val from = washColor
        washColor = color
        washAnim = ValueAnimator.ofObject(ArgbEvaluator(), from, color).apply {
            duration = WASH_MS
            addUpdateListener { wash.colors = intArrayOf(it.animatedValue as Int, Color.TRANSPARENT) }
            start()
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

    /** Headers ("Favourites", "Apps") span the row; favourites are bigger and named, other banners stand alone. */
    inner class TileAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Any> = emptyList()
        private var favCount = 0
        private var style = HomePrefs.TileStyle.BANNERS
        private val cache = object : LruCache<String, Img>(CACHE_BYTES) {
            override fun sizeOf(key: String, value: Img) = value.bytes
        }

        init { setHasStableIds(true) }

        fun submit(favs: List<HomeLogic.App>, rest: List<HomeLogic.App>, tiles: HomePrefs.TileStyle) {
            if (tiles != style) cache.evictAll()
            style = tiles
            favCount = favs.size
            rows = buildList {
                if (favs.isNotEmpty()) { add(getString(R.string.home_favourites)); addAll(favs) }
                if (rest.isNotEmpty()) { add(getString(R.string.home_apps)); addAll(rest) }
            }
            notifyDataSetChanged()
        }

        fun trim() = cache.evictAll()

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = when {
            rows.getOrNull(position) is String -> TYPE_HEADER
            favCount > 0 && position <= favCount -> TYPE_FAV
            else -> TYPE_APP
        }
        override fun getItemId(position: Int): Long = when (val r = rows[position]) {
            is HomeLogic.App -> (getItemViewType(position).toString() + r.packageName).hashCode().toLong()
            else -> ("h:" + r).hashCode().toLong()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            if (viewType == TYPE_HEADER) return object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_home_header, parent, false)) {}
            val b = ItemHomeTileBinding.inflate(inflater, parent, false)
            val fav = viewType == TYPE_FAV
            b.tileCard.layoutParams.height = resources.getDimensionPixelSize(if (fav) R.dimen.home_fav_height else R.dimen.home_app_height)
            b.tileName.visibility = if (fav) View.VISIBLE else View.GONE
            b.tileIconName.visibility = if (fav) View.GONE else View.VISIBLE
            if (fav) b.tileIcon.layoutParams.let { it.width = it.width * 3 / 2; it.height = it.height * 3 / 2 }
            return TileHolder(b, if (fav) FAV_LIFT else APP_LIFT)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            if (row is String) { (holder.itemView as TextView).text = row; return }
            val app = row as HomeLogic.App
            val h = holder as TileHolder
            val b = h.b
            b.tileName.text = app.label
            b.tileIconName.text = app.label
            b.tileCard.contentDescription = app.label
            b.tileCard.setOnClickListener { open(app) }
            b.tileCard.setOnLongClickListener { options(app); true }
            b.tileCard.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) { options(app); true } else false
            }
            b.tileCard.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                h.lift(v, has)
                if (has) cache.get(app.packageName)?.let { tintTo(it.wash) }
            }
            b.tileBanner.tag = app.packageName
            val hit = cache.get(app.packageName)
            if (hit != null) show(b, hit) else { show(b, null); load(b, app.packageName) }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is TileHolder) { holder.b.tileBanner.setImageDrawable(null); holder.b.tileIcon.setImageDrawable(null) }
        }

        private fun load(b: ItemHomeTileBinding, pkg: String) {
            val wantBanner = style == HomePrefs.TileStyle.BANNERS
            images.execute {
                // Apps ship banners up to 1280x720 and icons at 512 px: keep only a tile-sized copy, and decode
                // straight to that size when the image is a plain bitmap so the full-size one never exists.
                val banner = if (wantBanner) runCatching { bannerThumb(pkg) }.getOrNull() else null
                val bmp = banner ?: runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()?.let { thumbnail(it, ICON_PX, ICON_PX, crop = false) }
                val img = Img(bmp?.let { BitmapDrawable(resources, it) }, banner != null, bmp?.let { washOf(it) } ?: washColor, bmp?.byteCount ?: 1)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    cache.put(pkg, img)
                    if (b.tileBanner.tag == pkg) {
                        show(b, img)
                        if (b.tileCard.hasFocus()) tintTo(img.wash)
                    }
                }
            }
        }

        private fun show(b: ItemHomeTileBinding, img: Img?) {
            val banner = img?.banner == true
            b.tileBanner.setImageDrawable(if (banner) img?.d else null)
            b.tileIcon.setImageDrawable(if (banner) null else img?.d)
            b.tileBanner.visibility = if (banner) View.VISIBLE else View.GONE
            b.tileIconBox.visibility = if (img == null || banner) View.GONE else View.VISIBLE
            // An icon alone on a card looks lost: wash the card in the icon's own colour.
            b.tileIconBox.background = if (img != null && !banner) iconWash(img.wash) else null
        }
    }

    /** The app's TV banner at tile size, or null if it has none. */
    private fun bannerThumb(pkg: String): Bitmap? {
        val pm = packageManager
        val activity = PackageUtil.launchIntent(this, pkg)?.let { pm.resolveActivity(it, 0)?.activityInfo }
        val app = activity?.applicationInfo ?: pm.getApplicationInfo(pkg, 0)
        val res = activity?.banner?.takeIf { it != 0 } ?: app.banner.takeIf { it != 0 } ?: return null
        val small = runCatching {
            val source = android.graphics.ImageDecoder.createSource(pm.getResourcesForApplication(app), res)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val scale = maxOf(BANNER_W / info.size.width.toFloat(), BANNER_H / info.size.height.toFloat()).coerceAtMost(1f)
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
        if (small != null) {
            val thumb = thumbnail(BitmapDrawable(resources, small), BANNER_W, BANNER_H, crop = true)
            small.recycle()
            return thumb
        }
        // Not a plain image (a vector or layered drawable): small to draw anyway.
        val d = (activity?.loadBanner(pm) ?: pm.getApplicationBanner(app)) ?: return null
        return thumbnail(d, BANNER_W, BANNER_H, crop = true)
    }

    /**
     * Draws [d] into a small bitmap ([crop]: fill and centre-crop, like the tile), so the full-size original can go.
     * Banners go on the card colour as RGB_565, half the memory of ARGB with nothing visible lost.
     */
    private fun thumbnail(d: Drawable, w: Int, h: Int, crop: Boolean): Bitmap {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: w
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: h
        val bmp = Bitmap.createBitmap(w, h, if (crop) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (crop) c.drawColor(ContextCompat.getColor(this, R.color.surface))
        val scale = if (crop) maxOf(w / iw.toFloat(), h / ih.toFloat()) else minOf(w / iw.toFloat(), h / ih.toFloat())
        val dw = (iw * scale).toInt(); val dh = (ih * scale).toInt()
        val left = (w - dw) / 2; val top = (h - dh) / 2
        d.setBounds(left, top, left + dw, top + dh)
        d.draw(c)
        return bmp
    }

    /** The app's colour for the backdrop: its average, kept dark and soft so text stays readable over it. */
    private fun washOf(bmp: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bmp, 8, 8, true)
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (x in 0 until 8) for (y in 0 until 8) {
            val p = small.getPixel(x, y)
            val a = Color.alpha(p).toLong()
            r += Color.red(p) * a; g += Color.green(p) * a; b += Color.blue(p) * a; n += a
        }
        if (small !== bmp) small.recycle()
        if (n == 0L) return washColor
        val hsv = FloatArray(3)
        Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        hsv[1] = (hsv[1] * 1.15f).coerceAtMost(0.75f)
        hsv[2] = 0.62f
        return Color.HSVToColor(WASH_ALPHA, hsv)
    }

    private fun iconWash(color: Int) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.argb(0x55, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT)).apply {
        cornerRadius = resources.getDimension(R.dimen.radius_card) - resources.getDimension(R.dimen.focus_ring)
    }

    /** A loaded tile image: the TV banner, or the icon when the app has none (or icons are chosen), and its colour. */
    class Img(val d: Drawable?, val banner: Boolean, val wash: Int, val bytes: Int)

    class TileHolder(val b: ItemHomeTileBinding, private val scale: Float) : RecyclerView.ViewHolder(b.root) {
        private val z = b.root.resources.displayMetrics.density * 8

        init {
            val r = b.root.resources.getDimension(R.dimen.radius_card) - b.root.resources.getDimension(R.dimen.focus_ring)
            b.tileBanner.clipToOutline = true
            b.tileBanner.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }

        /** Focus lifts the card a little toward the viewer. */
        fun lift(v: View, up: Boolean) {
            v.animate().scaleX(if (up) scale else 1f).scaleY(if (up) scale else 1f).translationZ(if (up) z else 0f)
                .setDuration(LIFT_MS).start()
        }
    }

    companion object {
        private const val SPANS = 12
        private const val FAV_COLUMNS = 4
        private const val APP_COLUMNS = 6
        private const val TYPE_HEADER = 0
        private const val TYPE_FAV = 1
        private const val TYPE_APP = 2
        private const val HEALTH_MS = 30_000L
        private const val WASH_MS = 320L
        private const val LIFT_MS = 140L
        private const val FAV_LIFT = 1.05f
        private const val APP_LIFT = 1.08f
        private const val WASH_ALPHA = 0x70
        /** About 70 banners: more than fit on screen, a few MB at most. */
        private const val CACHE_BYTES = 8 * 1024 * 1024
        /** Tile-sized copies: about the favourite tile's on-screen size at 1080p. */
        private const val BANNER_W = 320
        private const val BANNER_H = 180
        private const val ICON_PX = 96

        /** Test hook: where memory is read from. */
        internal var meminfo: () -> String = { File("/proc/meminfo").readText() }

        /** The line above the clock. */
        fun greeting(hour: Int) = when (hour) {
            in 5..11 -> R.string.home_greet_morning
            in 12..16 -> R.string.home_greet_afternoon
            in 17..21 -> R.string.home_greet_evening
            else -> R.string.home_greet_night
        }
    }
}

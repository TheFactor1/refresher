package com.thefactor1.taskskiller.ui

import android.graphics.Outline
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.databinding.ActivityAllAppsBinding
import com.thefactor1.taskskiller.databinding.ItemHomeTileBinding
import com.thefactor1.taskskiller.home.HomeLogic
import com.thefactor1.taskskiller.home.HomePrefs
import com.thefactor1.taskskiller.home.HomeTheme
import com.thefactor1.taskskiller.home.TileImages
import com.thefactor1.taskskiller.util.PackageUtil
import java.util.concurrent.Executors

/**
 * Every app, favourites first. The grid is exactly as tall as the whole rows that fit, and moving focus scrolls by
 * whole rows, so a tile is never shown cut in half.
 */
class AllAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAllAppsBinding
    private lateinit var prefs: HomePrefs
    private val worker = Executors.newSingleThreadExecutor()
    private val adapter = Tiles()
    private var apps: List<HomeLogic.App> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAllAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = HomePrefs.get(this)
        val theme = HomeTheme.of(prefs.theme)
        binding.appsFrame.background = theme.backdrop(resources.displayMetrics.widthPixels)
        window.statusBarColor = theme.base

        binding.appsGrid.layoutManager = WholeRows(this, COLUMNS)
        binding.appsGrid.adapter = adapter
        binding.appsGrid.itemAnimator = null
        // Size the grid to the whole rows that fit.
        binding.appsArea.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            val row = resources.getDimensionPixelSize(R.dimen.home_app_height) + 2 * resources.getDimensionPixelSize(R.dimen.home_tile_pad)
            val rows = ((bottom - top) / row).coerceAtLeast(1)
            binding.appsGrid.post { binding.appsGrid.layoutParams = binding.appsGrid.layoutParams.apply { height = rows * row } }
        }
        load()
    }

    private fun load() {
        worker.execute {
            val list = PackageUtil.installedApps(this).filter { it.hasLauncherEntry }.map { HomeLogic.App(it.packageName, it.label) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                apps = list
                render(focusFirst = true)
            }
        }
    }

    private fun render(focusFirst: Boolean = false) {
        val (favs, rest) = HomeLogic.arrange(apps, prefs.favourites, prefs.hidden)
        adapter.submit(favs + rest, favs.size)
        binding.appsCount.text = resources.getQuantityString(R.plurals.home_app_count, favs.size + rest.size, favs.size + rest.size)
        if (focusFirst) binding.appsGrid.post { binding.appsGrid.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<View>(R.id.tileCard)?.requestFocus() }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /** Scrolls so the whole focused row shows (its padding too), which keeps rows lined up with the grid's edge. */
    class WholeRows(context: android.content.Context, columns: Int) : GridLayoutManager(context, columns) {
        override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean) =
            super.requestChildRectangleOnScreen(parent, child, Rect(0, 0, child.width, child.height), immediate, focusedChildVisible)
    }

    inner class Tiles : RecyclerView.Adapter<Tiles.Holder>() {
        private var rows: List<HomeLogic.App> = emptyList()
        private var favCount = 0

        init { setHasStableIds(true) }

        fun submit(list: List<HomeLogic.App>, favourites: Int) {
            rows = list; favCount = favourites
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size
        override fun getItemId(position: Int) = rows[position].packageName.hashCode().toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemHomeTileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = rows[position]
            val b = holder.b
            b.tileIconName.text = app.label
            b.tileCard.contentDescription = app.label
            b.tileCard.setOnClickListener { AppActions.open(this@AllAppsActivity, app) }
            b.tileCard.setOnLongClickListener { AppActions.options(this@AllAppsActivity, app) { render() }; true }
            b.tileCard.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) { AppActions.options(this@AllAppsActivity, app) { render() }; true } else false
            }
            b.tileFav.visibility = if (position < favCount) View.VISIBLE else View.GONE
            b.tileBanner.tag = app.packageName
            b.tileBanner.setImageDrawable(null); b.tileIcon.setImageDrawable(null)
            b.tileBanner.visibility = View.GONE; b.tileIconBox.visibility = View.GONE
            TileImages.load(this@AllAppsActivity, app.packageName, prefs.tileStyle) { img ->
                if (b.tileBanner.tag != app.packageName) return@load
                b.tileBanner.setImageDrawable(if (img.banner) img.d else null)
                b.tileIcon.setImageDrawable(if (img.banner) null else img.d)
                b.tileBanner.visibility = if (img.banner) View.VISIBLE else View.GONE
                b.tileIconBox.visibility = if (img.banner || img.d == null) View.GONE else View.VISIBLE
            }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.b.tileBanner.setImageDrawable(null); holder.b.tileIcon.setImageDrawable(null)
        }

        inner class Holder(val b: ItemHomeTileBinding) : RecyclerView.ViewHolder(b.root) {
            init {
                val r = resources.getDimension(R.dimen.radius_card) - resources.getDimension(R.dimen.focus_ring)
                b.tileBanner.clipToOutline = true
                b.tileBanner.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, r)
                }
                b.tileCard.setOnFocusChangeListener { v, has ->
                    val s = if (has) LIFT else 1f
                    v.animate().scaleX(s).scaleY(s).translationZ(if (has) resources.displayMetrics.density * 8 else 0f).setDuration(140).start()
                }
            }
        }
    }

    companion object {
        private const val COLUMNS = 6
        private const val LIFT = 1.08f
    }
}

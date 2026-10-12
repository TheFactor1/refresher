package com.thefactor1.taskskiller.ui

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.data.Rule
import com.thefactor1.taskskiller.data.RuleStore
import com.thefactor1.taskskiller.databinding.ActivityAppPickerBinding
import com.thefactor1.taskskiller.databinding.ItemAppTileBinding
import com.thefactor1.taskskiller.schedule.RestartScheduler
import com.thefactor1.taskskiller.util.PackageUtil

/**
 * "Add an app": the apps that most often need a restart first, then every app
 * A–Z. Picking one saves it straight away with sensible settings and opens its
 * rule, so adding an app is two presses; an app that's already added opens
 * its existing rule instead of making a second one.
 */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private val adapter = TileAdapter(::pick)
    private var apps: List<PackageUtil.InstalledApp> = emptyList()
    private var showSystem = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val grid = GridLayoutManager(this, COLUMNS)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (adapter.isHeader(position)) COLUMNS else 1
        }
        binding.appGrid.layoutManager = grid
        binding.appGrid.adapter = adapter
        binding.appGrid.itemAnimator = null
        binding.appGrid.isFocusable = false

        binding.systemToggle.setOnClickListener {
            showSystem = !showSystem
            binding.systemToggle.setText(if (showSystem) R.string.pick_hide_system else R.string.pick_show_system)
            show()
        }

        // Listing every package takes long enough on a TV box to keep off the main thread.
        Thread {
            val loaded = PackageUtil.installedApps(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                apps = loaded
                show()
                binding.appGrid.post { binding.appGrid.findViewHolderForAdapterPosition(adapter.firstTile())?.itemView?.requestFocus() }
            }
        }.start()
    }

    private fun show() {
        binding.loadingText.visibility = View.GONE
        val added = RuleStore.get(this).all().map { it.packageName }.toSet()
        val visible = apps.filter { showSystem || !it.isSystem || it.hasLauncherEntry }
        adapter.submit(Suggestions.sections(visible), added, getString(R.string.pick_suggested), getString(R.string.pick_all))
    }

    private fun pick(app: PackageUtil.InstalledApp) {
        val store = RuleStore.get(this)
        val rule = store.all().firstOrNull { it.packageName == app.packageName } ?: Rule(
            id = store.nextId(),
            packageName = app.packageName,
            label = app.label,
            intervalMinutes = DEFAULT_INTERVAL_MINUTES,
            relaunch = app.hasLauncherEntry,
            anchorAt = System.currentTimeMillis()
        ).also {
            store.upsert(it)
            if (store.masterEnabled) RestartScheduler.schedule(this, it)
        }
        startActivity(Intent(this, RuleEditActivity::class.java).putExtra(RuleEditActivity.EXTRA_RULE_ID, rule.id))
        finish()
    }

    /** Tiles under section headings; headings span the whole row. */
    class TileAdapter(private val onPick: (PackageUtil.InstalledApp) -> Unit) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private sealed class Item {
            data class Header(val text: String) : Item()
            data class Tile(val app: PackageUtil.InstalledApp, val added: Boolean) : Item()
        }

        private var items: List<Item> = emptyList()
        private val icons = HashMap<String, Drawable?>()

        fun submit(sections: Suggestions.Sections, added: Set<String>, suggestedTitle: String, allTitle: String) {
            items = buildList {
                if (sections.suggested.isNotEmpty()) {
                    add(Item.Header(suggestedTitle))
                    sections.suggested.forEach { add(Item.Tile(it, it.packageName in added)) }
                }
                add(Item.Header(allTitle))
                sections.rest.forEach { add(Item.Tile(it, it.packageName in added)) }
            }
            notifyDataSetChanged()
        }

        fun isHeader(position: Int) = items.getOrNull(position) is Item.Header
        fun firstTile() = items.indexOfFirst { it is Item.Tile }.coerceAtLeast(0)

        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (isHeader(position)) HEADER else TILE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == HEADER) {
                object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_section, parent, false)) {}
            } else {
                TileHolder(ItemAppTileBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Header -> (holder.itemView as TextView).text = item.text
                is Item.Tile -> {
                    val b = (holder as TileHolder).binding
                    val context = b.root.context
                    b.tileLabel.text = item.app.label
                    b.tileIcon.setImageDrawable(icons.getOrPut(item.app.packageName) { PackageUtil.icon(context, item.app.packageName) })
                    b.tileNote.text = if (item.added) context.getString(R.string.pick_added) else ""
                    b.tileNote.visibility = if (item.added) View.VISIBLE else View.INVISIBLE
                    b.root.contentDescription = item.app.label + if (item.added) ", " + context.getString(R.string.pick_added) else ""
                    b.root.setOnClickListener { onPick(item.app) }
                }
            }
        }

        class TileHolder(val binding: ItemAppTileBinding) : RecyclerView.ViewHolder(binding.root)

        private companion object {
            const val HEADER = 0
            const val TILE = 1
        }
    }

    companion object {
        const val COLUMNS = 6
        /** New apps start at every 6 hours, only while the TV sleeps, opened again after 3 s. */
        const val DEFAULT_INTERVAL_MINUTES = 360
    }
}

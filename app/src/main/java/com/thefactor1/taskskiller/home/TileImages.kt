package com.thefactor1.taskskiller.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.thefactor1.taskskiller.R
import com.thefactor1.taskskiller.util.PackageUtil
import java.util.concurrent.Executors

/**
 * App tile images for Home and All apps, shared: tile-sized copies (banners decoded straight to that size), one
 * background thread, and one small cache sized in bytes. The full-size originals never stay in memory.
 */
object TileImages {

    /**
     * A tile image: the TV banner, or the icon when the app has none (or icons are chosen). Also the app's colour
     * and, for banners, a tiny blurred copy that Home stretches across the screen behind everything.
     */
    class Img(val d: Drawable?, val banner: Boolean, val bytes: Int, val color: Int?, val art: Bitmap?)

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Img>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Img) = value.bytes
    }
    private var style: HomePrefs.TileStyle? = null

    fun cached(pkg: String): Img? = cache.get(pkg)

    /** Loads [pkg]'s image in the background and hands it to [done] on the main thread. */
    fun load(context: Context, pkg: String, tiles: HomePrefs.TileStyle, done: (Img) -> Unit) {
        if (tiles != style) { cache.evictAll(); style = tiles }
        cache.get(pkg)?.let { done(it); return }
        val app = context.applicationContext
        worker.execute {
            val banner = if (tiles == HomePrefs.TileStyle.BANNERS) runCatching { bannerThumb(app, pkg) }.getOrNull() else null
            val bmp = banner ?: runCatching { app.packageManager.getApplicationIcon(pkg) }.getOrNull()?.let { thumbnail(app, it, ICON_PX, ICON_PX, crop = false) }
            val color = bmp?.let { Ambience.colorOf(it) }
            val art = banner?.let { Ambience.artOf(it) }
            val img = Img(bmp?.let { BitmapDrawable(app.resources, it) }, banner != null, (bmp?.byteCount ?: 1) + (art?.byteCount ?: 0), color, art)
            main.post { cache.put(pkg, img); done(img) }
        }
    }

    fun trim() = cache.evictAll()

    /** The app's TV banner at tile size, or null if it has none. */
    private fun bannerThumb(context: Context, pkg: String): Bitmap? {
        val pm = context.packageManager
        val activity = PackageUtil.launchIntent(context, pkg)?.let { pm.resolveActivity(it, 0)?.activityInfo }
        val app = activity?.applicationInfo ?: pm.getApplicationInfo(pkg, 0)
        val res = activity?.banner?.takeIf { it != 0 } ?: app.banner.takeIf { it != 0 } ?: return null
        val small = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(pm.getResourcesForApplication(app), res)) { decoder, info, _ ->
                val scale = maxOf(BANNER_W / info.size.width.toFloat(), BANNER_H / info.size.height.toFloat()).coerceAtMost(1f)
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
        if (small != null) {
            val thumb = thumbnail(context, BitmapDrawable(context.resources, small), BANNER_W, BANNER_H, crop = true)
            small.recycle()
            return thumb
        }
        // Not a plain image (a vector or layered drawable): small to draw anyway.
        val d = (activity?.loadBanner(pm) ?: pm.getApplicationBanner(app)) ?: return null
        return thumbnail(context, d, BANNER_W, BANNER_H, crop = true)
    }

    /**
     * Draws [d] into a small bitmap ([crop]: fill and centre-crop, like the tile). Banners go on the card colour
     * as RGB_565, half the memory of ARGB with nothing visible lost.
     */
    private fun thumbnail(context: Context, d: Drawable, w: Int, h: Int, crop: Boolean): Bitmap {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: w
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: h
        val bmp = Bitmap.createBitmap(w, h, if (crop) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (crop) c.drawColor(ContextCompat.getColor(context, R.color.surface))
        val scale = if (crop) maxOf(w / iw.toFloat(), h / ih.toFloat()) else minOf(w / iw.toFloat(), h / ih.toFloat())
        val dw = (iw * scale).toInt(); val dh = (ih * scale).toInt()
        val left = (w - dw) / 2; val top = (h - dh) / 2
        d.setBounds(left, top, left + dw, top + dh)
        d.draw(c)
        return bmp
    }

    /** About 60 banners: more than both screens show, a few MB at most. */
    private const val CACHE_BYTES = 6 * 1024 * 1024
    /** Tile-sized copies: about a dock tile's on-screen size at 1080p. */
    private const val BANNER_W = 240
    private const val BANNER_H = 135
    private const val ICON_PX = 96
}

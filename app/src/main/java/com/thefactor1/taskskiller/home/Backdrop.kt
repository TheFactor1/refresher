package com.thefactor1.taskskiller.home

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.TransitionDrawable
import kotlin.random.Random

/**
 * Home's "cinema" backdrop, back to front: the theme's deep base and glows, the selected app's art (its banner,
 * tiny and blurred, stretched past the edges), a dark vignette and a dark floor for the dock, then a faint grain.
 * Plain drawables: drawn once per change, nothing runs while Home sits still.
 */
class Backdrop(private val res: Resources, private val theme: HomeTheme) {

    private val w = res.displayMetrics.widthPixels
    private val h = res.displayMetrics.heightPixels
    private var art: Drawable = ColorDrawable(Color.TRANSPARENT)
    private val layers: LayerDrawable

    val drawable: Drawable get() = layers

    init {
        val vignette = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.5f, 0.42f)
            gradientRadius = w * 0.62f
            colors = intArrayOf(0x6604060B, 0x9904060B.toInt(), 0xE604060B.toInt())
        }
        val floor = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xEB04060B.toInt(), 0x0004060B))
        layers = LayerDrawable(arrayOf(theme.backdrop(w), art, vignette, floor, grain()))
        layers.setId(ART, ART)
        // The art bleeds past every edge, so the blur never shows a hard border.
        val bleedX = (w * 0.12f).toInt(); val bleedY = (h * 0.12f).toInt()
        layers.setLayerInset(ART, -bleedX, -bleedY, -bleedX, -bleedY)
        layers.setLayerInsetTop(FLOOR, (h * 0.55f).toInt())
    }

    /** Cross-fades to [img]'s art, or to a soft glow of [color] when the app has no banner. */
    fun show(artBitmap: Bitmap?, color: Int) {
        val next: Drawable = if (artBitmap != null) {
            BitmapDrawable(res, artBitmap).apply {
                isFilterBitmap = true
                alpha = 0xB8
                // Richer colour, but never bright enough to wash out the white text over it.
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                    setSaturation(1.5f)
                    postConcat(ColorMatrix().apply { setScale(0.72f, 0.72f, 0.72f, 1f) })
                })
            }
        } else {
            GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                setGradientCenter(0.5f, 0.45f)
                gradientRadius = w * 0.55f
                colors = intArrayOf(Color.argb(0xA0, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT)
            }
        }
        val fade = TransitionDrawable(arrayOf(art, next)).apply { isCrossFadeEnabled = true }
        art = next
        layers.setDrawableByLayerId(ART, fade)
        layers.invalidateSelf()
        fade.startTransition(FADE_MS)
    }

    /** A faint film grain: one small random tile, repeated. */
    private fun grain(): Drawable {
        val size = 128
        val rnd = Random(7)
        val px = IntArray(size * size) { if (rnd.nextBoolean()) Color.argb(rnd.nextInt(10, 22), 255, 255, 255) else Color.argb(rnd.nextInt(10, 26), 0, 0, 0) }
        val bmp = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        return BitmapDrawable(res, bmp).apply { setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }

    companion object {
        private const val ART = 1
        private const val FLOOR = 3
        private const val FADE_MS = 360
    }
}

package com.thefactor1.taskskiller.home

import android.graphics.Bitmap
import android.graphics.Color

/**
 * The colour and the blurred backdrop art Home takes from the selected app. Both come from the small tile image
 * already loaded: the art is 32x18 pixels, blurred here once, and the screen stretches it smoothly, so the TV
 * never runs a real blur.
 */
object Ambience {

    const val ART_W = 32
    const val ART_H = 18

    /** The app's colour: its average, made vivid enough to glow. Null for greys (they borrow the theme's accent). */
    fun colorOf(bmp: Bitmap): Int? {
        val small = Bitmap.createScaledBitmap(bmp, 8, 8, true)
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (x in 0 until 8) for (y in 0 until 8) {
            val p = small.getPixel(x, y)
            val a = Color.alpha(p).toLong()
            r += Color.red(p) * a; g += Color.green(p) * a; b += Color.blue(p) * a; n += a
        }
        if (small !== bmp) small.recycle()
        if (n == 0L) return null
        return vivid(Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt()))
    }

    /** Lifts a colour to a glow: greys give null, everything else gets enough saturation and light to show. */
    fun vivid(c: Int): Int? {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        if (hsv[1] < 0.18f) return null
        hsv[1] = hsv[1].coerceIn(0.55f, 0.9f)
        hsv[2] = hsv[2].coerceIn(0.8f, 1f)
        return Color.HSVToColor(hsv)
    }

    /** A tiny, blurred copy of a banner for the backdrop. */
    fun artOf(bmp: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(bmp, ART_W, ART_H, true)
        val px = IntArray(ART_W * ART_H)
        small.getPixels(px, 0, ART_W, 0, 0, ART_W, ART_H)
        if (small !== bmp) small.recycle()
        repeat(2) { boxBlur(px, ART_W, ART_H, 2) }
        return Bitmap.createBitmap(px, ART_W, ART_H, Bitmap.Config.ARGB_8888)
    }

    /** A box blur of radius [r], rows then columns, edges clamped. */
    fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        fun pass(src: IntArray, dst: IntArray, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            for (line in 0 until lines) for (i in 0 until len) {
                var sr = 0; var sg = 0; var sb = 0; var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val p = src[if (horizontal) line * w + j else j * w + line]
                    sr += Color.red(p); sg += Color.green(p); sb += Color.blue(p); n++
                }
                dst[if (horizontal) line * w + i else i * w + line] = Color.rgb(sr / n, sg / n, sb / n)
            }
        }
        pass(px, tmp, true)
        pass(tmp, px, false)
    }
}

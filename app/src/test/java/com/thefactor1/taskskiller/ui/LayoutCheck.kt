package com.thefactor1.taskskiller.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.abs

/**
 * Margin, alignment and clipping checks on a laid-out screen. Each returns
 * plain-English problems; a screen passes when the list is empty.
 */
object LayoutCheck {

    /** Everything readable sits inside the TV's 5% overscan-safe area. */
    fun safeArea(root: View): List<String> {
        val d = root.resources.displayMetrics.density
        val w = root.width
        val h = root.height
        val safe = Rect((w * 0.05f).toInt(), (h * 0.05f).toInt(), w - (w * 0.05f).toInt(), h - (h * 0.05f).toInt())
        return leaves(root).mapNotNull { v ->
            val r = visibleRect(v) ?: return@mapNotNull null
            if (safe.contains(r)) null
            else "${describe(v)} at ${dp(r, d)} leaves the safe area ${dp(safe, d)}"
        }
    }

    /** No text is cut off: no ellipsis, no line pushed below the view's bottom. */
    fun textFits(root: View, allowEllipsis: (TextView) -> Boolean = { false }): List<String> =
        leaves(root).filterIsInstance<TextView>().filter { it.text.isNotEmpty() && visibleRect(it) != null }.mapNotNull { tv ->
            val layout = tv.layout ?: return@mapNotNull "${describe(tv)} has no layout"
            val ellipsized = (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }
            val textHeight = layout.height + tv.compoundPaddingTop + tv.compoundPaddingBottom
            when {
                ellipsized && !allowEllipsis(tv) -> "${describe(tv)} is cut off with an ellipsis"
                textHeight > tv.height + 1 -> "${describe(tv)} is clipped: text needs ${textHeight}px, has ${tv.height}px"
                else -> null
            }
        }

    /** No two leaf elements (text, icons, dots, switches) overlap: a sign of something pushed out of place. */
    fun noOverlaps(root: View): List<String> {
        val items = leaves(root).filter { it !is ViewGroup }.mapNotNull { v -> visibleRect(v)?.let { v to it } }
        val out = mutableListOf<String>()
        for (i in items.indices) for (j in i + 1 until items.size) {
            val (a, ra) = items[i]
            val (b, rb) = items[j]
            if (isAncestor(a, b) || isAncestor(b, a)) continue
            val overlap = Rect()
            if (overlap.setIntersect(ra, rb) && overlap.width() > 1 && overlap.height() > 1) {
                out += "${describe(a)} overlaps ${describe(b)}"
            }
        }
        return out
    }

    /** These views share a left edge (within a pixel). */
    fun sameLeft(name: String, vararg views: View): List<String> {
        val lefts = views.filter { it.isShown }.map { leftOf(it) }
        return if (lefts.isEmpty() || lefts.max() - lefts.min() <= 1) emptyList()
        else listOf("$name: left edges differ ${views.filter { it.isShown }.map { describe(it) + "@" + leftOf(it) }}")
    }

    /** These views share a right edge (within a pixel). */
    fun sameRight(name: String, vararg views: View): List<String> {
        val rights = views.filter { it.isShown }.map { leftOf(it) + it.width }
        return if (rights.isEmpty() || rights.max() - rights.min() <= 1) emptyList()
        else listOf("$name: right edges differ ${views.filter { it.isShown }.map { describe(it) + "@" + (leftOf(it) + it.width) }}")
    }

    /** These views are the same height (within a pixel). */
    fun sameHeight(name: String, views: List<View>): List<String> {
        val hs = views.map { it.height }
        return if (hs.isEmpty() || hs.max() - hs.min() <= 1) emptyList() else listOf("$name: heights differ $hs")
    }

    /** [view]'s left edge is exactly [dp] from the screen's left. */
    fun leftAt(view: View, dp: Int): List<String> {
        val expected = Math.round(dp * view.resources.displayMetrics.density)
        val actual = leftOf(view)
        return if (abs(actual - expected) <= 1) emptyList() else listOf("${describe(view)} starts at ${actual}px, not the ${dp}dp gutter (${expected}px)")
    }

    fun leftOf(v: View): Int = IntArray(2).also { v.getLocationInWindow(it) }[0]

    fun leaves(root: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE || v.width == 0 || v.height == 0) return
            if (v is ViewGroup && v.childCount > 0) (0 until v.childCount).forEach { walk(v.getChildAt(it)) } else out += v
        }
        walk(root)
        return out
    }

    /** The on-screen part of [v], or null when none of it is on screen. */
    private fun visibleRect(v: View): Rect? {
        if (!v.isShown || v.alpha == 0f) return null
        val r = Rect()
        return if (v.getGlobalVisibleRect(r) && !r.isEmpty) r else null
    }

    private fun isAncestor(a: View, b: View): Boolean {
        var p = b.parent
        while (p is View) { if (p === a) return true; p = p.parent }
        return false
    }

    fun describe(v: View): String {
        val id = if (v.id != View.NO_ID) try { v.resources.getResourceEntryName(v.id) } catch (e: Exception) { "?" } else v.javaClass.simpleName
        val text = (v as? TextView)?.text?.toString()?.take(40)?.let { " \"$it\"" } ?: ""
        return "$id$text"
    }

    private fun dp(r: Rect, d: Float) = "[${(r.left / d).toInt()},${(r.top / d).toInt()} – ${(r.right / d).toInt()},${(r.bottom / d).toInt()}]dp"
}

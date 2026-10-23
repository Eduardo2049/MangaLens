package com.example.translation

import kotlin.math.abs

/**
 * Tiny grayscale grid of the screen used to detect scrolling/page changes without touching the network.
 * Cells covered by MangaLens' own views (floating bubble, translation cards, status bar) are [MASKED] so
 * our overlays never make the screen look "changed" — that feedback loop is what used to re-translate
 * the same page forever.
 */
class FrameThumbnail(val cols: Int, val rows: Int, val luma: IntArray) {
    init {
        require(luma.size == cols * rows) { "luma must have cols*rows entries" }
    }

    /**
     * Fraction (0..1) of cells, visible in both thumbnails, whose brightness differs by more than
     * [cellThreshold]. Returns 1 when the grids are incompatible (e.g. after a rotation).
     */
    fun changedFraction(other: FrameThumbnail, cellThreshold: Int = DEFAULT_CELL_THRESHOLD): Float {
        if (other.cols != cols || other.rows != rows) return 1f
        var compared = 0
        var changed = 0
        for (i in luma.indices) {
            val a = luma[i]
            val b = other.luma[i]
            if (a == MASKED || b == MASKED) continue
            compared++
            if (abs(a - b) > cellThreshold) changed++
        }
        return if (compared == 0) 0f else changed.toFloat() / compared
    }

    companion object {
        const val MASKED = -1
        const val DEFAULT_CELL_THRESHOLD = 18
    }
}

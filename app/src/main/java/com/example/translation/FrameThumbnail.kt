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

    /** 64-bit difference hash, robust to small brightness shifts; used as the page cache key. */
    fun fingerprint(): Long {
        // Downsample to 9x8 blocks, then compare horizontally adjacent blocks.
        val bw = 9
        val bh = 8
        val blocks = IntArray(bw * bh)
        for (by in 0 until bh) {
            for (bx in 0 until bw) {
                var sum = 0L
                var n = 0
                val x0 = bx * cols / bw
                val x1 = ((bx + 1) * cols / bw).coerceAtLeast(x0 + 1)
                val y0 = by * rows / bh
                val y1 = ((by + 1) * rows / bh).coerceAtLeast(y0 + 1)
                for (y in y0 until y1) for (x in x0 until x1) {
                    val v = luma[y * cols + x]
                    if (v != MASKED) {
                        sum += v; n++
                    }
                }
                blocks[by * bw + bx] = if (n == 0) 0 else (sum / n).toInt()
            }
        }
        var hash = 0L
        var bit = 0
        for (by in 0 until bh) for (bx in 0 until bw - 1) {
            if (blocks[by * bw + bx] > blocks[by * bw + bx + 1]) hash = hash or (1L shl bit)
            bit++
        }
        return hash
    }

    companion object {
        const val MASKED = -1
        const val DEFAULT_CELL_THRESHOLD = 18

        fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
    }
}

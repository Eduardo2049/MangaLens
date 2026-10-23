package com.example.translation

import kotlin.math.max
import kotlin.math.min

/** Axis-aligned rectangle in capture (screen) pixels. Plain Kotlin so it can be unit-tested on the JVM. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong().coerceAtLeast(0) * height.toLong().coerceAtLeast(0)
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun intersects(other: Box): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

    fun union(other: Box): Box =
        Box(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

    fun inflate(dx: Int, dy: Int): Box = Box(left - dx, top - dy, right + dx, bottom + dy)

    fun clampTo(width: Int, height: Int): Box =
        Box(left.coerceIn(0, width), top.coerceIn(0, height), right.coerceIn(0, width), bottom.coerceIn(0, height))

    /** Fraction of this box covered by [other] (0..1). */
    fun overlapRatio(other: Box): Float {
        if (!intersects(other) || area == 0L) return 0f
        val ix = min(right, other.right) - max(left, other.left)
        val iy = min(bottom, other.bottom) - max(top, other.top)
        return (ix.toLong() * iy).toFloat() / area
    }
}

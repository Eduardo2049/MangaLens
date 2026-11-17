package com.example.translation

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Result of laying out wrapped text at one size and width. */
data class TextMeasure(
    val height: Int,
    val maxLineWidth: Float,
    val lineCount: Int,
    /** True when a word had to be split without a hyphen because it is wider than the line. */
    val breaksWords: Boolean
)

/**
 * Measures wrapped text exactly as it will be drawn. On Android this is a StaticLayout built with the same
 * paint and break strategy used to draw the card; tests use a deterministic fake.
 */
fun interface TextMeasurer {
    fun measure(text: String, textSizePx: Float, maxWidthPx: Int): TextMeasure
}

/** All sizes in screen pixels. */
data class FitSpec(
    val minTextPx: Float,
    /** Expansion stops as soon as the text reaches this size (usually the user's preferred size). */
    val comfortableTextPx: Float,
    val maxTextPx: Float,
    val stepPx: Float,
    val paddingPx: Int,
    val minCardWidthPx: Int
)

data class FitResult(
    val rect: Box,
    val textPx: Float,
    val textHeight: Int,
    /** Even at [FitSpec.minTextPx] and the widest allowed card the text did not fit the original box height. */
    val overflow: Boolean
)

/**
 * Chooses the card rectangle and font size for one translated bubble.
 *
 * The OCR box hugs the original glyphs, which is a good anchor but often too small for the translation
 * (Portuguese is ~30% longer than English and far longer than Japanese/Korean). Strategy:
 *  1. try the anchor box itself, then progressively larger boxes centered on it;
 *  2. a candidate is rejected when it would cover another bubble (obstacles) — covering the art around a
 *     bubble is acceptable, covering a neighbour's text is not;
 *  3. for every candidate, binary-search the largest size whose *measured* layout fits both dimensions
 *     without splitting words;
 *  4. stop at the first candidate whose size is comfortable (least art covered); otherwise keep the best;
 *  5. if nothing fits even at the minimum size, grow the card height (explicit overflow, never truncation).
 */
object BubbleTextFitter {

    /** Width/height growth steps tried in order. Height grows more slowly to keep the bubble's shape. */
    private val GROWTH = floatArrayOf(1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f, 2.5f)

    fun fit(
        text: String,
        anchor: Box,
        bounds: Box,
        obstacles: List<Box>,
        spec: FitSpec,
        measurer: TextMeasurer,
        isVertical: Boolean = anchor.height > anchor.width * 1.6f
    ): FitResult {
        // Vertical CJK columns are narrow: start from a horizontally readable width right away.
        val baseWidth = if (isVertical) max(anchor.width * 2, (anchor.height * 0.6f).roundToInt()) else anchor.width
        val baseW = baseWidth.coerceAtLeast(spec.minCardWidthPx).coerceAtMost(bounds.width)
        val baseH = anchor.height.coerceAtMost(bounds.height)
        val maxW = min(bounds.width, max(baseW, (baseW * if (isVertical) 1.5f else 2f).roundToInt()))
        val maxH = min(bounds.height, (baseH * 1.6f).roundToInt())

        var best: FitResult? = null
        var lastW = -1
        var lastH = -1
        for ((index, g) in GROWTH.withIndex()) {
            val w = (baseW * g).roundToInt().coerceAtMost(maxW)
            val h = (baseH * (1f + (g - 1f) * 0.5f)).roundToInt().coerceAtMost(maxH)
            if (w == lastW && h == lastH) continue
            lastW = w
            lastH = h
            val rect = centeredRect(anchor, w, h, bounds)
            // The anchor itself is always allowed; larger cards must not cover another bubble.
            if (index > 0 && obstacles.any { it.intersects(rect) }) continue
            val size = largestFittingSize(text, measurer, w - spec.paddingPx * 2, h - spec.paddingPx * 2, spec)
                ?: continue
            val measured = measurer.measure(text, size, (w - spec.paddingPx * 2).coerceAtLeast(1))
            val candidate = FitResult(rect, size, measured.height, overflow = false)
            if (best == null || size > best.textPx + 0.01f) best = candidate
            if (size >= spec.comfortableTextPx) return candidate
        }
        best?.let { return it }

        // Overflow: smallest readable size at the widest card that does not cover a neighbour, then grow
        // the height to whatever the measured layout needs.
        val width = widestFreeWidth(anchor, baseW, maxW, baseH, bounds, obstacles)
        val innerW = (width - spec.paddingPx * 2).coerceAtLeast(1)
        val measured = measurer.measure(text, spec.minTextPx, innerW)
        val height = max(baseH, measured.height + spec.paddingPx * 2).coerceAtMost(bounds.height)
        return FitResult(centeredRect(anchor, width, height, bounds), spec.minTextPx, measured.height, overflow = true)
    }

    /**
     * Largest size in [spec.minTextPx]..[spec.maxTextPx] (multiples of [FitSpec.stepPx]) whose layout fits
     * [width] x [height], or null when even the minimum does not fit. Binary search: O(log n) layouts
     * instead of one per step.
     */
    fun largestFittingSize(text: String, measurer: TextMeasurer, width: Int, height: Int, spec: FitSpec): Float? {
        if (width <= 0 || height <= 0) return null
        val steps = ((spec.maxTextPx - spec.minTextPx) / spec.stepPx).toInt().coerceAtLeast(0)
        fun sizeAt(i: Int) = spec.minTextPx + i * spec.stepPx
        fun fits(i: Int): Boolean {
            val m = measurer.measure(text, sizeAt(i), width)
            return m.height <= height && m.maxLineWidth <= width + 0.5f && !m.breaksWords
        }
        if (!fits(0)) return null
        var lo = 0 // fits
        var hi = steps // unknown
        if (fits(hi)) return sizeAt(hi)
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (fits(mid)) lo = mid else hi = mid
        }
        return sizeAt(lo)
    }

    private fun widestFreeWidth(anchor: Box, minW: Int, maxW: Int, h: Int, bounds: Box, obstacles: List<Box>): Int {
        var width = minW
        for (g in GROWTH) {
            val w = (minW * g).roundToInt().coerceAtMost(maxW)
            if (obstacles.any { it.intersects(centeredRect(anchor, w, h, bounds)) }) break
            width = w
        }
        return width
    }

    /** Rectangle of [w] x [h] centered on [anchor], shifted (not shrunk) to stay inside [bounds]. */
    fun centeredRect(anchor: Box, w: Int, h: Int, bounds: Box): Box {
        val width = w.coerceAtMost(bounds.width)
        val height = h.coerceAtMost(bounds.height)
        val left = (anchor.centerX - width / 2).coerceIn(bounds.left, bounds.right - width)
        val top = (anchor.centerY - height / 2).coerceIn(bounds.top, bounds.bottom - height)
        return Box(left, top, left + width, top + height)
    }

    /**
     * Rough number of translated characters that fit in a bubble when drawn at about the source glyph size.
     * Sent to the model as a soft length budget so it prefers shorter phrasing for small bubbles.
     */
    fun estimateCapacity(box: Box, glyphPx: Int): Int {
        val g = glyphPx.coerceAtLeast(8).toFloat()
        // Latin glyphs are ~0.55em wide; lines are ~1.2em tall.
        val capacity = box.area / (0.55f * g * 1.2f * g)
        return capacity.roundToInt().coerceIn(4, 400)
    }
}

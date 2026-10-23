package com.example.translation

import kotlin.math.max
import kotlin.math.min

/** One recognized text block from the on-device OCR. */
data class OcrBlock(val text: String, val box: Box, val lineHeight: Int, val lineCount: Int = 1)

/** A translated region ready to be drawn, in capture pixels. */
data class ScreenTranslation(
    val originalText: String,
    val translatedText: String,
    val box: Box,
    val isVertical: Boolean = false,
    val bubbleType: String = "SPEECH"
)

/**
 * Cleans raw OCR output and merges lines that belong to the same speech bubble, so each bubble is
 * translated as one sentence (better grammar) and drawn as one card.
 */
object TextRegions {

    /**
     * @param exclusions regions that must be ignored (our floating bubble, the status bar).
     */
    fun prepare(blocks: List<OcrBlock>, exclusions: List<Box>, screenWidth: Int, screenHeight: Int): List<OcrBlock> {
        val minSide = (min(screenWidth, screenHeight) * 0.012f).toInt().coerceAtLeast(6)
        val cleaned = blocks.filter { block ->
            val text = block.text.trim()
            block.box.width >= minSide && block.box.height >= minSide &&
                exclusions.none { block.box.overlapRatio(it) > 0.3f } &&
                isMeaningful(text)
        }
        return mergeIntoBubbles(cleaned)
    }

    /** Rejects page numbers, lone punctuation, timestamps and similar UI noise. */
    fun isMeaningful(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        if (letters == 0) return false
        val cjk = text.count { isCjk(it) }
        // A single CJK character is often a real SFX/word; Latin text needs at least two letters.
        return cjk >= 1 || letters >= 2
    }

    fun isCjk(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            block == Character.UnicodeBlock.HANGUL_JAMO ||
            block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
    }

    /**
     * Blocks that sit right above/below each other and overlap horizontally are joined (union-find).
     * Result is in reading order (top to bottom, then left to right).
     */
    fun mergeIntoBubbles(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.size < 2) return blocks
        val parent = IntArray(blocks.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        for (i in blocks.indices) for (j in i + 1 until blocks.size) {
            if (belongTogether(blocks[i], blocks[j])) parent[find(i)] = find(j)
        }
        return blocks.indices.groupBy { find(it) }.values
            .map { group ->
                val allVertical = group.all { isVerticalColumn(blocks[it]) }
                // Vertical Japanese/Chinese is read right-to-left; everything else top-to-bottom.
                val members = group.map { blocks[it] }.let { list ->
                    if (allVertical) list.sortedByDescending { it.box.right }
                    else list.sortedWith(compareBy({ it.box.top }, { it.box.left }))
                }
                OcrBlock(
                    text = members.joinToString(" ") { it.text.trim() },
                    box = members.map { it.box }.reduce(Box::union),
                    lineHeight = members.map { it.lineHeight }.average().toInt(),
                    lineCount = members.sumOf { it.lineCount }
                )
            }
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    private fun isVerticalColumn(b: OcrBlock) = b.box.height > b.box.width * 1.5f

    private fun belongTogether(a: OcrBlock, b: OcrBlock): Boolean {
        if (isVerticalColumn(a) && isVerticalColumn(b)) {
            // Neighbouring columns of one vertical bubble.
            val columnWidth = max(a.lineHeight, b.lineHeight).coerceAtLeast(1)
            val horizontalGap = max(a.box.left, b.box.left) - min(a.box.right, b.box.right)
            val verticalOverlap = min(a.box.bottom, b.box.bottom) - max(a.box.top, b.box.top)
            return horizontalGap < columnWidth * 1.2f && verticalOverlap > min(a.box.height, b.box.height) * 0.35f
        }
        val lineHeight = max(a.lineHeight, b.lineHeight).coerceAtLeast(1)
        val verticalGap = max(a.box.top, b.box.top) - min(a.box.bottom, b.box.bottom)
        if (verticalGap > lineHeight * 0.9f) return false
        val overlap = min(a.box.right, b.box.right) - max(a.box.left, b.box.left)
        val narrower = min(a.box.width, b.box.width).coerceAtLeast(1)
        // Lines in a bubble are centered, so they overlap strongly horizontally.
        return overlap > narrower * 0.35f
    }

    /** True when most blocks look like vertical columns (classic Japanese manga). */
    fun isMostlyVertical(blocks: List<OcrBlock>): Boolean {
        val candidates = blocks.filter { it.text.length >= 3 }
        if (candidates.size < 2) return false
        val vertical = candidates.count { it.box.height > it.box.width * 2 && it.text.count(::isCjk) >= 2 }
        return vertical >= candidates.size * 0.4f
    }
}

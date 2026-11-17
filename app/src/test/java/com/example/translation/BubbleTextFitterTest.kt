package com.example.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/** Pure JVM: a deterministic monospace measurer stands in for StaticLayout. */
class BubbleTextFitterTest {

    /** Greedy word wrap; every glyph is 0.55em wide, every line 1.2em tall. */
    private val measurer = TextMeasurer { text, size, width ->
        val charW = size * 0.55f
        var lines = 1
        var current = 0f
        var maxLine = 0f
        var broken = false
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val w = word.length * charW
            if (w > width) broken = true
            val needed = if (current == 0f) w else current + charW + w
            if (needed <= width || current == 0f) {
                current = needed
            } else {
                maxLine = maxOf(maxLine, current)
                lines++
                current = w
            }
        }
        maxLine = maxOf(maxLine, current)
        TextMeasure(ceil(lines * size * 1.2f).toInt(), maxLine, lines, broken)
    }

    private val spec = FitSpec(
        minTextPx = 16f,
        comfortableTextPx = 30f,
        maxTextPx = 48f,
        stepPx = 1f,
        paddingPx = 8,
        minCardWidthPx = 100
    )
    private val screen = Box(0, 0, 1080, 2400)

    private fun fitsInside(text: String, result: FitResult) {
        val inner = result.rect.width - spec.paddingPx * 2
        val m = measurer.measure(text, result.textPx, inner)
        assertTrue("height ${m.height} > ${result.rect.height - 16}", m.height <= result.rect.height - spec.paddingPx * 2)
        assertTrue(m.maxLineWidth <= inner)
        assertFalse(m.breaksWords)
    }

    @Test
    fun `short text in a big bubble uses the largest size and the original box`() {
        val anchor = Box(100, 100, 500, 300)
        val result = BubbleTextFitter.fit("Olá!", anchor, screen, emptyList(), spec, measurer)
        assertEquals(anchor, result.rect)
        assertEquals(48f, result.textPx)
        assertFalse(result.overflow)
        fitsInside("Olá!", result)
    }

    @Test
    fun `font shrinks to fit long text before the card grows`() {
        val anchor = Box(100, 100, 400, 260)
        val text = "Eu não acredito que você fez isso comigo depois de tudo"
        val result = BubbleTextFitter.fit(text, anchor, screen, emptyList(), spec, measurer)
        assertTrue(result.textPx < 48f)
        fitsInside(text, result)
    }

    @Test
    fun `translation longer than the original grows the card instead of dropping below comfortable size`() {
        val anchor = Box(400, 400, 560, 470) // a small bubble that held a short Japanese line
        val text = "Por que você está aqui a esta hora da noite?"
        val result = BubbleTextFitter.fit(text, anchor, screen, emptyList(), spec, measurer)
        assertTrue(result.rect.width > anchor.width)
        assertTrue(result.textPx >= spec.minTextPx)
        // Still centered on the original bubble.
        assertTrue(kotlin.math.abs(result.rect.centerX - anchor.centerX) <= 1)
        fitsInside(text, result)
    }

    @Test
    fun `growth never covers a neighbouring bubble`() {
        val anchor = Box(400, 400, 560, 470)
        val neighbour = Box(580, 380, 800, 480)
        val text = "Por que você está aqui a esta hora da noite?"
        val result = BubbleTextFitter.fit(text, anchor, screen, listOf(neighbour), spec, measurer)
        assertFalse(result.rect.intersects(neighbour))
    }

    @Test
    fun `long single word shrinks instead of being split`() {
        val anchor = Box(0, 0, 200, 200)
        val result = BubbleTextFitter.fit("AAAAAAAAAAAARGH!", anchor, screen, listOf(Box(200, 0, 400, 400), Box(0, 200, 200, 400)), spec, measurer)
        fitsInside("AAAAAAAAAAAARGH!", result)
    }

    @Test
    fun `vertical column becomes a horizontally readable card`() {
        val column = Box(500, 100, 560, 500) // tall and narrow, like vertical Japanese
        val text = "Por que você está aqui?"
        val result = BubbleTextFitter.fit(text, column, screen, emptyList(), spec, measurer)
        assertTrue(result.rect.width >= column.width * 2)
        fitsInside(text, result)
    }

    @Test
    fun `text that cannot fit is flagged as overflow and the card grows taller`() {
        val anchor = Box(0, 0, 120, 40)
        val blockers = listOf(Box(130, 0, 400, 60), Box(0, 50, 400, 300))
        val text = "Uma frase realmente muito comprida que jamais caberia em um balão tão pequeno assim"
        val result = BubbleTextFitter.fit(text, anchor, screen, blockers, spec, measurer)
        assertTrue(result.overflow)
        assertEquals(spec.minTextPx, result.textPx)
        assertTrue(result.rect.height > anchor.height)
        assertTrue(result.rect.top >= 0)
    }

    @Test
    fun `card stays on screen near the edges`() {
        val anchor = Box(1000, 2350, 1080, 2400)
        val result = BubbleTextFitter.fit("Socorro, alguém me ajude!", anchor, screen, emptyList(), spec, measurer)
        assertTrue(result.rect.left >= 0 && result.rect.right <= 1080)
        assertTrue(result.rect.top >= 0 && result.rect.bottom <= 2400)
    }

    @Test
    fun `binary search finds the same size as a linear scan`() {
        val text = "Vamos logo, estamos atrasados para a aula"
        for (width in listOf(150, 220, 333, 480)) {
            for (height in listOf(60, 120, 250)) {
                val linear = generateSequence(spec.maxTextPx) { it - spec.stepPx }
                    .takeWhile { it >= spec.minTextPx }
                    .firstOrNull { size ->
                        val m = measurer.measure(text, size, width)
                        m.height <= height && m.maxLineWidth <= width && !m.breaksWords
                    }
                assertEquals("w=$width h=$height", linear, BubbleTextFitter.largestFittingSize(text, measurer, width, height, spec))
            }
        }
    }

    @Test
    fun `nothing fits a zero sized box`() {
        assertNull(BubbleTextFitter.largestFittingSize("Oi", measurer, 0, 100, spec))
    }

    @Test
    fun `capacity grows with the bubble area`() {
        val small = BubbleTextFitter.estimateCapacity(Box(0, 0, 100, 40), 20)
        val big = BubbleTextFitter.estimateCapacity(Box(0, 0, 400, 200), 20)
        assertTrue(big > small * 5)
    }
}

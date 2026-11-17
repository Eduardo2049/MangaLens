package com.example.service

import com.example.translation.BubbleTextFitter
import com.example.translation.Box
import com.example.translation.FitSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Real Android text layout (native graphics), so the fitter is checked against actual glyph metrics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StaticLayoutMeasurerTest {

    private val measurer = StaticLayoutMeasurer(locale = Locale.forLanguageTag("pt"))
    private val spec = FitSpec(
        minTextPx = 22f, comfortableTextPx = 36f, maxTextPx = 48f, stepPx = 1f, paddingPx = 10, minCardWidthPx = 150
    )

    @Test
    fun `smaller font wraps into fewer lines`() {
        val text = "Eu nunca vou te perdoar por isso!"
        val big = measurer.measure(text, 48f, 300)
        val small = measurer.measure(text, 24f, 300)
        assertTrue(big.lineCount > small.lineCount)
        assertTrue(big.height > small.height)
    }

    @Test
    fun `a word wider than the line is reported as broken`() {
        assertTrue(measurer.measure("Inconstitucionalissimamente", 48f, 200).breaksWords)
        assertFalse(measurer.measure("Oi, tudo bem?", 30f, 400).breaksWords)
    }

    @Test
    fun `fitted layout really fits the card`() {
        val text = "Por que você está aqui a esta hora da noite?"
        val anchor = Box(300, 300, 520, 420)
        val fit = BubbleTextFitter.fit(text, anchor, Box(0, 0, 1080, 2400), emptyList(), spec, measurer)
        val inner = fit.rect.width - spec.paddingPx * 2
        val layout = measurer.build(text, fit.textPx, inner, 0)
        assertTrue(layout.height <= fit.rect.height - spec.paddingPx * 2)
        for (line in 0 until layout.lineCount) assertTrue(layout.getLineWidth(line) <= inner + 0.5f)
    }
}

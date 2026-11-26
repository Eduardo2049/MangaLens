package com.example.service

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.translation.TextMeasure
import com.example.translation.TextMeasurer
import com.example.translation.TextRegions
import java.util.Locale

/**
 * Measures with the very same StaticLayout configuration used to draw the card ([build]), so the size chosen
 * by [com.example.translation.BubbleTextFitter] is exactly what appears on screen.
 */
class StaticLayoutMeasurer(
    typeface: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD),
    locale: Locale = Locale.getDefault()
) : TextMeasurer {

    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textLocale = locale // Line breaking follows the target language.
    }

    /** Builds the layout that will be drawn. The returned paint is a copy: safe to keep with the layout. */
    fun build(text: String, textSizePx: Float, widthPx: Int, color: Int): StaticLayout {
        val drawPaint = TextPaint(paint).apply {
            textSize = textSizePx
            this.color = color
        }
        return layout(text, drawPaint, widthPx)
    }

    override fun measure(text: String, textSizePx: Float, maxWidthPx: Int): TextMeasure {
        paint.textSize = textSizePx
        val layout = layout(text, paint, maxWidthPx)
        var maxLine = 0f
        var broken = false
        for (line in 0 until layout.lineCount) {
            maxLine = maxOf(maxLine, layout.getLineWidth(line))
            if (line > 0 && splitsWord(text, layout.getLineStart(line))) broken = true
        }
        return TextMeasure(layout.height, maxLine, layout.lineCount, broken)
    }

    /** Automatic hyphenation is off, so a break between two letters means the word did not fit the line. */
    private fun splitsWord(text: String, start: Int): Boolean {
        if (start <= 0 || start >= text.length) return false
        val before = text[start - 1]
        val after = text[start]
        // Breaking between CJK characters is normal line breaking, not a split word.
        if (TextRegions.isCjk(before) || TextRegions.isCjk(after)) return false
        return before.isLetterOrDigit() && after.isLetterOrDigit()
    }

    // Layout.BREAK_STRATEGY_BALANCED (API 23) has the same value as LineBreaker.BREAK_STRATEGY_BALANCED, which
    // lint expects but only exists from API 29; minSdk is 24.
    @SuppressLint("WrongConstant")
    private fun layout(text: String, paint: TextPaint, widthPx: Int): StaticLayout {
        val w = widthPx.coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(0f, LINE_SPACING)
                // Balanced lines give the rounded "speech bubble" silhouette instead of one long line + orphan.
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                // No automatic hyphens: the fitter shrinks the font until whole words fit instead.
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, w, Layout.Alignment.ALIGN_CENTER, LINE_SPACING, 0f, false)
        }
    }

    private companion object {
        const val LINE_SPACING = 0.95f
    }
}

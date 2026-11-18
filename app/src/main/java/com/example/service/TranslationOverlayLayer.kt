package com.example.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.StaticLayout
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.example.data.local.UserPreferences
import com.example.translation.BubbleTextFitter
import com.example.translation.Box
import com.example.translation.FitResult
import com.example.translation.FitSpec
import com.example.translation.ScreenTranslation
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Draws one small, touch-transparent window per translated bubble, so the reader underneath keeps
 * scrolling normally.
 *
 * Android caps the opacity of untouchable overlay windows at 0.8, so a little of the original lettering
 * ghosts through; the cards are grown to cover it. (FLAG_BLUR_BEHIND was tried to hide it, but on Xiaomi
 * it blurs the whole screen, which also made the OCR read nothing.)
 */
class TranslationOverlayLayer(private val context: Context, private val windowManager: WindowManager) {

    private val cards = mutableListOf<View>()
    private val density = context.resources.displayMetrics.density
    private val scaledDensity = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, context.resources.displayMetrics)

    /** Screen-space rectangles currently covered by cards; masked out of change detection. */
    var cardBoxes: List<Box> = emptyList()
        private set

    val isShowing: Boolean get() = cardBoxes.isNotEmpty()

    /** Cards are added on demand; kept so the service lifecycle reads symmetrically. */
    fun attach() = Unit

    fun show(
        translations: List<ScreenTranslation>,
        prefs: UserPreferences,
        captureWidth: Int,
        captureHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ) {
        clear()
        if (translations.isEmpty() || captureWidth == 0 || captureHeight == 0) return

        val sx = screenWidth.toFloat() / captureWidth
        val sy = screenHeight.toFloat() / captureHeight
        // ML Kit boxes hug the glyphs; grow them so furigana and bubble outlines are covered too.
        val cover = (6 * density).roundToInt()
        val screen = Box(0, 0, screenWidth, screenHeight)
        val measurer = StaticLayoutMeasurer(locale = Locale.forLanguageTag(prefs.targetLanguage.code))
        val preferredPx = prefs.overlayFontSizeSp * scaledDensity
        val anchors = translations.map { item ->
            Box(
                (item.box.left * sx).roundToInt(), (item.box.top * sy).roundToInt(),
                (item.box.right * sx).roundToInt(), (item.box.bottom * sy).roundToInt()
            ).inflate(cover, cover).clampTo(screenWidth, screenHeight)
        }
        val placed = mutableListOf<Box>()
        val boxes = mutableListOf<Box>()
        var overflowCount = 0

        for ((index, item) in translations.withIndex()) {
            val anchor = anchors[index]
            if (anchor.width <= 0 || anchor.height <= 0 || item.translatedText.isBlank()) continue
            // Match the original lettering when the bubble is big, but never exceed 1.6x the user's size.
            val sourceGlyphPx = item.lineHeight * minOf(sx, sy) * 0.85f
            val spec = FitSpec(
                minTextPx = MIN_TEXT_SP * scaledDensity,
                comfortableTextPx = preferredPx * 0.9f,
                maxTextPx = sourceGlyphPx.coerceIn(preferredPx, preferredPx * 1.6f),
                stepPx = 0.5f * scaledDensity,
                paddingPx = (4 * density).roundToInt(),
                minCardWidthPx = (56 * density).roundToInt()
            )
            // Other bubbles' text and cards already placed must stay visible.
            val obstacles = anchors.filterIndexed { i, _ -> i != index } + placed
            val fit = BubbleTextFitter.fit(item.translatedText, anchor, screen, obstacles, spec, measurer, item.isVertical || anchor.height > anchor.width * 1.6f)
            if (fit.overflow) overflowCount++
            val rect = fit.rect
            // Two detections of the same bubble would stack unreadable cards: keep the first.
            if (placed.any { other ->
                    val smaller = if (rect.area <= other.area) rect else other
                    val larger = if (smaller === rect) other else rect
                    smaller.overlapRatio(larger) > 0.5f
                }) continue
            placed += rect

            val card = createCard(item.translatedText, fit, prefs, spec.paddingPx, measurer)
            try {
                windowManager.addView(card, cardParams(rect, prefs))
                cards += card
                // Masks are kept in capture coordinates, which is what the change detector samples.
                boxes += Box(
                    (rect.left / sx).toInt(), (rect.top / sy).toInt(),
                    (rect.right / sx).toInt(), (rect.bottom / sy).toInt()
                )
            } catch (e: Exception) {
                Log.e("MangaLens", "Overlay: falha ao adicionar card", e)
            }
        }
        if (overflowCount > 0) Log.d("MangaLens", "Overlay: $overflowCount card(s) cresceram além do balão")
        cardBoxes = boxes
    }

    private fun cardParams(rect: Box, prefs: UserPreferences): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return WindowManager.LayoutParams(rect.width, rect.height, type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rect.left
            y = rect.top
            // The system clamps untouchable overlays to 0.8 anyway; ask for it explicitly.
            alpha = prefs.overlayOpacity.coerceIn(UserPreferences.MIN_OVERLAY_OPACITY, UserPreferences.MAX_OVERLAY_OPACITY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun createCard(text: String, fit: FitResult, prefs: UserPreferences, padding: Int, measurer: StaticLayoutMeasurer): View {
        val (bgColor, fgColor) = when (prefs.bubbleStyle) {
            "SOLID_HIGHLIGHT" -> Color.rgb(15, 23, 42) to Color.WHITE
            "SEMI_TRANSPARENT" -> Color.BLACK to Color.WHITE
            else -> Color.WHITE to Color.rgb(15, 23, 42)
        }
        val bgAlpha = if (prefs.bubbleStyle == "SEMI_TRANSPARENT") 0.75f else 1f
        // Draw exactly the layout that was measured: a TextView would re-wrap with its own settings.
        val layout = measurer.build(text, fit.textPx, (fit.rect.width - padding * 2).coerceAtLeast(1), fgColor)
        return FittedTextView(context, layout, padding).apply {
            contentDescription = text
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(Color.argb((bgAlpha * 255).roundToInt(), Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor)))
            }
        }
    }

    /** Draws a pre-built layout centered in the card; clipped by the window if it ever overflows. */
    private class FittedTextView(context: Context, private val layout: StaticLayout, private val padding: Int) : View(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val top = padding + ((height - padding * 2 - layout.height) / 2f).coerceAtLeast(0f)
            canvas.save()
            canvas.translate(padding.toFloat(), top)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    fun clear() {
        cards.forEach { try { windowManager.removeViewImmediate(it) } catch (_: Exception) {} }
        cards.clear()
        cardBoxes = emptyList()
    }

    fun detach() = clear()

    private companion object {
        /** Below this the translation is unreadable on a phone; the card grows instead. */
        const val MIN_TEXT_SP = 8f
    }
}

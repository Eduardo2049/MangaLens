package com.example.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.data.local.UserPreferences
import com.example.translation.Box
import com.example.translation.ScreenTranslation
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
        val padding = (4 * density).roundToInt()
        // ML Kit boxes hug the glyphs; grow them so furigana and bubble outlines are covered too.
        val cover = (6 * density).roundToInt()
        val minWidth = (64 * density).roundToInt()
        val placed = mutableListOf<Box>()
        val boxes = mutableListOf<Box>()

        for (item in translations) {
            val box = Box(
                (item.box.left * sx).roundToInt(), (item.box.top * sy).roundToInt(),
                (item.box.right * sx).roundToInt(), (item.box.bottom * sy).roundToInt()
            ).inflate(cover, cover)
            // Vertical columns are narrow; widen so horizontal translated text stays readable.
            val targetWidth = when {
                item.isVertical || box.height > box.width * 1.6f -> maxOf(box.width * 2, box.height * 2 / 3)
                else -> box.width
            }.coerceAtLeast(minWidth).coerceAtMost(screenWidth)
            val (textSp, textHeight) = fitText(
                item.translatedText, targetWidth - padding * 2, box.height - padding * 2,
                prefs.overlayFontSizeSp.toFloat() + 1f, MIN_TEXT_SP
            )
            val cardHeight = maxOf(box.height, textHeight + padding * 2)

            val left = (box.centerX - targetWidth / 2).coerceIn(0, maxOf(0, screenWidth - targetWidth))
            val top = (box.centerY - cardHeight / 2).coerceIn(0, maxOf(0, screenHeight - cardHeight))
            val rect = Box(left, top, left + targetWidth, top + cardHeight)
            // Two detections of the same bubble would stack unreadable cards: keep the first.
            if (placed.any { other ->
                    val smaller = if (rect.area <= other.area) rect else other
                    val larger = if (smaller === rect) other else rect
                    smaller.overlapRatio(larger) > 0.5f
                }) continue
            placed += rect

            val card = createCard(item.translatedText, textSp, prefs, padding)
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
            alpha = prefs.overlayOpacity.coerceIn(0.5f, MAX_UNTOUCHABLE_ALPHA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun createCard(text: String, textSp: Float, prefs: UserPreferences, padding: Int): View {
        val (bgColor, fgColor) = when (prefs.bubbleStyle) {
            "SOLID_HIGHLIGHT" -> Color.rgb(15, 23, 42) to Color.WHITE
            "SEMI_TRANSPARENT" -> Color.BLACK to Color.WHITE
            else -> Color.WHITE to Color.rgb(15, 23, 42)
        }
        val bgAlpha = if (prefs.bubbleStyle == "SEMI_TRANSPARENT") 0.75f else 1f
        return TextView(context).apply {
            this.text = text
            setTextColor(fgColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(Color.argb((bgAlpha * 255).roundToInt(), Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor)))
            }
        }
    }

    /** Largest font size (sp) whose wrapped text fits [maxHeight]; at the minimum size returns the needed height. */
    private fun fitText(text: String, width: Int, maxHeight: Int, maxSp: Float, minSp: Float): Pair<Float, Int> {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        var sp = maxSp
        var height = 0
        while (sp >= minSp) {
            paint.textSize = sp * scaledDensity
            height = measure(text, paint, width)
            if (height <= maxHeight) return sp to height
            sp -= 0.5f
        }
        return minSp to height
    }

    private fun measure(text: String, paint: TextPaint, width: Int): Int {
        val w = width.coerceAtLeast(1)
        val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, w, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
        }
        return layout.height
    }

    fun clear() {
        cards.forEach { try { windowManager.removeViewImmediate(it) } catch (_: Exception) {} }
        cards.clear()
        cardBoxes = emptyList()
    }

    fun detach() = clear()

    private companion object {
        const val MIN_TEXT_SP = 7f
        const val MAX_UNTOUCHABLE_ALPHA = 0.8f
    }
}

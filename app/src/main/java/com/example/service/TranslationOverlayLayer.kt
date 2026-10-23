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
import android.widget.FrameLayout
import android.widget.TextView
import com.example.data.local.UserPreferences
import com.example.translation.Box
import com.example.translation.ScreenTranslation
import kotlin.math.roundToInt

/**
 * Draws translations in a single full-screen window that ignores touches, so the reader underneath keeps
 * scrolling normally. (Previously each bubble was its own touchable window, which blocked scrolling and
 * leaked windows.)
 */
class TranslationOverlayLayer(private val context: Context, private val windowManager: WindowManager) {

    private val root = FrameLayout(context)
    private var attached = false
    private val density = context.resources.displayMetrics.density
    private val scaledDensity = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, context.resources.displayMetrics)

    /** Screen-space rectangles currently covered by cards; masked out of change detection. */
    var cardBoxes: List<Box> = emptyList()
        private set

    val isShowing: Boolean get() = cardBoxes.isNotEmpty()

    fun attach() {
        if (attached) return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            windowManager.addView(root, params)
            attached = true
        } catch (e: Exception) {
            Log.e("MangaLens", "Overlay: não foi possível anexar camada", e)
        }
    }

    /**
     * @param captureWidth/captureHeight size of the frame the boxes refer to (maps to the real screen size).
     */
    fun show(
        translations: List<ScreenTranslation>,
        prefs: UserPreferences,
        captureWidth: Int,
        captureHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ) {
        clear()
        if (!attached || translations.isEmpty() || captureWidth == 0 || captureHeight == 0) return

        val sx = screenWidth.toFloat() / captureWidth
        val sy = screenHeight.toFloat() / captureHeight
        val origin = IntArray(2).also { root.getLocationOnScreen(it) }
        val padding = (4 * density).roundToInt()
        val minWidth = (64 * density).roundToInt()
        val boxes = mutableListOf<Box>()

        for (item in translations) {
            val box = Box(
                (item.box.left * sx).roundToInt(), (item.box.top * sy).roundToInt(),
                (item.box.right * sx).roundToInt(), (item.box.bottom * sy).roundToInt()
            )
            // Vertical columns are narrow; widen so horizontal translated text stays readable.
            val targetWidth = when {
                item.isVertical || box.height > box.width * 1.6f -> maxOf(box.width * 2, box.height * 2 / 3)
                else -> box.width
            }.coerceAtLeast(minWidth).coerceAtMost(screenWidth) + padding * 2
            val innerWidth = targetWidth - padding * 2
            val (textSp, textHeight) = fitText(
                item.translatedText, innerWidth, box.height, prefs.overlayFontSizeSp.toFloat() + 2f, MIN_TEXT_SP
            )
            val cardHeight = maxOf(box.height, textHeight) + padding * 2

            val left = (box.centerX - targetWidth / 2).coerceIn(0, maxOf(0, screenWidth - targetWidth))
            val top = (box.centerY - cardHeight / 2).coerceIn(0, maxOf(0, screenHeight - cardHeight))
            val card = createCard(item.translatedText, textSp, prefs, padding)
            root.addView(card, FrameLayout.LayoutParams(targetWidth, cardHeight).apply {
                leftMargin = left - origin[0]
                topMargin = top - origin[1]
            })
            // Masks are kept in capture coordinates, which is what the change detector samples.
            boxes += Box(
                (left / sx).toInt(), (top / sy).toInt(),
                ((left + targetWidth) / sx).toInt(), ((top + cardHeight) / sy).toInt()
            )
        }
        cardBoxes = boxes
    }

    private fun createCard(text: String, textSp: Float, prefs: UserPreferences, padding: Int): View {
        val (bgColor, fgColor) = when (prefs.bubbleStyle) {
            "SOLID_HIGHLIGHT" -> Color.rgb(15, 23, 42) to Color.WHITE
            "SEMI_TRANSPARENT" -> Color.BLACK to Color.WHITE
            else -> Color.WHITE to Color.rgb(15, 23, 42)
        }
        val alpha = if (prefs.bubbleStyle == "SEMI_TRANSPARENT") prefs.overlayOpacity * 0.75f else prefs.overlayOpacity
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
                setColor(Color.argb((alpha.coerceIn(0f, 1f) * 255).roundToInt(), Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor)))
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
        root.removeAllViews()
        cardBoxes = emptyList()
    }

    fun detach() {
        clear()
        if (attached) {
            try { windowManager.removeView(root) } catch (_: Exception) {}
            attached = false
        }
    }

    private companion object {
        const val MIN_TEXT_SP = 7f
    }
}

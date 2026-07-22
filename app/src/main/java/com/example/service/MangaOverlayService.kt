package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.BuildConfig
import com.example.MainActivity
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.TextOverlay
import com.example.data.remote.GeminiVisualTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MangaOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleContainer: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null

    // Real Screen Capture Projection
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageHandlerThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    private var latestCapturedBitmap: Bitmap? = null

    private var screenWidth = 1080
    private var screenHeight = 2400

    // Visual Translator
    private val visualTranslator = GeminiVisualTranslator()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Store individual floating speech cards directly on WindowManager
    private val activeSpeechCardViews = mutableListOf<View>()
    private var isTranslatedOnScreen = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // Check overlay permission to prevent SYSTEM_ALERT_WINDOW security error
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        startForegroundServiceNotification()
        setupFloatingOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("EXTRA_RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData = intent?.getParcelableExtra<Intent>("EXTRA_RESULT_DATA")

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            setupMediaProjection(resultCode, resultData)
        }

        return START_STICKY
    }

    private fun setupMediaProjection(resultCode: Int, resultData: Intent) {
        try {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)

            val metrics = DisplayMetrics()
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealMetrics(metrics)

            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            
            val thread = HandlerThread("ImageReaderHandlerThread").apply { start() }
            imageHandlerThread = thread
            imageHandler = Handler(thread.looper)

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * image.width

                    val bitmap = Bitmap.createBitmap(
                        image.width + rowPadding / pixelStride,
                        image.height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    val cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                    synchronized(this) {
                        latestCapturedBitmap?.recycle()
                        latestCapturedBitmap = cleanBitmap
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    image.close()
                }
            }, imageHandler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "MangaLensScreenProjection",
                screenWidth,
                screenHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                imageHandler
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "manga_lens_overlay_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Manga Lens Floating Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Serviço ativo para sobreposição sobre Komikku, Mihon, Tachiyomi, Kotatsu"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Manga Lens: Tradutor Ativo")
            .setContentText("Lupa flutuante pronta para traduzir no seu leitor de mangás")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun setupFloatingOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        if (windowManager == null) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Draggable Floating Control Capsule (WRAP_CONTENT, non-blocking)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 180
        }

        bubbleContainer = FrameLayout(this)

        // Floating Control Capsule View
        val bubbleView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            padding(8, 8, 12, 8)

            val shape = GradientDrawable().apply {
                cornerRadius = 60f
                setColor(Color.parseColor("#F20F172A")) // Dark theme glassmorphism
                setStroke(4, Color.parseColor("#38BDF8")) // Glowing cyan ring
            }
            background = shape
        }

        val lensIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setColorFilter(Color.parseColor("#38BDF8"))
            layoutParams = LinearLayout.LayoutParams(24.toPx(), 24.toPx()).apply {
                rightMargin = 6.toPx()
            }
        }

        val label = TextView(this).apply {
            text = "TRADUZIR TELA"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        // Button to Clear Translations on screen
        val btnClear = TextView(this).apply {
            text = " ✕ LIMPAR "
            setTextColor(Color.parseColor("#F43F5E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val bg = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(Color.parseColor("#331822"))
            }
            background = bg
            padding(8, 4, 8, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 10.toPx()
            }
            setOnClickListener {
                clearScreenTranslations(label)
            }
        }

        // Button to Close Service completely
        val btnExit = TextView(this).apply {
            text = " ✖ "
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val bg = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(Color.parseColor("#1E293B"))
            }
            background = bg
            padding(6, 4, 6, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 6.toPx()
            }
            setOnClickListener {
                stopSelf()
            }
        }

        bubbleView.addView(lensIcon)
        bubbleView.addView(label)
        bubbleView.addView(btnClear)
        bubbleView.addView(btnExit)

        bubbleContainer?.addView(bubbleView)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params?.x = initialX + (event.rawX - initialTouchX).toInt()
                    params?.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager?.updateViewLayout(bubbleContainer, params)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = Math.abs(event.rawX - initialTouchX)
                    val diffY = Math.abs(event.rawY - initialTouchY)
                    if (diffX < 12 && diffY < 12) {
                        // User Tapped the Lens -> Trigger OCR & Gemini Visual Translation
                        toggleScreenTranslation(label)
                    }
                    true
                }
                else -> false
            }
        }

        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                windowManager?.addView(bubbleContainer, params)
            } else {
                stopSelf()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun captureScreenBitmap(): Bitmap {
        synchronized(this) {
            if (latestCapturedBitmap != null && !latestCapturedBitmap!!.isRecycled) {
                return latestCapturedBitmap!!.copy(Bitmap.Config.ARGB_8888, false)
            }
        }

        // Try polling ImageReader for available frame
        for (retry in 0 until 6) {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * image.width

                    val bitmap = Bitmap.createBitmap(
                        image.width + rowPadding / pixelStride,
                        image.height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    val cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                    synchronized(this) {
                        latestCapturedBitmap = cleanBitmap
                    }
                    return cleanBitmap.copy(Bitmap.Config.ARGB_8888, false)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    image.close()
                }
            }
            try { Thread.sleep(50) } catch (e: Exception) {}
        }

        // Fallback canvas bitmap if display projection frame is delayed
        val width = if (screenWidth > 0) screenWidth else 1080
        val height = if (screenHeight > 0) screenHeight else 2400
        val fallbackBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(fallbackBitmap)
        canvas.drawColor(Color.parseColor("#111827"))
        val paint = android.graphics.Paint().apply {
            color = Color.WHITE
            textSize = 32f
            isAntiAlias = true
        }
        canvas.drawText("Manga Reader Screen", 100f, 200f, paint)
        return fallbackBitmap
    }

    private fun toggleScreenTranslation(statusLabel: TextView) {
        if (isTranslatedOnScreen) {
            clearScreenTranslations(statusLabel)
        } else {
            statusLabel.text = "ESCANEANDO..."
            statusLabel.setTextColor(Color.parseColor("#38BDF8"))

            serviceScope.launch {
                val bitmap = captureScreenBitmap()
                val userPrefs = UserPreferencesRepository(this@MangaOverlayService).preferences.value

                val result = visualTranslator.translateMangaImage(
                    bitmap = bitmap,
                    sourceLang = userPrefs.sourceLanguage,
                    targetLang = userPrefs.targetLanguage
                )

                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = { overlays ->
                            if (overlays.isEmpty()) {
                                statusLabel.text = "NENHUM TEXTO"
                                statusLabel.setTextColor(Color.parseColor("#F59E0B"))
                                Toast.makeText(this@MangaOverlayService, "Nenhum texto de mangá detectado nesta área.", Toast.LENGTH_SHORT).show()
                            } else {
                                renderDirectTranslationsOnScreen(overlays, bitmap)
                                isTranslatedOnScreen = true
                                statusLabel.text = "OCULTAR TRADUÇÃO"
                                statusLabel.setTextColor(Color.parseColor("#34D399"))
                                Toast.makeText(this@MangaOverlayService, "Tradução sobreposta ativada na tela!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onFailure = { error ->
                            statusLabel.text = "ERRO NA API"
                            statusLabel.setTextColor(Color.parseColor("#F43F5E"))
                            val msg = error.localizedMessage ?: "Erro na tradução Gemini"
                            Toast.makeText(this@MangaOverlayService, msg, Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
        }
    }

    private data class RectFPercent(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float
    )

    private fun detectMangaPageBounds(bitmap: Bitmap?): RectFPercent {
        if (bitmap == null || bitmap.isRecycled) {
            return RectFPercent(0.12f, 0.205f, 0.76f, 0.62f)
        }

        val w = bitmap.width
        val h = bitmap.height

        // Scan from center outward to find the manga page bounds reliably
        var startX = w / 2
        var startY = h / 2

        var foundLight = false
        val stepX = (w * 0.02f).toInt().coerceAtLeast(1)
        val stepY = (h * 0.02f).toInt().coerceAtLeast(1)

        for (dy in -5..5) {
            for (dx in -5..5) {
                val cx = (w / 2 + dx * stepX).coerceIn(0, w - 1)
                val cy = (h / 2 + dy * stepY).coerceIn(0, h - 1)
                val pixel = bitmap.getPixel(cx, cy)
                val lum = (0.299f * ((pixel shr 16) and 0xFF) + 0.587f * ((pixel shr 8) and 0xFF) + 0.114f * (pixel and 0xFF))
                if (lum > 100) {
                    startX = cx
                    startY = cy
                    foundLight = true
                    break
                }
            }
            if (foundLight) break
        }

        fun isDarkRow(y: Int, x1: Int, x2: Int): Boolean {
            var darkCount = 0
            val span = (x2 - x1).coerceAtLeast(1)
            val stp = (span / 20).coerceAtLeast(1)
            var tested = 0
            for (x in x1 until x2 step stp) {
                tested++
                val p = bitmap.getPixel(x, y)
                val lum = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF))
                if (lum < 60) darkCount++
            }
            return (darkCount.toFloat() / tested) > 0.85f
        }

        fun isDarkCol(x: Int, y1: Int, y2: Int): Boolean {
            var darkCount = 0
            val span = (y2 - y1).coerceAtLeast(1)
            val stp = (span / 20).coerceAtLeast(1)
            var tested = 0
            for (y in y1 until y2 step stp) {
                tested++
                val p = bitmap.getPixel(x, y)
                val lum = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF))
                if (lum < 60) darkCount++
            }
            return (darkCount.toFloat() / tested) > 0.85f
        }

        var top = (h * 0.205f).toInt()
        val scanMinX = (startX - w * 0.2f).toInt().coerceIn(0, w - 1)
        val scanMaxX = (startX + w * 0.2f).toInt().coerceIn(0, w - 1)
        for (y in startY downTo (h * 0.05f).toInt() step 4) {
            if (isDarkRow(y, scanMinX, scanMaxX)) {
                top = y
                break
            }
        }

        var bottom = (h * 0.825f).toInt()
        for (y in startY until (h * 0.95f).toInt() step 4) {
            if (isDarkRow(y, scanMinX, scanMaxX)) {
                bottom = y
                break
            }
        }

        var left = (w * 0.12f).toInt()
        val scanMinY = (startY - h * 0.15f).toInt().coerceIn(0, h - 1)
        val scanMaxY = (startY + h * 0.15f).toInt().coerceIn(0, h - 1)
        for (x in startX downTo (w * 0.05f).toInt() step 4) {
            if (isDarkCol(x, scanMinY, scanMaxY)) {
                left = x
                break
            }
        }

        var right = (w * 0.88f).toInt()
        for (x in startX until (w * 0.95f).toInt() step 4) {
            if (isDarkCol(x, scanMinY, scanMaxY)) {
                right = x
                break
            }
        }

        val pageW = (right - left).coerceAtLeast((w * 0.3f).toInt())
        val pageH = (bottom - top).coerceAtLeast((h * 0.3f).toInt())

        return RectFPercent(
            left = left.toFloat() / w,
            top = top.toFloat() / h,
            width = pageW.toFloat() / w,
            height = pageH.toFloat() / h
        )
    }

    private fun renderDirectTranslationsOnScreen(overlays: List<TextOverlay>, capturedBitmap: Bitmap? = null) {
        clearScreenTranslations()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val pageRect = detectMangaPageBounds(capturedBitmap)

        overlays.forEach { overlay ->
            val relativeX = pageRect.left + (overlay.box.xMin / 100f) * pageRect.width
            val relativeY = pageRect.top + (overlay.box.yMin / 100f) * pageRect.height

            val leftPx = (relativeX * screenWidth).toInt()
            val topPx = (relativeY * screenHeight).toInt()
            val maxCardWidthPx = ((overlay.box.xMax - overlay.box.xMin) / 100f * pageRect.width * screenWidth).toInt().coerceAtLeast(50.toPx())

            // Google Lens Style: Compact white overlay strictly wrapping translated text
            val bubbleCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                padding(4, 2, 4, 2)

                val shape = GradientDrawable().apply {
                    cornerRadius = 8f
                    setColor(Color.WHITE) // Matches manga speech balloon white background
                    setStroke(1, Color.parseColor("#E2E8F0"))
                }
                background = shape

                val tvText = TextView(this@MangaOverlayService).apply {
                    text = overlay.translatedText
                    setTextColor(Color.parseColor("#0F172A")) // Clean dark readable text
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    maxWidth = maxCardWidthPx
                }

                addView(tvText)

                // Tap text to copy to clipboard
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Tradução Manga", overlay.translatedText)
                    clipboard.setPrimaryClip(clip)
                    tvText.text = "✓ COPIADO"
                    tvText.setTextColor(Color.parseColor("#059669"))
                    mainHandler.postDelayed({
                        tvText.text = overlay.translatedText
                        tvText.setTextColor(Color.parseColor("#0F172A"))
                    }, 1200)
                }
            }

            // Non-blocking Google Lens overlay window
            val cardParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = leftPx
                y = topPx
            }

            try {
                windowManager?.addView(bubbleCard, cardParams)
                activeSpeechCardViews.add(bubbleCard)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun clearScreenTranslations(statusLabel: TextView? = null) {
        activeSpeechCardViews.forEach { card ->
            try {
                if (card.isAttachedToWindow) {
                    windowManager?.removeView(card)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        activeSpeechCardViews.clear()
        isTranslatedOnScreen = false
        statusLabel?.text = "TRADUZIR TELA"
        statusLabel?.setTextColor(Color.WHITE)
    }

    private fun View.padding(l: Int, t: Int, r: Int, b: Int) {
        setPadding(l.toPx(), t.toPx(), r.toPx(), b.toPx())
    }

    private fun Int.toPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearScreenTranslations()
        try {
            imageHandlerThread?.quitSafely()
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            if (bubbleContainer != null && bubbleContainer?.isAttachedToWindow == true) {
                windowManager?.removeView(bubbleContainer)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

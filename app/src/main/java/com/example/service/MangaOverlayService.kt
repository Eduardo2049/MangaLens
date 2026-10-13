package com.example.service

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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
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
import androidx.core.graphics.toColorInt
import com.example.MainActivity
import com.example.data.local.UserPreferences
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.TextOverlay
import com.example.data.remote.GeminiVisualTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.MessageDigest

class MangaOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleContainer: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null

    // Screen Capture Logic encapsulated in a dedicated Manager
    private var screenCaptureManager: ScreenCaptureManager? = null

    // Visual Translator & Scope
    private val visualTranslator = GeminiVisualTranslator()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val activeSpeechCardViews = mutableListOf<View>()
    private var isTranslatedOnScreen = false

    private var lastTranslatedBitmapHash: String? = null
    private var stableFrameHash: String? = null
    private var stableFrameSinceMillis = 0L
    private var lastRequestedFrameHash: String? = null
    private var lastRequestFinishedMillis = 0L
    private var dynamicTranslateJob: Job? = null
    private var floatingLabel: TextView? = null

    private val prefsRepo by lazy { UserPreferencesRepository(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        screenCaptureManager = ScreenCaptureManager(this)
        startForegroundServiceNotification()
        setupFloatingOverlay()

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("EXTRA_RESULT_CODE", -1) ?: -1
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("EXTRA_RESULT_DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("EXTRA_RESULT_DATA")
        }

        if (resultCode != -1 && resultData != null) {
            screenCaptureManager?.setup(resultCode, resultData)
            floatingLabel?.let { startDynamicMode(it) }
        }

        return START_STICKY
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
                description = "Serviço ativo para sobreposição sobre leitores de mangá"
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
            .setContentText("Lupa flutuante pronta para traduzir")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun setupFloatingOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

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

        val bubbleView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 16, 24, 16)

            background = GradientDrawable().apply {
                cornerRadius = 60f
                setColor("#F20F172A".toColorInt())
                setStroke(4, "#38BDF8".toColorInt())
            }
        }

        val lensIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setColorFilter("#38BDF8".toColorInt())
            layoutParams = LinearLayout.LayoutParams(48, 48).apply {
                rightMargin = 12
            }
        }

        val label = TextView(this).apply {
            text = "TRADUZIR TELA"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        floatingLabel = label

        val btnExit = TextView(this).apply {
            text = " ✖ "
            setTextColor("#94A3B8".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor("#1E293B".toColorInt())
            }
            setPadding(12, 8, 12, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 12 }
            setOnClickListener { stopSelf() }
        }

        bubbleView.addView(lensIcon)
        bubbleView.addView(label)
        bubbleView.addView(btnExit)
        bubbleContainer?.addView(bubbleView)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        bubbleView.setOnTouchListener { v, event ->
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
                    try { windowManager?.updateViewLayout(bubbleContainer, params) } catch (e: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = Math.abs(event.rawX - initialTouchX)
                    val diffY = Math.abs(event.rawY - initialTouchY)
                    if (diffX < 12 && diffY < 12) {
                        v.performClick()
                        toggleScreenTranslation(label)
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(bubbleContainer, params)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun toggleScreenTranslation(statusLabel: TextView) {
        if (dynamicTranslateJob?.isActive == true) stopDynamicMode(statusLabel) else startDynamicMode(statusLabel)
    }

    private fun translateCurrentFrame(statusLabel: TextView, userPrefs: UserPreferences, forceRefresh: Boolean) {
        statusLabel.text = "ESCANEANDO..."
        statusLabel.setTextColor("#38BDF8".toColorInt())
        Log.d("MangaLens", "Manual: Iniciando captura...")

        serviceScope.launch {
            val bitmap = screenCaptureManager?.captureBitmap() ?: run {
                Log.e("MangaLens", "Manual: Falha ao capturar bitmap")
                return@launch
            }
            val currentHash = computeBitmapHash(bitmap)

            if (!forceRefresh && currentHash == lastTranslatedBitmapHash) {
                Log.d("MangaLens", "Manual: Tela idêntica à última tradução")
                withContext(Dispatchers.Main) {
                    statusLabel.text = "TRADUZIR TELA"
                    statusLabel.setTextColor(Color.WHITE)
                    Toast.makeText(this@MangaOverlayService, "Tela não mudou.", Toast.LENGTH_SHORT).show()
                }
                bitmap.recycle()
                return@launch
            }
            Log.d("MangaLens", "Manual: Enviando para Gemini...")

            val result = visualTranslator.translateMangaImage(bitmap, userPrefs.sourceLanguage, userPrefs.targetLanguage)
            bitmap.recycle()

            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { overlays ->
                        Log.d("MangaLens", "Manual: Sucesso (${overlays.size} balões)")
                        lastTranslatedBitmapHash = currentHash
                        renderDirectTranslationsOnScreen(overlays)
                        isTranslatedOnScreen = true
                        statusLabel.text = "OCULTAR TRADUÇÃO"
                        statusLabel.setTextColor("#34D399".toColorInt())
                    },
                    onFailure = { error ->
                        Log.e("MangaLens", "Manual: Erro na tradução: ${error.message}")
                        statusLabel.text = "ERRO NA API"
                        statusLabel.setTextColor("#F43F5E".toColorInt())
                        Toast.makeText(this@MangaOverlayService, error.localizedMessage ?: "Erro Gemini", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    private fun startDynamicMode(statusLabel: TextView) {
        dynamicTranslateJob?.cancel()
        dynamicTranslateJob = serviceScope.launch {
            withContext(Dispatchers.Main) {
                statusLabel.text = "AUTO ●"
                statusLabel.setTextColor("#34D399".toColorInt())
            }
            while (true) {
                delay(500)
                val currentPrefs = prefsRepo.preferences.value
                if (!currentPrefs.isTranslationEnabled) continue

                val bitmap = screenCaptureManager?.captureBitmap() ?: run {
                    Log.w("MangaLens", "Auto: Bitmap nulo na captura")
                    continue
                }
                val currentHash = computeBitmapHash(bitmap)
                val now = System.currentTimeMillis()

                if (currentHash != stableFrameHash) {
                    stableFrameHash = currentHash
                    stableFrameSinceMillis = now
                    lastRequestedFrameHash = null
                    withContext(Dispatchers.Main) {
                        clearScreenTranslations()
                        statusLabel.text = "AGUARDANDO..."
                        statusLabel.setTextColor("#F59E0B".toColorInt())
                    }
                    bitmap.recycle()
                    continue
                }

                if (now - stableFrameSinceMillis < 2_000) {
                    bitmap.recycle()
                    continue
                }

                if (currentHash == lastRequestedFrameHash && now - lastRequestFinishedMillis < 5_000) {
                    bitmap.recycle()
                    continue
                }

                Log.d("MangaLens", "Auto: Tela estável por 2s, enviando tradução...")
                lastRequestedFrameHash = currentHash

                val result = visualTranslator.translateMangaImage(bitmap, currentPrefs.sourceLanguage, currentPrefs.targetLanguage)
                bitmap.recycle()

                withContext(Dispatchers.Main) {
                    result.onSuccess { overlays ->
                        Log.d("MangaLens", "Auto: Sucesso (${overlays.size} balões)")
                        lastTranslatedBitmapHash = currentHash
                        renderDirectTranslationsOnScreen(overlays)
                        isTranslatedOnScreen = true
                        statusLabel.text = "AUTO ●"
                        statusLabel.setTextColor("#34D399".toColorInt())
                    }.onFailure { error ->
                        Log.e("MangaLens", "Auto: Erro: ${error.message}")
                        lastRequestFinishedMillis = System.currentTimeMillis()
                        statusLabel.text = "ERRO ●"
                        statusLabel.setTextColor("#F43F5E".toColorInt())
                        Toast.makeText(
                            this@MangaOverlayService,
                            error.localizedMessage ?: "Falha na tradução",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun stopDynamicMode(statusLabel: TextView) {
        dynamicTranslateJob?.cancel()
        dynamicTranslateJob = null
        stableFrameHash = null
        stableFrameSinceMillis = 0L
        lastRequestedFrameHash = null
        lastRequestFinishedMillis = 0L
        clearScreenTranslations(statusLabel)
        statusLabel.text = "AUTO ○"
        statusLabel.setTextColor("#94A3B8".toColorInt())
    }

    private fun computeBitmapHash(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, 64, 64, false)
        val buffer = ByteBuffer.allocate(scaled.byteCount)
        scaled.copyPixelsToBuffer(buffer)
        if (scaled != bitmap) scaled.recycle()
        return MessageDigest.getInstance("MD5").digest(buffer.array()).joinToString("") { "%02x".format(it) }
    }

    private fun renderDirectTranslationsOnScreen(overlays: List<TextOverlay>) {
        clearScreenTranslations()
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val sw = displayMetrics.widthPixels
        val sh = displayMetrics.heightPixels

        overlays.forEach { overlay ->
            val leftPx = (overlay.box.xMin / 100f * sw).toInt()
            val topPx = (overlay.box.yMin / 100f * sh).toInt()
            val maxW = ((overlay.box.xMax - overlay.box.xMin) / 100f * sw).toInt().coerceAtLeast(100)

            val bubbleCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(8, 4, 8, 4)
                background = GradientDrawable().apply {
                    cornerRadius = 8f
                    setColor(Color.WHITE)
                    setStroke(1, "#E2E8F0".toColorInt())
                }
                val tvText = TextView(this@MangaOverlayService).apply {
                    text = overlay.translatedText
                    setTextColor("#0F172A".toColorInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    maxWidth = maxW
                }
                addView(tvText)
                setOnClickListener {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Manga", overlay.translatedText))
                    tvText.text = "✓ COPIADO"
                    tvText.setTextColor("#059669".toColorInt())
                    mainHandler.postDelayed({
                        tvText.text = overlay.translatedText
                        tvText.setTextColor("#0F172A".toColorInt())
                    }, 1200)
                }
            }

            val cardParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = leftPx
                y = topPx
            }

            try {
                windowManager?.addView(bubbleCard, cardParams)
                activeSpeechCardViews.add(bubbleCard)
            } catch (e: Exception) {}
        }
    }

    private fun clearScreenTranslations(statusLabel: TextView? = null) {
        activeSpeechCardViews.forEach { try { if (it.isAttachedToWindow) windowManager?.removeView(it) } catch (e: Exception) {} }
        activeSpeechCardViews.clear()
        isTranslatedOnScreen = false
        statusLabel?.apply {
            text = "TRADUZIR TELA"
            setTextColor(Color.WHITE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dynamicTranslateJob?.cancel()
        clearScreenTranslations()
        screenCaptureManager?.stop()
        try {
            if (bubbleContainer != null && bubbleContainer?.isAttachedToWindow == true) windowManager?.removeView(bubbleContainer)
        } catch (e: Exception) {}
    }
}

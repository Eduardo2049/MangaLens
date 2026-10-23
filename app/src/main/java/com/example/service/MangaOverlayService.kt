package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.toColorInt
import com.example.MainActivity
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.TranslationMode
import com.example.data.remote.RateLimitedException
import com.example.translation.Box
import com.example.translation.DailyLimitReachedException
import com.example.translation.FrameThumbnail
import com.example.translation.TranslationPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Floating translator. A watch loop samples a tiny thumbnail of the screen a few times per second
 * (no network). Only when the screen has settled on content that was not processed yet does it run
 * the [TranslationPipeline] — and the pipeline only calls the API for text it has never seen.
 */
class MangaOverlayService : Service() {

    private enum class Status(val label: String, val color: String) {
        WATCHING("AUTO ●", "#34D399"),
        PAUSED("AUTO ○", "#94A3B8"),
        MANUAL("TOQUE P/ TRADUZIR", "#FFFFFF"),
        WAITING("AGUARDANDO...", "#F59E0B"),
        READING("LENDO...", "#38BDF8"),
        TRANSLATING("TRADUZINDO...", "#38BDF8"),
        ERROR("ERRO ●", "#F43F5E"),
        LIMIT("LIMITE ⏳", "#F43F5E")
    }

    private var windowManager: WindowManager? = null
    private var bubbleRoot: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var statusLabel: TextView? = null
    private var overlayLayer: TranslationOverlayLayer? = null

    private val captureManager by lazy { ScreenCaptureManager(this) }
    private val pipeline by lazy { TranslationPipeline(this) }
    private val prefsRepo by lazy { UserPreferencesRepository(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var translateJob: Job? = null

    private var autoEnabled = true
    private var lastSeenFrame = -1L
    private var lastThumb: FrameThumbnail? = null
    private var lastMotionAt = 0L
    /** Thumbnail of the page that was last translated (or attempted); prevents re-processing it. */
    private var processedThumb: FrameThumbnail? = null
    /** Thumbnail of the page the visible cards belong to. */
    private var displayedThumb: FrameThumbnail? = null
    private var retryAt = 0L
    /** True once the current, settled screen content has been handled; lets idle ticks skip all work. */
    private var pageHandled = false
    private var dailyLimitWarned = false
    private var statusBarPx = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        statusBarPx = resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: (24 * resources.displayMetrics.density).toInt()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultData == null || !Settings.canDrawOverlays(this)) {
            // Started without a capture token (e.g. restarted by the system): nothing useful to do.
            startInForeground(withProjection = false)
            stopSelf()
            return START_NOT_STICKY
        }

        // The media-projection foreground type must be active before the projection is created.
        startInForeground(withProjection = true)
        val started = captureManager.start(resultCode, resultData) {
            serviceScope.launch {
                Toast.makeText(this@MangaOverlayService, "Captura de tela encerrada", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
        }
        if (!started) {
            Toast.makeText(this, "Não foi possível iniciar a captura de tela", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        if (bubbleRoot == null) setupOverlayWindows()
        resetPageState()
        startWatching()
        _isRunning.value = true
        return START_NOT_STICKY
    }

    private fun startInForeground(withProjection: Boolean) {
        val type = when {
            withProjection && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> 0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Lupa flutuante pronta para traduzir"), type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground falhou", e)
        }
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "manga_lens_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Manga Lens Floating Overlay", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Serviço ativo para sobreposição sobre leitores de mangá"
                }
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Manga Lens: Tradutor Ativo")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateUsageNotification() {
        val budget = pipeline.budget
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification("Requisições hoje: ${budget.usedToday}/${budget.limit}"))
    }

    // ---------------------------------------------------------------- UI

    private fun setupOverlayWindows() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        overlayLayer = TranslationOverlayLayer(this, wm).also { it.attach() }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 180
        }
        bubbleParams = params

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 16, 24, 16)
            background = GradientDrawable().apply {
                cornerRadius = 60f
                setColor("#F20F172A".toColorInt())
                setStroke(4, "#38BDF8".toColorInt())
            }
        }
        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setColorFilter("#38BDF8".toColorInt())
            layoutParams = LinearLayout.LayoutParams(48, 48).apply { rightMargin = 12 }
        }
        val label = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
        }
        statusLabel = label
        val exit = TextView(this).apply {
            text = " ✖ "
            setTextColor("#94A3B8".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor("#1E293B".toColorInt())
            }
            setPadding(12, 8, 12, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 12 }
            setOnClickListener { stopSelf() }
        }
        bubble.addView(icon)
        bubble.addView(label)
        bubble.addView(exit)
        attachDragAndGestures(bubble)

        try {
            wm.addView(bubble, params)
            bubbleRoot = bubble
        } catch (e: Exception) {
            Log.e(TAG, "Não foi possível exibir a bolha", e)
            stopSelf()
        }
        setStatus(idleStatus())
    }

    /** Drag moves the bubble, tap toggles/translates, long-press forces a fresh translation. */
    private fun attachDragAndGestures(view: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var downAt = 0L
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleParams?.x ?: 0
                    startY = bubbleParams?.y ?: 0
                    touchX = event.rawX
                    touchY = event.rawY
                    downAt = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    bubbleParams?.let {
                        it.x = startX + (event.rawX - touchX).toInt()
                        it.y = startY + (event.rawY - touchY).toInt()
                        try { windowManager?.updateViewLayout(v, it) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - touchX) > slop || abs(event.rawY - touchY) > slop
                    if (!moved) {
                        v.performClick()
                        if (System.currentTimeMillis() - downAt >= LONG_PRESS_MS) onLongPress() else onTap()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        val prefs = prefsRepo.preferences.value
        if (prefs.translationMode == TranslationMode.MANUAL) {
            translateNow(force = false)
            return
        }
        autoEnabled = !autoEnabled
        if (!autoEnabled) {
            translateJob?.cancel()
            clearTranslations()
        } else {
            processedThumb = null
        }
        setStatus(idleStatus())
    }

    private fun onLongPress() {
        Toast.makeText(this, "Traduzindo esta tela novamente…", Toast.LENGTH_SHORT).show()
        translateNow(force = true)
    }

    private fun idleStatus(): Status = when {
        prefsRepo.preferences.value.translationMode == TranslationMode.MANUAL -> Status.MANUAL
        autoEnabled -> Status.WATCHING
        else -> Status.PAUSED
    }

    private fun setStatus(status: Status) {
        statusLabel?.apply {
            text = status.label
            setTextColor(status.color.toColorInt())
        }
    }

    private fun clearTranslations() {
        overlayLayer?.clear()
        displayedThumb = null
    }

    /** Regions of the screen drawn by MangaLens itself, in capture coordinates. */
    private fun ownRegions(): List<Box> {
        val regions = mutableListOf<Box>()
        bubbleRoot?.takeIf { it.isAttachedToWindow }?.let { v ->
            val loc = IntArray(2).also { v.getLocationOnScreen(it) }
            val margin = (8 * resources.displayMetrics.density).toInt()
            regions += Box(loc[0] - margin, loc[1] - margin, loc[0] + v.width + margin, loc[1] + v.height + margin)
        }
        overlayLayer?.let { regions += it.cardBoxes }
        return regions
    }

    // ---------------------------------------------------------------- watch loop

    private fun resetPageState() {
        lastSeenFrame = -1L
        lastThumb = null
        processedThumb = null
        displayedThumb = null
        lastMotionAt = System.currentTimeMillis()
        retryAt = 0L
        pageHandled = false
    }

    private fun startWatching() {
        watchJob?.cancel()
        watchJob = serviceScope.launch {
            while (isActive) {
                delay(TICK_MS)
                try {
                    tick()
                } catch (e: Exception) {
                    Log.e(TAG, "Watch loop: ${e.message}", e)
                }
            }
        }
    }

    private suspend fun tick() {
        val prefs = prefsRepo.preferences.value
        val now = System.currentTimeMillis()
        if (retryAt in 1..now) {
            retryAt = 0L
            processedThumb = null
        }

        val frame = captureManager.frameCount
        val frameChanged = frame != lastSeenFrame
        // Nothing was redrawn and this page is already handled: no work at all.
        if (!frameChanged && pageHandled && processedThumb != null) return
        lastSeenFrame = frame
        pageHandled = false

        val masks = ownRegions()
        val thumb = withContext(Dispatchers.Default) {
            captureManager.captureThumbnail(THUMB_COLS, THUMB_ROWS, masks, statusBarPx)
        } ?: return

        lastThumb?.let { if (it.changedFraction(thumb) > MOTION_FRACTION) lastMotionAt = now }
        lastThumb = thumb

        // Content under the cards moved (scroll/page turn): stale translations must go immediately.
        displayedThumb?.let { if (it.changedFraction(thumb) > PAGE_CHANGED_FRACTION) clearTranslations() }

        val settled = now - lastMotionAt >= SETTLE_MS
        val alreadyHandled = processedThumb?.let { it.changedFraction(thumb) <= PAGE_CHANGED_FRACTION } == true
        val busy = translateJob?.isActive == true

        if (!settled) {
            if (!busy && overlayLayer?.isShowing != true && autoEnabled && prefs.translationMode == TranslationMode.DYNAMIC) {
                setStatus(Status.WAITING)
            }
            return
        }
        if (alreadyHandled || busy) {
            if (!busy && alreadyHandled) {
                pageHandled = true
                if (statusLabel?.text == Status.WAITING.label) setStatus(idleStatus())
            }
            return
        }
        if (!prefs.isTranslationEnabled || !autoEnabled || prefs.translationMode == TranslationMode.MANUAL) {
            setStatus(idleStatus())
            return
        }
        translatePage(thumb)
    }

    private fun translateNow(force: Boolean) {
        val thumb = captureManager.captureThumbnail(THUMB_COLS, THUMB_ROWS, ownRegions(), statusBarPx)
        if (thumb == null) {
            Toast.makeText(this, "Aguardando primeira imagem da tela…", Toast.LENGTH_SHORT).show()
            return
        }
        if (!force && displayedThumb?.let { it.changedFraction(thumb) <= PAGE_CHANGED_FRACTION } == true) {
            clearTranslations() // Manual mode: second tap hides the translation.
            setStatus(idleStatus())
            return
        }
        translateJob?.cancel()
        translatePage(thumb)
    }

    /** Draws [translations] when the screen still shows the page [thumb] was taken from. */
    private fun showIfSamePage(
        thumb: FrameThumbnail,
        translations: List<com.example.translation.ScreenTranslation>,
        prefs: com.example.data.local.UserPreferences
    ): Boolean {
        val current = captureManager.captureThumbnail(THUMB_COLS, THUMB_ROWS, ownRegions(), statusBarPx)
        val samePage = current != null && thumb.changedFraction(current) <= PAGE_CHANGED_FRACTION
        if (samePage && translations.isNotEmpty()) {
            overlayLayer?.show(
                translations, prefs,
                captureManager.width, captureManager.height,
                captureManager.width, captureManager.height
            )
            displayedThumb = thumb
        }
        return samePage
    }

    private fun translatePage(thumb: FrameThumbnail) {
        processedThumb = thumb
        translateJob = serviceScope.launch {
            clearTranslations()
            setStatus(Status.READING)
            // Give the window manager a frame to remove the old cards before grabbing the screen.
            delay(80)
            val prefs = prefsRepo.preferences.value
            val exclusions = ownRegions() + Box(0, 0, captureManager.width, statusBarPx)
            val bitmap = withContext(Dispatchers.Default) { captureManager.captureBitmap() }
            if (bitmap == null) {
                processedThumb = null
                setStatus(idleStatus())
                return@launch
            }
            val result = try {
                setStatus(Status.TRANSLATING)
                pipeline.translate(bitmap, prefs, exclusions) { ready ->
                    // Streamed results arrive on an IO thread; windows must be touched on the main thread.
                    serviceScope.launch { if (translateJob?.isActive == true) showIfSamePage(thumb, ready, prefs) }
                }
            } finally {
                bitmap.recycle()
            }
            if (result.requestsSent > 0) updateUsageNotification()

            // If the user scrolled meanwhile, the results are cached but no longer belong on screen.
            if (!showIfSamePage(thumb, result.translations, prefs)) processedThumb = null
            Log.d(TAG, "Página: ${result.translations.size} traduções, requests=${result.requestsSent}, cache=${result.usedCacheOnly}")

            when (val error = result.error) {
                null -> setStatus(idleStatus())
                is DailyLimitReachedException -> {
                    // Cached bubbles keep working; only new text is blocked until tomorrow.
                    setStatus(Status.LIMIT)
                    if (!dailyLimitWarned) {
                        dailyLimitWarned = true
                        Toast.makeText(this@MangaOverlayService, error.message, Toast.LENGTH_LONG).show()
                    }
                }
                is RateLimitedException -> {
                    setStatus(Status.LIMIT)
                    retryAt = error.retryAfterMillis
                    val seconds = ((error.retryAfterMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                    Toast.makeText(this@MangaOverlayService, "Limite da API: tentando de novo em ${seconds}s", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    setStatus(Status.ERROR)
                    Toast.makeText(
                        this@MangaOverlayService,
                        (error.message ?: "Falha na tradução") + "\nSegure a bolha para tentar de novo.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        captureManager.onDisplayChanged()
        clearTranslations()
        resetPageState()
    }

    override fun onDestroy() {
        _isRunning.value = false
        serviceScope.cancel()
        overlayLayer?.detach()
        overlayLayer = null
        bubbleRoot?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        bubbleRoot = null
        captureManager.stop()
        pipeline.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val TAG = "MangaLens"
        private const val NOTIFICATION_ID = 1001

        private const val TICK_MS = 250L
        /** Time without motion before a page counts as "being read". */
        private const val SETTLE_MS = 400L
        private const val LONG_PRESS_MS = 550L
        private const val THUMB_COLS = 36
        private const val THUMB_ROWS = 64
        /** Changed cells between two ticks that count as motion (scrolling, animation). */
        private const val MOTION_FRACTION = 0.015f
        /** Changed cells that mean we are looking at different content than before. */
        private const val PAGE_CHANGED_FRACTION = 0.06f

        private val _isRunning = MutableStateFlow(false)
        /** Whether the translator is running; observed by the app UI. */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }
}

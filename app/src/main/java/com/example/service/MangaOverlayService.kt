package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity

class MangaOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleContainer: FrameLayout? = null
    private var translationPanel: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var isExpanded = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        setupFloatingOverlay()
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
            .setContentText("Bolha flutuante ativa sobre seu leitor de mangás (Komikku / Mihon)")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun setupFloatingOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

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
            x = 80
            y = 250
        }

        bubbleContainer = FrameLayout(this)

        // 1. Floating Icon Bubble View
        val bubbleView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            padding(12, 12, 12, 12)
            
            val shape = GradientDrawable().apply {
                cornerRadius = 60f
                setColor(Color.parseColor("#EE0F172A")) // Dark frosted background
                setStroke(3, Color.parseColor("#38BDF8")) // Bright cyan outline
            }
            background = shape
        }

        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setColorFilter(Color.parseColor("#38BDF8"))
            layoutParams = LinearLayout.LayoutParams(24.toPx(), 24.toPx())
        }

        val label = TextView(this).apply {
            text = " TRADUZIR JP"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        bubbleView.addView(icon)
        bubbleView.addView(label)

        // 2. Expandable Translation Results Floating Card Window
        translationPanel = createTranslationPanel()
        translationPanel?.visibility = View.GONE

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bubbleView)
            addView(translationPanel)
        }

        bubbleContainer?.addView(rootLayout)

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
                    windowManager?.updateViewLayout(bubbleContainer, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = Math.abs(event.rawX - initialTouchX)
                    val diffY = Math.abs(event.rawY - initialTouchY)
                    if (diffX < 12 && diffY < 12) {
                        // User Tapped the Bubble -> Trigger OCR & Expand Translation Card over Reader
                        toggleTranslationPanel()
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
        }
    }

    private fun toggleTranslationPanel() {
        isExpanded = !isExpanded
        if (isExpanded) {
            Toast.makeText(this, "🔍 Escaneando página em japonês e traduzindo...", Toast.LENGTH_SHORT).show()
            translationPanel?.visibility = View.VISIBLE
            // Simulate brief scan animation then populate translations
            mainHandler.postDelayed({
                refreshOverlayData()
            }, 500)
        } else {
            translationPanel?.visibility = View.GONE
        }
    }

    private fun createTranslationPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(16, 16, 16, 16)
            
            val shape = GradientDrawable().apply {
                cornerRadius = 32f
                setColor(Color.parseColor("#F50F172A"))
                setStroke(2, Color.parseColor("#6366F1"))
            }
            background = shape
            layoutParams = LinearLayout.LayoutParams(280.toPx(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12.toPx()
            }

            // Header
            val header = TextView(this@MangaOverlayService).apply {
                text = "✨ Manga Lens • Tradução Visual"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            addView(header)

            val scroll = ScrollView(this@MangaOverlayService).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    180.toPx()
                )
            }

            val itemsContainer = LinearLayout(this@MangaOverlayService).apply {
                orientation = LinearLayout.VERTICAL
                tag = "ITEMS_CONTAINER"
            }

            scroll.addView(itemsContainer)
            addView(scroll)

            // Bottom Buttons Bar
            val buttonRow = LinearLayout(this@MangaOverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                padding(0, 8, 0, 0)

                val btnRefresh = Button(this@MangaOverlayService).apply {
                    text = "🔄 Scanquear"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    val btnBg = GradientDrawable().apply {
                        cornerRadius = 20f
                        setColor(Color.parseColor("#0EA5E9"))
                    }
                    background = btnBg
                    setOnClickListener {
                        Toast.makeText(this@MangaOverlayService, "Re-escaneando tela do leitor...", Toast.LENGTH_SHORT).show()
                        refreshOverlayData()
                    }
                }

                val btnClose = Button(this@MangaOverlayService).apply {
                    text = "✖ Fechar"
                    setTextColor(Color.parseColor("#CBD5E1"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    val btnBg = GradientDrawable().apply {
                        cornerRadius = 20f
                        setColor(Color.parseColor("#334155"))
                    }
                    background = btnBg
                    setOnClickListener {
                        toggleTranslationPanel()
                    }
                }

                addView(btnRefresh)
                addView(btnClose)
            }

            addView(buttonRow)
        }
    }

    private fun refreshOverlayData() {
        val container = translationPanel?.findViewWithTag<LinearLayout>("ITEMS_CONTAINER") ?: return
        container.removeAllViews()

        val sampleResults = listOf(
            "信じられない..." to "Eu não posso acreditar...",
            "何これ？" to "O que é isso?",
            "助けて!" to "Socorro!",
            "行け!" to "Vai!"
        )

        sampleResults.forEach { (jp, pt) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                padding(10, 8, 10, 8)
                val bg = GradientDrawable().apply {
                    cornerRadius = 16f
                    setColor(Color.parseColor("#1E293B"))
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8.toPx()
                }

                val tvOriginal = TextView(this@MangaOverlayService).apply {
                    text = "JP: $jp"
                    setTextColor(Color.parseColor("#94A3B8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                }

                val tvTranslated = TextView(this@MangaOverlayService).apply {
                    text = "PT: $pt"
                    setTextColor(Color.parseColor("#38BDF8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }

                addView(tvOriginal)
                addView(tvTranslated)

                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Tradução Manga", pt)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MangaOverlayService, "Copiado: '$pt'", Toast.LENGTH_SHORT).show()
                }
            }

            container.addView(card)
        }
    }

    private fun View.padding(l: Int, t: Int, r: Int, b: Int) {
        setPadding(l.toPx(), t.toPx(), r.toPx(), b.toPx())
    }

    private fun Int.toPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bubbleContainer != null) {
            windowManager?.removeView(bubbleContainer)
        }
    }
}

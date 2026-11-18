package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.service.MangaOverlayService
import com.example.ui.reader.MangaReaderScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var screenCaptureResult: ((Boolean) -> Unit)? = null

    // Lança a permissão de captura de tela somente após garantir que o overlay já foi autorizado
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, MangaOverlayService::class.java).apply {
                putExtra(MangaOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(MangaOverlayService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "Bolha ativada com suporte a captura de tela!", Toast.LENGTH_SHORT).show()
            screenCaptureResult?.invoke(true)
        } else {
            Toast.makeText(this, "Permissão de captura de tela necessária para tradução automática.", Toast.LENGTH_LONG).show()
            screenCaptureResult?.invoke(false)
        }
        screenCaptureResult = null
    }

    // Retorno da tela de configurações do overlay (Settings.ACTION_MANAGE_OVERLAY_PERMISSION).
    // Ao voltar, verifica novamente se a permissão foi concedida antes de pedir captura de tela.
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(this)) {
            launchScreenCapture()
        } else {
            Toast.makeText(
                this,
                "Permissão de sobreposição negada. Habilite o Manga Lens nas configurações para usar o tradutor.",
                Toast.LENGTH_LONG
            ).show()
            screenCaptureResult?.invoke(false)
            screenCaptureResult = null
        }
    }

    // Android 13+: the notification shows the translator is running and how many requests were used today.
    // Denying it does not block anything; the flow simply continues.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        continueWithOverlayCheck()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true) {
                MangaReaderScreen(
                    onRequestScreenCapture = { onResult ->
                        requestScreenCaptureWithOverlayCheck(onResult)
                    },
                )
            }
        }
    }

    /**
     * Verifica a permissão de overlay (SYSTEM_ALERT_WINDOW) antes de solicitar a captura de tela.
     * Em Android 10+ (API 29+) a permissão precisa ser concedida manualmente pelo usuário nas
     * configurações do sistema. Se já concedida, prossegue direto para a captura de tela.
     */
    private fun requestScreenCaptureWithOverlayCheck(onResult: (Boolean) -> Unit) {
        screenCaptureResult = onResult
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            continueWithOverlayCheck()
        }
    }

    private fun continueWithOverlayCheck() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Autorize a sobreposição de tela para o Manga Lens nas configurações que serão abertas.",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            launchScreenCapture()
        }
    }

    /**
     * Android 14+ lets the user share a single app. MangaLens must see the whole screen: sharing only one
     * app (usually MangaLens itself, the app in front when the dialog opens) makes the reader invisible to
     * the OCR and puts the coordinates out of sync, so the full-display capture is requested explicitly.
     */
    private fun launchScreenCapture() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjectionManager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mediaProjectionManager.createScreenCaptureIntent()
        }
        screenCaptureLauncher.launch(intent)
    }
}


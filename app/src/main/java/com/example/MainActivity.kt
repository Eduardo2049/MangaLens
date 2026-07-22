package com.example

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.example.service.MangaOverlayService
import com.example.ui.reader.MangaReaderScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, MangaOverlayService::class.java).apply {
                putExtra("EXTRA_RESULT_CODE", result.resultCode)
                putExtra("EXTRA_RESULT_DATA", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "Bolha ativada com suporte a captura de tela!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permissão de captura de tela necessária para tradução automática.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true) {
                MangaReaderScreen(
                    onRequestScreenCapture = {
                        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                    }
                )
            }
        }
    }
}

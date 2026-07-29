package com.example.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages screen capture using MediaProjection API.
 * Encapsulates the complexity of ImageReader, VirtualDisplay, and Bitmap handling.
 */
class ScreenCaptureManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageHandlerThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    private val latestCapturedBitmap = AtomicReference<Bitmap?>(null)
    private var screenWidth = 1080
    private var screenHeight = 2400

    fun setup(resultCode: Int, resultData: Intent) {
        try {
            val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)

            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            
            val thread = HandlerThread("ScreenCaptureThread").apply { start() }
            imageHandlerThread = thread
            imageHandler = Handler(thread.looper)

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - (pixelStride * image.width)

                    val bitmap = createBitmap(
                        image.width + (rowPadding / pixelStride),
                        image.height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    val cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                    
                    latestCapturedBitmap.getAndSet(cleanBitmap)?.recycle()
                    if (bitmap != cleanBitmap) bitmap.recycle()
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    image.close()
                }
            }, imageHandler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "MangaLensCapture",
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

    /**
     * Captures the latest frame from the buffer.
     * Uses coroutine-friendly delay if frame is not yet available.
     */
    suspend fun captureBitmap(): Bitmap {
        var snap = latestCapturedBitmap.get()
        
        if (snap == null || snap.isRecycled) {
            repeat(6) {
                delay(50.milliseconds)
                snap = latestCapturedBitmap.get()
                if (snap != null && !snap.isRecycled) return@repeat
            }
        }

        return if (snap != null && !snap.isRecycled) {
            Bitmap.createBitmap(snap)
        } else {
            createFallbackBitmap()
        }
    }

    private fun createFallbackBitmap(): Bitmap {
        val width = if (screenWidth > 0) screenWidth else 1080
        val height = if (screenHeight > 0) screenHeight else 2400
        val fallbackBitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(fallbackBitmap)
        canvas.drawColor("#111827".toColorInt())
        return fallbackBitmap
    }

    fun stop() {
        try {
            imageHandlerThread?.quitSafely()
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            imageHandlerThread = null
            virtualDisplay = null
            imageReader = null
            mediaProjection = null
        }
    }
}

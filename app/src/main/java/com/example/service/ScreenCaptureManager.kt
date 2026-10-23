package com.example.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.graphics.createBitmap
import com.example.translation.Box
import com.example.translation.FrameThumbnail
import java.util.concurrent.atomic.AtomicLong

/**
 * Mirrors the screen into an [ImageReader] and converts frames only on demand.
 *
 * The previous version built two full-screen bitmaps for every frame the display produced (~20 MB per
 * frame at 60 fps while scrolling), which caused GC storms and crashes. Now the reader thread only keeps
 * a reference to the newest [Image]; change detection samples a few thousand pixels straight from its
 * buffer and a real [Bitmap] is created only when a page is actually translated.
 */
class ScreenCaptureManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private val lock = Any()
    private var latestImage: Image? = null
    private val frames = AtomicLong(0)

    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set

    /** Increments every time the screen content changes; unchanged means nothing moved. */
    val frameCount: Long get() = frames.get()

    val isActive: Boolean get() = virtualDisplay != null

    /**
     * @param onStopped invoked when the system or the user revokes the capture (e.g. via the status bar).
     * @return false when the projection could not be started.
     */
    fun start(resultCode: Int, resultData: Intent, onStopped: () -> Unit): Boolean {
        stop()
        return try {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, resultData) ?: return false
            mediaProjection = projection

            val thread = HandlerThread("ScreenCaptureThread").apply { start() }
            handlerThread = thread
            handler = Handler(thread.looper)

            // Android 14+ refuses to create the virtual display without a registered callback.
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "Capture: projeção encerrada pelo sistema")
                    releaseDisplay()
                    onStopped()
                }
            }
            projectionCallback = callback
            projection.registerCallback(callback, handler)

            val (w, h, dpi) = readDisplayMetrics()
            width = w
            height = h
            val reader = newImageReader(w, h)
            imageReader = reader
            virtualDisplay = projection.createVirtualDisplay(
                "MangaLensCapture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
            Log.d(TAG, "Capture: VirtualDisplay ${w}x$h")
            virtualDisplay != null
        } catch (e: Exception) {
            Log.e(TAG, "Capture: falha ao iniciar", e)
            stop()
            false
        }
    }

    /** Call after rotation / display changes so the mirror keeps the real screen size. */
    fun onDisplayChanged() {
        val display = virtualDisplay ?: return
        val (w, h, dpi) = readDisplayMetrics()
        if (w == width && h == height) return
        val newReader = newImageReader(w, h)
        synchronized(lock) {
            latestImage?.close()
            latestImage = null
            width = w
            height = h
        }
        display.resize(w, h, dpi)
        display.surface = newReader.surface
        imageReader?.close()
        imageReader = newReader
        Log.d(TAG, "Capture: redimensionado para ${w}x$h")
    }

    private fun newImageReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ reader ->
                val image = try {
                    reader.acquireLatestImage()
                } catch (e: IllegalStateException) {
                    null
                } ?: return@setOnImageAvailableListener
                synchronized(lock) {
                    if (image.width != width || image.height != height) {
                        image.close()
                        return@synchronized
                    }
                    latestImage?.close()
                    latestImage = image
                }
                frames.incrementAndGet()
            }, handler)
        }

    /**
     * Grayscale grid of the current frame. Cells touching [masks] or the top [topInsetPx] (status bar clock)
     * are marked [FrameThumbnail.MASKED].
     */
    fun captureThumbnail(cols: Int, rows: Int, masks: List<Box>, topInsetPx: Int): FrameThumbnail? = synchronized(lock) {
        val image = latestImage ?: return null
        val plane = try { image.planes[0] } catch (e: IllegalStateException) { return null }
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        val cellW = w / cols
        val cellH = h / rows
        val luma = IntArray(cols * rows)
        for (cy in 0 until rows) {
            for (cx in 0 until cols) {
                val cell = Box(cx * cellW, cy * cellH, (cx + 1) * cellW, (cy + 1) * cellH)
                if (cell.top < topInsetPx || masks.any { it.intersects(cell) }) {
                    luma[cy * cols + cx] = FrameThumbnail.MASKED
                    continue
                }
                // Average a 3x3 sample inside the cell: cheap and robust to single-pixel noise.
                var sum = 0
                for (sy in 1..3) for (sx in 1..3) {
                    val x = cell.left + cellW * sx / 4
                    val y = cell.top + cellH * sy / 4
                    val offset = y * rowStride + x * pixelStride
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    sum += (r * 77 + g * 150 + b * 29) shr 8
                }
                luma[cy * cols + cx] = sum / 9
            }
        }
        FrameThumbnail(cols, rows, luma)
    }

    /** Full-resolution copy of the current frame, or null when no frame has arrived yet. */
    fun captureBitmap(): Bitmap? = synchronized(lock) {
        val image = latestImage ?: return null
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val padded = createBitmap(image.width + rowPadding / pixelStride, image.height)
            plane.buffer.rewind()
            padded.copyPixelsFromBuffer(plane.buffer)
            if (padded.width == image.width) {
                padded
            } else {
                Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture: erro ao converter frame: ${e.message}")
            null
        }
    }

    private fun readDisplayMetrics(): Triple<Int, Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dpi = context.resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), dpi)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, dpi)
        }
    }

    private fun releaseDisplay() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        synchronized(lock) {
            latestImage?.close()
            latestImage = null
        }
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
    }

    /** Releases everything without firing the onStopped callback (we initiated the stop). */
    fun stop() {
        releaseDisplay()
        projectionCallback?.let { cb -> try { mediaProjection?.unregisterCallback(cb) } catch (_: Exception) {} }
        projectionCallback = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    private companion object {
        const val TAG = "MangaLens"
    }
}

package com.example.translation

import android.graphics.Bitmap
import android.util.Log
import com.example.data.model.SupportedLanguage
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device text recognition (ML Kit, bundled models — no network, no quota). */
class OcrEngine {

    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val japanese by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val korean by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    private val chinese by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val created = mutableSetOf<TextRecognizer>()

    suspend fun recognize(bitmap: Bitmap, source: SupportedLanguage): List<OcrBlock> {
        val image = InputImage.fromBitmap(bitmap, 0)
        return when (source) {
            SupportedLanguage.JAPANESE -> recognizeWith(japanese, image)
            SupportedLanguage.KOREAN -> recognizeWith(korean, image)
            SupportedLanguage.CHINESE -> recognizeWith(chinese, image)
            SupportedLanguage.AUTO -> recognizeAuto(image)
            else -> recognizeWith(latin, image)
        }
    }

    /** Runs the Japanese and Korean models (both also read Latin) and keeps the one that found its script. */
    private suspend fun recognizeAuto(image: InputImage): List<OcrBlock> = coroutineScope {
        val ja = async { recognizeWith(japanese, image) }
        val ko = async { recognizeWith(korean, image) }
        val jaBlocks = ja.await()
        val koBlocks = ko.await()
        val hangul = koBlocks.sumOf { b -> b.text.count { it in HANGUL_SYLLABLES } }
        val kanaKanji = jaBlocks.sumOf { b -> b.text.count { it in KANA || it in CJK_IDEOGRAPHS } }
        if (hangul >= kanaKanji) koBlocks else jaBlocks
    }

    private suspend fun recognizeWith(recognizer: TextRecognizer, image: InputImage): List<OcrBlock> {
        synchronized(created) { created += recognizer }
        val result = try {
            recognizer.process(image).await()
        } catch (e: Exception) {
            Log.e("MangaLens", "OCR falhou: ${e.message}")
            return emptyList()
        }
        return result.textBlocks.mapNotNull { it.toOcrBlock() }
    }

    private fun Text.TextBlock.toOcrBlock(): OcrBlock? {
        val rect = boundingBox ?: return null
        val lineHeights = lines.mapNotNull { it.boundingBox?.let { r -> minOf(r.height(), r.width()) } }
        return OcrBlock(
            text = lines.joinToString(" ") { it.text.trim() }.ifBlank { text },
            box = Box(rect.left, rect.top, rect.right, rect.bottom),
            lineHeight = if (lineHeights.isEmpty()) rect.height() else lineHeights.average().toInt(),
            lineCount = lines.size.coerceAtLeast(1)
        )
    }

    private companion object {
        val HANGUL_SYLLABLES = 0xAC00.toChar()..0xD7A3.toChar()
        val KANA = 0x3040.toChar()..0x30FF.toChar()
        val CJK_IDEOGRAPHS = 0x4E00.toChar()..0x9FFF.toChar()
    }

    fun close() {
        synchronized(created) {
            created.forEach { runCatching { it.close() } }
            created.clear()
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

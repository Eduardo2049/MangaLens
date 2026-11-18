package com.example.translation

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.example.BuildConfig
import com.example.data.local.UserPreferences
import com.example.data.model.TranslationEngine
import com.example.data.remote.OpenRouterClient

/**
 * Translates a screenshot as cheaply as possible:
 * 1. on-device OCR finds the text and its exact position (free) — positions ALWAYS come from the OCR,
 *    never from the language model, which places boxes badly;
 * 2. bubbles already translated come from the cache (free — typical while scrolling a webtoon);
 * 3. only the new bubbles go to OpenRouter in a single request ([BlockTranslator] validates the answer).
 *    With the "image" engine the screenshot is attached too, so the model can fix OCR mistakes and read
 *    vertical text (slower, uses more quota).
 * Pages without text never reach the network.
 */
class TranslationPipeline(
    context: Context,
    private val client: OpenRouterClient = OpenRouterClient(),
    private val ocr: OcrEngine = OcrEngine()
) {
    val budget = RequestBudget(context.applicationContext, BuildConfig.OPENROUTER_DAILY_LIMIT)
    private val translator = BlockTranslator(budget, OpenRouterClient.textModels, OpenRouterClient.visionModels)

    suspend fun translate(
        bitmap: Bitmap,
        prefs: UserPreferences,
        exclusions: List<Box> = emptyList(),
        /** Called with what is ready so far: cached bubbles first, then streamed ones (may be an IO thread). */
        onPartial: (List<ScreenTranslation>) -> Unit = {}
    ): PageResult {
        if (!client.isConfigured(prefs.openRouterApiKey)) {
            return PageResult(emptyList(), 0, IllegalStateException(OpenRouterClient.MISSING_KEY_MESSAGE))
        }
        // Users who bought OpenRouter credits get 1000/day instead of 50: the limit follows the settings.
        budget.dailyLimit = prefs.dailyRequestLimit
        val ocrStart = SystemClock.elapsedRealtime()
        val blocks = TextRegions.prepare(
            ocr.recognize(bitmap, prefs.sourceLanguage), exclusions, bitmap.width, bitmap.height
        )
        Log.d(TAG, "OCR: ${blocks.size} regiões em ${SystemClock.elapsedRealtime() - ocrStart}ms")
        val chat = ChatBackend { models, system, user, image, maxTokens, onText ->
            client.complete(models, system, user, image, maxTokens, prefs.openRouterApiKey, onText)
        }
        val apiStart = SystemClock.elapsedRealtime()
        val result = translator.translate(
            blocks, prefs.sourceLanguage, prefs.targetLanguage, chat,
            image = bitmap.takeIf { prefs.translationEngine == TranslationEngine.VISION },
            onPartial = onPartial
        )
        if (result.requestsSent > 0) Log.d(TAG, "API: ${result.requestsSent} requisição(ões) em ${SystemClock.elapsedRealtime() - apiStart}ms")
        Log.d(TAG, result.stats.logLine())
        result.error?.let { Log.e(TAG, "Tradução: ${it.message}") }
        return result
    }

    fun close() = ocr.close()

    private companion object {
        const val TAG = "MangaLens"
    }
}

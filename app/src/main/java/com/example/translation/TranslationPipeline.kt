package com.example.translation

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.example.BuildConfig
import com.example.data.local.UserPreferences
import com.example.data.model.TranslationEngine
import com.example.data.model.TranslationProvider
import com.example.data.remote.OpenRouterClient

/**
 * Translates a screenshot as cheaply as possible:
 * 1. on-device OCR finds the text and its exact position (free) — positions ALWAYS come from the OCR,
 *    never from the language model, which places boxes badly;
 * 2. bubbles already translated come from the cache (free — typical while scrolling a webtoon);
 * 3. only the new bubbles go to the chosen provider (OpenRouter, OpenAI or Gemini) in a single request ([BlockTranslator] validates the answer).
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
    // One cache for every provider: switching provider does not lose bubbles that are already translated.
    private val cache = TextTranslationCache()
    private val otherClients = mutableMapOf<TranslationProvider, OpenRouterClient>()

    /** OpenRouter keeps the default client (build key, backend proxy); the others only need endpoint and dialect. */
    private fun clientFor(provider: TranslationProvider): OpenRouterClient =
        if (provider == TranslationProvider.OPENROUTER) client else otherClients.getOrPut(provider) {
            OpenRouterClient(
                endpoint = provider.endpoint, apiKey = "", dialect = provider.dialect,
                backendUrl = "", providerName = provider.title
            )
        }

    suspend fun translate(
        bitmap: Bitmap,
        prefs: UserPreferences,
        exclusions: List<Box> = emptyList(),
        /** Called with what is ready so far: cached bubbles first, then streamed ones (may be an IO thread). */
        onPartial: (List<ScreenTranslation>) -> Unit = {}
    ): PageResult {
        val provider = prefs.provider
        val key = prefs.keyFor(provider)
        val providerClient = clientFor(provider)
        val configured = if (provider == TranslationProvider.OPENROUTER) providerClient.isConfigured(key) else key.isNotBlank()
        if (!configured) {
            return PageResult(emptyList(), 0, IllegalStateException(provider.missingKeyMessage))
        }
        budget.provider = provider
        if (provider.hasFreeTierLimit) {
            // Users who bought OpenRouter credits get 1000/day instead of 50: the limit follows the settings.
            budget.dailyLimit = prefs.dailyRequestLimit
            budget.minIntervalMillis = OPENROUTER_MIN_INTERVAL_MS
        } else {
            // Paid keys have no app-side daily cap (the provider bills and limits them); only a short pause remains.
            budget.dailyLimit = 0
            budget.minIntervalMillis = PAID_MIN_INTERVAL_MS
        }
        val textModels = if (provider.usesChosenModel) listOf(prefs.modelFor(provider)) else OpenRouterClient.textModels
        val visionModels = if (provider.usesChosenModel) listOf(prefs.modelFor(provider)) else OpenRouterClient.visionModels
        val translator = BlockTranslator(budget, textModels, visionModels, cache)
        val ocrStart = SystemClock.elapsedRealtime()
        val blocks = TextRegions.prepare(
            ocr.recognize(bitmap, prefs.sourceLanguage), exclusions, bitmap.width, bitmap.height
        )
        Log.d(TAG, "OCR: ${blocks.size} regiões em ${SystemClock.elapsedRealtime() - ocrStart}ms")
        val chat = ChatBackend { models, system, user, image, maxTokens, onText ->
            // Reasoning models (GPT-5, Gemini) spend part of the limit on thinking; leave room for the answer.
            val tokens = if (provider.usesChosenModel) maxOf(maxTokens * 4, PAID_MIN_TOKENS) else maxTokens
            providerClient.complete(models, system, user, image, tokens, key, onText)
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
        const val OPENROUTER_MIN_INTERVAL_MS = 3_500L
        const val PAID_MIN_INTERVAL_MS = 1_000L
        const val PAID_MIN_TOKENS = 4096
    }
}

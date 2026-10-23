package com.example.translation

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.example.BuildConfig
import com.example.data.local.UserPreferences
import com.example.data.model.TranslationEngine
import com.example.data.remote.OpenRouterClient
import com.example.data.remote.RateLimitedException
import kotlinx.coroutines.delay

/** Outcome of translating one screen. [error] may be set even when some (cached) translations exist. */
data class PageResult(
    val translations: List<ScreenTranslation>,
    val requestsSent: Int,
    val error: Throwable? = null,
    val usedCacheOnly: Boolean = false
)

class DailyLimitReachedException(limit: Int) :
    Exception("Limite diário de $limit requisições atingido (cache continua funcionando)")

/**
 * Translates a screenshot as cheaply as possible:
 * 1. on-device OCR finds the text and its exact position (free) — positions ALWAYS come from the OCR,
 *    never from the language model, which places boxes badly;
 * 2. bubbles already translated come from the cache (free — typical while scrolling a webtoon);
 * 3. only the new bubbles go to OpenRouter in a single request. With the "image" engine the screenshot is
 *    attached too, so the model can fix OCR mistakes and read vertical text (slower, uses more quota).
 * Pages without text never reach the network.
 */
class TranslationPipeline(
    context: Context,
    private val client: OpenRouterClient = OpenRouterClient(),
    private val ocr: OcrEngine = OcrEngine()
) {
    val budget = RequestBudget(context.applicationContext, BuildConfig.OPENROUTER_DAILY_LIMIT)
    private val textCache = TextTranslationCache()

    suspend fun translate(
        bitmap: Bitmap,
        prefs: UserPreferences,
        exclusions: List<Box> = emptyList(),
        /** Called with what is ready so far: cached bubbles first, then streamed ones (may be an IO thread). */
        onPartial: (List<ScreenTranslation>) -> Unit = {}
    ): PageResult {
        if (!client.isConfigured) {
            return PageResult(emptyList(), 0, IllegalStateException("Configure OPENROUTER_API_KEY no local.properties"))
        }
        val ocrStart = SystemClock.elapsedRealtime()
        val blocks = TextRegions.prepare(
            ocr.recognize(bitmap, prefs.sourceLanguage), exclusions, bitmap.width, bitmap.height
        )
        Log.d(TAG, "OCR: ${blocks.size} regiões em ${SystemClock.elapsedRealtime() - ocrStart}ms")
        return translateBlocks(blocks, prefs, bitmap.takeIf { prefs.translationEngine == TranslationEngine.VISION }, onPartial)
    }

    private suspend fun translateBlocks(
        blocks: List<OcrBlock>,
        prefs: UserPreferences,
        image: Bitmap?,
        onPartial: (List<ScreenTranslation>) -> Unit
    ): PageResult {
        if (blocks.isEmpty()) return PageResult(emptyList(), 0, usedCacheOnly = true)
        val pair = "${prefs.sourceLanguage.code}>${prefs.targetLanguage.code}"

        val missing = blocks.filter { textCache.get(pair, it.text) == null }
            .distinctBy { TextTranslationCache.normalize(it.text) }
        var error: Throwable? = null
        var requests = 0
        if (missing.isNotEmpty()) {
            val cached = cachedTranslations(blocks, pair)
            if (cached.isNotEmpty()) onPartial(cached)
            val blocked = awaitBudget()
            if (blocked != null) {
                error = blocked
            } else {
                requests = 1
                budget.recordRequest()
                val items = missing.mapIndexed { index, block -> index + 1 to block.text }
                val apiStart = SystemClock.elapsedRealtime()
                var emitted = 0
                var lastEmitAt = 0L
                val result = client.complete(
                    models = if (image != null) OpenRouterClient.visionModels else OpenRouterClient.textModels,
                    systemPrompt = TranslationPrompts.textSystemPrompt(prefs.sourceLanguage, prefs.targetLanguage, image != null),
                    userText = TranslationPrompts.textUserPrompt(items, textCache.recent(pair, CONTEXT_LINES)),
                    image = image,
                    maxTokens = 300 + missing.sumOf { it.text.length } * 5,
                    onText = { soFar ->
                        // Show bubbles while the model is still writing the rest of the page.
                        val done = TranslationPrompts.parsePartialTranslations(soFar)
                        val now = SystemClock.elapsedRealtime()
                        if (done.size > emitted && now - lastEmitAt >= STREAM_EMIT_INTERVAL_MS) {
                            items.forEach { (id, text) -> done[id]?.let { textCache.put(pair, text, it) } }
                            if (emitted == 0) Log.d(TAG, "API: primeiro balão em ${now - apiStart}ms")
                            emitted = done.size
                            lastEmitAt = now
                            onPartial(cachedTranslations(blocks, pair))
                        }
                    }
                )
                Log.d(TAG, "API: ${items.size} textos em ${SystemClock.elapsedRealtime() - apiStart}ms (ok=${result.isSuccess})")
                result.onSuccess { raw ->
                    val parsed = TranslationPrompts.parseTextTranslations(raw)
                    if (parsed.isEmpty()) error = IllegalStateException("Resposta da IA sem traduções válidas")
                    // Empty strings are cached too: "not story text" should not be asked again.
                    items.forEach { (id, text) -> parsed[id]?.let { textCache.put(pair, text, it) } }
                }.onFailure { error = handleFailure(it) }
            }
        }

        return PageResult(cachedTranslations(blocks, pair), requests, error, usedCacheOnly = missing.isEmpty())
    }

    private fun cachedTranslations(blocks: List<OcrBlock>, pair: String): List<ScreenTranslation> =
        blocks.mapNotNull { block ->
            val translated = textCache.get(pair, block.text)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ScreenTranslation(block.text, translated, block.box)
        }

    /** Waits for the per-minute spacing; returns an error when the daily limit or a long cool-down applies. */
    private suspend fun awaitBudget(): Throwable? {
        val wait = budget.waitTimeMillis() ?: return DailyLimitReachedException(budget.limit)
        if (wait > MAX_INLINE_WAIT_MS) {
            return RateLimitedException(System.currentTimeMillis() + wait, "Aguardando limite da OpenRouter")
        }
        if (wait > 0) delay(wait)
        return null
    }

    private fun handleFailure(error: Throwable): Throwable {
        if (error is RateLimitedException) budget.blockUntil(error.retryAfterMillis)
        Log.e(TAG, "Tradução falhou: ${error.message}")
        return error
    }

    fun close() = ocr.close()

    private companion object {
        const val TAG = "MangaLens"
        const val CONTEXT_LINES = 8
        const val STREAM_EMIT_INTERVAL_MS = 350L
        const val MAX_INLINE_WAIT_MS = 8_000L
    }
}

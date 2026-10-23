package com.example.translation

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.BuildConfig
import com.example.data.local.UserPreferences
import com.example.data.model.SupportedLanguage
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
 * 1. on-device OCR finds the text and its exact position (free);
 * 2. bubbles already translated come from the cache (free — typical while scrolling a webtoon);
 * 3. only the new bubbles go to OpenRouter, as text, in a single request.
 * Pages without text never reach the network. Vision mode (the LLM reads the image) is used when chosen
 * in settings or when the page is vertical Japanese/Chinese, which the OCR reads poorly.
 */
class TranslationPipeline(
    context: Context,
    private val client: OpenRouterClient = OpenRouterClient(),
    private val ocr: OcrEngine = OcrEngine()
) {
    val budget = RequestBudget(context.applicationContext, BuildConfig.OPENROUTER_DAILY_LIMIT)
    private val textCache = TextTranslationCache()
    private val pageCache = PageCache<List<ScreenTranslation>>()

    suspend fun translate(
        bitmap: Bitmap,
        prefs: UserPreferences,
        exclusions: List<Box> = emptyList(),
        fingerprint: Long? = null
    ): PageResult {
        if (!client.isConfigured) {
            return PageResult(emptyList(), 0, IllegalStateException("Configure OPENROUTER_API_KEY no local.properties"))
        }
        val width = bitmap.width
        val height = bitmap.height
        val blocks = TextRegions.prepare(ocr.recognize(bitmap, prefs.sourceLanguage), exclusions, width, height)
        Log.d(TAG, "OCR: ${blocks.size} regiões de texto")

        val verticalScript = prefs.sourceLanguage in setOf(SupportedLanguage.AUTO, SupportedLanguage.JAPANESE, SupportedLanguage.CHINESE) &&
            TextRegions.isMostlyVertical(blocks)
        return if (prefs.translationEngine == TranslationEngine.VISION || verticalScript) {
            translateWithVision(bitmap, prefs, blocks, fingerprint)
        } else {
            translateTextBlocks(blocks, prefs)
        }
    }

    private suspend fun translateTextBlocks(blocks: List<OcrBlock>, prefs: UserPreferences): PageResult {
        if (blocks.isEmpty()) return PageResult(emptyList(), 0, usedCacheOnly = true)
        val pair = languagePair(prefs)

        val missing = blocks.filter { textCache.get(pair, it.text) == null }
            .distinctBy { TextTranslationCache.normalize(it.text) }
        var error: Throwable? = null
        var requests = 0
        if (missing.isNotEmpty()) {
            val ready = awaitBudget()
            if (ready != null) {
                error = ready
            } else {
                requests = 1
                budget.recordRequest()
                val items = missing.mapIndexed { index, block -> index + 1 to block.text }
                val result = client.complete(
                    models = OpenRouterClient.textModels,
                    systemPrompt = TranslationPrompts.textSystemPrompt(prefs.sourceLanguage, prefs.targetLanguage),
                    userText = TranslationPrompts.textUserPrompt(items, textCache.recent(pair, CONTEXT_LINES)),
                    maxTokens = 400 + missing.sumOf { it.text.length } * 6
                )
                result.onSuccess { raw ->
                    val parsed = TranslationPrompts.parseTextTranslations(raw)
                    if (parsed.isEmpty()) error = IllegalStateException("Resposta da IA sem traduções válidas")
                    // Empty strings are cached too: "not story text" should not be asked again.
                    items.forEach { (id, text) -> parsed[id]?.let { textCache.put(pair, text, it) } }
                }.onFailure { error = handleFailure(it) }
            }
        }

        val translations = blocks.mapNotNull { block ->
            val translated = textCache.get(pair, block.text)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ScreenTranslation(block.text, translated, block.box)
        }
        return PageResult(translations, requests, error, usedCacheOnly = missing.isEmpty())
    }

    private suspend fun translateWithVision(
        bitmap: Bitmap,
        prefs: UserPreferences,
        ocrBlocks: List<OcrBlock>,
        fingerprint: Long?
    ): PageResult {
        val cacheKey = fingerprint?.let { it xor languagePair(prefs).hashCode().toLong() }
        cacheKey?.let { key -> pageCache.get(key)?.let { return PageResult(it, 0, usedCacheOnly = true) } }

        awaitBudget()?.let { return PageResult(emptyList(), 0, it) }
        budget.recordRequest()
        // Mirrors OpenRouterClient's downscale, in case the model answers in absolute pixels.
        val scale = minOf(1f, 1280f / maxOf(bitmap.width, bitmap.height))
        val sentWidth = (bitmap.width * scale).toInt()
        val sentHeight = (bitmap.height * scale).toInt()

        val result = client.complete(
            models = OpenRouterClient.visionModels,
            systemPrompt = TranslationPrompts.visionSystemPrompt(prefs.sourceLanguage, prefs.targetLanguage),
            userText = "Translate this page.",
            image = bitmap,
            maxTokens = 3000
        )
        return result.fold(
            onSuccess = { raw ->
                val items = TranslationPrompts.parseVisionItems(raw, sentWidth, sentHeight, bitmap.width, bitmap.height)
                val refined = TranslationPrompts.refineWithOcr(items, ocrBlocks)
                val pair = languagePair(prefs)
                refined.forEach { if (it.originalText.isNotBlank()) textCache.put(pair, it.originalText, it.translatedText) }
                cacheKey?.let { pageCache.put(it, refined) }
                PageResult(refined, 1)
            },
            onFailure = { PageResult(emptyList(), 1, handleFailure(it)) }
        )
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

    private fun languagePair(prefs: UserPreferences) = "${prefs.sourceLanguage.code}>${prefs.targetLanguage.code}"

    fun close() = ocr.close()

    private companion object {
        const val TAG = "MangaLens"
        const val CONTEXT_LINES = 12
        const val MAX_INLINE_WAIT_MS = 8_000L
    }
}

package com.example.translation

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.example.BuildConfig
import com.example.data.local.AppDatabase
import com.example.data.local.RoomTranslationStore
import com.example.data.local.UserPreferences
import com.example.data.model.TranslationEngine
import com.example.data.model.TranslationProvider
import com.example.data.remote.OpenRouterClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates a screenshot as cheaply as possible:
 * 1. on-device OCR finds the text and its exact position (free) — positions ALWAYS come from the OCR,
 *    never from the language model, which places boxes badly;
 * 2. bubbles already translated come from the cache (free — typical while scrolling a webtoon), which is
 *    filled from the [TranslationStore] so translations survive between sessions;
 * 3. only the new bubbles go to the chosen provider (OpenRouter, OpenAI or Gemini) in a single request ([BlockTranslator] validates the answer).
 *    With the "image" engine the screenshot is attached too, so the model can fix OCR mistakes and read
 *    vertical text (slower, uses more quota).
 * Pages without text never reach the network.
 *
 * Use [acquire] / [release]: the app screen and the floating translator share one pipeline, so they share
 * the request spacing, the 429 cool-down and the cache.
 */
class TranslationPipeline(
    context: Context,
    private val client: OpenRouterClient = OpenRouterClient(),
    private val ocr: OcrEngine = OcrEngine(),
    private val store: TranslationStore? = null
) {
    val budget = RequestBudget(context.applicationContext, BuildConfig.OPENROUTER_DAILY_LIMIT)

    /** Translations received from the model that are not in the [store] yet (filled by the cache). */
    private val unsaved = mutableListOf<Triple<String, String, String>>()
    private val loadedPairs = mutableSetOf<String>()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Database writes run one at a time, in the order they were asked for: a delete never overtakes a save. */
    private val storeOps = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        storeScope.launch {
            for (op in storeOps) {
                try {
                    op()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Cache salvo: ${e.message}")
                }
            }
        }
    }

    // One cache for every provider: switching provider does not lose bubbles that are already translated.
    private val cache = TextTranslationCache().apply {
        onPut = { pair, original, translation -> synchronized(unsaved) { unsaved += Triple(pair, original, translation) } }
    }
    private val otherClients = ConcurrentHashMap<TranslationProvider, OpenRouterClient>()

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
        /** True when the user asked for a new translation (long-press): this page's cached texts are dropped. */
        fresh: Boolean = false,
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
        val textModels = if (provider.usesChosenModel) listOf(prefs.modelFor(provider)) else OpenRouterClient.textModelsFor(prefs.customModels[provider].orEmpty())
        val visionModels = if (provider.usesChosenModel) listOf(prefs.modelFor(provider)) else OpenRouterClient.visionModels
        val translator = BlockTranslator(budget, textModels, visionModels, cache)
        val pair = BlockTranslator.languagePair(prefs.sourceLanguage, prefs.targetLanguage)
        loadSaved(pair)
        val ocrStart = SystemClock.elapsedRealtime()
        val blocks = TextRegions.prepare(
            ocr.recognize(bitmap, prefs.sourceLanguage), exclusions, bitmap.width, bitmap.height
        )
        Log.d(TAG, "OCR: ${blocks.size} regiões em ${SystemClock.elapsedRealtime() - ocrStart}ms")
        if (fresh) forget(pair, blocks)
        val chat = ChatBackend { models, system, user, image, maxTokens, onText ->
            // Reasoning models (GPT-5, Gemini) spend part of the limit on thinking; leave room for the answer.
            val tokens = if (provider.usesChosenModel) maxOf(maxTokens * 4, PAID_MIN_TOKENS) else maxTokens
            providerClient.complete(models, system, user, image, tokens, key, onText)
        }
        val apiStart = SystemClock.elapsedRealtime()
        val result = try {
            translator.translate(
                blocks, prefs.sourceLanguage, prefs.targetLanguage, chat,
                image = bitmap.takeIf { prefs.translationEngine == TranslationEngine.VISION },
                onPartial = onPartial
            )
        } finally {
            // Also when the page was abandoned mid-stream: what already arrived was paid for.
            scheduleSave()
        }
        if (result.requestsSent > 0) Log.d(TAG, "API: ${result.requestsSent} requisição(ões) em ${SystemClock.elapsedRealtime() - apiStart}ms")
        Log.d(TAG, result.stats.logLine())
        result.error?.let { Log.e(TAG, "Tradução: ${it.message}") }
        return result
    }

    /** First use of a language pair in this process: bring back what earlier sessions translated. */
    private suspend fun loadSaved(pair: String) {
        val store = store ?: return
        if (!synchronized(loadedPairs) { loadedPairs.add(pair) }) return
        try {
            val saved = store.load(pair, PRELOAD_LIMIT)
            cache.preload(pair, saved)
            if (saved.isNotEmpty()) Log.d(TAG, "Cache salvo: ${saved.size} traduções carregadas ($pair)")
        } catch (e: CancellationException) {
            synchronized(loadedPairs) { loadedPairs.remove(pair) }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Cache salvo: falha ao carregar: ${e.message}")
        }
    }

    /** Long-press: the texts on this page are asked again instead of coming from the cache. */
    private fun forget(pair: String, blocks: List<OcrBlock>) {
        val keys = blocks.flatMap { cache.forget(pair, it.text) }.distinct()
        if (keys.isEmpty()) return
        // Translations of these texts that were still waiting to be saved are stale now.
        val keySet = keys.toSet()
        synchronized(unsaved) {
            unsaved.removeAll { (p, original, _) -> p == pair && TextTranslationCache.normalize(original) in keySet }
        }
        val store = store ?: return
        storeOps.trySend { store.delete(pair, keys) }
    }

    /** Writes what the model returned to the [store] without making the page wait for the database. */
    private fun scheduleSave() {
        val store = store ?: return
        val batch = synchronized(unsaved) {
            if (unsaved.isEmpty()) return
            unsaved.toList().also { unsaved.clear() }
        }
        storeOps.trySend {
            batch.groupBy({ it.first }, { it.second to it.third }).forEach { (pair, items) -> store.save(pair, items) }
        }
    }

    fun close() = ocr.close()

    companion object {
        private const val TAG = "MangaLens"
        private const val OPENROUTER_MIN_INTERVAL_MS = 3_500L
        private const val PAID_MIN_INTERVAL_MS = 1_000L
        private const val PAID_MIN_TOKENS = 4096
        /** Saved translations brought into the in-memory cache per language pair (it holds 600 in all). */
        private const val PRELOAD_LIMIT = 500

        private var shared: TranslationPipeline? = null
        private var users = 0

        /**
         * The process-wide pipeline. The app screen and the overlay service each call this once and
         * [release] when they are done.
         */
        fun acquire(context: Context): TranslationPipeline = synchronized(this) {
            users++
            shared ?: TranslationPipeline(
                context.applicationContext,
                store = RoomTranslationStore(AppDatabase.get(context).cachedTranslationDao())
            ).also { shared = it }
        }

        /** With no user left, the OCR models are freed; the cache and the request spacing are kept. */
        fun release() {
            synchronized(this) {
                if (users > 0) users--
                if (users == 0) shared?.close()
            }
        }
    }
}

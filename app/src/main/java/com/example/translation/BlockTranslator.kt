package com.example.translation

import android.graphics.Bitmap
import com.example.data.model.SupportedLanguage
import com.example.data.remote.ChatReply
import com.example.data.remote.RateLimitedException
import kotlinx.coroutines.delay

/** The network call the translator needs: OpenRouterClient in the app, a fake in tests. */
fun interface ChatBackend {
    suspend fun complete(
        models: List<String>,
        systemPrompt: String,
        userText: String,
        image: Bitmap?,
        maxTokens: Int,
        onText: ((String) -> Unit)?
    ): Result<ChatReply>
}

/** Request spacing and daily quota ([RequestBudget] in the app). */
interface RequestGate {
    val limit: Int
    /** Milliseconds to wait before the next request, or null when the daily limit is spent. */
    fun waitTimeMillis(): Long?
    fun recordRequest()
    fun blockUntil(timestampMillis: Long)
}

/** Counters for one page, so "API ok but 0 translations" can be told apart from cache hits or empty pages. */
data class PageStats(
    val regions: Int = 0,
    val cacheHits: Int = 0,
    val requested: Int = 0,
    val received: Int = 0,
    val valid: Int = 0,
    val shown: Int = 0,
    val attempts: Int = 0,
    val models: List<String> = emptyList(),
    val reason: String? = null
) {
    fun logLine(): String =
        "Página: ocr=$regions cacheHits=$cacheHits solicitados=$requested recebidos=$received " +
            "válidos=$valid exibidos=$shown tentativas=$attempts modelos=${models.joinToString(",").ifEmpty { "-" }}" +
            (reason?.let { " motivo=$it" } ?: "")
}

/** Outcome of translating one screen. [error] may be set even when some (cached) translations exist. */
data class PageResult(
    val translations: List<ScreenTranslation>,
    val requestsSent: Int,
    val error: Throwable? = null,
    val usedCacheOnly: Boolean = false,
    val stats: PageStats = PageStats(),
    /** Translations obtained from the API for this page (not from the cache); what goes to the history. */
    val fresh: List<ScreenTranslation> = emptyList()
)

class DailyLimitReachedException(limit: Int) :
    Exception("Limite diário de $limit requisições atingido (cache continua funcionando)")

class InvalidModelResponseException(message: String) : Exception(message)

/**
 * Turns OCR blocks into validated translations:
 *  - only texts missing from the cache are sent, each with a fresh 1..n id and a length budget;
 *  - the answer is accepted per id: unknown ids, 0-based ids, untranslated echoes and runaway text are
 *    rejected instead of trusting "HTTP 200";
 *  - when a model answers with no usable translations (e.g. a content-safety model picked by the fallback)
 *    or leaves many bubbles out, the missing ones are asked again once, with the model order rotated.
 */
class BlockTranslator(
    private val gate: RequestGate,
    private val textModels: List<String>,
    private val visionModels: List<String>,
    val cache: TextTranslationCache = TextTranslationCache(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {

    suspend fun translate(
        blocks: List<OcrBlock>,
        source: SupportedLanguage,
        target: SupportedLanguage,
        chat: ChatBackend,
        image: Bitmap? = null,
        onPartial: (List<ScreenTranslation>) -> Unit = {}
    ): PageResult {
        if (blocks.isEmpty()) return PageResult(emptyList(), 0, usedCacheOnly = true, stats = PageStats())
        val pair = "${source.code}>${target.code}"
        val uncached = blocks.filter { cache.get(pair, it.text) == null }
        var pending = uncached.distinctBy { TextTranslationCache.normalize(it.text) }
        var stats = PageStats(regions = blocks.size, cacheHits = blocks.size - uncached.size, requested = pending.size)
        if (pending.isEmpty()) {
            val shown = toScreen(blocks, pair)
            return PageResult(shown, 0, usedCacheOnly = true, stats = stats.copy(shown = shown.size))
        }

        toScreen(blocks, pair).takeIf { it.isNotEmpty() }?.let(onPartial)
        var error: Throwable? = null
        var requests = 0
        var models = if (image != null) visionModels else textModels

        while (pending.isNotEmpty() && requests < MAX_ATTEMPTS) {
            val blocked = awaitBudget()
            if (blocked != null) {
                error = blocked
                break
            }
            requests++
            gate.recordRequest()
            val items = pending.mapIndexed { index, block -> TranslationItem(index + 1, block.text, lengthBudget(block)) }
            var emitted = 0
            var lastEmitAt = 0L
            val result = chat.complete(
                models,
                TranslationPrompts.textSystemPrompt(source, target, image != null),
                TranslationPrompts.textUserPrompt(items, cache.recent(pair, CONTEXT_LINES)),
                image,
                (300 + items.sumOf { it.text.length } * 5).coerceAtLeast(512)
            ) { soFar ->
                // Show bubbles while the model is still writing the rest of the page. Ids are normalized
                // exactly like the final answer: a 0-based answer would otherwise show (and cache) every
                // bubble with its neighbour's text until the stream ends.
                val partial = normalizeIds(TranslationPrompts.parsePartialTranslations(soFar), items.size)
                val done = accept(items, partial, target)
                val now = clock()
                if (done.size > emitted && now - lastEmitAt >= STREAM_EMIT_INTERVAL_MS) {
                    done.forEach { (item, text) -> cache.put(pair, item.text, text) }
                    emitted = done.size
                    lastEmitAt = now
                    onPartial(toScreen(blocks, pair))
                }
            }

            val reply = result.getOrNull()
            if (reply == null) {
                // OpenRouter already falls through its model list on provider errors: do not retry here.
                error = handleFailure(result.exceptionOrNull() ?: IllegalStateException("Falha na tradução"))
                break
            }
            val parsed = normalizeIds(TranslationPrompts.parseTextTranslations(reply.content), items.size)
            val accepted = accept(items, parsed, target)
            // Empty strings are cached too: "not story text" should not be asked again.
            accepted.forEach { (item, text) -> cache.put(pair, item.text, text) }
            val acceptedIds = accepted.map { it.first.id }.toSet()
            val missing = items.filter { it.id !in acceptedIds }
            stats = stats.copy(
                received = stats.received + parsed.size,
                valid = stats.valid + accepted.size,
                attempts = requests,
                models = stats.models + (reply.model ?: "?"),
                reason = when {
                    missing.isEmpty() -> null
                    parsed.isEmpty() -> "estrutura_inválida"
                    else -> "incompleta(${missing.size})"
                }
            )
            pending = missing.map { pending[it.id - 1] }
            // A few missing bubbles are not worth a second request out of a small daily quota.
            val worthRetry = accepted.isEmpty() || missing.size >= maxOf(2, items.size / 4)
            if (pending.isEmpty() || !worthRetry) break
            models = rotate(models, reply.model)
        }

        if (error == null && stats.valid == 0 && stats.requested > 0) {
            error = InvalidModelResponseException(
                "Resposta da IA sem traduções válidas (modelo ${stats.models.lastOrNull() ?: "?"})"
            )
        }
        val shown = toScreen(blocks, pair)
        val requestedTexts = uncached.map { it.text }.toSet()
        return PageResult(
            shown, requests, error, usedCacheOnly = false, stats = stats.copy(shown = shown.size),
            fresh = shown.filter { it.originalText in requestedTexts }
        )
    }

    private fun toScreen(blocks: List<OcrBlock>, pair: String): List<ScreenTranslation> =
        blocks.mapNotNull { block ->
            val translated = cache.get(pair, block.text)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ScreenTranslation(
                originalText = block.text,
                translatedText = translated,
                box = block.box,
                isVertical = block.box.height > block.box.width * 1.5f && block.text.any(TextRegions::isCjk),
                lineHeight = block.lineHeight
            )
        }

    /** Waits for the per-minute spacing; returns an error when the daily limit or a long cool-down applies. */
    private suspend fun awaitBudget(): Throwable? {
        val wait = gate.waitTimeMillis() ?: return DailyLimitReachedException(gate.limit)
        if (wait > MAX_INLINE_WAIT_MS) {
            return RateLimitedException(clock() + wait, "Aguardando limite da OpenRouter")
        }
        if (wait > 0) sleep(wait)
        return null
    }

    private fun handleFailure(error: Throwable): Throwable {
        if (error is RateLimitedException) gate.blockUntil(error.retryAfterMillis)
        return error
    }

    companion object {
        const val MAX_ATTEMPTS = 2
        const val CONTEXT_LINES = 8
        const val STREAM_EMIT_INTERVAL_MS = 350L
        const val MAX_INLINE_WAIT_MS = 8_000L

        private val CJK_TARGETS = setOf(SupportedLanguage.JAPANESE, SupportedLanguage.KOREAN, SupportedLanguage.CHINESE)

        /** Soft character budget for the model; Latin sources always get at least their own length. */
        fun lengthBudget(block: OcrBlock): Int? {
            if (block.lineHeight <= 0) return null
            val capacity = BubbleTextFitter.estimateCapacity(block.box, block.lineHeight)
            val latinSource = block.text.none(TextRegions::isCjk)
            return if (latinSource) maxOf(capacity, block.text.length) else capacity
        }

        /** Models sometimes number from 0; shift that back and drop ids that were never asked. */
        fun normalizeIds(parsed: Map<Int, String>, count: Int): Map<Int, String> {
            if (parsed.isEmpty()) return parsed
            // Stray ids (e.g. 99) must not hide a 0..n-1 numbering, or every bubble gets its neighbour's text.
            val zeroBased = 0 in parsed && count !in parsed
            val shifted = if (zeroBased) parsed.mapKeys { it.key + 1 } else parsed
            return shifted.filterKeys { it in 1..count }
        }

        /** Pairs each item with its translation when the translation looks like a real one. */
        fun accept(items: List<TranslationItem>, parsed: Map<Int, String>, target: SupportedLanguage): List<Pair<TranslationItem, String>> =
            items.mapNotNull { item ->
                val text = parsed[item.id]?.trim() ?: return@mapNotNull null
                if (isPlausible(item.text, text, target)) item to text else null
            }

        fun isPlausible(source: String, translation: String, target: SupportedLanguage): Boolean {
            if (translation.isEmpty()) return true // "not story text"
            // Runaway output (explanations, repeated text) would not fit any bubble.
            if (translation.length > maxOf(120, source.length * 8)) return false
            if (target !in CJK_TARGETS) {
                // Untranslated Japanese/Korean/Chinese echoed back into a Latin-script target.
                val letters = translation.count { it.isLetter() }
                val cjk = translation.count(TextRegions::isCjk)
                if (cjk >= 2 && cjk * 10 > letters * 3) return false
            }
            return true
        }

        /** Next attempt starts with a different model than the one that just failed us. */
        fun rotate(models: List<String>, failedModel: String?): List<String> {
            if (models.size < 2) return models
            val index = models.indexOfFirst { failedModel != null && (it == failedModel || failedModel.startsWith(it.substringBefore(':'))) }
            return if (index >= 0) models.filterIndexed { i, _ -> i != index } + models[index] else models.drop(1) + models.first()
        }
    }
}

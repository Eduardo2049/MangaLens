package com.example.translation

import com.example.data.model.SupportedLanguage
import com.example.data.remote.ChatReply
import com.example.data.remote.RateLimitedException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Reproduces the situations seen in the device logs with scripted model answers (no network, no quota).
 * org.json is an Android class, so this runs under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BlockTranslatorTest {

    private class FakeGate(var dailyLeft: Int = 100) : RequestGate {
        override val limit = 50
        var recorded = 0
        var blockedUntil = 0L
        override fun waitTimeMillis(): Long? = if (dailyLeft <= 0) null else 0L
        override fun recordRequest() {
            recorded++
            dailyLeft--
        }
        override fun blockUntil(timestampMillis: Long) {
            blockedUntil = timestampMillis
        }
    }

    /** Answers each call with the next scripted function of the request's items. */
    private class ScriptedChat(vararg answers: (List<Pair<Int, String>>) -> Result<ChatReply>) : ChatBackend {
        private val queue = ArrayDeque(answers.toList())
        val calls = mutableListOf<Pair<List<String>, List<Pair<Int, String>>>>()
        val userPrompts = mutableListOf<String>()
        var streamChunks: ((List<Pair<Int, String>>) -> Sequence<String>)? = null

        override suspend fun complete(
            models: List<String>, systemPrompt: String, userText: String,
            image: android.graphics.Bitmap?, maxTokens: Int, onText: ((String) -> Unit)?
        ): Result<ChatReply> {
            val items = JSONObject(userText).getJSONArray("items").let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getInt("id") to o.getString("text") } }
            }
            calls += models to items
            userPrompts += userText
            streamChunks?.invoke(items)?.forEach { onText?.invoke(it) }
            return queue.removeFirst()(items)
        }
    }

    private fun json(entries: List<Pair<Int, String>>) =
        JSONObject().put("t", JSONArray().apply { entries.forEach { (id, t) -> put(JSONArray().put(id).put(t)) } }).toString()

    private fun translated(model: String = "dots-studio/dots-3-note-preview:free") = { items: List<Pair<Int, String>> ->
        Result.success(ChatReply(json(items.map { (id, text) -> id to "PT:$text" }), model))
    }

    // Clearly different sentences: the cache tolerates OCR noise, so near-identical lines would collide.
    private val sentences = listOf(
        "I can't believe it", "Where are you going?", "Stop right there!", "Thank you so much",
        "What happened to him?", "Let's go home now", "Nobody saw anything", "Wait for me, please!",
        "This is the end", "Run as fast as you can"
    )

    private fun blocks(n: Int) =
        (0 until n).map { i -> OcrBlock(sentences[i], Box(100, 100 + i * 200, 500, 160 + i * 200), lineHeight = 30) }

    private var now = 0L
    private fun translator(gate: RequestGate = FakeGate(), cache: TextTranslationCache = TextTranslationCache()) =
        BlockTranslator(gate, listOf("a:free", "b:free", "c:free"), listOf("v:free"), cache, clock = { now }, sleep = {})

    private val en = SupportedLanguage.ENGLISH
    private val pt = SupportedLanguage.PORTUGUESE

    @Test
    fun `all bubbles translated in one request`() = runTest {
        val chat = ScriptedChat(translated())
        val result = translator().translate(blocks(10), en, pt, chat)
        assertNull(result.error)
        assertEquals(10, result.translations.size)
        assertEquals(1, result.requestsSent)
        assertEquals(PageStats(10, 0, 10, 10, 10, 10, 1, listOf("dots-studio/dots-3-note-preview:free"), null), result.stats)
        assertEquals("PT:I can't believe it", result.translations[0].translatedText)
    }

    @Test
    fun `log case - content safety model answers ok with no translations, retried on another model`() = runTest {
        val chat = ScriptedChat(
            { _ -> Result.success(ChatReply("User Safety: safe", "nvidia/nemotron-3.5-content-safety:free")) },
            translated("b:free")
        )
        val result = translator().translate(blocks(10), en, pt, chat)
        assertNull(result.error)
        assertEquals(10, result.translations.size)
        assertEquals(2, result.requestsSent)
        assertEquals(listOf("nvidia/nemotron-3.5-content-safety:free", "b:free"), result.stats.models)
        // Second attempt starts with a different model.
        assertEquals("b:free", chat.calls[1].first.first())
    }

    @Test
    fun `both attempts invalid reports an explicit error instead of a silent empty page`() = runTest {
        val bad = { _: List<Pair<Int, String>> -> Result.success(ChatReply("safe", "x")) }
        val result = translator().translate(blocks(10), en, pt, ScriptedChat(bad, bad))
        assertTrue(result.error is InvalidModelResponseException)
        assertEquals(0, result.translations.size)
        assertEquals("estrutura_inválida", result.stats.reason)
        assertEquals(2, result.stats.attempts)
    }

    @Test
    fun `log case - nine regions with one cached sends eight and shows nine`() = runTest {
        val cache = TextTranslationCache()
        val page = blocks(9)
        cache.put("en>pt", page[4].text, "já traduzido")
        val chat = ScriptedChat(translated())
        val result = translator(cache = cache).translate(page, en, pt, chat)
        assertEquals(8, chat.calls.single().second.size)
        assertEquals(1, result.stats.cacheHits)
        assertEquals(8, result.stats.requested)
        assertEquals(9, result.stats.shown)
        assertEquals("já traduzido", result.translations[4].translatedText)
    }

    @Test
    fun `fully cached page and empty page never hit the network`() = runTest {
        val gate = FakeGate()
        val chat = ScriptedChat()
        val t = translator(gate)
        val empty = t.translate(emptyList(), en, pt, chat)
        assertTrue(empty.usedCacheOnly)
        t.cache.put("en>pt", "Hello there friend", "Olá, amigo")
        val cached = t.translate(listOf(OcrBlock("Hello there friend", Box(0, 0, 100, 40), 20)), en, pt, chat)
        assertEquals(1, cached.translations.size)
        assertEquals(0, gate.recorded)
        assertTrue(chat.calls.isEmpty())
    }

    @Test
    fun `zero based ids are shifted and unknown ids ignored`() = runTest {
        val chat = ScriptedChat({ items ->
            val zeroBased = items.map { (id, text) -> id - 1 to "PT:$text" } + (99 to "lixo")
            Result.success(ChatReply(json(zeroBased), "m"))
        })
        val result = translator().translate(blocks(3), en, pt, chat)
        assertEquals(listOf("PT:I can't believe it", "PT:Where are you going?", "PT:Stop right there!"),
            result.translations.map { it.translatedText })
    }

    @Test
    fun `untranslated japanese echo is rejected and asked again`() = runTest {
        val page = listOf(
            OcrBlock("どうしてここにいるの", Box(500, 100, 540, 400), 40),
            OcrBlock("ありがとう", Box(100, 100, 140, 300), 40)
        )
        val chat = ScriptedChat(
            { items -> Result.success(ChatReply(json(items.map { (id, text) -> id to text }), "m1")) },
            { items -> Result.success(ChatReply(json(items.map { (id, _) -> id to "Tradução $id" }), "m2")) }
        )
        val result = translator().translate(page, SupportedLanguage.JAPANESE, pt, chat)
        assertEquals(2, result.requestsSent)
        assertTrue(result.translations.none { it.translatedText.any(TextRegions::isCjk) })
        assertTrue(result.translations.all { it.isVertical })
    }

    @Test
    fun `one missing bubble out of ten is not worth a second request`() = runTest {
        val chat = ScriptedChat({ items ->
            Result.success(ChatReply(json(items.drop(1).map { (id, text) -> id to "PT:$text" }), "m"))
        })
        val result = translator().translate(blocks(10), en, pt, chat)
        assertEquals(1, result.requestsSent)
        assertEquals(9, result.translations.size)
        assertNull(result.error)
        assertEquals("incompleta(1)", result.stats.reason)
    }

    @Test
    fun `many missing bubbles are asked again with only the missing ones`() = runTest {
        val chat = ScriptedChat(
            { items -> Result.success(ChatReply(json(items.take(4).map { (id, text) -> id to "PT:$text" }), "m")) },
            translated()
        )
        val result = translator().translate(blocks(10), en, pt, chat)
        assertEquals(6, chat.calls[1].second.size)
        // Fresh ids for the retry.
        assertEquals((1..6).toList(), chat.calls[1].second.map { it.first })
        assertEquals(10, result.translations.size)
    }

    @Test
    fun `empty answer means not story text and is cached`() = runTest {
        val t = translator()
        val chat = ScriptedChat({ items -> Result.success(ChatReply(json(items.map { (id, _) -> id to "" }), "m")) })
        val first = t.translate(blocks(2), en, pt, chat)
        assertEquals(0, first.translations.size)
        assertNull(first.error) // valid answers, just nothing to draw
        val second = t.translate(blocks(2), en, pt, chat)
        assertEquals(0, second.requestsSent)
    }

    @Test
    fun `daily limit blocks the request but keeps cached bubbles`() = runTest {
        val gate = FakeGate(dailyLeft = 0)
        val result = translator(gate).translate(blocks(2), en, pt, ScriptedChat())
        assertTrue(result.error is DailyLimitReachedException)
        assertEquals(0, result.requestsSent)
    }

    @Test
    fun `rate limit failure blocks the gate and is not retried`() = runTest {
        val gate = FakeGate()
        val chat = ScriptedChat({ _ -> Result.failure(RateLimitedException(123_456L, "429")) })
        val result = translator(gate).translate(blocks(2), en, pt, chat)
        assertTrue(result.error is RateLimitedException)
        assertEquals(123_456L, gate.blockedUntil)
        assertEquals(1, chat.calls.size)
    }

    @Test
    fun `network failure is reported`() = runTest {
        val chat = ScriptedChat({ _ -> Result.failure(IOException("timeout")) })
        val result = translator().translate(blocks(2), en, pt, chat)
        assertEquals("timeout", result.error?.message)
    }

    @Test
    fun `streamed bubbles are shown progressively`() = runTest {
        val partials = mutableListOf<Int>()
        val chat = ScriptedChat(translated())
        chat.streamChunks = { items ->
            val full = json(items.map { (id, text) -> id to "PT:$text" })
            // Cut the answer into growing prefixes, advancing the clock between them.
            (1..4).asSequence().map { k -> now += 400; full.substring(0, full.length * k / 4) }
        }
        val result = translator().translate(blocks(4), en, pt, chat) { partials += it.size }
        assertTrue("partials=$partials", partials.isNotEmpty())
        assertTrue(partials.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(4, result.translations.size)
    }

    @Test
    fun `prompt carries a length budget per bubble`() = runTest {
        val chat = ScriptedChat(translated())
        translator().translate(blocks(1), en, pt, chat)
        val item = JSONObject(chat.userPrompts.single()).getJSONArray("items").getJSONObject(0)
        assertTrue(item.getInt("max") >= "I can't believe it".length)
    }

    @Test
    fun `rotation moves the failing model to the end`() {
        val models = listOf("a/x:free", "b/y:free", "c/z:free")
        assertEquals(listOf("a/x:free", "c/z:free", "b/y:free"), BlockTranslator.rotate(models, "b/y"))
        assertEquals(listOf("b/y:free", "c/z:free", "a/x:free"), BlockTranslator.rotate(models, "unknown/safety"))
    }

    @Test
    fun `plausibility rules`() {
        assertTrue(BlockTranslator.isPlausible("ありがとう", "Obrigado", pt))
        assertTrue(!BlockTranslator.isPlausible("ありがとう", "ありがとう", pt))
        assertTrue(BlockTranslator.isPlausible("Thanks", "ありがとう", SupportedLanguage.JAPANESE))
        assertTrue(!BlockTranslator.isPlausible("Hi", "x".repeat(500), pt))
    }
}

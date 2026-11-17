package com.example.translation

import com.example.BuildConfig
import com.example.data.model.SupportedLanguage
import com.example.data.remote.ApiDialect
import com.example.data.remote.OpenRouterClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.ceil

/**
 * Real translation quality check against a live model. Skipped unless explicitly enabled, so normal builds
 * and CI never spend quota:
 *
 *   $env:MANGALENS_LIVE_TEST="1"
 *   # OpenRouter: OPENROUTER_API_KEY in local.properties or the environment
 *   # OpenAI:     $env:OPENAI_API_KEY="sk-..."   (optional $env:OPENAI_MODEL, default gpt-4.1-mini)
 *   .\gradlew.bat :app:testDebugUnitTest --tests "*LiveTranslationTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveTranslationTest {

    private val enabled = System.getenv("MANGALENS_LIVE_TEST") == "1"

    private class OpenGate : RequestGate {
        override val limit = 0
        private var last = 0L
        // Keep the free-tier spacing even in tests.
        override fun waitTimeMillis(): Long = maxOf(0L, last + 3_500L - System.currentTimeMillis())
        override fun recordRequest() { last = System.currentTimeMillis() }
        override fun blockUntil(timestampMillis: Long) = Unit
    }

    private data class Sample(val text: String, val box: Box, val lineHeight: Int, val expected: List<String>)

    private val japanesePage = listOf(
        Sample("ありがとう", Box(800, 100, 850, 330), 48, listOf("obrigad", "valeu")),
        Sample("どうしてここにいるの？", Box(700, 100, 750, 560), 48, listOf("por que", "porque", "aqui")),
        Sample("おはようございます", Box(600, 100, 650, 520), 48, listOf("bom dia")),
        Sample("待って！", Box(500, 100, 550, 300), 48, listOf("espera", "espere", "aguarde"))
    )
    private val englishPage = listOf(
        Sample("Where are you going?", Box(100, 100, 420, 170), 32, listOf("onde", "aonde")),
        Sample("I'll never forgive you!", Box(100, 400, 440, 470), 32, listOf("perdo")),
        Sample("Thank you for saving me.", Box(100, 700, 460, 770), 32, listOf("obrigad", "salvar", "salvou"))
    )

    @Test
    fun `openrouter translates manga lines with good quality`() {
        val key = BuildConfig.OPENROUTER_API_KEY.ifBlank { System.getenv("OPENROUTER_API_KEY").orEmpty() }
        assumeTrue("MANGALENS_LIVE_TEST=1 e OPENROUTER_API_KEY necessários", enabled && key.isNotBlank())
        val client = OpenRouterClient(apiKey = key)
        check(OpenRouterClient.textModels, client)
    }

    @Test
    fun `openai translates manga lines with good quality`() {
        val key = System.getenv("OPENAI_API_KEY").orEmpty()
        assumeTrue("MANGALENS_LIVE_TEST=1 e OPENAI_API_KEY necessários", enabled && key.isNotBlank())
        val client = OpenRouterClient(OpenRouterClient.OPENAI_URL, key, dialect = ApiDialect.OPENAI)
        check(listOf(System.getenv("OPENAI_MODEL")?.takeIf { it.isNotBlank() } ?: "gpt-4.1-mini"), client)
    }

    private fun check(models: List<String>, client: OpenRouterClient) = runBlocking {
        val translator = BlockTranslator(OpenGate(), models, models)
        val chat = ChatBackend { m, system, user, image, maxTokens, onText ->
            client.complete(m, system, user, image, maxTokens, onText = onText)
        }
        for ((source, page) in listOf(SupportedLanguage.JAPANESE to japanesePage, SupportedLanguage.ENGLISH to englishPage)) {
            val blocks = page.map { OcrBlock(it.text, it.box, it.lineHeight) }
            val result = translator.translate(blocks, source, SupportedLanguage.PORTUGUESE, chat)
            println(result.stats.logLine())
            result.translations.forEach { println("  ${it.originalText} -> ${it.translatedText}") }
            assertEquals("erro: ${result.error?.message}", null, result.error)
            assertEquals("todas as falas traduzidas", page.size, result.translations.size)

            for (sample in page) {
                val translation = result.translations.single { it.originalText == sample.text }.translatedText
                val lower = translation.lowercase()
                assertTrue("'${sample.text}' -> '$translation' sem CJK", translation.none(TextRegions::isCjk))
                assertTrue("'${sample.text}' -> '$translation' esperado um de ${sample.expected}", sample.expected.any { it in lower })
                // The translation must fit its bubble at a readable size (monospace estimate).
                val fit = BubbleTextFitter.fit(translation, sample.box, Box(0, 0, 1080, 2400), emptyList(), SPEC, MONOSPACE)
                assertTrue("'$translation' não coube no balão", !fit.overflow)
            }
        }
    }

    private companion object {
        val SPEC = FitSpec(minTextPx = 22f, comfortableTextPx = 36f, maxTextPx = 48f, stepPx = 1f, paddingPx = 8, minCardWidthPx = 150)

        val MONOSPACE = TextMeasurer { text, size, width ->
            val charW = size * 0.55f
            val perLine = (width / charW).toInt().coerceAtLeast(1)
            var lines = 1
            var current = 0
            var broken = false
            for (word in text.split(' ').filter { it.isNotEmpty() }) {
                if (word.length > perLine) broken = true
                current = when {
                    current == 0 -> word.length
                    current + 1 + word.length <= perLine -> current + 1 + word.length
                    else -> { lines++; word.length }
                }
            }
            TextMeasure(ceil(lines * size * 1.2f).toInt(), minOf(width.toFloat(), perLine * charW), lines, broken)
        }
    }
}

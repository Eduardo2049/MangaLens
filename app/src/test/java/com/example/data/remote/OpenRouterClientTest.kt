package com.example.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The HTTP layer is replaced by an interceptor: checks request shape and response handling offline. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenRouterClientTest {

    private var lastRequest: Request? = null

    private fun http(code: Int = 200, body: String, headers: Map<String, String> = emptyMap()) =
        OkHttpClient.Builder().addInterceptor { chain ->
            lastRequest = chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("x")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

    private fun sentJson(): JSONObject {
        val buffer = Buffer()
        lastRequest!!.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    private val okBody = """{"model":"dots-studio/dots-3-note-preview","choices":[{"message":{"content":"{\"t\":[[1,\"Olá\"]]}"},"finish_reason":"stop"}]}"""

    @Test
    fun `openrouter dialect sends fallback models and disables reasoning`() = runTest {
        val client = OpenRouterClient(apiKey = "build-key", httpClient = http(body = okBody))
        val reply = client.complete(listOf("a:free", "b:free", "c:free", "d:free"), "sys", "user").getOrThrow()
        assertEquals("""{"t":[[1,"Olá"]]}""", reply.content)
        assertEquals("dots-studio/dots-3-note-preview", reply.model)
        val sent = sentJson()
        assertEquals(3, sent.getJSONArray("models").length()) // OpenRouter rejects longer lists
        assertFalse(sent.getJSONObject("reasoning").getBoolean("enabled"))
        assertEquals(OpenRouterClient.OPENROUTER_URL, lastRequest!!.url.toString())
        assertEquals("Bearer build-key", lastRequest!!.header("Authorization"))
    }

    @Test
    fun `key typed in the app overrides the build key`() = runTest {
        val client = OpenRouterClient(apiKey = "build-key", httpClient = http(body = okBody))
        client.complete(listOf("a:free"), "sys", "user", apiKeyOverride = "  app-key ").getOrThrow()
        assertEquals("Bearer app-key", lastRequest!!.header("Authorization"))
        assertTrue(client.isConfigured("app-key"))
    }

    @Test
    fun `openai dialect sends a single model`() = runTest {
        val client = OpenRouterClient(OpenRouterClient.OPENAI_URL, "k", http(body = okBody), ApiDialect.OPENAI)
        client.complete(listOf("gpt-4.1-mini"), "sys", "user", maxTokens = 700).getOrThrow()
        val sent = sentJson()
        assertEquals("gpt-4.1-mini", sent.getString("model"))
        assertEquals(700, sent.getInt("max_completion_tokens"))
        assertFalse(sent.has("models"))
        assertFalse(sent.has("reasoning"))
    }

    @Test
    fun `null content is an error, not the string null`() = runTest {
        val body = """{"model":"m","choices":[{"message":{"content":null},"finish_reason":"length"}]}"""
        val result = OpenRouterClient(apiKey = "k", httpClient = http(body = body)).complete(listOf("a:free"), "s", "u")
        assertTrue(result.isFailure)
    }

    @Test
    fun `http 429 becomes RateLimitedException with retry time`() = runTest {
        val client = OpenRouterClient(apiKey = "k", httpClient = http(429, "{}", mapOf("Retry-After" to "30")))
        val error = client.complete(listOf("a:free"), "s", "u").exceptionOrNull()
        assertTrue(error is RateLimitedException)
        assertTrue((error as RateLimitedException).retryAfterMillis > System.currentTimeMillis() + 20_000)
    }

    @Test
    fun `error object inside a 200 is a failure`() = runTest {
        val client = OpenRouterClient(apiKey = "k", httpClient = http(body = """{"error":{"message":"No endpoints found","code":404}}"""))
        assertEquals("OpenRouter: No endpoints found", client.complete(listOf("a:free"), "s", "u").exceptionOrNull()?.message)
    }

    @Test
    fun `stream is accumulated and reported progressively`() = runTest {
        val sse = listOf(
            """: OPENROUTER PROCESSING""",
            """data: {"model":"m1","choices":[{"delta":{"content":"{\"t\":[[1,"}}]}""",
            """data: {"model":"m1","choices":[{"delta":{"content":"\"Oi\"]]}"}}]}""",
            """data: {"model":"m1","choices":[{"delta":{"content":null},"finish_reason":"stop"}]}""",
            "data: [DONE]"
        ).joinToString("\n\n")
        val seen = mutableListOf<String>()
        val reply = OpenRouterClient(apiKey = "k", httpClient = http(body = sse))
            .complete(listOf("a:free"), "s", "u", onText = { seen += it }).getOrThrow()
        assertEquals("""{"t":[[1,"Oi"]]}""", reply.content)
        assertEquals("m1", reply.model)
        assertEquals(2, seen.size)
        assertTrue(sentJson().getBoolean("stream"))
    }
}

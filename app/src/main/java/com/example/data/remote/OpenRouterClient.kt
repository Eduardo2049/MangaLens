package com.example.data.remote

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thrown on HTTP 429; [retryAfterMillis] is when it is worth trying again. */
class RateLimitedException(val retryAfterMillis: Long, message: String) : Exception(message)

/** Model answer plus the model that actually produced it (OpenRouter may fall back to another one). */
data class ChatReply(val content: String, val model: String?)

/** Request shape differences between OpenAI-compatible providers. */
enum class ApiDialect {
    /** OpenRouter: `models` fallback list and `reasoning` switch. */
    OPENROUTER,
    /** Plain OpenAI Chat Completions (also Groq, Together, local servers...): single `model`. */
    OPENAI
}

/**
 * OpenAI-compatible chat client. Talks to OpenRouter directly when an API key is available (typed in the
 * app settings or local.properties), otherwise to the MangaLens backend proxy which injects the key.
 * Uses OpenRouter's `models` fallback list so a busy free model falls through to the next one.
 */
class OpenRouterClient(
    /** Fixed endpoint; null = OpenRouter when a key is available, otherwise the backend proxy. */
    private val endpoint: String? = null,
    private val apiKey: String = BuildConfig.OPENROUTER_API_KEY.trim(),
    private val httpClient: OkHttpClient = sharedHttpClient,
    private val dialect: ApiDialect = ApiDialect.OPENROUTER,
    /** Backend proxy endpoint; blank = no backend (the user must type an OpenRouter key). */
    private val backendUrl: String = backendEndpoint(),
    /** Sent as [APP_TOKEN_HEADER] to the backend, which may require it (MANGALENS_APP_TOKEN). */
    private val backendToken: String = BuildConfig.TRANSLATION_API_TOKEN.trim()
) {

    val isConfigured: Boolean get() = isConfigured(null)

    fun isConfigured(keyOverride: String?): Boolean = endpointFor(effectiveKey(keyOverride)).isNotBlank()

    private fun effectiveKey(keyOverride: String?) = keyOverride?.trim()?.takeIf { it.isNotEmpty() } ?: apiKey

    private fun endpointFor(key: String): String = endpoint ?: if (key.isNotEmpty()) OPENROUTER_URL else backendUrl

    suspend fun complete(
        models: List<String>,
        systemPrompt: String,
        userText: String,
        image: Bitmap? = null,
        maxTokens: Int = 2048,
        /** Key typed by the user in the app; overrides the build-time key. */
        apiKeyOverride: String? = null,
        /** When set, the answer is streamed and this receives the text accumulated so far (IO thread). */
        onText: ((String) -> Unit)? = null
    ): Result<ChatReply> = withContext(Dispatchers.IO) {
        try {
            val key = effectiveKey(apiKeyOverride)
            val url = endpointFor(key)
            if (url.isBlank()) throw IllegalStateException(MISSING_KEY_MESSAGE)
            val userContent: Any = if (image == null) {
                userText
            } else {
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", userText))
                    .put(
                        JSONObject().put("type", "image_url").put(
                            "image_url",
                            JSONObject().put("url", "data:image/jpeg;base64," + encodeJpeg(image))
                        )
                    )
            }
            val body = JSONObject()
                .put(
                    "messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", userContent))
                )
                .put("temperature", 0.3)
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("stream", onText != null)
            when (dialect) {
                ApiDialect.OPENROUTER -> body
                    .put("models", JSONArray(models.take(MAX_FALLBACK_MODELS)))
                    .put("max_tokens", maxTokens)
                    // Thinking adds latency and burns the free quota; translation does not need it.
                    .put("reasoning", JSONObject().put("enabled", false))
                ApiDialect.OPENAI -> body
                    .put("model", models.first())
                    .put("max_completion_tokens", maxTokens)
            }

            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("X-Title", "MangaLens")
                .apply {
                    if (key.isNotEmpty()) header("Authorization", "Bearer $key")
                    else if (backendToken.isNotEmpty()) header(APP_TOKEN_HEADER, backendToken)
                }
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            // The 60 s call limit would cut a long page that a slow free model is still streaming; for streams the
            // whole call may take longer, while readTimeout (45 s without a single byte) still catches a stall.
            val client = if (onText != null) {
                httpClient.newBuilder().callTimeout(STREAM_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
            } else {
                httpClient
            }
            val content = client.newCall(request).await().use { response ->
                if (response.code == 429) {
                    throw RateLimitedException(retryAfterMillis(response), "Limite de requisições da OpenRouter atingido")
                }
                if (onText != null && response.isSuccessful) return@use readStream(response, onText)
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                val error = json?.optJSONObject("error")
                if (!response.isSuccessful || error != null) {
                    val message = error?.optString("message").takeUnless { it.isNullOrBlank() } ?: "HTTP ${response.code}"
                    if (error?.optInt("code") == 429) {
                        throw RateLimitedException(System.currentTimeMillis() + 30_000, message)
                    }
                    throw IOException("OpenRouter: $message")
                }
                val choice = json?.optJSONArray("choices")?.optJSONObject(0)
                // `content` is JSON null when the model only "thought"; optString would turn that into "null".
                val content = choice?.optJSONObject("message")?.opt("content") as? String ?: ""
                val model = json?.optString("model")?.takeIf { it.isNotEmpty() }
                Log.d(TAG, "model=$model finish=${choice?.optString("finish_reason")} " +
                    "tokens=${json?.optJSONObject("usage")?.optInt("total_tokens")}")
                if (content.isBlank()) throw IOException("OpenRouter devolveu resposta vazia (modelo $model)")
                ChatReply(content, model)
            }
            Result.success(content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reads an OpenAI-style server-sent-event stream. Lines starting with ":" are OpenRouter keep-alive
     * comments; an `error` object can arrive mid-stream when the provider fails after starting.
     */
    private suspend fun readStream(response: Response, onText: (String) -> Unit): ChatReply {
        val source = response.body?.source() ?: throw IOException("OpenRouter devolveu resposta vazia")
        val text = StringBuilder()
        var model: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue
            chunk.optJSONObject("error")?.let { throw IOException("OpenRouter: ${it.optString("message")}") }
            if (model == null) model = chunk.optString("model").takeIf { it.isNotEmpty() }
            // `content` is JSON null on the final chunk; optString would turn that into "null".
            val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.opt("content") as? String
            if (!delta.isNullOrEmpty()) {
                text.append(delta)
                onText(text.toString())
            }
        }
        Log.d(TAG, "model=$model stream chars=${text.length}")
        if (text.isBlank()) throw IOException("OpenRouter devolveu resposta vazia (modelo $model)")
        return ChatReply(text.toString(), model)
    }

    private fun retryAfterMillis(response: Response): Long {
        val now = System.currentTimeMillis()
        response.header("Retry-After")?.trim()?.toLongOrNull()?.let { return now + it * 1000 }
        // X-RateLimit-Reset is an epoch timestamp in milliseconds.
        response.header("X-RateLimit-Reset")?.trim()?.toLongOrNull()?.let { reset ->
            val millis = if (reset < 10_000_000_000L) reset * 1000 else reset
            if (millis > now) return millis.coerceAtMost(now + 6 * 60 * 60 * 1000L)
        }
        return now + 60_000
    }

    private fun encodeJpeg(bitmap: Bitmap): String {
        // 1280px on the long side keeps small manga text legible while limiting image tokens.
        val maxSide = 1280
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "MangaLens"
        /** OpenRouter rejects a `models` list longer than this. */
        private const val MAX_FALLBACK_MODELS = 3
        const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
        const val APP_TOKEN_HEADER = "X-MangaLens-Token"
        private const val STREAM_CALL_TIMEOUT_SECONDS = 150L
        const val MISSING_KEY_MESSAGE = "Configure a chave da OpenRouter em Configurações → Chave da OpenRouter"

        val textModels: List<String> = parseModels(BuildConfig.OPENROUTER_TEXT_MODELS)
        val visionModels: List<String> = parseModels(BuildConfig.OPENROUTER_VISION_MODELS)

        private fun parseModels(csv: String) = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        private fun backendEndpoint(): String {
            val backend = BuildConfig.TRANSLATION_API_BASE_URL.trim().trimEnd('/')
            return if (backend.isBlank()) "" else "$backend/v1/chat/completions"
        }

        fun usesDirectKey(): Boolean = BuildConfig.OPENROUTER_API_KEY.isNotBlank()

        /** True when this build points at a MangaLens backend proxy (TRANSLATION_API_BASE_URL). */
        fun hasBackend(): Boolean = backendEndpoint().isNotBlank()

        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}

/** Cancellable bridge: cancelling the coroutine cancels the HTTP call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
}

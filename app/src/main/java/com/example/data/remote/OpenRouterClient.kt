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
    OPENAI,
    /** Gemini's OpenAI-compatible endpoint: single `model`, `max_tokens` and a low `reasoning_effort`. */
    GEMINI
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
    private val backendToken: String = BuildConfig.TRANSLATION_API_TOKEN.trim(),
    /** Shown in error messages ("OpenRouter: ...", "OpenAI: ...", "Google Gemini: ..."). */
    private val providerName: String = "OpenRouter"
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
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("stream", onText != null)
            when (dialect) {
                ApiDialect.OPENROUTER -> body
                    .put("temperature", 0.3)
                    .put("models", JSONArray(models.take(MAX_FALLBACK_MODELS)))
                    .put("max_tokens", maxTokens)
                    // Thinking adds latency and burns the free quota; translation does not need it.
                    .put("reasoning", JSONObject().put("enabled", false))
                // No `temperature` here: OpenAI's reasoning models (GPT-5 family) reject anything but the default.
                ApiDialect.OPENAI -> body
                    .put("model", models.first())
                    .put("max_completion_tokens", maxTokens)
                ApiDialect.GEMINI -> body
                    .put("temperature", 0.3)
                    .put("model", models.first())
                    // Gemini counts thinking tokens inside max_tokens; keep thinking short so the answer fits.
                    .put("max_tokens", maxTokens)
                    .put("reasoning_effort", "low")
            }

            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .apply {
                    if (dialect == ApiDialect.OPENROUTER) header("X-Title", "MangaLens")
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
                    // OpenAI answers 429 both for "too fast" and for "no credits left"; only the first is worth waiting for.
                    val error = runCatching { JSONObject(response.body?.string().orEmpty()).optJSONObject("error") }.getOrNull()
                    if (error?.optString("code") == "insufficient_quota" || error?.optString("type") == "insufficient_quota") {
                        throw IOException("$providerName: a conta está sem créditos. Adicione saldo no painel do provedor.")
                    }
                    throw RateLimitedException(retryAfterMillis(response), "Limite de requisições da $providerName atingido")
                }
                if (response.code == 401 || response.code == 403) {
                    throw IOException("$providerName: chave recusada (HTTP ${response.code}). Confira a chave em Configurações.")
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
                    throw IOException("$providerName: $message")
                }
                val choice = json?.optJSONArray("choices")?.optJSONObject(0)
                // `content` is JSON null when the model only "thought"; optString would turn that into "null".
                val content = choice?.optJSONObject("message")?.opt("content") as? String ?: ""
                val model = json?.optString("model")?.takeIf { it.isNotEmpty() }
                Log.d(TAG, "model=$model finish=${choice?.optString("finish_reason")} " +
                    "tokens=${json?.optJSONObject("usage")?.optInt("total_tokens")}")
                if (content.isBlank()) throw IOException("$providerName devolveu resposta vazia (modelo $model)")
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
        val source = response.body?.source() ?: throw IOException("$providerName devolveu resposta vazia")
        val text = StringBuilder()
        var model: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue
            chunk.optJSONObject("error")?.let { throw IOException("$providerName: ${it.optString("message")}") }
            if (model == null) model = chunk.optString("model").takeIf { it.isNotEmpty() }
            // `content` is JSON null on the final chunk; optString would turn that into "null".
            val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.opt("content") as? String
            if (!delta.isNullOrEmpty()) {
                text.append(delta)
                onText(text.toString())
            }
        }
        Log.d(TAG, "model=$model stream chars=${text.length}")
        if (text.isBlank()) throw IOException("$providerName devolveu resposta vazia (modelo $model)")
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
        const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        const val APP_TOKEN_HEADER = "X-MangaLens-Token"
        private const val STREAM_CALL_TIMEOUT_SECONDS = 150L
        const val MISSING_KEY_MESSAGE = "Configure a chave da OpenRouter em Configurações → Chave da OpenRouter"

        val textModels: List<String> = parseModels(BuildConfig.OPENROUTER_TEXT_MODELS)
        val visionModels: List<String> = parseModels(BuildConfig.OPENROUTER_VISION_MODELS)

        private fun parseModels(csv: String) = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        /**
         * Free models typed in Settings (comma separated, in order of preference) or, when blank, the list
         * this build came with. Free models come and go on OpenRouter; this avoids a new APK for each change.
         */
        fun textModelsFor(custom: String): List<String> = parseModels(custom).ifEmpty { textModels }

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

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

/**
 * OpenAI-compatible chat client for OpenRouter. Talks to OpenRouter directly when an API key is
 * configured (local.properties), otherwise to the MangaLens backend proxy which injects the key.
 * Uses OpenRouter's `models` fallback list so a busy free model falls through to the next one.
 */
class OpenRouterClient(
    private val endpoint: String = defaultEndpoint(),
    private val apiKey: String = BuildConfig.OPENROUTER_API_KEY.trim(),
    private val httpClient: OkHttpClient = sharedHttpClient
) {

    val isConfigured: Boolean get() = endpoint.isNotBlank()

    suspend fun complete(
        models: List<String>,
        systemPrompt: String,
        userText: String,
        image: Bitmap? = null,
        maxTokens: Int = 2048,
        /** When set, the answer is streamed and this receives the text accumulated so far (IO thread). */
        onText: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
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
                .put("models", JSONArray(models.take(MAX_FALLBACK_MODELS)))
                .put(
                    "messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", userContent))
                )
                .put("temperature", 0.3)
                .put("max_tokens", maxTokens)
                .put("response_format", JSONObject().put("type", "json_object"))
                // Thinking adds latency and burns the free quota; translation does not need it.
                .put("reasoning", JSONObject().put("enabled", false))
                .put("stream", onText != null)

            val request = Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .header("X-Title", "MangaLens")
                .apply { if (apiKey.isNotEmpty()) header("Authorization", "Bearer $apiKey") }
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val content = httpClient.newCall(request).await().use { response ->
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
                val content = choice?.optJSONObject("message")?.optString("content").orEmpty()
                Log.d(TAG, "model=${json?.optString("model")} finish=${choice?.optString("finish_reason")} " +
                    "tokens=${json?.optJSONObject("usage")?.optInt("total_tokens")}")
                if (content.isBlank()) throw IOException("OpenRouter devolveu resposta vazia")
                content
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
    private suspend fun readStream(response: Response, onText: (String) -> Unit): String {
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
        if (text.isBlank()) throw IOException("OpenRouter devolveu resposta vazia")
        return text.toString()
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
        private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"

        val textModels: List<String> = parseModels(BuildConfig.OPENROUTER_TEXT_MODELS)
        val visionModels: List<String> = parseModels(BuildConfig.OPENROUTER_VISION_MODELS)

        private fun parseModels(csv: String) = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        fun defaultEndpoint(): String {
            if (BuildConfig.OPENROUTER_API_KEY.isNotBlank()) return OPENROUTER_URL
            val backend = BuildConfig.TRANSLATION_API_BASE_URL.trim().trimEnd('/')
            return if (backend.isBlank()) "" else "$backend/v1/chat/completions"
        }

        fun usesDirectKey(): Boolean = BuildConfig.OPENROUTER_API_KEY.isNotBlank()

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

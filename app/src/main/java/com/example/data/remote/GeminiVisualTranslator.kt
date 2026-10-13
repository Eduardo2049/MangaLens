package com.example.data.remote

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.model.BoundingBox
import com.example.data.model.SupportedLanguage
import com.example.data.model.TextOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Visual translation client backed by the MangaLens translation API.
 * The Gemini API key remains on the backend and is never packaged in the APK.
 */
class GeminiVisualTranslator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun translateMangaImage(
        bitmap: Bitmap,
        sourceLang: SupportedLanguage,
        targetLang: SupportedLanguage
    ): Result<List<TextOverlay>> = withContext(Dispatchers.IO) {
        val apiBaseUrl = BuildConfig.TRANSLATION_API_BASE_URL.trim().trimEnd('/')
        if (apiBaseUrl.isBlank() || apiBaseUrl == "MY_TRANSLATION_API_BASE_URL") {
            Log.e("GeminiTranslator", "Translation backend URL is not configured.")
            return@withContext Result.failure(
                IllegalStateException("Translation backend URL is not configured.")
            )
        }

        try {
            Log.d("MangaLens", "API: Iniciando tradução...")
            val requestJson = JSONObject().apply {
                put("imageBase64", bitmapToBase64(bitmap))
                put("sourceLanguage", sourceLang.code)
                put("targetLanguage", targetLang.code)
            }
            val request = Request.Builder()
                .url("$apiBaseUrl/v1/translate")
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val responseBodyString = response.use { it.body?.string() ?: "" }

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Translation backend returned HTTP ${response.code}: ${extractApiError(responseBodyString)}")
                )
            }

            val textOverlays = parseOverlaysFromJson(responseBodyString)
            if (textOverlays.isEmpty()) {
                return@withContext Result.failure(Exception("No text regions found in the image."))
            }
            Result.success(textOverlays)
        } catch (e: Exception) {
            Log.e("GeminiTranslator", "Translation request failed", e)
            Result.failure(Exception("Translation failed: ${e.message}", e))
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        // Resize bitmap to keep network payload fast and reduce Gemini API quota consumption.
        // 1024px max + 75% JPEG quality is sufficient for OCR without sacrificing accuracy.
        val maxDimension = 1024
        val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
            val (w, h) = if (bitmap.width >= bitmap.height) {
                maxDimension to (maxDimension / aspect).toInt()
            } else {
                (maxDimension * aspect).toInt() to maxDimension
            }
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        val byteArray = outputStream.toByteArray()
        if (scaledBitmap != bitmap) scaledBitmap.recycle()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun buildPrompt(sourceLang: SupportedLanguage, targetLang: SupportedLanguage): String {
        val srcName = if (sourceLang == SupportedLanguage.AUTO) "Detect Automatically (Japanese/Korean/Chinese/English)" else sourceLang.displayName
        val tgtName = targetLang.displayName

        return """
            You are a high-precision OCR and Visual Translator for Manga, Manhwa, and Webtoons.
            Analyze the image frame carefully.
            Source Language: $srcName
            Target Language: $tgtName

            Task:
            1. Find all text regions inside speech bubbles, thought bubbles, narration boxes, sound effects, or vertical/horizontal text blocks.
            2. Extract the original text.
            3. Translate the extracted text accurately to $tgtName.
            4. Provide bounding box coordinates as integers from 0 to 100 (percentage of total image width and height):
               - yMin: top edge percentage (0..100)
               - xMin: left edge percentage (0..100)
               - yMax: bottom edge percentage (0..100)
               - xMax: right edge percentage (0..100)
            5. Indicate whether text is vertical (true for traditional Japanese manga) or horizontal (false for manhwa/webtoon/narration).

            Return a valid JSON array of objects with the exact schema:
            [
              {
                "originalText": "...",
                "translatedText": "...",
                "yMin": 12,
                "xMin": 45,
                "yMax": 25,
                "xMax": 88,
                "isVertical": true,
                "bubbleType": "SPEECH"
              }
            ]
        """.trimIndent()
    }

    internal fun parseOverlaysFromJson(jsonString: String): List<TextOverlay> {
        val list = mutableListOf<TextOverlay>()
        try {
            // Remove markdown formatting if present
            val cleanJson = extractJsonPayload(jsonString)

            val jsonArray = when {
                cleanJson.startsWith("[") -> JSONArray(cleanJson)
                cleanJson.startsWith("{") -> {
                    val obj = JSONObject(cleanJson)
                    // Resilience: look for the array under common keys
                    obj.optJSONArray("overlays") ?: obj.optJSONArray("items") ?: obj.optJSONArray("textRegions") ?: JSONArray()
                }
                else -> JSONArray()
            }

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val orig = item.optString("originalText", item.optString("text", "")).trim()
                val trans = item.optString("translatedText", item.optString("translation", "")).trim()

                if (orig.isNotEmpty() && trans.isNotEmpty()) {
                    // Gemini 2.0+ usually follows 0-100 percentage range for bounding boxes
                    val yMin = item.optDouble("yMin", 0.0).toFloat().coerceIn(0f, 100f)
                    val xMin = item.optDouble("xMin", 0.0).toFloat().coerceIn(0f, 100f)
                    val yMax = item.optDouble("yMax", yMin + 10.0).toFloat().coerceIn(yMin, 100f)
                    val xMax = item.optDouble("xMax", xMin + 15.0).toFloat().coerceIn(xMin, 100f)
                    val isVertical = item.optBoolean("isVertical", false)
                    val bubbleType = item.optString("bubbleType", "SPEECH")

                    list.add(
                        TextOverlay(
                            originalText = orig,
                            translatedText = trans,
                            box = BoundingBox(yMin, xMin, yMax, xMax),
                            isVertical = isVertical,
                            bubbleType = bubbleType
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("GeminiTranslator", "Error parsing overlay JSON: ${e.message}", e)
        }
        return list
    }

    private fun extractJsonPayload(rawText: String): String {
        val withoutFence = rawText.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        if (withoutFence.startsWith("[") || withoutFence.startsWith("{")) {
            return withoutFence
        }

        val arrayStart = withoutFence.indexOf('[')
        val arrayEnd = withoutFence.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return withoutFence.substring(arrayStart, arrayEnd + 1)
        }

        val objectStart = withoutFence.indexOf('{')
        val objectEnd = withoutFence.lastIndexOf('}')
        return if (objectStart >= 0 && objectEnd > objectStart) {
            withoutFence.substring(objectStart, objectEnd + 1)
        } else {
            withoutFence
        }
    }

    private fun extractApiError(responseBody: String): String {
        return runCatching {
            val response = JSONObject(responseBody)
            val message = response.optString("error", "Unknown backend error")
            val requestId = response.optString("requestId")
            if (requestId.isBlank()) message else "$message (requestId=$requestId)"
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Check your API key, model access, and quota."
    }
}

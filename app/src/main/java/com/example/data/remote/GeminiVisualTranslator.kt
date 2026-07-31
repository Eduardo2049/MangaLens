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
 * Visual translation client powered by the Gemini REST API.
 *
 * ⚠️  SECURITY NOTE — API key embedded in the client APK
 * ─────────────────────────────────────────────────────────────────────
 * `GEMINI_API_KEY` is baked into the APK at build time via [BuildConfig]. For personal use
 * this is fine, but any determined person can extract it by decompiling the APK with apktool.
 *
 * Recommended mitigations (pick at least option 1):
 *
 * 1. **Restrict the key in Google Cloud Console** (easy, do this now):
 *    console.cloud.google.com → APIs & Services → Credentials → your key →
 *    "Application restrictions" → Android apps → add your package name +
 *    the SHA-1 fingerprint of your signing certificate.
 *    The key will then only accept requests that originate from your signed APK.
 *    Get the fingerprint: `./gradlew signingReport`
 *
 * 2. **Server-side proxy** (complete solution, more effort):
 *    Move the key to a backend (e.g. Cloud Run / Firebase Functions).
 *    The app calls your backend which calls Gemini — the key never reaches the device.
 *
 * 3. **Firebase App Check** (already partially configured in this project):
 *    Combine with option 1 or 2 to prevent non-app clients from abusing your endpoint.
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
        val apiKey = BuildConfig.GEMINI_API_KEY
        val hasValidApiKey = apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY"

        if (!hasValidApiKey) {
            Log.e("GeminiTranslator", "API key is missing or is still the placeholder value.")
            return@withContext Result.failure(
                IllegalStateException("Gemini API key not configured. Set GEMINI_API_KEY in local.properties.")
            )
        }

        try {
            Log.d("MangaLens", "API: Iniciando tradução...")
            val base64Image = bitmapToBase64(bitmap)
            val promptText = buildPrompt(sourceLang, targetLang)

            val requestJson = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    val partsArray = JSONArray().apply {
                        put(JSONObject().put("text", promptText))
                        put(JSONObject().put("inlineData", JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", base64Image)
                        }))
                    }
                    put(JSONObject().put("parts", partsArray))
                }
                put("contents", contentsArray)

                val genConfig = JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.2)
                }
                put("generationConfig", genConfig)
            }

            val requestUrl = "https://generativelanguage.googleapis.com/v1/models/gemini-3.5-flash:generateContent?key=$apiKey"

            val body = requestJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(requestUrl)
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBodyString = response.body?.string() ?: ""
            Log.d("MangaLens", "API: Resposta recebida (Código: ${response.code})")

            if (!response.isSuccessful) {
                Log.e("MangaLens", "API: Erro HTTP ${response.code}: $responseBodyString")
                return@withContext Result.failure(
                    Exception("Gemini API returned HTTP ${response.code}. Check your API key and quota.")
                )
            }

            val jsonResponse = JSONObject(responseBodyString)
            val candidates = jsonResponse.optJSONArray("candidates")

            if (candidates == null || candidates.length() == 0) {
                Log.e("GeminiTranslator", "No candidates in response: $responseBodyString")
                return@withContext Result.failure(
                    Exception("Gemini returned no candidates. The image may have been blocked by safety filters.")
                )
            }

            val content = candidates.getJSONObject(0).optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val rawText = parts?.optJSONObject(0)?.optString("text") ?: ""

            if (rawText.isBlank()) {
                Log.e("GeminiTranslator", "Gemini returned empty text content.")
                return@withContext Result.failure(
                    Exception("Gemini returned an empty response. The model may not have found any text.")
                )
            }

            val textOverlays = parseOverlaysFromJson(rawText)

            if (textOverlays.isEmpty()) {
                Log.w("GeminiTranslator", "Parsed 0 overlays from response: $rawText")
                return@withContext Result.failure(
                    Exception("No text regions found in the image. Try on a page with visible speech bubbles.")
                )
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

    private fun parseOverlaysFromJson(jsonString: String): List<TextOverlay> {
        val list = mutableListOf<TextOverlay>()
        try {
            // Remove markdown formatting if present
            val cleanJson = jsonString.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

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
                    val yMax = item.optDouble("yMax", yMin + 10.0).toFloat().coerceIn(0f, 100f)
                    val xMax = item.optDouble("xMax", xMin + 15.0).toFloat().coerceIn(0f, 100f)
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
}

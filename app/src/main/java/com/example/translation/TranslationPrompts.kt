package com.example.translation

import com.example.data.model.SupportedLanguage
import org.json.JSONArray
import org.json.JSONObject

/** Prompts and tolerant response parsing for the LLM calls. */
object TranslationPrompts {

    private fun translatorRules(source: SupportedLanguage, target: SupportedLanguage) = """
        You are a professional manga, manhwa and webtoon translator (scanlation quality).
        Translate from ${source.promptName} into natural, fluent ${target.promptName}.
        Rules:
        - Write the way a native ${target.promptName} speaker would actually say it in a comic: idiomatic, never word-for-word.
        - Keep each character's tone (polite, rude, childish, sarcastic) and keep exclamations and emotion.
        - Keep it short enough to fit inside the original speech bubble.
        - Keep person names romanized and consistent with the context; keep honorifics like -san, -kun, -senpai, oppa, hyung.
        - Sound effects: give a short equivalent onomatopoeia in ${target.promptName}.
        - If a text is already in ${target.promptName}, return it unchanged.
    """.trimIndent()

    // ---------- Text mode (on-device OCR + LLM translation) ----------

    fun textSystemPrompt(source: SupportedLanguage, target: SupportedLanguage) = """
        ${translatorRules(source, target)}
        - The texts come from OCR and may have wrong characters, missing spaces or broken lines; infer what was meant.
        - The items are the speech bubbles of ONE page in reading order; read all of them before translating so the dialogue flows.
        - "context" holds earlier lines already translated; use it for names, gender and terminology, but do not translate it again.
        - If an item is not readable story text (watermark, website, credits, random OCR noise), return "" for it.
        Answer ONLY with JSON: {"translations":[{"id":<same id>,"text":"<translation>"}]} with one entry per item.
    """.trimIndent()

    fun textUserPrompt(items: List<Pair<Int, String>>, context: List<Pair<String, String>>): String {
        val json = JSONObject()
        json.put("context", JSONArray().apply {
            context.forEach { (src, tr) -> put(JSONObject().put("src", src).put("tr", tr)) }
        })
        json.put("items", JSONArray().apply {
            items.forEach { (id, text) -> put(JSONObject().put("id", id).put("text", text)) }
        })
        return json.toString()
    }

    /** Returns id -> translation. Missing ids are simply absent. */
    fun parseTextTranslations(raw: String): Map<Int, String> {
        val payload = extractJson(raw) ?: return emptyMap()
        val array = when (payload) {
            is JSONArray -> payload
            is JSONObject -> payload.optJSONArray("translations")
                ?: payload.optJSONArray("items")
                ?: payload.optJSONArray("t")
                ?: return emptyMap()
            else -> return emptyMap()
        }
        val result = mutableMapOf<Int, String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optInt("id", -1)
            if (id < 0) continue
            val text = item.optString("text", item.optString("translation", item.optString("tr", ""))).trim()
            result[id] = text
        }
        return result
    }

    // ---------- Vision mode (LLM reads the screenshot) ----------

    fun visionSystemPrompt(source: SupportedLanguage, target: SupportedLanguage) = """
        ${translatorRules(source, target)}
        You receive a screenshot of a comic reader. Find every piece of story text: speech bubbles, thought bubbles,
        narration boxes and important sound effects. Ignore the reader app's interface, page numbers and watermarks.
        Treat each bubble as one item (join its lines). For vertical Japanese text read columns right-to-left.
        Give a tight bounding box around the text of each item as [ymin, xmin, ymax, xmax], integers normalized to 0-1000
        of the image height/width.
        Answer ONLY with JSON:
        {"items":[{"text":"<original>","translation":"<translation>","box":[ymin,xmin,ymax,xmax],"vertical":false,"type":"SPEECH|THOUGHT|NARRATION|SOUND_EFFECT"}]}
        If there is no text, answer {"items":[]}.
    """.trimIndent()

    /**
     * @param imageWidth/imageHeight size of the image that was sent (to interpret absolute pixel boxes).
     * @param screenWidth/screenHeight capture size the boxes are mapped to.
     */
    fun parseVisionItems(
        raw: String,
        imageWidth: Int,
        imageHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ): List<ScreenTranslation> {
        val payload = extractJson(raw) ?: return emptyList()
        val array = when (payload) {
            is JSONArray -> payload
            is JSONObject -> payload.optJSONArray("items") ?: payload.optJSONArray("overlays") ?: return emptyList()
            else -> return emptyList()
        }
        data class RawItem(val original: String, val translated: String, val coords: DoubleArray, val vertical: Boolean, val type: String)

        val items = (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val original = item.optString("text", item.optString("originalText", "")).trim()
            val translated = item.optString("translation", item.optString("translatedText", "")).trim()
            if (translated.isEmpty()) return@mapNotNull null
            val box = item.optJSONArray("box") ?: item.optJSONArray("box_2d")
            val coords = if (box != null && box.length() == 4) {
                DoubleArray(4) { box.optDouble(it, 0.0) }
            } else if (item.has("yMin")) {
                doubleArrayOf(item.optDouble("yMin"), item.optDouble("xMin"), item.optDouble("yMax"), item.optDouble("xMax"))
            } else {
                return@mapNotNull null
            }
            if (coords.any { it.isNaN() }) return@mapNotNull null
            RawItem(original, translated, coords, item.optBoolean("vertical", item.optBoolean("isVertical", false)),
                item.optString("type", item.optString("bubbleType", "SPEECH")).uppercase())
        }
        if (items.isEmpty()) return emptyList()

        // Models disagree on units: detect fractions, legacy yMin/xMin percentages, 0-1000, or absolute pixels.
        val maxCoord = items.maxOf { it.coords.max() }
        val usesPercent = array.optJSONObject(0)?.has("yMin") == true && maxCoord <= 100.0
        return items.map { item ->
            val (y0, x0, y1, x1) = item.coords.toList()
            fun mapX(v: Double): Int = when {
                maxCoord <= 1.0 -> v * screenWidth
                usesPercent -> v / 100.0 * screenWidth
                maxCoord <= 1000.0 -> v / 1000.0 * screenWidth
                else -> v / imageWidth * screenWidth
            }.toInt()
            fun mapY(v: Double): Int = when {
                maxCoord <= 1.0 -> v * screenHeight
                usesPercent -> v / 100.0 * screenHeight
                maxCoord <= 1000.0 -> v / 1000.0 * screenHeight
                else -> v / imageHeight * screenHeight
            }.toInt()
            val box = Box(
                minOf(mapX(x0), mapX(x1)), minOf(mapY(y0), mapY(y1)),
                maxOf(mapX(x0), mapX(x1)), maxOf(mapY(y0), mapY(y1))
            ).clampTo(screenWidth, screenHeight)
            ScreenTranslation(item.original, item.translated, box, item.vertical, item.type)
        }.filter { it.box.width > 4 && it.box.height > 4 }
    }

    /**
     * Snaps the model's approximate boxes to the precise boxes found by the local OCR when they overlap.
     * Vision LLMs read text well but place boxes loosely; ML Kit places boxes exactly.
     */
    fun refineWithOcr(items: List<ScreenTranslation>, ocrBlocks: List<OcrBlock>): List<ScreenTranslation> {
        if (ocrBlocks.isEmpty()) return items
        return items.map { item ->
            val search = item.box.inflate(item.box.width / 6, item.box.height / 6)
            val matches = ocrBlocks.filter { search.contains(it.box.centerX, it.box.centerY) }
            if (matches.isEmpty()) item else item.copy(box = matches.map { it.box }.reduce(Box::union))
        }
    }

    // ---------- JSON helpers ----------

    /** Finds the JSON payload even when wrapped in markdown fences or surrounded by prose/reasoning. */
    fun extractJson(raw: String): Any? {
        val text = raw.trim()
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
        val candidates = listOf(text) + listOfNotNull(
            sliceBetween(text, '{', '}'),
            sliceBetween(text, '[', ']')
        )
        for (candidate in candidates) {
            val parsed = runCatching {
                when {
                    candidate.startsWith("{") -> JSONObject(candidate)
                    candidate.startsWith("[") -> JSONArray(candidate)
                    else -> null
                }
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun sliceBetween(text: String, open: Char, close: Char): String? {
        val start = text.indexOf(open)
        val end = text.lastIndexOf(close)
        return if (start >= 0 && end > start) text.substring(start, end + 1) else null
    }
}

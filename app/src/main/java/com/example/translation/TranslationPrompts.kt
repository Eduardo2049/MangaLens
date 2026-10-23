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

    fun textSystemPrompt(source: SupportedLanguage, target: SupportedLanguage, withImage: Boolean = false) = """
        ${translatorRules(source, target)}
        - The texts come from OCR and may have wrong characters, missing spaces or broken lines; infer what was meant.
        - The items are the speech bubbles of ONE page in reading order; read all of them before translating so the dialogue flows.
        - "context" holds earlier lines already translated; use it for names, gender and terminology, but do not translate it again.
        - If an item is not readable story text (watermark, website, credits, random OCR noise), return "" for it.
        ${if (withImage) "- The page image is attached: use it to correct OCR mistakes, to read vertical text and to understand who is speaking." else ""}
        Answer ONLY with compact JSON, one entry per item in the same order: {"t":[[<id>,"<translation>"],...]}
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
            // Compact form: [id, "translation"].
            array.optJSONArray(i)?.let { pair ->
                val id = pair.optInt(0, -1)
                if (id >= 0) result[id] = pair.optString(1, "").trim()
            }
            val item = array.optJSONObject(i) ?: continue
            val id = item.optInt("id", -1)
            if (id < 0) continue
            val text = item.optString("text", item.optString("translation", item.optString("tr", ""))).trim()
            result[id] = text
        }
        return result
    }

    private val compactEntry = Regex("""\[\s*(\d+)\s*,\s*("(?:[^"\\]|\\.)*")\s*]""")
    private val objectEntry = Regex("""\{\s*"id"\s*:\s*(\d+)\s*,\s*"text"\s*:\s*("(?:[^"\\]|\\.)*")\s*}""")

    /**
     * Entries that are already complete in a still-streaming answer, so bubbles can be shown one by one
     * instead of waiting for the whole page.
     */
    fun parsePartialTranslations(partial: String): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        for (regex in listOf(compactEntry, objectEntry)) {
            for (match in regex.findAll(partial)) {
                val id = match.groupValues[1].toIntOrNull() ?: continue
                val text = runCatching { JSONArray("[" + match.groupValues[2] + "]").getString(0) }.getOrNull() ?: continue
                result[id] = text.trim()
            }
        }
        return result
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

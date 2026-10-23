package com.example.translation

/**
 * Remembers translations per original sentence. On webtoons the same bubble stays on screen while you
 * scroll, so after the first request it is re-positioned from the local OCR without spending tokens.
 */
class TextTranslationCache(private val maxEntries: Int = 600) {

    private class Entry(val original: String, val translation: String)

    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(languagePair: String, original: String): String? {
        val key = normalize(original)
        if (key.isEmpty()) return null
        entries["$languagePair|$key"]?.let { return it.translation }

        // OCR of the same bubble can differ by a character between frames; accept near-identical text.
        if (key.length < 6) return null
        val prefix = "$languagePair|"
        var best: String? = null
        var bestScore = 0f
        for ((cachedKey, entry) in entries) {
            if (!cachedKey.startsWith(prefix)) continue
            val cachedText = cachedKey.substring(prefix.length)
            if (abs(cachedText.length - key.length) > key.length / 8 + 1) continue
            val score = similarity(cachedText, key)
            if (score > bestScore) {
                bestScore = score
                best = entry.translation
            }
        }
        return if (bestScore >= FUZZY_MATCH_THRESHOLD) best else null
    }

    @Synchronized
    fun put(languagePair: String, original: String, translation: String) {
        val key = normalize(original)
        if (key.isNotEmpty()) entries["$languagePair|$key"] = Entry(original.trim(), translation)
    }

    /** Most recent pairs, oldest first; sent as context so names and tone stay consistent. */
    @Synchronized
    fun recent(languagePair: String, limit: Int): List<Pair<String, String>> {
        val prefix = "$languagePair|"
        return entries.entries.filter { it.key.startsWith(prefix) && it.value.translation.isNotEmpty() }
            .takeLast(limit)
            .map { it.value.original to it.value.translation }
    }

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        private const val FUZZY_MATCH_THRESHOLD = 0.88f

        fun normalize(text: String): String =
            text.lowercase().filter { it.isLetterOrDigit() || it in "!?…~♡♥" }

        private fun abs(v: Int) = if (v < 0) -v else v

        /** 1 - normalized Levenshtein distance. */
        fun similarity(a: String, b: String): Float {
            if (a == b) return 1f
            if (a.isEmpty() || b.isEmpty()) return 0f
            var prev = IntArray(b.length + 1) { it }
            var curr = IntArray(b.length + 1)
            for (i in 1..a.length) {
                curr[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                }
                val tmp = prev; prev = curr; curr = tmp
            }
            return 1f - prev[b.length].toFloat() / maxOf(a.length, b.length)
        }
    }
}

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

        // OCR of the same bubble can differ by a character between frames; accept text that differs only
        // the way OCR noise does (a dropped/extra character, l↔1, o↔0...). A real letter change such as
        // "ele morreu" vs "ela morreu" is a different sentence and must NOT reuse the cached translation.
        if (key.length < 6) return null
        val prefix = "$languagePair|"
        val allowance = ocrAllowance(key.length)
        var best: String? = null
        var bestDistance = Float.MAX_VALUE
        for ((cachedKey, entry) in entries) {
            if (!cachedKey.startsWith(prefix)) continue
            val cachedText = cachedKey.substring(prefix.length)
            if (abs(cachedText.length - key.length) > allowance) continue
            val distance = ocrDistance(cachedText, key)
            if (distance < bestDistance) {
                bestDistance = distance
                best = entry.translation
            }
        }
        return if (bestDistance <= allowance) best else null
    }

    /**
     * Told about every translation stored with [put] (not with [preload]), so they can be saved for the next
     * session. Called while the cache is locked: keep it quick (append to a list).
     */
    @Volatile
    var onPut: ((languagePair: String, original: String, translation: String) -> Unit)? = null

    @Synchronized
    fun put(languagePair: String, original: String, translation: String) {
        val key = normalize(original)
        if (key.isEmpty()) return
        val previous = entries["$languagePair|$key"]
        entries["$languagePair|$key"] = Entry(original.trim(), translation)
        // Streaming stores the same bubble again when the final answer arrives: report real changes only.
        if (previous?.translation != translation) onPut?.invoke(languagePair, original.trim(), translation)
    }

    /** Fills the cache with translations saved in an earlier session, oldest first, without reporting them. */
    @Synchronized
    fun preload(languagePair: String, items: List<Pair<String, String>>) {
        for ((original, translation) in items) {
            val key = normalize(original)
            if (key.isNotEmpty()) entries["$languagePair|$key"] = Entry(original.trim(), translation)
        }
    }

    /**
     * Drops the cached translation of [original] — the exact text and any text [get] would treat as the same
     * bubble — so the next page asks the model again. Returns the normalized keys that were removed.
     */
    @Synchronized
    fun forget(languagePair: String, original: String): List<String> {
        val key = normalize(original)
        if (key.isEmpty()) return emptyList()
        val prefix = "$languagePair|"
        val allowance = ocrAllowance(key.length)
        val removed = entries.keys.filter { cachedKey ->
            if (!cachedKey.startsWith(prefix)) return@filter false
            val cachedText = cachedKey.substring(prefix.length)
            cachedText == key || (key.length >= 6 && abs(cachedText.length - key.length) <= allowance &&
                ocrDistance(cachedText, key) <= allowance)
        }
        removed.forEach { entries.remove(it) }
        return removed.map { it.substring(prefix.length) }
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
        /** Characters ML Kit commonly swaps between frames; substituting inside a group is cheap. */
        private val CONFUSABLE_GROUPS = listOf("il1|!", "o0", "s5", "b8", "z2", "gq9", "uv", "ー一-")
        private const val CONFUSABLE_COST = 0.5f
        private const val INDEL_COST = 1f
        /** Larger than any allowance: a real letter change disqualifies the match by itself. */
        private const val OTHER_SUBSTITUTION_COST = 100f

        /**
         * How much OCR noise a text of [length] normalized characters may have and still be "the same bubble".
         * Below 40 characters a single dropped character (or a few confusable swaps) is tolerated, but not a
         * deletion+insertion pair, which is how a letter change could otherwise sneak in.
         */
        fun ocrAllowance(length: Int): Float = if (length < 40) 1.5f else length / 25f

        private fun confusable(a: Char, b: Char): Boolean = CONFUSABLE_GROUPS.any { a in it && b in it }

        /** Weighted edit distance where only OCR-like differences are cheap (see [ocrAllowance]). */
        fun ocrDistance(a: String, b: String): Float {
            if (a == b) return 0f
            var prev = FloatArray(b.length + 1) { it * INDEL_COST }
            var curr = FloatArray(b.length + 1)
            for (i in 1..a.length) {
                curr[0] = i * INDEL_COST
                for (j in 1..b.length) {
                    val x = a[i - 1]
                    val y = b[j - 1]
                    val cost = when {
                        x == y -> 0f
                        confusable(x, y) -> CONFUSABLE_COST
                        else -> OTHER_SUBSTITUTION_COST
                    }
                    curr[j] = minOf(curr[j - 1] + INDEL_COST, prev[j] + INDEL_COST, prev[j - 1] + cost)
                }
                val tmp = prev; prev = curr; curr = tmp
            }
            return prev[b.length]
        }

        fun normalize(text: String): String =
            text.lowercase().filter { it.isLetterOrDigit() || it in "!?…~♡♥" }

        private fun abs(v: Int) = if (v < 0) -v else v
    }
}

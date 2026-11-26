package com.example.translation

/**
 * Where translations survive between sessions (Room in the app). [TextTranslationCache] stays the fast path:
 * the store only fills it when a language pair is first used and receives what the model returned.
 * Re-reading a chapter or reopening the bubble then costs no request.
 */
interface TranslationStore {
    /** Up to [limit] saved translations (original to translation) for [languagePair], oldest first. */
    suspend fun load(languagePair: String, limit: Int): List<Pair<String, String>>

    /** Saves (or replaces) translations for [languagePair]. */
    suspend fun save(languagePair: String, items: List<Pair<String, String>>)

    /** Removes the given texts, as normalized by [TextTranslationCache.normalize]. */
    suspend fun delete(languagePair: String, normalizedKeys: List<String>)
}

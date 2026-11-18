package com.example.data.local

import com.example.data.model.SupportedLanguage

/** Writes translations to the history table (shared by the app screen and the floating translator). */
object TranslationHistory {
    /** Bubbles saved per page: enough to look a line up again without flooding the list. */
    const val MAX_PER_PAGE = 10

    suspend fun record(
        dao: TranslationHistoryDao,
        items: List<Pair<String, String>>,
        source: SupportedLanguage,
        target: SupportedLanguage,
        chapterTitle: String
    ) {
        val rows = items.filter { (original, translated) -> original.isNotBlank() && translated.isNotBlank() }
            .take(MAX_PER_PAGE)
        if (rows.isEmpty()) return
        rows.forEach { (original, translated) ->
            dao.insertTranslation(
                TranslationHistoryEntity(
                    originalText = original,
                    translatedText = translated,
                    // Codes ("ja", "pt"); rows written by older versions hold the display name instead.
                    sourceLang = source.code,
                    targetLang = target.code,
                    chapterTitle = chapterTitle
                )
            )
        }
        dao.trimTo(AppDatabase.HISTORY_LIMIT)
    }

    /** Short label ("JA", "PT", "AUTO") for a stored language, whether it was saved as a code or a name. */
    fun languageLabel(stored: String): String =
        SupportedLanguage.entries
            .firstOrNull { it.code.equals(stored, ignoreCase = true) || it.displayName == stored }
            ?.code?.uppercase()
            ?: stored
}

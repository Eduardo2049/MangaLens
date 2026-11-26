package com.example.data.local

import com.example.translation.TextTranslationCache
import com.example.translation.TranslationStore

/** [TranslationStore] backed by the `cached_translation` table, trimmed to [AppDatabase.CACHE_LIMIT] rows. */
class RoomTranslationStore(
    private val dao: CachedTranslationDao,
    private val limit: Int = AppDatabase.CACHE_LIMIT,
    private val clock: () -> Long = System::currentTimeMillis
) : TranslationStore {

    override suspend fun load(languagePair: String, limit: Int): List<Pair<String, String>> =
        dao.newest(languagePair, limit).asReversed().map { it.original to it.translation }

    override suspend fun save(languagePair: String, items: List<Pair<String, String>>) {
        val now = clock()
        // One millisecond apart, in order: the last item of a page is the newest and trimming stays exact.
        val rows = items.mapIndexedNotNull { index, (original, translation) ->
            val key = TextTranslationCache.normalize(original)
            if (key.isEmpty()) null else CachedTranslationEntity(languagePair, key, original.trim(), translation, now + index)
        }.associateBy { it.normalized }.values.toList()
        if (rows.isEmpty()) return
        dao.upsert(rows)
        dao.trimTo(limit)
    }

    override suspend fun delete(languagePair: String, normalizedKeys: List<String>) {
        if (normalizedKeys.isNotEmpty()) dao.delete(languagePair, normalizedKeys)
    }
}

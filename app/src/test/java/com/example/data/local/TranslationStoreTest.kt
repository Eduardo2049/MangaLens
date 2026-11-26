package com.example.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The saved translation cache (Room) and the upgrade of databases created by version 1 of the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranslationStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private var now = 1_000L

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private fun store(limit: Int = AppDatabase.CACHE_LIMIT) =
        RoomTranslationStore(db.cachedTranslationDao(), limit, clock = { now })

    @Test
    fun `saved translations come back oldest first, per language pair`() = runBlocking {
        val store = store()
        store.save("ja>pt", listOf("逃げろ!" to "Corra!", "ありがとう" to "Obrigado"))
        now += 100
        store.save("ja>pt", listOf("待って" to "Espere"))
        store.save("ko>pt", listOf("안녕" to "Oi"))

        assertEquals(
            listOf("逃げろ!" to "Corra!", "ありがとう" to "Obrigado", "待って" to "Espere"),
            store.load("ja>pt", 10)
        )
        assertEquals(listOf("안녕" to "Oi"), store.load("ko>pt", 10))
        // The limit keeps the newest ones.
        assertEquals(listOf("待って" to "Espere"), store.load("ja>pt", 1))
    }

    @Test
    fun `saving the same text again replaces it`() = runBlocking {
        val store = store()
        store.save("ja>pt", listOf("逃げろ!" to "Corra!"))
        now += 10
        store.save("ja>pt", listOf(" 逃げろ! " to "Fuja!"))
        assertEquals(listOf("逃げろ!" to "Fuja!"), store.load("ja>pt", 10))
        assertEquals(1, db.cachedTranslationDao().count())
    }

    @Test
    fun `the table is trimmed to the newest rows`() = runBlocking {
        val store = store(limit = 3)
        (1..5).forEach { i ->
            now += 10
            store.save("en>pt", listOf("line number $i" to "linha $i"))
        }
        assertEquals(3, db.cachedTranslationDao().count())
        assertEquals(listOf("line number 3", "line number 4", "line number 5"), store.load("en>pt", 10).map { it.first })
    }

    @Test
    fun `delete removes normalized keys of one pair`() = runBlocking {
        val store = store()
        store.save("ja>pt", listOf("逃げろ!" to "Corra!", "待って" to "Espere"))
        store.save("ja>en", listOf("逃げろ!" to "Run!"))
        store.delete("ja>pt", listOf(com.example.translation.TextTranslationCache.normalize("逃げろ!")))
        assertEquals(listOf("待って" to "Espere"), store.load("ja>pt", 10))
        assertEquals(listOf("逃げろ!" to "Run!"), store.load("ja>en", 10))
    }

    @Test
    fun `a version 1 database is upgraded and keeps its history`() = runBlocking {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        // Exactly what version 1 of the app created (history only).
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { old ->
            old.execSQL(
                "CREATE TABLE IF NOT EXISTS `translation_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`originalText` TEXT NOT NULL, `translatedText` TEXT NOT NULL, `sourceLang` TEXT NOT NULL, " +
                    "`targetLang` TEXT NOT NULL, `chapterTitle` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)"
            )
            old.insert("translation_history", null, ContentValues().apply {
                put("originalText", "逃げろ!")
                put("translatedText", "Corra!")
                put("sourceLang", "ja")
                put("targetLang", "pt")
                put("chapterTitle", "Leitor")
                put("timestamp", 42L)
            })
            old.version = 1
        }

        val upgraded = AppDatabase.builder(context, name).allowMainThreadQueries().build()
        try {
            val history = upgraded.translationHistoryDao().getRecentHistory().first()
            assertEquals(listOf("Corra!"), history.map { it.translatedText })
            RoomTranslationStore(upgraded.cachedTranslationDao()).save("ja>pt", listOf("待って" to "Espere"))
            assertTrue(upgraded.cachedTranslationDao().count() == 1)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }
}

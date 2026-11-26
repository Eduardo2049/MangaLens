package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "translation_history")
data class TranslationHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val originalText: String,
    val translatedText: String,
    val sourceLang: String,
    val targetLang: String,
    val chapterTitle: String = "Mangá",
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface TranslationHistoryDao {
    @Query("SELECT * FROM translation_history ORDER BY timestamp DESC LIMIT 10")
    fun getRecentHistory(): Flow<List<TranslationHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranslation(history: TranslationHistoryEntity)

    @Query("DELETE FROM translation_history")
    suspend fun clearHistory()

    @Query("DELETE FROM translation_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Keeps only the newest [keep] rows: the floating translator writes on every page. */
    @Query("DELETE FROM translation_history WHERE id NOT IN (SELECT id FROM translation_history ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}

/**
 * One translated text, kept between sessions so a chapter read again (or the bubble reopened) costs no
 * request. [normalized] is [com.example.translation.TextTranslationCache.normalize] of [original].
 */
@Entity(tableName = "cached_translation", primaryKeys = ["languagePair", "normalized"])
data class CachedTranslationEntity(
    val languagePair: String,
    val normalized: String,
    val original: String,
    val translation: String,
    val updatedAt: Long
)

@Dao
interface CachedTranslationDao {
    @Query("SELECT * FROM cached_translation WHERE languagePair = :languagePair ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun newest(languagePair: String, limit: Int): List<CachedTranslationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<CachedTranslationEntity>)

    @Query("DELETE FROM cached_translation WHERE languagePair = :languagePair AND normalized IN (:keys)")
    suspend fun delete(languagePair: String, keys: List<String>)

    /** Keeps (about) the newest [keep] rows; rows saved in the same millisecond at the edge may go too. */
    @Query(
        "DELETE FROM cached_translation WHERE updatedAt <= " +
            "(SELECT updatedAt FROM cached_translation ORDER BY updatedAt DESC LIMIT 1 OFFSET :keep)"
    )
    suspend fun trimTo(keep: Int)

    @Query("SELECT COUNT(*) FROM cached_translation")
    suspend fun count(): Int

    @Query("DELETE FROM cached_translation")
    suspend fun clear()
}

@Database(
    entities = [TranslationHistoryEntity::class, CachedTranslationEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun translationHistoryDao(): TranslationHistoryDao
    abstract fun cachedTranslationDao(): CachedTranslationDao

    companion object {
        /** Rows kept in the history table. */
        const val HISTORY_LIMIT = 200
        /** Rows kept in the translation cache table (a few chapters' worth of bubbles). */
        const val CACHE_LIMIT = 5000

        private const val NAME = "manga_lens_db"

        /** Version 2: adds the persistent translation cache. The history table is untouched. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cached_translation` (" +
                        "`languagePair` TEXT NOT NULL, `normalized` TEXT NOT NULL, `original` TEXT NOT NULL, " +
                        "`translation` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`languagePair`, `normalized`))"
                )
            }
        }

        @Volatile private var instance: AppDatabase? = null

        /** One instance per process: the app screen and the overlay service share it. */
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: builder(context, NAME).build().also { instance = it }
        }

        /** Same configuration as [get]; tests use it to open a database under another name. */
        internal fun builder(context: Context, name: String): RoomDatabase.Builder<AppDatabase> =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
    }
}

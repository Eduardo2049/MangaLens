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

@Database(entities = [TranslationHistoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun translationHistoryDao(): TranslationHistoryDao

    companion object {
        /** Rows kept in the history table. */
        const val HISTORY_LIMIT = 200

        @Volatile private var instance: AppDatabase? = null

        /** One instance per process: the app screen and the overlay service share it. */
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "manga_lens_db")
                .build()
                .also { instance = it }
        }
    }
}

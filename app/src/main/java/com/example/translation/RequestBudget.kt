package com.example.translation

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guards the OpenRouter free tier (20 requests/min, 50/day without credits): spaces requests out,
 * counts them per day and honours the cool-down returned by a 429.
 */
class RequestBudget(
    context: Context,
    private val dailyLimit: Int,
    private val minIntervalMillis: Long = 3_500L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("manga_lens_budget", Context.MODE_PRIVATE)
    private var lastRequestAt = 0L

    @Volatile
    var blockedUntil: Long = 0L
        private set

    val usedToday: Int
        @Synchronized get() = if (prefs.getString(KEY_DAY, null) == today()) prefs.getInt(KEY_COUNT, 0) else 0

    val limit: Int get() = dailyLimit

    /** Milliseconds to wait before the next request may be sent, or null when the daily limit is spent. */
    @Synchronized
    fun waitTimeMillis(): Long? {
        if (dailyLimit in 1..usedToday) return null
        val now = clock()
        return maxOf(0L, blockedUntil - now, lastRequestAt + minIntervalMillis - now)
    }

    @Synchronized
    fun recordRequest() {
        lastRequestAt = clock()
        val day = today()
        val count = if (prefs.getString(KEY_DAY, null) == day) prefs.getInt(KEY_COUNT, 0) else 0
        prefs.edit().putString(KEY_DAY, day).putInt(KEY_COUNT, count + 1).apply()
    }

    @Synchronized
    fun blockUntil(timestampMillis: Long) {
        blockedUntil = maxOf(blockedUntil, timestampMillis)
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(clock()))

    private companion object {
        const val KEY_DAY = "day"
        const val KEY_COUNT = "count"
    }
}

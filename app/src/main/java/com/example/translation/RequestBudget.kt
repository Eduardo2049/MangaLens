package com.example.translation

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Guards the OpenRouter free tier (20 requests/min, 50/day without credits): spaces requests out,
 * counts them per day and honours the cool-down returned by a 429.
 *
 * The day is the UTC day, because that is when OpenRouter resets the free quota (21:00 in Brasília);
 * counting by the local day made the app block when the quota was already renewed, and vice versa.
 */
class RequestBudget(
    context: Context,
    /** 0 = no app-side daily limit. Adjustable at runtime (Settings: "comprei créditos" = 1000/dia). */
    @Volatile var dailyLimit: Int,
    private val minIntervalMillis: Long = 3_500L,
    private val clock: () -> Long = System::currentTimeMillis
) : RequestGate {
    private val prefs: SharedPreferences = context.getSharedPreferences("manga_lens_budget", Context.MODE_PRIVATE)
    private var lastRequestAt = 0L

    @Volatile
    var blockedUntil: Long = 0L
        private set

    val usedToday: Int
        @Synchronized get() = if (prefs.getString(KEY_DAY, null) == today()) prefs.getInt(KEY_COUNT, 0) else 0

    override val limit: Int get() = dailyLimit

    /** Milliseconds to wait before the next request may be sent, or null when the daily limit is spent. */
    @Synchronized
    override fun waitTimeMillis(): Long? {
        if (dailyLimit in 1..usedToday) return null
        val now = clock()
        return maxOf(0L, blockedUntil - now, lastRequestAt + minIntervalMillis - now)
    }

    @Synchronized
    override fun recordRequest() {
        lastRequestAt = clock()
        val day = today()
        val count = if (prefs.getString(KEY_DAY, null) == day) prefs.getInt(KEY_COUNT, 0) else 0
        prefs.edit().putString(KEY_DAY, day).putInt(KEY_COUNT, count + 1).apply()
    }

    @Synchronized
    override fun blockUntil(timestampMillis: Long) {
        blockedUntil = maxOf(blockedUntil, timestampMillis)
    }

    private fun today(): String = dayOf(clock())

    companion object {
        private const val KEY_DAY = "day"
        private const val KEY_COUNT = "count"

        /** OpenRouter quota day ("yyyy-MM-dd" in UTC) for an epoch-millis timestamp. */
        fun dayOf(timestampMillis: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(timestampMillis))
    }
}

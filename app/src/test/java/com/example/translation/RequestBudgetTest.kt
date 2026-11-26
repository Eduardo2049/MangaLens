package com.example.translation

import android.content.Context
import com.example.data.model.TranslationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Spacing, 429 cool-down and the UTC daily count of [RequestBudget], with a controllable clock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RequestBudgetTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var now = 1_780_000_000_000L // a fixed instant, far from midnight UTC

    @Before
    fun clearSavedCounts() {
        context.getSharedPreferences("manga_lens_budget", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun budget(limit: Int = 50, interval: Long = 3_500L) =
        RequestBudget(context, dailyLimit = limit, minIntervalMillis = interval, clock = { now })

    @Test
    fun `requests are spaced by the minimum interval`() {
        val budget = budget()
        assertEquals(0L, budget.waitTimeMillis())
        budget.recordRequest()
        assertEquals(3_500L, budget.waitTimeMillis())
        now += 1_000
        assertEquals(2_500L, budget.waitTimeMillis())
        now += 2_500
        assertEquals(0L, budget.waitTimeMillis())
    }

    @Test
    fun `daily limit returns null until the next UTC day`() {
        val budget = budget(limit = 2, interval = 0)
        repeat(2) { budget.recordRequest() }
        assertNull(budget.waitTimeMillis())
        now += 24 * 60 * 60 * 1000L
        assertEquals(0, budget.usedToday)
        assertEquals(0L, budget.waitTimeMillis())
    }

    @Test
    fun `zero limit means no app-side daily cap`() {
        val budget = budget(limit = 0, interval = 0)
        repeat(100) { budget.recordRequest() }
        assertEquals(0L, budget.waitTimeMillis())
    }

    @Test
    fun `a 429 cool-down only holds back the provider that returned it`() {
        val budget = budget(interval = 0)
        budget.provider = TranslationProvider.OPENROUTER
        budget.blockUntil(now + 60_000)
        assertEquals(60_000L, budget.waitTimeMillis())

        budget.provider = TranslationProvider.OPENAI
        assertEquals(0L, budget.waitTimeMillis())

        budget.provider = TranslationProvider.OPENROUTER
        assertEquals(60_000L, budget.waitTimeMillis())
        // An earlier deadline never shortens a longer one.
        budget.blockUntil(now + 1_000)
        assertEquals(60_000L, budget.blockedUntil - now)
    }

    @Test
    fun `spacing is kept per provider`() {
        val budget = budget(interval = 3_500L)
        budget.provider = TranslationProvider.OPENROUTER
        budget.recordRequest()
        budget.provider = TranslationProvider.GEMINI
        assertEquals(0L, budget.waitTimeMillis())
    }

    @Test
    fun `the quota day is the UTC day`() {
        // 2026-11-26 23:30 in Brasília is already 2026-11-27 in UTC, when OpenRouter renews the quota.
        assertEquals("2026-11-27", RequestBudget.dayOf(1_795_746_600_000L))
    }
}

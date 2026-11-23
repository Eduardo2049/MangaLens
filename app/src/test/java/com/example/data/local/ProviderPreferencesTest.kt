package com.example.data.local

import android.content.Context
import com.example.data.model.TranslationProvider
import com.example.translation.RequestBudget
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Switching provider must keep every key, and each provider counts its own daily requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderPreferencesTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `each provider keeps its own key when the user switches`() {
        val repo = UserPreferencesRepository(context)
        repo.updateApiKey(TranslationProvider.OPENROUTER, " sk-or-1 ")
        repo.updateApiKey(TranslationProvider.OPENAI, "sk-openai")
        repo.updateApiKey(TranslationProvider.GEMINI, "AIza-gemini")

        repo.updateProvider(TranslationProvider.GEMINI)
        repo.updateProvider(TranslationProvider.OPENAI)

        val reloaded = UserPreferencesRepository(context).preferences.value
        assertEquals(TranslationProvider.OPENAI, reloaded.provider)
        assertEquals("sk-or-1", reloaded.keyFor(TranslationProvider.OPENROUTER))
        assertEquals("sk-openai", reloaded.keyFor())
        assertEquals("AIza-gemini", reloaded.keyFor(TranslationProvider.GEMINI))
    }

    @Test
    fun `blank model falls back to the provider default`() {
        val repo = UserPreferencesRepository(context)
        assertEquals(TranslationProvider.GEMINI.defaultModel, repo.preferences.value.modelFor(TranslationProvider.GEMINI))
        repo.updateModel(TranslationProvider.GEMINI, " gemini-custom ")
        assertEquals("gemini-custom", repo.preferences.value.modelFor(TranslationProvider.GEMINI))
        repo.updateModel(TranslationProvider.GEMINI, "")
        assertEquals(TranslationProvider.GEMINI.defaultModel, repo.preferences.value.modelFor(TranslationProvider.GEMINI))
    }

    @Test
    fun `request counts do not mix between providers`() {
        val budget = RequestBudget(context, dailyLimit = 50, minIntervalMillis = 0)
        budget.provider = TranslationProvider.OPENROUTER
        val openRouterBefore = budget.usedToday
        budget.provider = TranslationProvider.OPENAI
        val openAiBefore = budget.usedToday
        repeat(3) { budget.recordRequest() }
        assertEquals(openAiBefore + 3, budget.usedToday)
        budget.provider = TranslationProvider.OPENROUTER
        assertEquals(openRouterBefore, budget.usedToday)
    }
}

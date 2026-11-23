package com.example.data.model

import com.example.data.remote.ApiDialect
import com.example.data.remote.OpenRouterClient

/**
 * Where translations come from. Each provider has its own key (kept only on the device), so the user can
 * switch between them in Settings whenever they want without typing the key again.
 */
enum class TranslationProvider(
    val id: String,
    val title: String,
    val description: String,
    val endpoint: String,
    val dialect: ApiDialect,
    /** Single model used by the provider; blank for OpenRouter, which uses its own fallback list of free models. */
    val defaultModel: String,
    val keyPlaceholder: String,
    val keyHelp: String,
    /** True when the app enforces a per-day request limit (OpenRouter's free tier). */
    val hasFreeTierLimit: Boolean
) {
    OPENROUTER(
        id = "openrouter",
        title = "OpenRouter",
        description = "Modelos gratuitos, com troca automática de modelo",
        endpoint = OpenRouterClient.OPENROUTER_URL,
        dialect = ApiDialect.OPENROUTER,
        defaultModel = "",
        keyPlaceholder = "sk-or-v1-…",
        keyHelp = "Grátis em openrouter.ai/keys",
        hasFreeTierLimit = true
    ),
    OPENAI(
        id = "openai",
        title = "OpenAI",
        description = "Chave da API da OpenAI, cobrada por uso",
        endpoint = OpenRouterClient.OPENAI_URL,
        dialect = ApiDialect.OPENAI,
        defaultModel = "gpt-5.4-mini",
        keyPlaceholder = "sk-…",
        keyHelp = "Crie em platform.openai.com/api-keys",
        hasFreeTierLimit = false
    ),
    GEMINI(
        id = "gemini",
        title = "Google Gemini",
        description = "Chave do Google AI Studio, com faixa gratuita limitada",
        endpoint = OpenRouterClient.GEMINI_URL,
        dialect = ApiDialect.GEMINI,
        defaultModel = "gemini-3.6-flash",
        keyPlaceholder = "AIza…",
        keyHelp = "Crie em aistudio.google.com/apikey",
        hasFreeTierLimit = false
    );

    /** True when the user picks the model name (every provider except OpenRouter). */
    val usesChosenModel: Boolean get() = this != OPENROUTER

    val missingKeyMessage: String
        get() = if (this == OPENROUTER) {
            OpenRouterClient.MISSING_KEY_MESSAGE
        } else {
            "Configure a chave da $title em Configurações → Provedor de tradução"
        }

    companion object {
        fun fromId(id: String?): TranslationProvider = entries.firstOrNull { it.id == id } ?: OPENROUTER
    }
}

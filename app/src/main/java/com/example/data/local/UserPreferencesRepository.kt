package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig
import com.example.data.model.SupportedLanguage
import com.example.data.model.TranslationEngine
import com.example.data.model.TranslationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserPreferences(
    val sourceLanguage: SupportedLanguage = SupportedLanguage.AUTO,
    val targetLanguage: SupportedLanguage = SupportedLanguage.PORTUGUESE,
    val translationMode: TranslationMode = TranslationMode.DYNAMIC,
    val translationEngine: TranslationEngine = TranslationEngine.OCR_TEXT,
    /** Card opacity. Android caps touch-through overlays at [MAX_OVERLAY_OPACITY], so that is the maximum. */
    val overlayOpacity: Float = MAX_OVERLAY_OPACITY,
    val overlayFontSizeSp: Int = 13,
    val bubbleStyle: String = "SPEECH_BUBBLE", // SPEECH_BUBBLE, SOLID_HIGHLIGHT, SEMI_TRANSPARENT
    /** OpenRouter key typed in the app (empty = build-time key or backend proxy). */
    val openRouterApiKey: String = "",
    /** Requests per (UTC) day the app allows itself: 50 on the free tier, 1000 after buying credits once. */
    val dailyRequestLimit: Int = BuildConfig.OPENROUTER_DAILY_LIMIT
) {
    companion object {
        const val MIN_OVERLAY_OPACITY = 0.5f
        /** System limit for untouchable overlay windows (Android 12+); anything above is ignored by Android. */
        const val MAX_OVERLAY_OPACITY = 0.8f
        const val FREE_DAILY_LIMIT = 50
        const val CREDITS_DAILY_LIMIT = 1000
    }
}

class UserPreferencesRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("manga_lens_prefs", Context.MODE_PRIVATE)
    // Separate file so it can be excluded from cloud backup / device transfer (see res/xml backup rules).
    private val secrets: SharedPreferences = context.getSharedPreferences(SECRETS_FILE, Context.MODE_PRIVATE)

    private val _preferences = MutableStateFlow(loadPreferences())
    val preferences: StateFlow<UserPreferences> = _preferences.asStateFlow()

    // The app UI and the overlay service each own a repository instance; this listener keeps the
    // service in sync when settings change in the app. Kept as a field: SharedPreferences holds it weakly.
    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _preferences.value = loadPreferences()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(changeListener)
        secrets.registerOnSharedPreferenceChangeListener(changeListener)
    }

    private fun loadPreferences(): UserPreferences {
        val srcCode = prefs.getString("source_lang", SupportedLanguage.AUTO.code) ?: SupportedLanguage.AUTO.code
        val tgtCode = prefs.getString("target_lang", SupportedLanguage.PORTUGUESE.code) ?: SupportedLanguage.PORTUGUESE.code
        val modeStr = prefs.getString("translation_mode", TranslationMode.DYNAMIC.name) ?: TranslationMode.DYNAMIC.name
        val engineStr = prefs.getString("translation_engine", TranslationEngine.OCR_TEXT.name) ?: TranslationEngine.OCR_TEXT.name
        // Values saved by older versions (up to 100%) are brought into the range Android really applies.
        val opacity = prefs.getFloat("overlay_opacity", UserPreferences.MAX_OVERLAY_OPACITY)
            .coerceIn(UserPreferences.MIN_OVERLAY_OPACITY, UserPreferences.MAX_OVERLAY_OPACITY)
        val fontSize = prefs.getInt("overlay_font_size", 13)
        val bubbleStyle = prefs.getString("bubble_style", "SPEECH_BUBBLE") ?: "SPEECH_BUBBLE"
        val dailyLimit = prefs.getInt("daily_request_limit", BuildConfig.OPENROUTER_DAILY_LIMIT)

        return UserPreferences(
            sourceLanguage = SupportedLanguage.fromCode(srcCode),
            targetLanguage = SupportedLanguage.fromCode(tgtCode),
            translationMode = try { TranslationMode.valueOf(modeStr) } catch (e: Exception) { TranslationMode.DYNAMIC },
            translationEngine = try { TranslationEngine.valueOf(engineStr) } catch (e: Exception) { TranslationEngine.OCR_TEXT },
            overlayOpacity = opacity,
            overlayFontSizeSp = fontSize,
            bubbleStyle = bubbleStyle,
            openRouterApiKey = secrets.getString(KEY_OPENROUTER, "").orEmpty(),
            dailyRequestLimit = dailyLimit
        )
    }

    fun updateOpenRouterApiKey(key: String) {
        val clean = key.trim()
        secrets.edit().putString(KEY_OPENROUTER, clean).apply()
        _preferences.value = _preferences.value.copy(openRouterApiKey = clean)
    }

    private companion object {
        const val SECRETS_FILE = "manga_lens_secrets"
        const val KEY_OPENROUTER = "openrouter_api_key"
    }

    fun updateSourceLanguage(language: SupportedLanguage) {
        prefs.edit().putString("source_lang", language.code).apply()
        _preferences.value = _preferences.value.copy(sourceLanguage = language)
    }

    fun updateTargetLanguage(language: SupportedLanguage) {
        prefs.edit().putString("target_lang", language.code).apply()
        _preferences.value = _preferences.value.copy(targetLanguage = language)
    }

    fun updateTranslationMode(mode: TranslationMode) {
        prefs.edit().putString("translation_mode", mode.name).apply()
        _preferences.value = _preferences.value.copy(translationMode = mode)
    }

    fun updateTranslationEngine(engine: TranslationEngine) {
        prefs.edit().putString("translation_engine", engine.name).apply()
        _preferences.value = _preferences.value.copy(translationEngine = engine)
    }

    fun updateOverlayOpacity(opacity: Float) {
        val clamped = opacity.coerceIn(UserPreferences.MIN_OVERLAY_OPACITY, UserPreferences.MAX_OVERLAY_OPACITY)
        prefs.edit().putFloat("overlay_opacity", clamped).apply()
        _preferences.value = _preferences.value.copy(overlayOpacity = clamped)
    }

    fun updateDailyRequestLimit(limit: Int) {
        prefs.edit().putInt("daily_request_limit", limit).apply()
        _preferences.value = _preferences.value.copy(dailyRequestLimit = limit)
    }

    fun updateOverlayFontSize(fontSizeSp: Int) {
        prefs.edit().putInt("overlay_font_size", fontSizeSp).apply()
        _preferences.value = _preferences.value.copy(overlayFontSizeSp = fontSizeSp)
    }

    fun updateBubbleStyle(style: String) {
        prefs.edit().putString("bubble_style", style).apply()
        _preferences.value = _preferences.value.copy(bubbleStyle = style)
    }

}

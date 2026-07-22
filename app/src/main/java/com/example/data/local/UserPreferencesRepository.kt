package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.SupportedLanguage
import com.example.data.model.TranslationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserPreferences(
    val sourceLanguage: SupportedLanguage = SupportedLanguage.AUTO,
    val targetLanguage: SupportedLanguage = SupportedLanguage.PORTUGUESE,
    val translationMode: TranslationMode = TranslationMode.DYNAMIC,
    val isTranslationEnabled: Boolean = true,
    val overlayOpacity: Float = 0.92f,
    val overlayFontSizeSp: Int = 13,
    val bubbleStyle: String = "SPEECH_BUBBLE", // SPEECH_BUBBLE, SOLID_HIGHLIGHT, SEMI_TRANSPARENT
    val readingMode: String = "WEBTOON" // WEBTOON (vertical scroll) vs MANGA_PAGES (page by page)
)

class UserPreferencesRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("manga_lens_prefs", Context.MODE_PRIVATE)

    private val _preferences = MutableStateFlow(loadPreferences())
    val preferences: StateFlow<UserPreferences> = _preferences.asStateFlow()

    private fun loadPreferences(): UserPreferences {
        val srcCode = prefs.getString("source_lang", SupportedLanguage.AUTO.code) ?: SupportedLanguage.AUTO.code
        val tgtCode = prefs.getString("target_lang", SupportedLanguage.PORTUGUESE.code) ?: SupportedLanguage.PORTUGUESE.code
        val modeStr = prefs.getString("translation_mode", TranslationMode.DYNAMIC.name) ?: TranslationMode.DYNAMIC.name
        val isEnabled = prefs.getBoolean("is_translation_enabled", true)
        val opacity = prefs.getFloat("overlay_opacity", 0.92f)
        val fontSize = prefs.getInt("overlay_font_size", 13)
        val bubbleStyle = prefs.getString("bubble_style", "SPEECH_BUBBLE") ?: "SPEECH_BUBBLE"
        val readingMode = prefs.getString("reading_mode", "WEBTOON") ?: "WEBTOON"

        return UserPreferences(
            sourceLanguage = SupportedLanguage.fromCode(srcCode),
            targetLanguage = SupportedLanguage.fromCode(tgtCode),
            translationMode = try { TranslationMode.valueOf(modeStr) } catch (e: Exception) { TranslationMode.DYNAMIC },
            isTranslationEnabled = isEnabled,
            overlayOpacity = opacity,
            overlayFontSizeSp = fontSize,
            bubbleStyle = bubbleStyle,
            readingMode = readingMode
        )
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

    fun setTranslationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("is_translation_enabled", enabled).apply()
        _preferences.value = _preferences.value.copy(isTranslationEnabled = enabled)
    }

    fun updateOverlayOpacity(opacity: Float) {
        prefs.edit().putFloat("overlay_opacity", opacity).apply()
        _preferences.value = _preferences.value.copy(overlayOpacity = opacity)
    }

    fun updateOverlayFontSize(fontSizeSp: Int) {
        prefs.edit().putInt("overlay_font_size", fontSizeSp).apply()
        _preferences.value = _preferences.value.copy(overlayFontSizeSp = fontSizeSp)
    }

    fun updateBubbleStyle(style: String) {
        prefs.edit().putString("bubble_style", style).apply()
        _preferences.value = _preferences.value.copy(bubbleStyle = style)
    }

    fun updateReadingMode(mode: String) {
        prefs.edit().putString("reading_mode", mode).apply()
        _preferences.value = _preferences.value.copy(readingMode = mode)
    }
}

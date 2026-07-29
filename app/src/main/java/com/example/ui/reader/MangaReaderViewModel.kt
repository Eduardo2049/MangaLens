package com.example.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.data.local.AppDatabase
import com.example.data.local.TranslationHistoryEntity
import com.example.data.local.UserPreferences
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.SupportedLanguage
import com.example.data.model.TextOverlay
import com.example.data.model.TranslationMode
import com.example.data.remote.GeminiVisualTranslator
import com.example.data.samples.MangaChapterSample
import com.example.data.samples.SampleMangaData
import kotlinx.coroutines.Job
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class MangaUiState(
    val chapters: List<MangaChapterSample> = SampleMangaData.samples,
    val selectedChapterIndex: Int = 0,
    val selectedPanelIndex: Int = 0,
    val customBitmap: Bitmap? = null,
    val activeOverlays: List<TextOverlay> = emptyList(),
    val isProcessing: Boolean = false,
    val isHistoryVisible: Boolean = false,
    val isSettingsOpen: Boolean = false,
    val isLanguagePickerOpen: Boolean = false,
    val isOverlayActive: Boolean = false,
    val isOverlayPermissionGranted: Boolean = false,
    val userPreferences: UserPreferences = UserPreferences(),
    val historyList: List<TranslationHistoryEntity> = emptyList(),
    val statusMessage: String? = null
)

class MangaReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "manga_lens_db"
    ).build()

    private val historyDao = db.translationHistoryDao()
    private val prefsRepo = UserPreferencesRepository(application)
    private val visualTranslator = GeminiVisualTranslator()

    private val _uiState = MutableStateFlow(MangaUiState())
    val uiState: StateFlow<MangaUiState> = _uiState.asStateFlow()

    private var autoTranslateJob: Job? = null

    init {
        // Observe preferences
        viewModelScope.launch {
            prefsRepo.preferences.collectLatest { prefs ->
                _uiState.value = _uiState.value.copy(userPreferences = prefs)
                // Refresh overlays when source/target languages change
                loadActiveOverlays()
            }
        }

        // Observe Room History
        viewModelScope.launch {
            historyDao.getRecentHistory().collectLatest { history ->
                _uiState.value = _uiState.value.copy(historyList = history)
            }
        }

        // Initial overlay load
        loadActiveOverlays()
    }

    fun setCustomBitmap(bitmap: Bitmap?) {
        _uiState.value = _uiState.value.copy(
            customBitmap = bitmap,
            selectedPanelIndex = 0,
            statusMessage = "Imagem carregada. Traduzindo..."
        )
        triggerTranslation()
    }

    private val currentChapter: MangaChapterSample?
        get() = _uiState.value.chapters.getOrNull(_uiState.value.selectedChapterIndex)

    private fun loadActiveOverlays() {
        val custom = _uiState.value.customBitmap
        if (custom != null) {
            // Trigger translation for custom image
            triggerTranslation()
            return
        }

        val chapter = currentChapter
        val panel = chapter?.panels?.getOrNull(_uiState.value.selectedPanelIndex)
        if (panel != null) {
            _uiState.value = _uiState.value.copy(
                activeOverlays = panel.sampleOverlays,
            )
        }
    }

    fun triggerTranslation() {
        val prefs = _uiState.value.userPreferences
        if (!prefs.isTranslationEnabled) return

        _uiState.value = _uiState.value.copy(isProcessing = true)

        viewModelScope.launch {
            delay(400.milliseconds) // Smooth visual feedback

            val bitmapToProcess = _uiState.value.customBitmap

            if (bitmapToProcess != null) {
                val result = visualTranslator.translateMangaImage(
                    bitmap = bitmapToProcess,
                    sourceLang = prefs.sourceLanguage,
                    targetLang = prefs.targetLanguage
                )

                result.fold(
                    onSuccess = { overlays ->
                        _uiState.value = _uiState.value.copy(
                            activeOverlays = overlays,
                            isProcessing = false,
                            statusMessage = if (overlays.isEmpty()) "Nenhum texto detectado nesta área." else null
                        )
                        saveOverlaysToHistory(overlays)
                    },
                    onFailure = { err ->
                        Log.e("MangaViewModel", "Translation failed", err)
                        _uiState.value = _uiState.value.copy(
                            isProcessing = false,
                            statusMessage = "Falha no OCR/Tradução: ${err.localizedMessage}"
                        )
                    }
                )
            } else {
                // Built-in sample panel: simulated dynamic translation update with sample data
                val chapter = currentChapter
                val panel = chapter?.panels?.getOrNull(_uiState.value.selectedPanelIndex)
                val sampleOverlays = panel?.sampleOverlays ?: emptyList()

                _uiState.value = _uiState.value.copy(
                    activeOverlays = sampleOverlays,
                    isProcessing = false,
                    statusMessage = null
                )
                saveOverlaysToHistory(sampleOverlays)
            }
        }
    }

    private suspend fun saveOverlaysToHistory(overlays: List<TextOverlay>) {
        val prefs = _uiState.value.userPreferences
        val chapterTitle = currentChapter?.title ?: "Manga Personalizado"

        overlays.take(3).forEach { overlay ->
            historyDao.insertTranslation(
                TranslationHistoryEntity(
                    originalText = overlay.originalText,
                    translatedText = overlay.translatedText,
                    sourceLang = prefs.sourceLanguage.displayName,
                    targetLang = prefs.targetLanguage.displayName,
                    chapterTitle = chapterTitle
                )
            )
        }
    }

    fun setTranslationEnabled(enabled: Boolean) {
        prefsRepo.setTranslationEnabled(enabled)
        if (enabled) {
            triggerTranslation()
        } else {
            _uiState.value = _uiState.value.copy(activeOverlays = emptyList())
        }
    }

    fun setTranslationMode(mode: TranslationMode) {
        prefsRepo.updateTranslationMode(mode)
    }

    fun updateSourceLanguage(lang: SupportedLanguage) {
        prefsRepo.updateSourceLanguage(lang)
    }

    fun updateTargetLanguage(lang: SupportedLanguage) {
        prefsRepo.updateTargetLanguage(lang)
    }

    fun updateOverlayOpacity(opacity: Float) {
        prefsRepo.updateOverlayOpacity(opacity)
    }

    fun updateOverlayFontSize(fontSizeSp: Int) {
        prefsRepo.updateOverlayFontSize(fontSizeSp)
    }

    fun updateBubbleStyle(style: String) {
        prefsRepo.updateBubbleStyle(style)
    }

    fun openSettings() {
        _uiState.value = _uiState.value.copy(isSettingsOpen = true)
    }

    fun closeSettings() {
        _uiState.value = _uiState.value.copy(isSettingsOpen = false)
    }

    fun openLanguagePicker() {
        _uiState.value = _uiState.value.copy(isLanguagePickerOpen = true)
    }

    fun closeLanguagePicker() {
        _uiState.value = _uiState.value.copy(isLanguagePickerOpen = false)
    }

    fun setOverlayActive(active: Boolean) {
        _uiState.value = _uiState.value.copy(isOverlayActive = active)
    }

    fun updateOverlayPermissionGranted(granted: Boolean) {
        _uiState.value = _uiState.value.copy(isOverlayPermissionGranted = granted)
    }

    override fun onCleared() {
        super.onCleared()
        autoTranslateJob?.cancel()
    }
}

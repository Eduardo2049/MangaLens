package com.example.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.TranslationHistory
import com.example.data.local.TranslationHistoryEntity
import com.example.data.local.UserPreferences
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.SupportedLanguage
import com.example.data.model.TextOverlay
import com.example.data.model.BoundingBox
import com.example.data.model.TranslationEngine
import com.example.data.model.TranslationMode
import com.example.data.model.TranslationProvider
import com.example.data.samples.MangaChapterSample
import com.example.data.samples.SampleMangaData
import com.example.diagnostics.CrashReporter
import com.example.diagnostics.ProblemReport
import com.example.translation.TranslationPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val statusMessage: String? = null,
    /** Last crash or unexpected error, shown until the user dismisses it. */
    val problemReport: ProblemReport? = null
)

class MangaReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val historyDao = AppDatabase.get(application).translationHistoryDao()
    private val prefsRepo = UserPreferencesRepository(application)
    /** Shared with the floating translator (same cache, quota and request spacing); released in onCleared. */
    private val pipeline = TranslationPipeline.acquire(application)

    private val _uiState = MutableStateFlow(MangaUiState())
    val uiState: StateFlow<MangaUiState> = _uiState.asStateFlow()

    /** The translation in progress; a new one (another image, language or engine) cancels it. */
    private var translationJob: Job? = null

    init {
        // Observe preferences
        viewModelScope.launch {
            prefsRepo.preferences.collectLatest { prefs ->
                val previous = _uiState.value.userPreferences
                _uiState.value = _uiState.value.copy(userPreferences = prefs)
                // Re-translate only when something that affects the result changed (not opacity/font size),
                // otherwise dragging a slider would fire one API request per step.
                val translationChanged = previous.sourceLanguage != prefs.sourceLanguage ||
                    previous.targetLanguage != prefs.targetLanguage ||
                    previous.translationEngine != prefs.translationEngine
                if (translationChanged) loadActiveOverlays()
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

        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { runCatching { CrashReporter.pending(application) }.getOrNull() }
            _uiState.value = _uiState.value.copy(problemReport = report)
        }
    }

    fun dismissProblemReport() {
        CrashReporter.dismiss(getApplication())
        _uiState.value = _uiState.value.copy(problemReport = null)
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

        _uiState.value = _uiState.value.copy(isProcessing = true)

        // Without this, changing the language twice fired two paid requests and whichever finished last
        // won, possibly showing the translation for the language that is no longer selected.
        translationJob?.cancel()
        translationJob = viewModelScope.launch {
            delay(400.milliseconds) // Smooth visual feedback

            val bitmapToProcess = _uiState.value.customBitmap

            if (bitmapToProcess != null) {
                val result = try {
                    pipeline.translate(bitmapToProcess, prefs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    CrashReporter.recordHandled(getApplication(), "teste com imagem", e)
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = "Erro inesperado ao traduzir a imagem: ${e::class.java.simpleName}"
                    )
                    return@launch
                }
                val width = bitmapToProcess.width.toFloat()
                val height = bitmapToProcess.height.toFloat()
                val overlays = result.translations.map {
                    TextOverlay(
                        originalText = it.originalText,
                        translatedText = it.translatedText,
                        box = BoundingBox(
                            yMin = it.box.top / height * 100f,
                            xMin = it.box.left / width * 100f,
                            yMax = it.box.bottom / height * 100f,
                            xMax = it.box.right / width * 100f
                        ),
                        isVertical = it.isVertical,
                        bubbleType = it.bubbleType
                    )
                }
                val error = result.error

                if (error != null && overlays.isEmpty()) {
                    Log.e("MangaViewModel", "Translation failed", error)
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = "Falha no OCR/Tradução: ${error.localizedMessage}"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        activeOverlays = overlays,
                        isProcessing = false,
                        statusMessage = if (overlays.isEmpty()) "Nenhum texto detectado nesta área." else null
                    )
                    TranslationHistory.record(
                        historyDao,
                        result.fresh.map { it.originalText to it.translatedText },
                        prefs.sourceLanguage, prefs.targetLanguage, CUSTOM_IMAGE_TITLE
                    )
                }
            } else {
                // Built-in sample panel: simulated dynamic translation update with sample data
                val chapter = currentChapter
                val panel = chapter?.panels?.getOrNull(_uiState.value.selectedPanelIndex)
                val sampleOverlays = panel?.sampleOverlays ?: emptyList()

                // Sample data is not a real translation: it does not go to the history.
                _uiState.value = _uiState.value.copy(
                    activeOverlays = sampleOverlays,
                    isProcessing = false,
                    statusMessage = null
                )
            }
        }
    }

    fun setTranslationMode(mode: TranslationMode) {
        prefsRepo.updateTranslationMode(mode)
    }

    fun setTranslationEngine(engine: TranslationEngine) {
        prefsRepo.updateTranslationEngine(engine)
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

    fun updateProvider(provider: TranslationProvider) {
        prefsRepo.updateProvider(provider)
    }

    fun updateApiKey(provider: TranslationProvider, key: String) {
        prefsRepo.updateApiKey(provider, key)
    }

    fun updateModel(provider: TranslationProvider, model: String) {
        prefsRepo.updateModel(provider, model)
    }

    fun updateDailyRequestLimit(limit: Int) {
        prefsRepo.updateDailyRequestLimit(limit)
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
        translationJob?.cancel()
        TranslationPipeline.release()
    }

    private companion object {
        const val CUSTOM_IMAGE_TITLE = "Imagem de teste"
    }
}

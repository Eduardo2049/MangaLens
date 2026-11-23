package com.example.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.data.local.TranslationHistoryEntity
import com.example.data.local.UserPreferences
import com.example.data.model.TranslationMode
import com.example.data.model.TranslationProvider
import com.example.diagnostics.ProblemReport
import com.example.ui.components.LanguagePickerContent
import com.example.ui.components.SettingsContent
import com.example.ui.reader.MainScreenActions
import com.example.ui.reader.MainScreenContent
import com.example.ui.reader.MangaReaderScreen
import com.example.ui.reader.MangaUiState
import com.example.ui.theme.LensColors
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the app screens at phone sizes. In CI the images are recorded (-Proborazzi.test.record=true) and
 * uploaded as the "ui-screenshots" artifact; locally without that flag the tests still check that every
 * screen composes without crashing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, fill: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent {
            MyApplicationTheme {
                Surface(
                    color = LensColors.Background,
                    modifier = if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
                ) { content() }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private val sampleHistory = listOf(
        TranslationHistoryEntity(originalText = "おい、待てよ！", translatedText = "Ei, espera aí!", sourceLang = "ja", targetLang = "pt"),
        TranslationHistoryEntity(originalText = "この本、読めないんだ…", translatedText = "Não consigo ler este livro…", sourceLang = "ja", targetLang = "pt"),
        TranslationHistoryEntity(originalText = "大丈夫。翻訳するから。", translatedText = "Relaxa. Eu traduzo pra você.", sourceLang = "ja", targetLang = "pt")
    )

    private val crash = ProblemReport(
        title = "O MangaLens fechou por um erro",
        summary = "IllegalStateException: exemplo de erro mostrado ao usuário",
        details = "detalhes",
        timestamp = 0L
    )

    // ---------------------------------------------------------------- main screen

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun main_realScreen_smallPhone() = capture("main_real_360x780") { MangaReaderScreen() }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun main_realScreen_phone() = capture("main_real_411x891") { MangaReaderScreen() }

    @Test
    @Config(qualifiers = "w360dp-h2000dp-xhdpi")
    fun main_firstRun_needsPermissionAndKey() = capture("main_first_run_360") {
        MainScreenContent(
            state = MangaUiState(
                userPreferences = UserPreferences(provider = TranslationProvider.OPENAI),
                problemReport = crash
            ),
            hasOverlayPermission = false,
            actions = MainScreenActions()
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h2000dp-xhdpi")
    fun main_running_withHistory() = capture("main_running_360") {
        MainScreenContent(
            state = MangaUiState(
                isOverlayActive = true,
                userPreferences = UserPreferences(
                    provider = TranslationProvider.OPENAI,
                    apiKeys = mapOf(TranslationProvider.OPENAI to "sk-test"),
                    customModels = mapOf(TranslationProvider.OPENAI to "gpt-6-luna"),
                    translationMode = TranslationMode.MANUAL
                ),
                historyList = sampleHistory
            ),
            hasOverlayPermission = true,
            actions = MainScreenActions()
        )
    }

    // ---------------------------------------------------------------- settings

    @Test
    @Config(qualifiers = "w360dp-h2400dp-xhdpi")
    fun settings_openRouterWithoutKey() = capture("settings_openrouter_360", fill = false) {
        SettingsContent(userPreferences = UserPreferences())
    }

    @Test
    @Config(qualifiers = "w360dp-h2400dp-xhdpi")
    fun settings_openAiWithKeyAndModel() = capture("settings_openai_360", fill = false) {
        SettingsContent(
            userPreferences = UserPreferences(
                provider = TranslationProvider.OPENAI,
                apiKeys = mapOf(TranslationProvider.OPENAI to "sk-test-key", TranslationProvider.OPENROUTER to "sk-or-1"),
                customModels = mapOf(TranslationProvider.OPENAI to "gpt-6-luna")
            )
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h1400dp-xhdpi")
    fun languages_picker() = capture("languages_360", fill = false) {
        LanguagePickerContent(userPreferences = UserPreferences(), onSelectSource = {}, onSelectTarget = {})
    }
}

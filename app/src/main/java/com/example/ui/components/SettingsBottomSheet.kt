package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.UserPreferences
import com.example.data.model.SupportedLanguage
import com.example.data.model.TranslationEngine
import com.example.data.model.TranslationMode
import com.example.data.model.TranslationProvider
import com.example.data.remote.OpenRouterClient
import com.example.ui.theme.LensColors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    userPreferences: UserPreferences,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onUpdateOpacity: (Float) -> Unit,
    onUpdateFontSize: (Int) -> Unit,
    onUpdateBubbleStyle: (String) -> Unit,
    onUpdateDailyLimit: (Int) -> Unit = {},
    onUpdateTranslationMode: (TranslationMode) -> Unit = {},
    onUpdateTranslationEngine: (TranslationEngine) -> Unit = {},
    onUpdateProvider: (TranslationProvider) -> Unit = {},
    onSaveApiKey: (TranslationProvider, String) -> Unit = { _, _ -> },
    onSaveModel: (TranslationProvider, String) -> Unit = { _, _ -> }
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = LensColors.Background,
        contentColor = LensColors.Text
    ) {
        SettingsContent(
            userPreferences = userPreferences,
            onUpdateOpacity = onUpdateOpacity,
            onUpdateFontSize = onUpdateFontSize,
            onUpdateBubbleStyle = onUpdateBubbleStyle,
            onUpdateDailyLimit = onUpdateDailyLimit,
            onUpdateTranslationMode = onUpdateTranslationMode,
            onUpdateTranslationEngine = onUpdateTranslationEngine,
            onUpdateProvider = onUpdateProvider,
            onSaveApiKey = onSaveApiKey,
            onSaveModel = onSaveModel
        )
    }
}

/** Everything in the settings sheet; scrolls on its own so it also fits small phones with the keyboard open. */
@Composable
fun SettingsContent(
    userPreferences: UserPreferences,
    modifier: Modifier = Modifier,
    onUpdateOpacity: (Float) -> Unit = {},
    onUpdateFontSize: (Int) -> Unit = {},
    onUpdateBubbleStyle: (String) -> Unit = {},
    onUpdateDailyLimit: (Int) -> Unit = {},
    onUpdateTranslationMode: (TranslationMode) -> Unit = {},
    onUpdateTranslationEngine: (TranslationEngine) -> Unit = {},
    onUpdateProvider: (TranslationProvider) -> Unit = {},
    onSaveApiKey: (TranslationProvider, String) -> Unit = { _, _ -> },
    onSaveModel: (TranslationProvider, String) -> Unit = { _, _ -> }
) {
    val selected = userPreferences.provider
    Column(
        verticalArrangement = Arrangement.spacedBy(28.dp),
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
            .testTag("settings_bottom_sheet")
    ) {
        Text(text = "Configurações", color = LensColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Column {
            GroupLabel("Provedor de tradução", supporting = "Cada provedor guarda a própria chave neste aparelho. Dá para trocar quando quiser.")
            ChoiceGroup {
                TranslationProvider.entries.forEach { option ->
                    val hasKey = userPreferences.keyFor(option).isNotBlank() ||
                        (option == TranslationProvider.OPENROUTER && (OpenRouterClient.usesDirectKey() || OpenRouterClient.hasBackend()))
                    ChoiceRow(
                        title = option.title,
                        description = option.description,
                        status = if (hasKey) "Chave salva" else "Sem chave",
                        statusColor = if (hasKey) LensColors.Success else LensColors.TextMuted,
                        selected = selected == option,
                        onClick = { onUpdateProvider(option) }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            ApiKeyField(
                provider = selected,
                savedKey = userPreferences.keyFor(selected),
                onSave = { onSaveApiKey(selected, it) }
            )
            // OpenRouter: optional list of free models, so a model taken offline does not need a new APK.
            Spacer(Modifier.height(12.dp))
            ModelField(
                provider = selected,
                savedModel = userPreferences.customModels[selected].orEmpty(),
                onSave = { onSaveModel(selected, it) }
            )
        }

        Column {
            GroupLabel("Quando traduzir")
            ChoiceGroup {
                ChoiceRow(
                    title = "Sozinho",
                    description = "Traduz cada página nova quando você para de rolar.",
                    selected = userPreferences.translationMode == TranslationMode.DYNAMIC,
                    onClick = { onUpdateTranslationMode(TranslationMode.DYNAMIC) }
                )
                ChoiceRow(
                    title = "Quando eu tocar na bolha",
                    description = "Nada é enviado até você pedir. Toque de novo para esconder.",
                    selected = userPreferences.translationMode == TranslationMode.MANUAL,
                    onClick = { onUpdateTranslationMode(TranslationMode.MANUAL) }
                )
            }
        }

        Column {
            GroupLabel("Leitura do texto")
            ChoiceGroup {
                ChoiceRow(
                    title = "Rápida",
                    description = "O texto é lido no celular e só ele vai para a IA. Gasta menos.",
                    selected = userPreferences.translationEngine == TranslationEngine.OCR_TEXT,
                    onClick = { onUpdateTranslationEngine(TranslationEngine.OCR_TEXT) }
                )
                ChoiceRow(
                    title = "Precisa",
                    description = "A IA também vê a página: corrige erros de leitura e texto vertical. Mais lenta.",
                    selected = userPreferences.translationEngine == TranslationEngine.VISION,
                    onClick = { onUpdateTranslationEngine(TranslationEngine.VISION) }
                )
            }
        }

        if (selected.hasFreeTierLimit) {
            Column {
                GroupLabel("Limite diário da OpenRouter", supporting = "O app para de pedir traduções novas ao chegar no limite do seu plano.")
                ChoiceGroup {
                    ChoiceRow(
                        title = "${UserPreferences.FREE_DAILY_LIMIT} por dia",
                        description = "Plano grátis",
                        selected = userPreferences.dailyRequestLimit == UserPreferences.FREE_DAILY_LIMIT,
                        onClick = { onUpdateDailyLimit(UserPreferences.FREE_DAILY_LIMIT) }
                    )
                    ChoiceRow(
                        title = "${UserPreferences.CREDITS_DAILY_LIMIT} por dia",
                        description = "Depois de comprar US$ 10 em créditos uma vez",
                        selected = userPreferences.dailyRequestLimit == UserPreferences.CREDITS_DAILY_LIMIT,
                        onClick = { onUpdateDailyLimit(UserPreferences.CREDITS_DAILY_LIMIT) }
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GroupLabel("Aparência das traduções")

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Opacidade", color = LensColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("${(userPreferences.overlayOpacity * 100).roundToInt()}%", color = LensColors.TextMuted, fontSize = 14.sp)
                }
                Slider(
                    value = userPreferences.overlayOpacity,
                    onValueChange = onUpdateOpacity,
                    valueRange = UserPreferences.MIN_OVERLAY_OPACITY..UserPreferences.MAX_OVERLAY_OPACITY,
                    colors = SliderDefaults.colors(
                        thumbColor = LensColors.Cyan,
                        activeTrackColor = LensColors.Cyan,
                        inactiveTrackColor = LensColors.SurfaceHigh
                    )
                )
                Text(
                    text = "O Android limita a 80% as janelas que deixam o toque passar para o leitor.",
                    color = LensColors.TextMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tamanho do texto", color = LensColors.Text, fontSize = 15.sp)
                SegmentedChoice(
                    options = listOf(11 to "Pequeno", 13 to "Médio", 16 to "Grande"),
                    selected = userPreferences.overlayFontSizeSp,
                    onSelect = onUpdateFontSize
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Fundo dos balões", color = LensColors.Text, fontSize = 15.sp)
                SegmentedChoice(
                    options = listOf("SPEECH_BUBBLE" to "Branco", "SOLID_HIGHLIGHT" to "Escuro", "SEMI_TRANSPARENT" to "Translúcido"),
                    selected = userPreferences.bubbleStyle,
                    onSelect = onUpdateBubbleStyle
                )
            }
        }
    }
}

@Composable
private fun ApiKeyField(
    provider: TranslationProvider,
    savedKey: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Keyed by provider too: switching provider must not leave the previous provider's draft on screen.
    var draft by remember(provider, savedKey) { mutableStateOf(savedKey) }
    var visible by remember(provider) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val canSave = draft.isNotBlank() && draft != savedKey
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.trim() },
            singleLine = true,
            label = { Text("Chave da ${provider.title}") },
            placeholder = { Text(provider.keyPlaceholder) },
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (visible) "Esconder chave" else "Mostrar chave"
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (canSave) onSave(draft)
                focusManager.clearFocus()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("api_key_field")
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = if (savedKey.isBlank()) "${provider.keyHelp}. Fica só neste aparelho." else "Chave salva neste aparelho.",
                color = LensColors.TextMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
            if (savedKey.isNotBlank()) {
                TextButton(onClick = { onSave("") }) { Text("Remover", color = LensColors.Error) }
            }
            TextButton(onClick = { onSave(draft); focusManager.clearFocus() }, enabled = canSave) {
                Text("Salvar", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Model for OpenAI/Gemini, or the list of free models for OpenRouter; empty = the default. */
@Composable
private fun ModelField(
    provider: TranslationProvider,
    savedModel: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by remember(provider, savedModel) { mutableStateOf(savedModel) }
    val focusManager = LocalFocusManager.current
    // OpenRouter takes a list of free models (tried in order); the other providers take a single model.
    val isList = !provider.usesChosenModel
    val defaultText = if (isList) OpenRouterClient.textModels.joinToString(", ") else provider.defaultModel
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.trim() },
            singleLine = !isList,
            label = { Text(if (isList) "Modelos gratuitos (até 3, separados por vírgula)" else "Modelo") },
            placeholder = { Text(defaultText) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (draft != savedModel) onSave(draft)
                focusManager.clearFocus()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("model_field")
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = when {
                    savedModel.isBlank() && isList -> "Vazio usa a lista do app. Veja os modelos grátis em openrouter.ai/models."
                    savedModel.isBlank() -> "Vazio usa o padrão: ${provider.defaultModel}"
                    isList -> "Usando a sua lista de modelos."
                    else -> "Usando o modelo escolhido."
                },
                color = LensColors.TextMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
            if (savedModel.isNotBlank()) {
                TextButton(onClick = { onSave("") }) { Text("Usar padrão", color = LensColors.TextMuted) }
            }
            TextButton(onClick = { onSave(draft); focusManager.clearFocus() }, enabled = draft != savedModel) {
                Text("Salvar", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePickerDialog(
    userPreferences: UserPreferences,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onSelectSource: (SupportedLanguage) -> Unit,
    onSelectTarget: (SupportedLanguage) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = LensColors.Background,
        contentColor = LensColors.Text
    ) {
        LanguagePickerContent(
            userPreferences = userPreferences,
            onSelectSource = onSelectSource,
            onSelectTarget = { onSelectTarget(it); onDismiss() }
        )
    }
}

private fun SupportedLanguage.pickerName(): String =
    if (this == SupportedLanguage.AUTO) "Detectar automaticamente" else displayName

/** Source first, then target: picking the source moves on to the target instead of closing the sheet. */
@Composable
fun LanguagePickerContent(
    userPreferences: UserPreferences,
    onSelectSource: (SupportedLanguage) -> Unit,
    onSelectTarget: (SupportedLanguage) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: Int = 0
) {
    var tab by remember { mutableIntStateOf(initialTab) }
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
            .testTag("language_picker_dialog")
    ) {
        Text(text = "Idiomas", color = LensColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        SegmentedChoice(
            options = listOf(
                0 to "De: ${if (userPreferences.sourceLanguage == SupportedLanguage.AUTO) "Detectar" else userPreferences.sourceLanguage.displayName.substringBefore(" (")}",
                1 to "Para: ${userPreferences.targetLanguage.displayName.substringBefore(" (")}"
            ),
            selected = tab,
            onSelect = { tab = it }
        )
        val options = if (tab == 0) SupportedLanguage.getSourceLanguages() else SupportedLanguage.getTargetLanguages()
        val current = if (tab == 0) userPreferences.sourceLanguage else userPreferences.targetLanguage
        ChoiceGroup {
            options.forEach { language ->
                ChoiceRow(
                    title = language.pickerName(),
                    description = if (language == SupportedLanguage.AUTO) "Japonês, coreano, chinês ou inglês" else null,
                    selected = language == current,
                    onClick = {
                        if (tab == 0) {
                            onSelectSource(language)
                            tab = 1
                        } else {
                            onSelectTarget(language)
                        }
                    }
                )
            }
        }
    }
}

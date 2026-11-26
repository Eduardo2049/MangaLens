package com.example.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.TranslationHistory
import com.example.data.local.UserPreferences
import com.example.data.model.TranslationEngine
import com.example.data.model.TranslationMode
import com.example.data.model.TranslationProvider
import com.example.data.remote.OpenRouterClient
import com.example.diagnostics.ProblemReport
import com.example.service.MangaOverlayService
import com.example.translation.ScreenMath
import com.example.ui.components.LanguagePickerDialog
import com.example.ui.components.LensMark
import com.example.ui.components.MangaCanvasViewer
import com.example.ui.components.NavRow
import com.example.ui.components.NoticeRow
import com.example.ui.components.RowDivider
import com.example.ui.components.SectionCard
import com.example.ui.components.SectionTitle
import com.example.ui.components.SettingsBottomSheet
import com.example.ui.components.StatusDot
import com.example.ui.theme.LensColors

/** What the main screen can ask for; kept apart from the screen so it can be previewed and tested. */
class MainScreenActions(
    val onToggleTranslator: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onOpenLanguages: () -> Unit = {},
    val onGrantOverlay: () -> Unit = {},
    val onPickImage: () -> Unit = {},
    val onCopyProblem: (ProblemReport) -> Unit = {},
    val onDismissProblem: () -> Unit = {}
)

/** True when the selected provider has a key (or, for OpenRouter, a build key or backend). */
fun UserPreferences.hasUsableKey(): Boolean =
    keyFor().isNotBlank() ||
        (provider == TranslationProvider.OPENROUTER && (OpenRouterClient.usesDirectKey() || OpenRouterClient.hasBackend()))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaReaderScreen(
    viewModel: MangaReaderViewModel = viewModel(),
    onRequestScreenCapture: ((Boolean) -> Unit) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val langSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Re-checked every time the screen comes back (e.g. from the system overlay settings).
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LifecycleResumeEffect(Unit) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(hasOverlayPermission) {
        viewModel.updateOverlayPermissionGranted(hasOverlayPermission)
    }

    // Mirror the real service state (it can stop itself: ✖ on the bubble, capture revoked, errors).
    val serviceRunning by MangaOverlayService.isRunning.collectAsState()
    LaunchedEffect(serviceRunning) {
        viewModel.setOverlayActive(serviceRunning)
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val bitmap = try {
                decodeTestImage(context, it)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            if (bitmap != null) {
                viewModel.setCustomBitmap(bitmap)
            } else {
                Toast.makeText(context, "Não foi possível abrir esta imagem", Toast.LENGTH_LONG).show()
            }
        }
    }

    val openOverlaySettings = {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
        )
    }

    val actions = MainScreenActions(
        onToggleTranslator = {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "Permita que o MangaLens apareça sobre outros apps", Toast.LENGTH_LONG).show()
                openOverlaySettings()
            } else if (uiState.isOverlayActive) {
                context.stopService(Intent(context, MangaOverlayService::class.java))
                viewModel.setOverlayActive(false)
            } else {
                // The active state follows MangaOverlayService.isRunning once capture starts.
                onRequestScreenCapture { }
            }
        },
        onOpenSettings = { viewModel.openSettings() },
        onOpenLanguages = { viewModel.openLanguagePicker() },
        onGrantOverlay = openOverlaySettings,
        onPickImage = { imagePickerLauncher.launch("image/*") },
        onCopyProblem = { report ->
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("MangaLens", report.details))
            Toast.makeText(context, "Detalhes copiados", Toast.LENGTH_SHORT).show()
        },
        onDismissProblem = { viewModel.dismissProblemReport() }
    )

    Scaffold(containerColor = LensColors.Background) { innerPadding ->
        MainScreenContent(
            state = uiState,
            hasOverlayPermission = hasOverlayPermission,
            actions = actions,
            modifier = Modifier.padding(innerPadding)
        )
    }

    if (uiState.isSettingsOpen) {
        SettingsBottomSheet(
            userPreferences = uiState.userPreferences,
            sheetState = settingsSheetState,
            onDismiss = { viewModel.closeSettings() },
            onUpdateOpacity = { viewModel.updateOverlayOpacity(it) },
            onUpdateFontSize = { viewModel.updateOverlayFontSize(it) },
            onUpdateBubbleStyle = { viewModel.updateBubbleStyle(it) },
            onUpdateDailyLimit = { viewModel.updateDailyRequestLimit(it) },
            onUpdateTranslationMode = { viewModel.setTranslationMode(it) },
            onUpdateTranslationEngine = { viewModel.setTranslationEngine(it) },
            onUpdateProvider = { viewModel.updateProvider(it) },
            onSaveApiKey = { provider, key -> viewModel.updateApiKey(provider, key) },
            onSaveModel = { provider, model -> viewModel.updateModel(provider, model) }
        )
    }

    if (uiState.isLanguagePickerOpen) {
        LanguagePickerDialog(
            userPreferences = uiState.userPreferences,
            sheetState = langSheetState,
            onDismiss = { viewModel.closeLanguagePicker() },
            onSelectSource = { viewModel.updateSourceLanguage(it) },
            onSelectTarget = { viewModel.updateTargetLanguage(it) }
        )
    }
}

@Composable
fun MainScreenContent(
    state: MangaUiState,
    hasOverlayPermission: Boolean,
    actions: MainScreenActions,
    modifier: Modifier = Modifier
) {
    val prefs = state.userPreferences
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("manga_reader_dashboard")
    ) {
        TopBar(onOpenSettings = actions.onOpenSettings)

        state.problemReport?.let { report ->
            ProblemCard(report, onCopy = { actions.onCopyProblem(report) }, onDismiss = actions.onDismissProblem)
        }

        TranslatorCard(
            active = state.isOverlayActive,
            hasOverlayPermission = hasOverlayPermission,
            hasKey = prefs.hasUsableKey(),
            providerTitle = prefs.provider.title,
            actions = actions
        )

        SetupCard(prefs = prefs, actions = actions)

        TestImageCard(state = state, onPickImage = actions.onPickImage)

        if (state.historyList.isNotEmpty()) HistoryCard(state)

        Text(
            text = "Funciona sobre Mihon, Tachiyomi, Komikku, Kotatsu e outros leitores de mangá e webtoon.",
            color = LensColors.TextMuted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun TopBar(onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        LensMark(size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "MangaLens", color = LensColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "Tradução por cima do seu leitor",
                color = LensColors.TextMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("open_settings_button")) {
            Icon(Icons.Default.Settings, contentDescription = "Configurações", tint = LensColors.Text)
        }
    }
}

@Composable
private fun ProblemCard(report: ProblemReport, onCopy: () -> Unit, onDismiss: () -> Unit) {
    SectionCard(color = LensColors.ErrorContainer) {
        Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = LensColors.Error,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = report.title, color = LensColors.OnErrorContainer, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = report.summary,
                        color = LensColors.OnErrorContainer,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = "Copie os detalhes e envie para que o problema seja corrigido.",
                        color = LensColors.TextMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            TextButton(onClick = onDismiss) { Text("Dispensar", color = LensColors.TextMuted) }
            TextButton(onClick = onCopy) { Text("Copiar detalhes", color = LensColors.OnErrorContainer, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun TranslatorCard(
    active: Boolean,
    hasOverlayPermission: Boolean,
    hasKey: Boolean,
    providerTitle: String,
    actions: MainScreenActions
) {
    SectionCard {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(on = active)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (active) "Tradutor ligado" else "Tradutor desligado",
                    color = LensColors.Text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = if (active) {
                    "Abra o seu leitor de mangá. A bolha traduz cada página quando você para de rolar."
                } else {
                    "Ligue o tradutor e abra o seu leitor. Uma bolha flutuante traduz os balões por cima da página."
                },
                color = LensColors.TextMuted,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            if (!hasOverlayPermission) {
                NoticeRow(
                    icon = Icons.Default.Layers,
                    text = "Falta permitir que o MangaLens apareça sobre outros apps.",
                    actionLabel = "Permitir",
                    onAction = actions.onGrantOverlay
                )
            }
            if (!hasKey) {
                NoticeRow(
                    icon = Icons.Default.Key,
                    text = "Nenhuma chave da $providerTitle salva. Sem ela, nada é traduzido.",
                    actionLabel = "Configurar",
                    onAction = actions.onOpenSettings
                )
            }
            Button(
                onClick = actions.onToggleTranslator,
                shape = RoundedCornerShape(14.dp),
                colors = if (active) {
                    ButtonDefaults.buttonColors(containerColor = LensColors.Danger, contentColor = Color.White)
                } else {
                    ButtonDefaults.buttonColors(containerColor = LensColors.Cyan, contentColor = LensColors.OnCyan)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("toggle_overlay_main_button")
            ) {
                Icon(
                    if (active) Icons.Default.Stop else Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (active) "Desligar tradutor" else "Ligar tradutor",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SetupCard(prefs: UserPreferences, actions: MainScreenActions) {
    val provider = prefs.provider
    val providerValue = when {
        !prefs.hasUsableKey() -> "${provider.title}, sem chave"
        provider.usesChosenModel -> "${provider.title}, modelo ${prefs.modelFor()}"
        else -> "${provider.title}, modelos gratuitos"
    }
    SectionCard {
        SectionTitle("Tradução", modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 4.dp))
        NavRow(
            icon = Icons.Default.Language,
            title = "Idiomas",
            value = "${prefs.sourceLanguage.shortLabel()} para ${prefs.targetLanguage.shortLabel()}",
            onClick = actions.onOpenLanguages
        )
        RowDivider()
        NavRow(
            icon = Icons.Default.Key,
            title = "Provedor",
            value = providerValue,
            valueColor = if (prefs.hasUsableKey()) LensColors.TextMuted else LensColors.Warning,
            onClick = actions.onOpenSettings
        )
        RowDivider()
        NavRow(
            icon = Icons.Default.TouchApp,
            title = "Quando traduzir",
            value = if (prefs.translationMode == TranslationMode.DYNAMIC) "Sozinho, a cada página nova" else "Só quando tocar na bolha",
            onClick = actions.onOpenSettings
        )
        RowDivider()
        NavRow(
            icon = Icons.Default.DocumentScanner,
            title = "Leitura do texto",
            value = if (prefs.translationEngine == TranslationEngine.OCR_TEXT) "Rápida: só o texto vai para a IA" else "Precisa: a IA também vê a imagem",
            onClick = actions.onOpenSettings
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun TestImageCard(state: MangaUiState, onPickImage: () -> Unit) {
    SectionCard {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(18.dp)
        ) {
            SectionTitle("Testar com uma imagem")
            Text(
                text = "Escolha uma página salva no celular para ver a tradução aqui, sem ligar a bolha.",
                color = LensColors.TextMuted,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            if (state.customBitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black)
                ) {
                    MangaCanvasViewer(
                        panelData = null,
                        customBitmap = state.customBitmap,
                        overlays = state.activeOverlays,
                        userPreferences = state.userPreferences,
                        isProcessing = state.isProcessing,
                        onOverlaySelected = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            state.statusMessage?.let {
                Text(text = it, color = LensColors.Text, fontSize = 13.sp, lineHeight = 18.sp)
            }
            OutlinedButton(
                onClick = onPickImage,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = LensColors.Cyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (state.customBitmap == null) "Escolher imagem" else "Escolher outra imagem",
                    color = LensColors.Text,
                    fontSize = 15.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(state: MangaUiState) {
    SectionCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp)
        ) {
            SectionTitle("Últimas traduções", modifier = Modifier.weight(1f))
            Text(text = "${state.historyList.size} no histórico", color = LensColors.TextMuted, fontSize = 13.sp)
        }
        state.historyList.take(3).forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = LensColors.Divider, modifier = Modifier.padding(horizontal = 18.dp))
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text(
                    text = item.originalText,
                    color = LensColors.TextMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.translatedText,
                    color = LensColors.Text,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    text = "${TranslationHistory.languageLabel(item.sourceLang)} para ${TranslationHistory.languageLabel(item.targetLang)}",
                    color = LensColors.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** "Japonês (日本語)" → "Japonês"; the native name is shown in the picker, not in the summary. */
private fun com.example.data.model.SupportedLanguage.shortLabel(): String =
    if (this == com.example.data.model.SupportedLanguage.AUTO) "Detectar idioma" else displayName.substringBefore(" (")

/** Largest test image kept in memory: 16 MP (64 MB) stays under Android's 100 MB limit for drawing a bitmap. */
private const val MAX_TEST_IMAGE_PIXELS = 16_000_000L
private const val MAX_TEST_IMAGE_SIDE = 16_384

/** Decodes a picked image, sampled down when it is very large (long webtoon strips, 50 MP photos). */
private fun decodeTestImage(context: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = ScreenMath.sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_TEST_IMAGE_PIXELS, MAX_TEST_IMAGE_SIDE)
    }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}

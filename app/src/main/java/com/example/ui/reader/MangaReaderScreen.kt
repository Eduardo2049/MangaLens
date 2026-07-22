package com.example.ui.reader

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.service.MangaOverlayService
import com.example.ui.components.FloatingControlBar
import com.example.ui.components.FloatingHistoryPanel
import com.example.ui.components.LanguagePickerDialog
import com.example.ui.components.MangaCanvasViewer
import com.example.ui.components.SettingsBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaReaderScreen(
    viewModel: MangaReaderViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val langSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Image Picker Launcher for importing custom Manga/Webtoon pages
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        viewModel.setCustomBitmap(bitmap)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val currentChapter = uiState.chapters.getOrNull(uiState.selectedChapterIndex)
    val currentPanel = currentChapter?.panels?.getOrNull(uiState.selectedPanelIndex)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color(0xFF0B0C10)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("manga_reader_screen")
        ) {
            // 1. Central Manga Canvas Viewer
            MangaCanvasViewer(
                panelData = currentPanel,
                customBitmap = uiState.customBitmap,
                overlays = uiState.activeOverlays,
                userPreferences = uiState.userPreferences,
                isProcessing = uiState.isProcessing,
                onOverlaySelected = { overlay ->
                    // Selected overlay
                },
                modifier = Modifier.fillMaxSize()
            )

            // 2. Discreet Top Bar (App Title + Chapter Selector + Import Image Button)
            Surface(
                shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                color = Color(0xDC0F172A),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color(0xFF6366F1), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Translate,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Manga Lens",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Tradução Visual em Tempo Real",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 10.sp
                                )
                            }
                        }

                        // Header Action Buttons Row
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Overlay over external readers button (Komikku/Mihon/Tachiyomi)
                            val isOverlayActive = uiState.isOverlayActive
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isOverlayActive) Color(0xFF059669) else Color(0xFF312E81),
                                modifier = Modifier
                                    .clickable {
                                        if (!Settings.canDrawOverlays(context)) {
                                            Toast.makeText(
                                                context,
                                                "Conceda a permissão 'Sobreposição a outros apps' para usar sobre Mihon/Komikku/Tachiyomi",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                        } else {
                                            val intent = Intent(context, MangaOverlayService::class.java)
                                            if (isOverlayActive) {
                                                context.stopService(intent)
                                                viewModel.setOverlayActive(false)
                                                Toast.makeText(context, "Bolha flutuante desativada", Toast.LENGTH_SHORT).show()
                                            } else {
                                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                    context.startForegroundService(intent)
                                                } else {
                                                    context.startService(intent)
                                                }
                                                viewModel.setOverlayActive(true)
                                                Toast.makeText(context, "Bolha ativada sobre Komikku/Mihon/Tachiyomi!", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                    .testTag("toggle_overlay_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Layers,
                                        contentDescription = "Bolha Flutuante",
                                        tint = if (isOverlayActive) Color(0xFF6EE7B7) else Color(0xFF818CF8),
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isOverlayActive) "Bolha On" else "Modo Sobreposição",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Import Custom Webtoon / Image Button
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF1E293B),
                                modifier = Modifier
                                    .clickable { imagePickerLauncher.launch("image/*") }
                                    .testTag("import_image_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AddPhotoAlternate,
                                        contentDescription = "Abrir Imagem",
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Imagem",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }



                    // Chapter Selector Pills (if not custom image)
                    if (uiState.customBitmap == null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            uiState.chapters.forEachIndexed { index, chapter ->
                                val isSelected = index == uiState.selectedChapterIndex
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) Color(0xFF4338CA) else Color(0xFF1E293B),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { viewModel.selectChapter(index) }
                                ) {
                                    Text(
                                        text = chapter.title.split(":")[0], // Short title
                                        color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(vertical = 6.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }

                        // Page pills if chapter has multiple panels
                        val numPanels = currentChapter?.panels?.size ?: 0
                        if (numPanels > 1) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                (0 until numPanels).forEach { pIdx ->
                                    val isPSelected = pIdx == uiState.selectedPanelIndex
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .padding(horizontal = 4.dp)
                                            .size(width = if (isPSelected) 32.dp else 20.dp, height = 20.dp)
                                            .background(
                                                if (isPSelected) Color(0xFF0EA5E9) else Color(0xFF334155),
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable { viewModel.selectPanel(pIdx) }
                                    ) {
                                        Text(
                                            text = "${pIdx + 1}",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Custom image active banner
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Lendo imagem importada da galeria",
                                color = Color(0xFFA5B4FC),
                                fontSize = 11.sp
                            )
                            Text(
                                text = "Voltar aos exemplos",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    viewModel.selectChapter(0)
                                }
                            )
                        }
                    }
                }
            }

            // 3. Floating History Panel Overlay (Non-fullscreen)
            FloatingHistoryPanel(
                historyList = uiState.historyList,
                isVisible = uiState.isHistoryVisible,
                onClose = { viewModel.closeHistory() },
                onClearHistory = { viewModel.clearHistory() },
                snackbarHostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 110.dp)
            )

            // 4. Floating Control Bar (Anchored at Bottom)
            FloatingControlBar(
                userPreferences = uiState.userPreferences,
                isProcessing = uiState.isProcessing,
                historyCount = uiState.historyList.size,
                onToggleTranslation = { enabled ->
                    viewModel.setTranslationEnabled(enabled)
                },
                onToggleMode = { mode ->
                    viewModel.setTranslationMode(mode)
                },
                onOpenLanguagePicker = { viewModel.openLanguagePicker() },
                onForceRefresh = { viewModel.triggerTranslation(forceManual = true) },
                onToggleHistory = { viewModel.toggleHistoryVisibility() },
                onOpenSettings = { viewModel.openSettings() },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // 5. Settings Bottom Sheet
            if (uiState.isSettingsOpen) {
                SettingsBottomSheet(
                    userPreferences = uiState.userPreferences,
                    sheetState = settingsSheetState,
                    onDismiss = { viewModel.closeSettings() },
                    onUpdateSourceLang = { lang -> viewModel.updateSourceLanguage(lang) },
                    onUpdateTargetLang = { lang -> viewModel.updateTargetLanguage(lang) },
                    onUpdateMode = { mode -> viewModel.setTranslationMode(mode) },
                    onUpdateOpacity = { opacity -> viewModel.updateOverlayOpacity(opacity) },
                    onUpdateFontSize = { sizeSp -> viewModel.updateOverlayFontSize(sizeSp) },
                    onUpdateBubbleStyle = { style -> viewModel.updateBubbleStyle(style) },
                    onUpdateReadingMode = { mode -> }
                )
            }

            // 6. Language Picker Dialog
            if (uiState.isLanguagePickerOpen) {
                LanguagePickerDialog(
                    userPreferences = uiState.userPreferences,
                    sheetState = langSheetState,
                    onDismiss = { viewModel.closeLanguagePicker() },
                    onSelectSource = { lang -> viewModel.updateSourceLanguage(lang) },
                    onSelectTarget = { lang -> viewModel.updateTargetLanguage(lang) }
                )
            }
        }
    }
}

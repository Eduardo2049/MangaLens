package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.UserPreferences
import com.example.data.model.SupportedLanguage
import com.example.data.model.TranslationMode

@Composable
fun FloatingControlBar(
    userPreferences: UserPreferences,
    isProcessing: Boolean,
    historyCount: Int,
    onToggleTranslation: (Boolean) -> Unit,
    onToggleMode: (TranslationMode) -> Unit,
    onOpenLanguagePicker: () -> Unit,
    onForceRefresh: () -> Unit,
    onToggleHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color(0xEE0F172A),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.horizontalGradient(
                listOf(Color(0xFF6366F1), Color(0xFF00E5FF))
            )
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("floating_control_bar")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Main Top Bar Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. Translation Toggle Switch
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("translation_toggle_row")
                ) {
                    Switch(
                        checked = userPreferences.isTranslationEnabled,
                        onCheckedChange = onToggleTranslation,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF6366F1),
                            uncheckedThumbColor = Color(0xFF94A3B8),
                            uncheckedTrackColor = Color(0xFF334155)
                        ),
                        modifier = Modifier
                            .scale(0.85f)
                            .testTag("translation_switch")
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (userPreferences.isTranslationEnabled) "Tradução On" else "Tradução Off",
                        color = if (userPreferences.isTranslationEnabled) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 2. Language Selector Pill
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1E293B),
                    modifier = Modifier
                        .clickable { onOpenLanguagePicker() }
                        .testTag("language_selector_pill")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "${userPreferences.sourceLanguage.flagEmoji} ➔ ${userPreferences.targetLanguage.flagEmoji}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }

                // 3. Dynamic / Manual Mode Pill
                val isDynamic = userPreferences.translationMode == TranslationMode.DYNAMIC
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isDynamic) Color(0xFF312E81) else Color(0xFF334155),
                    modifier = Modifier
                        .clickable {
                            val newMode = if (isDynamic) TranslationMode.MANUAL else TranslationMode.DYNAMIC
                            onToggleMode(newMode)
                        }
                        .testTag("mode_toggle_pill")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (isDynamic) Icons.Default.AutoAwesome else Icons.Default.TouchApp,
                            contentDescription = "Modo",
                            tint = if (isDynamic) Color(0xFF818CF8) else Color(0xFFCBD5E1),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isDynamic) "Auto" else "Manual",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                // 4. Manual Force Refresh Action Button
                Box {
                    IconButton(
                        onClick = onForceRefresh,
                        enabled = !isProcessing,
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF0EA5E9), CircleShape)
                            .testTag("force_refresh_button")
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Atualizar Tradução",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // 5. Floating History Panel Toggle Button
                Box {
                    IconButton(
                        onClick = onToggleHistory,
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF334155), CircleShape)
                            .testTag("history_toggle_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Histórico",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (historyCount > 0) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(16.dp)
                                .background(Color(0xFFEF4444), CircleShape)
                        ) {
                            Text(
                                text = "$historyCount",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // 6. Settings Sheet Button
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFF334155), CircleShape)
                        .testTag("settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Configurações",
                        tint = Color(0xFFCBD5E1),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Processing Status Banner (discreet feedback)
            AnimatedVisibility(
                visible = isProcessing,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .background(Color(0xFF1E1B4B), RoundedCornerShape(8.dp))
                        .padding(vertical = 4.dp, horizontal = 8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        color = Color(0xFF818CF8),
                        strokeWidth = 1.5.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Detectando OCR e traduzindo texto...",
                        color = Color(0xFFA5B4FC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

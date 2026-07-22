package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.UserPreferences
import com.example.data.model.TextOverlay
import com.example.data.samples.MangaPanelData
import kotlin.math.roundToInt

@Composable
fun MangaCanvasViewer(
    panelData: MangaPanelData?,
    customBitmap: Bitmap?,
    overlays: List<TextOverlay>,
    userPreferences: UserPreferences,
    isProcessing: Boolean,
    onOverlaySelected: (TextOverlay) -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var selectedOverlayId by remember { mutableStateOf<String?>(null) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.8f, 3.5f)
        offset = if (scale > 1.05f) offset + panChange else Offset.Zero
    }

    val clipboardManager = LocalClipboardManager.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF111118))
            .transformable(state = transformState)
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
            .testTag("manga_canvas_viewer"),
        contentAlignment = Alignment.Center
    ) {
        val containerWidthPx = constraints.maxWidth.toFloat()
        val containerHeightPx = constraints.maxHeight.toFloat()

        // Background Manga Illustration Canvas
        if (customBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = customBitmap.asImageBitmap(),
                contentDescription = "Manga Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
        } else {
            // Render Styled Vector Manga Art for Built-in Sample Panel
            MangaPanelArtworkCanvas(
                panelData = panelData,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Overlay Text Layer
        if (userPreferences.isTranslationEnabled && overlays.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                overlays.forEach { overlay ->
                    val isSelected = overlay.id == selectedOverlayId

                    val leftDp = (overlay.box.xMin / 100f * containerWidthPx) / LocalDensityWidth()
                    val topDp = (overlay.box.yMin / 100f * containerHeightPx) / LocalDensityHeight()
                    val widthDp = (overlay.box.widthPercent / 100f * containerWidthPx) / LocalDensityWidth()
                    val heightDp = (overlay.box.heightPercent / 100f * containerHeightPx) / LocalDensityHeight()

                    OverlayBubbleItem(
                        overlay = overlay,
                        userPreferences = userPreferences,
                        isSelected = isSelected,
                        leftDp = leftDp.coerceAtLeast(10f),
                        topDp = topDp.coerceAtLeast(10f),
                        widthDp = widthDp.coerceAtLeast(70f),
                        heightDp = heightDp.coerceAtLeast(35f),
                        onClick = {
                            selectedOverlayId = if (isSelected) null else overlay.id
                            onOverlaySelected(overlay)
                        },
                        onCopyText = {
                            clipboardManager.setText(AnnotatedString(overlay.translatedText))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayBubbleItem(
    overlay: TextOverlay,
    userPreferences: UserPreferences,
    isSelected: Boolean,
    leftDp: Float,
    topDp: Float,
    widthDp: Float,
    heightDp: Float,
    onClick: () -> Unit,
    onCopyText: () -> Unit
) {
    val alphaValue = userPreferences.overlayOpacity

    val bubbleBgColor = when (userPreferences.bubbleStyle) {
        "SOLID_HIGHLIGHT" -> Color(0xFF1E1B4B).copy(alpha = alphaValue)
        "SEMI_TRANSPARENT" -> Color(0xDD0F172A).copy(alpha = alphaValue)
        else -> Color.White.copy(alpha = alphaValue) // SPEECH_BUBBLE
    }

    val textColor = when (userPreferences.bubbleStyle) {
        "SOLID_HIGHLIGHT", "SEMI_TRANSPARENT" -> Color.White
        else -> Color(0xFF0F172A)
    }

    val fontSizeSp = userPreferences.overlayFontSizeSp.sp

    Box(
        modifier = Modifier
            .offset { IntOffset(leftDp.dp.roundToPx(), topDp.dp.roundToPx()) }
            .widthIn(min = widthDp.dp, max = (widthDp * 1.3f).coerceAtLeast(140f).dp)
            .clickable { onClick() }
            .testTag("overlay_bubble_${overlay.id}")
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = bubbleBgColor,
            shadowElevation = if (isSelected) 8.dp else 4.dp,
            border = if (isSelected) {
                androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF6366F1))
            } else {
                androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.6f))
            },
            modifier = Modifier.padding(2.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = overlay.translatedText,
                    color = textColor,
                    fontSize = fontSizeSp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = (fontSizeSp.value * 1.2f).sp,
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.SansSerif
                )

                // Quick copy popup bar when selected
                AnimatedVisibility(
                    visible = isSelected,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Orig: ${overlay.originalText}",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copiar",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { onCopyText() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaPanelArtworkCanvas(
    panelData: MangaPanelData?,
    modifier: Modifier = Modifier
) {
    val isJapanese = panelData?.title?.contains("Japonês") == true
    val isKorean = panelData?.title?.contains("Coreano") == true

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Dark Canvas Background
        drawRect(Color(0xFF12131A))

        // Action speed lines / energy rays effect
        val center = Offset(w * 0.5f, h * 0.45f)
        val numLines = 36
        for (i in 0 until numLines) {
            val angle = (i * 360f / numLines) * (Math.PI / 180.0)
            val endX = center.x + (w * 0.9f * Math.cos(angle)).toFloat()
            val endY = center.y + (h * 0.9f * Math.sin(angle)).toFloat()
            drawLine(
                color = Color.White.copy(alpha = 0.06f),
                start = center,
                end = Offset(endX, endY),
                strokeWidth = 3f
            )
        }

        // Panel Border Frames (Comic/Manga layout)
        val panelTop = h * 0.05f
        val panelBottom = h * 0.92f
        val panelLeft = w * 0.06f
        val panelRight = w * 0.94f

        drawRoundRect(
            color = Color.White,
            topLeft = Offset(panelLeft, panelTop),
            size = Size(panelRight - panelLeft, panelBottom - panelTop),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f, 16f),
            style = Stroke(width = 6f)
        )

        // Mid panel diagonal divider line
        drawLine(
            color = Color.White,
            start = Offset(panelLeft, h * 0.52f),
            end = Offset(panelRight, h * 0.48f),
            strokeWidth = 5f
        )

        // Manga Hero / Character Vector Silhouette
        val charPaintColor = if (isKorean) Color(0xFF6366F1).copy(alpha = 0.35f) else Color(0xFFA855F7).copy(alpha = 0.35f)
        drawCircle(
            color = charPaintColor,
            radius = w * 0.22f,
            center = Offset(w * 0.5f, h * 0.32f)
        )

        // Draw original Manga Speech Bubbles
        panelData?.sampleOverlays?.forEach { overlay ->
            val bLeft = (overlay.box.xMin / 100f) * w
            val bTop = (overlay.box.yMin / 100f) * h
            val bWidth = (overlay.box.widthPercent / 100f) * w
            val bHeight = (overlay.box.heightPercent / 100f) * h

            // White speech bubble outline
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(bLeft, bTop),
                size = Size(bWidth, bHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f, 24f)
            )

            drawRoundRect(
                color = Color.Black,
                topLeft = Offset(bLeft, bTop),
                size = Size(bWidth, bHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f, 24f),
                style = Stroke(width = 4f)
            )
        }
    }
}

@Composable
private fun LocalDensityWidth(): Float {
    return androidx.compose.ui.platform.LocalDensity.current.density
}

@Composable
private fun LocalDensityHeight(): Float {
    return androidx.compose.ui.platform.LocalDensity.current.density
}

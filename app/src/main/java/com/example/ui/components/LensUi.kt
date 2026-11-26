package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.LensColors

/*
 * Building blocks shared by the main screen and the settings, so both look and behave the same.
 * Every row lets its text wrap and gives the trailing element a fixed size: on narrow phones the old rows
 * squeezed buttons into vertical strips of letters.
 */

/** The app icon (same vector as the launcher), cropped to the adaptive icon's visible area. */
@Composable
fun LensMark(size: Dp, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
    ) {
        // Adaptive icon layers are 108dp with a 72dp visible zone: draw them 1.5x and let the clip crop.
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.requiredSize(size * 1.5f))
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(size * 1.5f))
    }
}

/** Rounded block that groups one topic. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    color: Color = LensColors.Surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(shape = RoundedCornerShape(20.dp), color = color, modifier = modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = LensColors.Text,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
    )
}

/** Small heading above a group of controls inside the settings. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier, supporting: String? = null) {
    Column(modifier = modifier.padding(bottom = 10.dp)) {
        Text(text = text, color = LensColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (supporting != null) {
            Text(text = supporting, color = LensColors.TextMuted, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/** Row that opens something: icon, title, current value and a chevron. */
@Composable
fun NavRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    valueColor: Color = LensColors.TextMuted
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = LensColors.Cyan, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = LensColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                text = value,
                color = valueColor,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = LensColors.TextMuted,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
fun RowDivider() {
    HorizontalDivider(color = LensColors.Divider, modifier = Modifier.padding(start = 56.dp))
}

/** Wraps [ChoiceRow]s so accessibility services announce them as one radio group. */
@Composable
fun ChoiceGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        content = content
    )
}

/** One option of a single choice: radio, title and a short explanation. */
@Composable
fun ChoiceRow(
    title: String,
    description: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Short status under the description, e.g. whether a provider already has a key. */
    status: String? = null,
    statusColor: Color = LensColors.TextMuted
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) LensColors.CyanContainer else LensColors.Surface,
        border = BorderStroke(1.dp, if (selected) LensColors.Cyan else Color.Transparent),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = LensColors.Cyan, unselectedColor = LensColors.TextMuted),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = LensColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (description != null) {
                    Text(text = description, color = LensColors.TextMuted, fontSize = 13.sp, lineHeight = 18.sp)
                }
                if (status != null) {
                    Text(
                        text = status,
                        color = statusColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

/** Two to four short options side by side (text size, card style, source/target tabs). */
@Composable
fun <T> SegmentedChoice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LensColors.Surface)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) LensColors.Cyan else Color.Transparent)
                    .selectable(selected = isSelected, onClick = { onSelect(value) }, role = Role.Tab)
                    .padding(horizontal = 6.dp, vertical = 10.dp)
            ) {
                Text(
                    text = label,
                    color = if (isSelected) LensColors.OnCyan else LensColors.Text,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }
    }
}

/** Something the user must fix before translating, with the action that fixes it. */
@Composable
fun NoticeRow(
    icon: ImageVector,
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = LensColors.WarningContainer,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onAction)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = null, tint = LensColors.Warning, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                color = LensColors.Text,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(text = actionLabel, color = LensColors.Warning, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Coloured dot used for on/off states. */
@Composable
fun StatusDot(on: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) LensColors.Success else LensColors.TextMuted)
    )
}

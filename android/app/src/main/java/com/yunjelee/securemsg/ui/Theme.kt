package com.yunjelee.securemsg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SecureMsg design tokens — light palette, mirroring the web app shell so the
 * phone and the browser are the same product (v0.11.1).
 *
 * Accent roles are deliberately split: `accent` is the blue used for text,
 * focus and progress, while `brandGradient`/`avatarGradient` are the indigo
 * FILLS (wordmark, avatar, own bubble). Every pair below was checked against
 * WCAG AA on the surface it actually sits on — a light background is far less
 * forgiving than the dark one this replaced, where near-anything read.
 */
object Sm {
    val bg = Color(0xFFF6F7FB)
    val surface = Color(0xFFFFFFFF)
    val surfaceAlt = Color(0xFFF0F1F5)
    /** Hairlines and decorative separators only — too faint to bound a control. */
    val border = Color(0xFFE2E8F0)
    /** Interactive boundaries (inputs, ghost buttons): WCAG 1.4.11 needs 3:1. */
    val borderStrong = Color(0xFF767E8C)
    val borderSoft = Color(0xFFEEF0F4)

    val text1 = Color(0xFF111827)
    val text2 = Color(0xFF374151)
    val text3 = Color(0xFF475569)
    val text4 = Color(0xFF6B7280)

    /** Primary accent (blue-600). Named `teal` for source compatibility. */
    val teal = Color(0xFF2563EB)
    val sky = Color(0xFF4F46E5)
    val cyan = Color(0xFF2563EB)
    val accentDeep = Color(0xFF1D4ED8)
    val success = Color(0xFF046B4E)
    val danger = Color(0xFFB91C1C)
    val warning = Color(0xFF92400E)
    /** Label on any accent-filled surface. */
    val onAccent = Color(0xFFFFFFFF)
    val progressTrack = Color(0xFFDBEAFE)

    val gradient = Brush.linearGradient(listOf(teal, teal))
    val gradientSoft = Brush.linearGradient(
        listOf(Color(0xFFEFF6FF), Color(0xFFEFF6FF)),
    )
    val avatarGradient = Brush.linearGradient(
        listOf(Color(0xFF5B4BE7), Color(0xFF4338CA)),
    )
    /** Wordmark only — the one place a visible gradient survives. */
    val brandGradient = Brush.linearGradient(
        listOf(Color(0xFF4F46E5), Color(0xFF5B52E8)),
    )
}

/** Rounded card used for every settings/composer section. */
@Composable
fun SmCard(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(10.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardShape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Shadow BEFORE clip, or it gets clipped away. The dark theme
            // separated the card by being lighter than the page; white on
            // #F6F7FB is a 5% luminance step, so it needs the lift instead.
            .shadow(
                elevation = 2.dp,
                shape = cardShape,
                ambientColor = Color(0x1A0F172A),
                spotColor = Color(0x1A0F172A),
            )
            .clip(cardShape)
            .background(Sm.surface)
            .border(1.dp, Sm.border, cardShape)
            .padding(16.dp),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = Sm.text1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun Caption(text: String) {
    Text(text, color = Sm.text4, fontSize = 11.sp, lineHeight = 15.sp)
}

/** Primary action — teal→sky gradient fill, dark label. */
@Composable
fun SmGradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                if (enabled) Sm.gradient
                else Brush.linearGradient(listOf(Sm.surfaceAlt, Sm.surfaceAlt)),
            )
            .border(
                1.dp,
                if (enabled) Color.Transparent else Color(0xFFCBD5E1),
                shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            // White on blue-600 is 5.17:1; the old near-black was tuned for
            // the teal fill and drops to 3.09:1 here.
            color = if (enabled) Sm.onAccent else Sm.text4,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
    }
}

/** Secondary action — quiet filled surface with border. */
@Composable
fun SmGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = Sm.text2,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Sm.surfaceAlt)
            // borderStrong, not border: on a light ground the hairline is
            // 1.15:1 and the whole button disappears.
            .border(1.dp, Sm.borderStrong, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SmTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Sm.cyan,
            unfocusedBorderColor = Sm.borderStrong,
            focusedLabelColor = Sm.cyan,
            unfocusedLabelColor = Sm.text4,
            cursorColor = Sm.cyan,
            focusedTextColor = Sm.text1,
            unfocusedTextColor = Sm.text1,
        ),
        modifier = modifier,
    )
}

/** Gradient circle avatar with the contact initial (web sidebar style). */
@Composable
fun SmAvatar(name: String, size: Int = 40) {
    val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Sm.avatarGradient),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size / 2.6).sp)
    }
}

/** Pill-style tab switcher. */
@Composable
fun SmTabs(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Sm.surface)
            .border(1.dp, Sm.border, RoundedCornerShape(14.dp))
            .padding(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val isSel = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSel) Sm.gradientSoft else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (isSel) Sm.cyan else Sm.text3,
                    fontSize = 13.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Small status pill with a colored dot. */
@Composable
fun SmChip(text: String, color: Color, label: Color = color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(text, color = label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** Chat bubble — own messages right/accent, incoming left/surface. */
@Composable
fun ChatBubble(mine: Boolean, blocked: Boolean, text: String) {
    val shape = if (mine) {
        RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)
    } else {
        RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(
                    when {
                        blocked -> Sm.surface
                        mine -> Color(0xFF4F46E5)
                        else -> Sm.surface
                    },
                )
                // An incoming bubble is white on a near-white ground (1.05:1),
                // so the outline is what makes it a bubble at all.
                .border(
                    1.dp,
                    if (mine) Color.Transparent else Color(0xFFE9EBF0),
                    shape,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text,
                color = when {
                    blocked -> Sm.text4
                    mine -> Sm.onAccent
                    else -> Sm.text2
                },
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

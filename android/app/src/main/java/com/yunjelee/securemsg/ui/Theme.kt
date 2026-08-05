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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SecureMsg design tokens — the same brand as the web app (v0.4.4 redesign):
 * deep night-navy surfaces, teal→sky gradient accents, 4-step text hierarchy.
 */
object Sm {
    val bg = Color(0xFF0A0F16)
    val surface = Color(0xFF101827)
    val surfaceAlt = Color(0xFF182234)
    val border = Color(0xFF1E293B)
    val borderSoft = Color(0x551E293B)

    val text1 = Color(0xFFF1F5F9)
    val text2 = Color(0xFFCBD5E1)
    val text3 = Color(0xFF94A3B8)
    val text4 = Color(0xFF64748B)

    val teal = Color(0xFF2DD4BF)
    val sky = Color(0xFF38BDF8)
    val cyan = Color(0xFF22D3EE)
    val accentDeep = Color(0xFF0E7490)
    val danger = Color(0xFFF87171)
    val warning = Color(0xFFFBBF24)

    val gradient = Brush.linearGradient(listOf(teal, sky))
    val gradientSoft = Brush.linearGradient(listOf(Color(0x2E2DD4BF), Color(0x2E38BDF8)))
    val avatarGradient = Brush.linearGradient(listOf(Color(0xFF0D9488), Color(0xFF0284C7)))
}

/** Rounded card used for every settings/composer section. */
@Composable
fun SmCard(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(10.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Sm.surface)
            .border(1.dp, Sm.border, RoundedCornerShape(16.dp))
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
                else Brush.linearGradient(listOf(Sm.border, Sm.border)),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) Color(0xFF052530) else Sm.text4,
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
            .border(1.dp, Sm.border, shape)
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
            unfocusedBorderColor = Sm.border,
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
fun SmChip(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
                        mine -> Color(0xFF115E59)
                        else -> Sm.surfaceAlt
                    },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text,
                color = when {
                    blocked -> Sm.text4
                    mine -> Color(0xFFCCFBF1)
                    else -> Sm.text2
                },
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

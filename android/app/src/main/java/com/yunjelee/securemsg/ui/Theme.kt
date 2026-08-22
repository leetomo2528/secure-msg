package com.yunjelee.securemsg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
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
    /** Incoming bubble body — a touch bluer than `text1` so it sits on the tinted ground. */
    val bubbleText = Color(0xFF172033)
    /**
     * Base of every shadow and hairline (slate-900). Never painted solid —
     * always `ink.copy(alpha = …)`, so the lift reads as the page darkening.
     */
    val ink = Color(0xFF0F172A)

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
    /** Amber star — favourites header and the avatar badge. */
    val star = Color(0xFFF59E0B)
    /** Indigo tint behind a `sky` icon (message button, attach, device icon box). */
    val accentTint = Color(0xFFECEEFF)
    /** Chevron-right stroke (slate-400): an affordance, not text, so it may sit below AA. */
    val chevron = Color(0xFF94A3B8)

    val gradient = Brush.linearGradient(listOf(teal, teal))
    val gradientSoft = Brush.linearGradient(
        listOf(Color(0xFFEFF6FF), Color(0xFFEFF6FF)),
    )
    val avatarGradient = Brush.linearGradient(
        listOf(Color(0xFF5B4BE7), Color(0xFF4338CA)),
    )
    /** Wordmark, FAB and send button — the places a visible gradient survives. */
    val brandGradient = Brush.linearGradient(
        listOf(Color(0xFF4F46E5), Color(0xFF5B52E8)),
    )
}

/** Full-round pill used by chips, search and the composer field. */
private val Pill = RoundedCornerShape(999.dp)

/** Shadow tint for every neutral lift; indigo surfaces use `Sm.sky` instead. */
private val InkShadow: Color get() = Sm.ink.copy(alpha = 0.10f)

/**
 * Lift + surface + hairline shared by every card-shaped container. Shadow
 * goes BEFORE clip, or it gets clipped away. The dark theme separated the
 * card by being lighter than the page; white on #F6F7FB is a 5% luminance
 * step, so it needs the lift instead.
 */
private fun Modifier.cardSurface(shape: Shape): Modifier = this
    .shadow(elevation = 2.dp, shape = shape, ambientColor = InkShadow, spotColor = InkShadow)
    .clip(shape)
    .background(Sm.surface)
    .border(1.dp, Sm.border, shape)

/** 1dp separator. A plain Box so the colour is exactly the token, no theme alpha. */
@Composable
private fun Hairline(color: Color) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

/** Digits line up in phone numbers and timestamps; inherits everything else. */
@Composable
private fun tabularFigures(): TextStyle = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")

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
            .cardSurface(RoundedCornerShape(16.dp))
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
    leadingIcon: SmIconKind? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    // White on blue-600 is 5.17:1; the old near-black was tuned for the teal
    // fill and drops to 3.09:1 here.
    val label = if (enabled) Sm.onAccent else Sm.text4
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                if (enabled) Sm.gradient
                else Brush.linearGradient(listOf(Sm.surfaceAlt, Sm.surfaceAlt)),
            )
            .border(
                1.dp,
                if (enabled) Color.Transparent else Sm.border,
                shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) SmIcon(leadingIcon, size = 16.dp, tint = label)
            Text(text, color = label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
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

/**
 * Gradient circle avatar with the contact initial (web sidebar style).
 * [personIcon] swaps the initial for a silhouette (unknown number);
 * [starBadge] hangs an 18dp favourite star off the bottom-right edge — it
 * overflows the [size] box by 3dp, so give the row at least that much padding.
 */
@Composable
fun SmAvatar(
    name: String,
    size: Int = 40,
    starBadge: Boolean = false,
    personIcon: Boolean = false,
) {
    val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
    Box(Modifier.size(size.dp)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(Sm.avatarGradient),
            contentAlignment = Alignment.Center,
        ) {
            if (personIcon) {
                // 20dp on the 44dp list avatar; scales with the circle.
                SmIcon(SmIconKind.Person, size = (size * 0.45f).dp, tint = Sm.onAccent, strokeWidth = 1.9.dp)
            } else {
                // 44→16, 40→15, 34→13: the artboard sizes, floored.
                Text(
                    initial,
                    color = Sm.onAccent,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size / 2.6).toInt().sp,
                )
            }
        }
        if (starBadge) {
            val badgeShadow = Sm.ink.copy(alpha = 0.18f)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
                    .offset(x = 3.dp, y = 3.dp)
                    .shadow(1.dp, CircleShape, ambientColor = badgeShadow, spotColor = badgeShadow)
                    .background(Sm.surface),
                contentAlignment = Alignment.Center,
            ) {
                SmIcon(SmIconKind.Star, size = 12.dp, tint = Sm.star, strokeWidth = 1.5.dp, fill = true)
            }
        }
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
            .clip(Pill)
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(text, color = label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** [SmChip] at card-title scale — sits beside a 14sp [SectionTitle]. */
@Composable
fun SmChipSmall(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(Pill)
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(color))
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------------------
// Icons
// ---------------------------------------------------------------------------

/**
 * Every line icon the shell needs. Kinds the core material set already ships
 * render that glyph; the rest are stroked from the artboard paths so nothing
 * pulls in `material-icons-extended`.
 */
enum class SmIconKind {
    Bubble, Users, Gear, Search, Pencil, Plus, ChevronRight, ChevronLeft,
    MoreVertical, Paperclip, ArrowUp, Star, Person, CircleSlash, Qr, Smartphone,
}

/** Core material glyph, or null when the kind is drawn from [pathData]. */
private fun SmIconKind.materialVector(fill: Boolean): ImageVector? = when (this) {
    SmIconKind.Gear -> Icons.Outlined.Settings
    SmIconKind.Search -> Icons.Outlined.Search
    SmIconKind.Pencil -> Icons.Outlined.Edit
    SmIconKind.Plus -> Icons.Outlined.Add
    SmIconKind.MoreVertical -> Icons.Outlined.MoreVert
    SmIconKind.Star -> if (fill) Icons.Filled.Star else Icons.Outlined.Star
    SmIconKind.Person -> if (fill) Icons.Filled.Person else Icons.Outlined.Person
    else -> null
}

/**
 * SVG path data on the 24×24 artboard grid, copied verbatim from the design
 * files (circles/rects rewritten as arcs). Empty for kinds that have a
 * material glyph.
 */
private val SmIconKind.pathData: String
    get() = when (this) {
        SmIconKind.Bubble ->
            "M4 6.5A2.5 2.5 0 0 1 6.5 4h11A2.5 2.5 0 0 1 20 6.5v8a2.5 2.5 0 0 1-2.5 2.5H9l-4.2 3.2V17H6.5A2.5 2.5 0 0 1 4 14.5v-8z"
        SmIconKind.Users ->
            "M5.8 8.5a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0-6.4 0" +
                "M3.5 19c0-3 2.5-5 5.5-5s5.5 2 5.5 5" +
                "M16 4.5a3.2 3.2 0 0 1 0 6.3" +
                "M17.5 14.3c2 .6 3 2.3 3 4.7"
        SmIconKind.ChevronRight -> "M9 5l7 7-7 7"
        SmIconKind.ChevronLeft -> "M15 5l-7 7 7 7"
        SmIconKind.Paperclip ->
            "M20 11.5l-8.2 8.2a5.5 5.5 0 0 1-7.8-7.8l8.5-8.5a3.7 3.7 0 0 1 5.2 5.2l-8.5 8.5a1.8 1.8 0 0 1-2.6-2.6l7.8-7.8"
        SmIconKind.ArrowUp -> "M12 19V5M6 11l6-6 6 6"
        SmIconKind.CircleSlash -> "M4 12a8 8 0 1 0 16 0a8 8 0 1 0-16 0M6.5 6.5l11 11"
        SmIconKind.Qr ->
            "M4.5 3h4A1.5 1.5 0 0 1 10 4.5v4A1.5 1.5 0 0 1 8.5 10h-4A1.5 1.5 0 0 1 3 8.5v-4A1.5 1.5 0 0 1 4.5 3z" +
                "M15.5 3h4A1.5 1.5 0 0 1 21 4.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 14 8.5v-4A1.5 1.5 0 0 1 15.5 3z" +
                "M4.5 14h4a1.5 1.5 0 0 1 1.5 1.5v4A1.5 1.5 0 0 1 8.5 21h-4A1.5 1.5 0 0 1 3 19.5v-4A1.5 1.5 0 0 1 4.5 14z" +
                "M14 14h3v3h-3zM20 14v3M17 20h4"
        SmIconKind.Smartphone ->
            "M9 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM11 17h2"
        else -> ""
    }

/**
 * Line icon at [size]. [strokeWidth] and [fill] only affect the stroked kinds;
 * material glyphs are fixed-weight paths (Star/Person pick Filled vs Outlined
 * from [fill]). Decorative by default — add a `contentDescription` via
 * [modifier] when the icon is the only label of a control.
 */
@Composable
fun SmIcon(
    kind: SmIconKind,
    size: Dp,
    tint: Color,
    strokeWidth: Dp = 1.8.dp,
    fill: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val vector = kind.materialVector(fill)
    if (vector != null) {
        Icon(vector, contentDescription = null, tint = tint, modifier = modifier.size(size))
        return
    }
    val path = remember(kind) { PathParser().parsePathString(kind.pathData).toPath() }
    Canvas(modifier.size(size)) {
        // Scale the 24-unit grid to the box, but divide the stroke back out so
        // it stays at its dp width instead of growing with the glyph.
        val unit = this.size.minDimension / 24f
        val stroke = Stroke(
            width = strokeWidth.toPx() / unit,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        scale(unit, pivot = Offset.Zero) {
            if (fill) drawPath(path, tint)
            drawPath(path, tint, style = stroke)
        }
    }
}

/**
 * Icon in a filled [shape] (circle by default). Covers the contact message
 * button, the composer attach button, and the 32dp device box in settings.
 */
@Composable
fun SmIconCircle(
    kind: SmIconKind,
    size: Dp,
    tint: Color,
    background: Color,
    iconSize: Dp,
    strokeWidth: Dp = 1.8.dp,
    onClick: (() -> Unit)? = null,
    shape: Shape = CircleShape,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(background)
            .then(
                if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        SmIcon(kind, size = iconSize, tint = tint, strokeWidth = strokeWidth)
    }
}

// ---------------------------------------------------------------------------
// Shell: navigation, search, FAB
// ---------------------------------------------------------------------------

data class SmNavItem(val label: String, val icon: SmIconKind)

/**
 * Bottom navigation. Runs under the gesture/navigation bar and pads that
 * inset INSIDE its surface, so the white continues to the screen edge the way
 * the artboards draw it. The screen root must therefore leave the bottom
 * inset alone (it consumes only the IME, which this then sees as zero).
 */
@Composable
fun SmBottomNav(
    items: List<SmNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Hairline(Sm.ink.copy(alpha = 0.07f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Sm.surface.copy(alpha = 0.94f))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            items.forEachIndexed { i, item ->
                val active = i == selected
                val tint = if (active) Sm.sky else Sm.text4
                Column(
                    modifier = Modifier
                        .widthIn(min = 64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .selectable(selected = active, role = Role.Tab) { onSelect(i) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SmIcon(item.icon, size = 22.dp, tint = tint, strokeWidth = 1.8.dp)
                    Text(
                        item.label,
                        color = tint,
                        fontSize = 10.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** Search field as a quiet pill. Clears with the trailing × like the old field's "지우기". */
@Composable
fun SmSearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Pill)
            .background(Sm.surfaceAlt)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        SmIcon(SmIconKind.Search, size = 17.dp, tint = Sm.text3)
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = Sm.text1, fontSize = 13.sp),
            cursorBrush = SolidColor(Sm.teal),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(placeholder, color = Sm.text3, fontSize = 13.sp)
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "지우기",
                    tint = Sm.text4,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Compose-new floating button. Position it from the caller (20dp off the corner). */
@Composable
fun SmFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val glow = Sm.sky.copy(alpha = 0.28f)
    Box(
        modifier = modifier
            .size(56.dp)
            .shadow(8.dp, CircleShape, ambientColor = glow, spotColor = glow)
            .background(Sm.brandGradient)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "새 메시지" },
        contentAlignment = Alignment.Center,
    ) {
        SmIcon(SmIconKind.Pencil, size = 22.dp, tint = Sm.onAccent, strokeWidth = 2.dp)
    }
}

// ---------------------------------------------------------------------------
// List rows
// ---------------------------------------------------------------------------

/**
 * Conversation list row. Unread rows lift onto a translucent card and bold
 * the name; [unreadCount] fills the badge and is only read while [unread].
 */
@Composable
fun SmConversationRow(
    name: String,
    subtitle: String,
    time: String,
    unread: Boolean,
    unreadCount: Int,
    showPersonIcon: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (unread) {
                    Modifier
                        .shadow(2.dp, shape, ambientColor = InkShadow, spotColor = InkShadow)
                        .background(Sm.surface.copy(alpha = 0.92f))
                } else {
                    Modifier.clip(shape)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmAvatar(name, size = 44, personIcon = showPersonIcon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    name,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                    color = Sm.text1,
                    fontSize = 14.sp,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    time,
                    modifier = Modifier.alignByBaseline(),
                    color = Sm.text4,
                    fontSize = 10.sp,
                    maxLines = 1,
                    softWrap = false,
                    style = tabularFigures(),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    subtitle,
                    modifier = Modifier.weight(1f),
                    color = if (unread) Sm.text3 else Sm.text4,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (unread && unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .height(19.dp)
                            .widthIn(min = 19.dp)
                            .clip(Pill)
                            .background(Sm.sky)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            unreadCount.toString(),
                            color = Sm.onAccent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Contact row. Tap opens/starts the thread, long-press toggles favourite,
 * the trailing bubble is the explicit "message" affordance — indigo when a
 * thread already exists, grey otherwise (the grey state is inferred; no
 * artboard draws it).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SmContactRow(
    name: String,
    phone: String,
    favorite: Boolean,
    hasThread: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMessageClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmAvatar(name, size = 40, starBadge = favorite)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                name,
                color = Sm.text1,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(phone, color = Sm.text4, fontSize = 11.sp, style = tabularFigures())
        }
        SmIconCircle(
            kind = SmIconKind.Bubble,
            size = 32.dp,
            tint = if (hasThread) Sm.sky else Sm.text4,
            background = if (hasThread) Sm.accentTint else Sm.surfaceAlt,
            iconSize = 16.dp,
            strokeWidth = 1.8.dp,
            onClick = onMessageClick,
        )
    }
}

/** "번호로 새 문자" entry card at the top of the contacts list. */
@Composable
fun SmEntryCard(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cardSurface(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(Sm.brandGradient),
            contentAlignment = Alignment.Center,
        ) {
            SmIcon(SmIconKind.Plus, size = 18.dp, tint = Sm.onAccent, strokeWidth = 2.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Sm.text1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Sm.text4, fontSize = 11.sp)
        }
        SmIcon(SmIconKind.ChevronRight, size = 14.dp, tint = Sm.chevron, strokeWidth = 2.dp)
    }
}

/** Contacts group header (즐겨찾기 / 초성). */
@Composable
fun SmSectionHeader(text: String, leadingStar: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingStar) SmIcon(SmIconKind.Star, size = 12.dp, tint = Sm.star, fill = true)
        Text(
            text,
            color = Sm.text4,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.44.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// Settings primitives
// ---------------------------------------------------------------------------

/** [SmCard] variant that holds [SmListRow]s edge to edge (4dp vertical inset). */
@Composable
fun SmListRowCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .cardSurface(RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        content = content,
    )
}

/** One tappable row inside [SmListRowCard]: label, optional value, chevron. */
@Composable
fun SmListRow(
    label: String,
    value: String?,
    onClick: () -> Unit,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), color = Sm.text1, fontSize = 13.sp)
            if (value != null) Text(value, color = Sm.text4, fontSize = 12.sp, maxLines = 1)
            SmIcon(SmIconKind.ChevronRight, size = 14.dp, tint = Sm.chevron, strokeWidth = 2.dp)
        }
        if (showDivider) Hairline(Sm.borderSoft)
    }
}

/** Pending-approval inset inside the 기기 보안 card. */
@Composable
fun SmInsetNotice(title: String, subtitle: String) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Sm.teal.copy(alpha = 0.08f))
            .border(1.dp, Sm.teal, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, color = Sm.accentDeep, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Sm.text3, fontSize = 11.sp)
    }
}

// ---------------------------------------------------------------------------
// Chat
// ---------------------------------------------------------------------------

/** Centred day separator in the message list ("오늘", "어제", M/d). */
@Composable
fun SmDatePill(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text,
            modifier = Modifier
                .clip(Pill)
                .background(Sm.surface.copy(alpha = 0.76f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            color = Sm.text4,
            fontSize = 10.sp,
        )
    }
}

/** Stand-in for a blocked message body. [ChatBubble] centres it; bare use is left-aligned. */
@Composable
fun SmBlockedChip(text: String) {
    Row(
        modifier = Modifier
            .clip(Pill)
            .background(Sm.ink.copy(alpha = 0.03f))
            .border(1.dp, Sm.ink.copy(alpha = 0.05f), Pill)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SmIcon(SmIconKind.CircleSlash, size = 12.dp, tint = Sm.text3, strokeWidth = 2.dp)
        Text(text, color = Sm.text3, fontSize = 10.sp)
    }
}

/**
 * Conversation header: back, avatar, name/subtitle, search, optional overflow.
 * First element of the chat screen: it runs under the status bar and pads
 * that inset inside its surface (the artboard's 58dp = inset + 14dp).
 */
@Composable
fun SmChatHeader(
    name: String,
    subtitle: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onMore: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(2.dp, ambientColor = InkShadow, spotColor = InkShadow)
            .background(Sm.surface.copy(alpha = 0.88f))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.semantics { contentDescription = "뒤로" }) {
                SmIconCircle(
                    kind = SmIconKind.ChevronLeft,
                    size = 36.dp,
                    tint = Sm.text3,
                    background = Color.Transparent,
                    iconSize = 20.dp,
                    strokeWidth = 2.dp,
                    onClick = onBack,
                )
            }
            SmAvatar(name, size = 34)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    name,
                    color = Sm.text1,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    color = Sm.text4,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = tabularFigures(),
                )
            }
            Box(Modifier.semantics { contentDescription = "메시지 검색" }) {
                SmIconCircle(
                    kind = SmIconKind.Search,
                    size = 36.dp,
                    tint = Sm.text3,
                    background = Color.Transparent,
                    iconSize = 18.dp,
                    onClick = onSearch,
                )
            }
            if (onMore != null) {
                Box(Modifier.semantics { contentDescription = "더보기" }) {
                    SmIconCircle(
                        kind = SmIconKind.MoreVertical,
                        size = 36.dp,
                        tint = Sm.text3,
                        background = Color.Transparent,
                        iconSize = 18.dp,
                        strokeWidth = 2.dp,
                        onClick = onMore,
                    )
                }
            }
        }
        Hairline(Sm.ink.copy(alpha = 0.08f))
    }
}

/**
 * Message composer. Send is live only when [canSend] (bridge/permissions) and
 * the text is non-blank; while [sending] the button keeps its filled look but
 * swaps the arrow for a spinner and ignores taps. [onAttach] null hides the
 * paperclip — there is no attachment path yet. Sits at the very bottom of
 * the chat screen, so like [SmBottomNav] it pads the navigation-bar inset
 * inside its own surface; the keyboard is the screen root's inset to apply.
 */
@Composable
fun SmComposer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    canSend: Boolean,
    sending: Boolean,
    onSend: () -> Unit,
    onAttach: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ready = canSend && value.isNotBlank()
    val filled = ready || sending
    val glow = Sm.sky.copy(alpha = 0.22f)
    // The mock's upward shadow has no elevation equivalent; the hairline
    // does the separating here.
    Column(
        modifier
            .fillMaxWidth()
            .background(Sm.surface.copy(alpha = 0.88f))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
    ) {
        Hairline(Sm.ink.copy(alpha = 0.08f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onAttach != null) {
                Box(Modifier.semantics { contentDescription = "첨부" }) {
                    SmIconCircle(
                        kind = SmIconKind.Paperclip,
                        size = 40.dp,
                        tint = Sm.sky,
                        background = Sm.accentTint,
                        iconSize = 18.dp,
                        strokeWidth = 1.7.dp,
                        onClick = onAttach,
                    )
                }
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                maxLines = 5,
                textStyle = LocalTextStyle.current.copy(color = Sm.text1, fontSize = 14.sp, lineHeight = 20.sp),
                cursorBrush = SolidColor(Sm.teal),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .clip(Pill)
                            .background(Sm.surfaceAlt)
                            .border(1.dp, Sm.ink.copy(alpha = 0.08f), Pill)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty()) Text(placeholder, color = Sm.text3, fontSize = 13.sp)
                        inner()
                    }
                },
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .then(
                        if (filled) Modifier.shadow(8.dp, CircleShape, ambientColor = glow, spotColor = glow)
                        else Modifier.clip(CircleShape),
                    )
                    .background(
                        if (filled) Sm.brandGradient
                        else Brush.linearGradient(listOf(Sm.border, Sm.border)),
                    )
                    .clickable(enabled = ready && !sending, role = Role.Button, onClick = onSend)
                    .semantics { contentDescription = "보내기" },
                contentAlignment = Alignment.Center,
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Sm.onAccent,
                        strokeWidth = 2.dp,
                    )
                } else {
                    SmIcon(
                        SmIconKind.ArrowUp,
                        size = 16.dp,
                        tint = if (ready) Sm.onAccent else Sm.text4,
                        strokeWidth = 2.2.dp,
                    )
                }
            }
        }
    }
}

/**
 * Chat bubble — own messages right/indigo, incoming left/surface. [statusLine]
 * (time + carrier state) sits inside the bubble under the body; [blocked]
 * replaces the whole bubble with a centred [SmBlockedChip] showing [text].
 */
@Composable
fun ChatBubble(mine: Boolean, blocked: Boolean, text: String, statusLine: String? = null) {
    if (blocked) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SmBlockedChip(text)
        }
        return
    }
    val shape = if (mine) {
        RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
    }
    val outgoingShadow = Sm.sky.copy(alpha = 0.25f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        // A 78%-wide box with the bubble wrapping inside it gives "max width
        // 78%" without BoxWithConstraints.
        Box(
            modifier = Modifier.fillMaxWidth(0.78f),
            contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Column(
                modifier = Modifier
                    .then(
                        if (mine) {
                            Modifier
                                .shadow(6.dp, shape, ambientColor = outgoingShadow, spotColor = outgoingShadow)
                                .background(Sm.avatarGradient)
                        } else {
                            // An incoming bubble is white on a near-white
                            // ground (1.05:1), so the outline is what makes it
                            // a bubble at all.
                            Modifier
                                .shadow(3.dp, shape, ambientColor = InkShadow, spotColor = InkShadow)
                                .background(Sm.surface.copy(alpha = 0.92f))
                                .border(1.dp, Sm.ink.copy(alpha = 0.055f), shape)
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text,
                    color = if (mine) Sm.onAccent else Sm.bubbleText,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
                if (statusLine != null) {
                    Text(
                        statusLine,
                        color = if (mine) Sm.onAccent.copy(alpha = 0.82f) else Sm.text4,
                        fontSize = 10.sp,
                        style = tabularFigures(),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Design-time smoke test
// ---------------------------------------------------------------------------

/** Every shell primitive on one canvas, so a broken draw shows up in the IDE. */
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SmPrimitivesPreview() {
    Column(
        Modifier.background(Sm.bg).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SmSearchPill(query = "", onQueryChange = {}, placeholder = "대화·메시지 검색")
        SmConversationRow(
            name = "김민준", subtitle = "010-1234-5678", time = "오후 8:32",
            unread = true, unreadCount = 1, showPersonIcon = false, onClick = {},
        )
        SmConversationRow(
            name = "010-9999-0000", subtitle = "SMS", time = "어제",
            unread = false, unreadCount = 0, showPersonIcon = true, onClick = {},
        )
        SmEntryCard("번호로 새 문자", "연락처에 없는 번호로 바로 보내기", onClick = {})
        SmSectionHeader("즐겨찾기", leadingStar = true)
        SmContactRow(
            name = "이서연", phone = "010-2222-3333", favorite = true, hasThread = true,
            onClick = {}, onLongClick = {}, onMessageClick = {},
        )
        SmContactRow(
            name = "Alex", phone = "010-4444-5555", favorite = false, hasThread = false,
            onClick = {}, onLongClick = {}, onMessageClick = {},
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmChipSmall("검증된 계정", Sm.success)
            SmChip("브리지 사용 준비됨", Sm.success)
        }
        SmInsetNotice("새 기기 승인 요청 1건", "웹 · device-mf5e · 방금 전")
        SmGradientButton(
            "QR 스캔으로 승인", onClick = {}, modifier = Modifier.fillMaxWidth(),
            leadingIcon = SmIconKind.Qr,
        )
        SmListRowCard {
            SmListRow("격리된 스팸", "7건", onClick = {})
            SmListRow("발신번호 차단", "4개", onClick = {})
            SmListRow("앱 업데이트", "v0.11.1 · 최신", onClick = {}, showDivider = false)
        }
        SmChatHeader("김민준", "SMS · 010-1234-5678", onBack = {}, onSearch = {}, onMore = {})
        SmDatePill("오늘")
        ChatBubble(mine = false, blocked = false, text = "안녕하세요", statusLine = "오후 8:31")
        ChatBubble(mine = true, blocked = false, text = "네, 확인했습니다.", statusLine = "오후 8:32 · 통신사 접수")
        ChatBubble(mine = false, blocked = true, text = "차단된 메시지")
        SmComposer(
            value = "", onValueChange = {}, placeholder = "메시지 입력",
            canSend = true, sending = false, onSend = {}, onAttach = {},
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmFab(onClick = {})
            SmIconCircle(
                kind = SmIconKind.Smartphone, size = 32.dp, tint = Sm.sky,
                background = Sm.accentTint, iconSize = 16.dp, shape = RoundedCornerShape(10.dp),
            )
        }
        SmIconKind.entries.chunked(8).forEach { rowKinds ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowKinds.forEach { SmIcon(it, size = 18.dp, tint = Sm.text3) }
            }
        }
        SmBottomNav(
            items = listOf(
                SmNavItem("메시지", SmIconKind.Bubble),
                SmNavItem("연락처", SmIconKind.Users),
                SmNavItem("설정", SmIconKind.Gear),
            ),
            selected = 0,
            onSelect = {},
        )
    }
}

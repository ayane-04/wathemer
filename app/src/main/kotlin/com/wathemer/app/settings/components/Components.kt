package com.wathemer.app.settings.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wathemer.app.util.RestartWhatsApp

/* ── Shared design tokens (must match the locked design system) ─────────── */

object Palette {
    val Bg = Color(0xFF0A0A0A)
    val Surface = Color(0xFF141414)
    val SurfaceElev = Color(0xFF111111)  // very subtle lift over Bg, used for inset surfaces
    val Rule = Color.White.copy(alpha = 0.09f)
    val RuleStrong = Color.White.copy(alpha = 0.14f)
    val Fg = Color.White
    val FgMuted = Color.White.copy(alpha = 0.55f)
    val FgSubtle = Color.White.copy(alpha = 0.32f)
}

/** The app's own brand colour, locked to matte coral and independent of the user's WhatsApp accent. */
val AppAccent = Color(0xFFC95548)

/** Ambient accent for app chrome, fixed at [AppAccent]. Never `provides Color(prefs.primary)`: the UI then follows the user's WA theme. */
val LocalAccent = compositionLocalOf { AppAccent }

/** Soft accent-tinted drop shadow matching the card corners, deliberately subtle. */
fun Modifier.cardGlow(
    accent: Color,
    elevation: Dp = 5.dp,
    cornerRadius: Dp = 10.dp,
    intensity: Float = 0.6f,
): Modifier = this.shadow(
    elevation = elevation,
    shape = RoundedCornerShape(cornerRadius),
    ambientColor = accent.copy(alpha = intensity),
    spotColor = accent.copy(alpha = intensity),
)

/** Halo for the small swatch boxes: the swatch's own colour as shadow, so it looks softly lit. */
fun Modifier.swatchHalo(swatchColor: Color, cornerRadius: Dp = 8.dp): Modifier =
    this.shadow(
        elevation = 9.dp,
        shape = RoundedCornerShape(cornerRadius),
        ambientColor = swatchColor.copy(alpha = 0.9f),
        spotColor = swatchColor.copy(alpha = 0.9f),
    )

/* ── Chrome icons ────────────────────────────────────────────────────────
 * Drawn, not typed: text glyphs render in the system font and do not match the preview Canvas icons. */

@Composable
internal fun BackIcon(tint: Color, size: Dp = 16.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val s = 1.7.dp.toPx()
        drawLine(tint, Offset(w * 0.16f, h * 0.5f), Offset(w * 0.86f, h * 0.5f), s)
        drawLine(tint, Offset(w * 0.16f, h * 0.5f), Offset(w * 0.44f, h * 0.24f), s)
        drawLine(tint, Offset(w * 0.16f, h * 0.5f), Offset(w * 0.44f, h * 0.76f), s)
    }
}

/** Disclosure chevron. Rotate 90° for the expand/collapse caret. */
@Composable
internal fun ChevronIcon(tint: Color, size: Dp = 14.dp, degrees: Float = 0f) {
    Canvas(modifier = Modifier.size(size).rotate(degrees)) {
        val w = this.size.width; val h = this.size.height
        val s = 1.6.dp.toPx()
        drawLine(tint, Offset(w * 0.36f, h * 0.22f), Offset(w * 0.66f, h * 0.5f), s)
        drawLine(tint, Offset(w * 0.66f, h * 0.5f), Offset(w * 0.36f, h * 0.78f), s)
    }
}

/** Reset to default: a circular arrow. */
@Composable
internal fun ResetIcon(tint: Color, size: Dp = 14.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val s = 1.5.dp.toPx()
        drawArc(
            color = tint,
            startAngle = 55f, sweepAngle = 285f, useCenter = false,
            topLeft = Offset(w * 0.16f, h * 0.16f),
            size = Size(w * 0.68f, h * 0.68f),
            style = Stroke(s),
        )
        drawLine(tint, Offset(w * 0.80f, h * 0.72f), Offset(w * 0.88f, h * 0.46f), s)
        drawLine(tint, Offset(w * 0.80f, h * 0.72f), Offset(w * 0.54f, h * 0.68f), s)
    }
}

@Composable
internal fun CloseIcon(tint: Color, size: Dp = 14.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val s = 1.6.dp.toPx()
        drawLine(tint, Offset(w * 0.22f, h * 0.22f), Offset(w * 0.78f, h * 0.78f), s)
        drawLine(tint, Offset(w * 0.78f, h * 0.22f), Offset(w * 0.22f, h * 0.78f), s)
    }
}

@Composable
internal fun CheckIcon(tint: Color, size: Dp = 15.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val s = 1.9.dp.toPx()
        drawLine(tint, Offset(w * 0.18f, h * 0.52f), Offset(w * 0.42f, h * 0.76f), s)
        drawLine(tint, Offset(w * 0.42f, h * 0.76f), Offset(w * 0.84f, h * 0.26f), s)
    }
}

/* ── NavTopBar: reusable back-button header ────────────────────────────── */

/** Top bar for any screen; [onBack] = null at the root removes the back arrow. */
@Composable
fun NavTopBar(
    title: String,
    subtitle: String,
    accent: Color = LocalAccent.current,
    onBack: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.Bg)
            .padding(start = 14.dp, end = 18.dp, top = 18.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .cardGlow(accent, elevation = 4.dp, cornerRadius = 9.dp, intensity = 0.4f)
                    .border(1.dp, Palette.RuleStrong, RoundedCornerShape(8.dp))
                    .background(Palette.SurfaceElev, RoundedCornerShape(8.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                BackIcon(Palette.Fg)
            }
            Spacer(Modifier.size(12.dp))
        } else {
            Spacer(Modifier.size(6.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Palette.Fg, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, lineHeight = 24.sp)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                // Soft accent dot with a glow halo behind it.
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .shadow(6.dp, CircleShape, ambientColor = accent.copy(alpha = 0.95f), spotColor = accent.copy(alpha = 0.95f))
                        .background(accent, CircleShape),
                )
                Spacer(Modifier.size(6.dp))
                Text(subtitle.uppercase(), color = Palette.FgMuted, fontSize = 11.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        trailing()
    }
    HorizontalRule(accent = accent)
}

/** Hairline rule with a soft accent bloom in the middle third. */
@Composable
fun HorizontalRule(accent: Color = LocalAccent.current) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    0.00f to Palette.Rule,
                    0.30f to Palette.Rule,
                    0.50f to accent.copy(alpha = 0.30f),
                    0.70f to Palette.Rule,
                    1.00f to Palette.Rule,
                )
            )
    )
}

/* ── PreviewPanel ───────────────────────────────────────────────────────── */

/** Preview frame, deliberately unlabelled: an untouched mock is itself the signal the hook is not working. */
@Composable
fun PreviewPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, Palette.Rule, RoundedCornerShape(12.dp))
                .padding(1.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize()) { content() }
        }
    }
}

/* ── CategoryRow: tap-to-enter row ─────────────────────────────────────── */

@Composable
fun CategoryRow(
    label: String,
    description: String,
    leading: (@Composable () -> Unit)? = null,
    trailingHint: String? = null,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cardGlow(accent, elevation = 6.dp, intensity = 0.5f)
            .background(Palette.SurfaceElev, RoundedCornerShape(12.dp))
            .border(1.dp, Palette.RuleStrong, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.size(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Palette.Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            Text(description, color = Palette.FgMuted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Spacer(Modifier.size(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (trailingHint != null) {
                Text(trailingHint, color = accent.copy(alpha = 0.85f), fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
            }
            Box(modifier = Modifier.padding(top = if (trailingHint != null) 2.dp else 0.dp)) { ChevronIcon(Palette.FgMuted) }
        }
    }
}

/* ── SectionHeader: small caps category title within a screen ─────────── */

@Composable
fun SectionHeader(title: String, subtitle: String? = null) {
    Column {
        Text(title.uppercase(), color = Palette.Fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        if (subtitle != null) {
            Text(subtitle, color = Palette.FgMuted, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/* ── ExpandableSection: tap to expand a labeled card with token rows inside ── */

@Composable
fun ExpandableSection(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    swatchColor: Int = 0,
    content: @Composable () -> Unit,
) {
    val accent = LocalAccent.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.SurfaceElev, RoundedCornerShape(12.dp))
            .border(1.dp, Palette.RuleStrong, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (swatchColor != 0) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(Color(swatchColor), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                )
                Spacer(Modifier.size(10.dp))
            }
            Text(
                label,
                color = Palette.Fg,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
                modifier = Modifier.weight(1f),
            )
            ChevronIcon(accent, degrees = if (expanded) 90f else 0f)
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 0.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        }
    }
}

/* ── TokenRow: color-chip row (clickable to open picker) ───────────────── */

@Composable
fun TokenRow(
    icon: String,
    name: String,
    color: Int,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cardGlow(accent, elevation = 5.dp, intensity = 0.5f)
            .background(Palette.SurfaceElev, RoundedCornerShape(12.dp))
            .border(1.dp, Palette.RuleStrong, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .swatchHalo(Color(color), cornerRadius = 23.dp)
                .background(Color(color), CircleShape)
                .border(1.5.dp, Color.White.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                icon,
                color = if (luminance(color) > 0.5f) Color.Black else Color.White,
                fontSize = 22.sp, fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.size(14.dp))
        Text(
            name,
            color = Palette.Fg,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("#%08X".format(color), color = Palette.Fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
            Text("EDIT", color = accent.copy(alpha = 0.75f), fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/* ── StubNote: a small explanatory note under a list ───────────────────── */

@Composable
fun StubNote(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Palette.Rule, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).background(Palette.FgSubtle, CircleShape))
            Spacer(Modifier.size(8.dp))
            Text(text, color = Palette.FgMuted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

/* ── OverrideRow: per-element override (use global / custom) ───────────── */

/** Override token row: 0 shows [globalValue] as GLOBAL, non-zero shows CUSTOM plus a reset icon. [isNew] opts into the NEW tag. */
@Composable
fun OverrideRow(
    name: String,
    overrideValue: Int,
    globalValue: Int,
    onPickCustom: () -> Unit,
    onResetToGlobal: () -> Unit,
    isNew: Boolean = false,
) {
    val accent = LocalAccent.current
    val isOverridden = overrideValue != 0
    val effective = if (isOverridden) overrideValue else globalValue
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cardGlow(accent, elevation = 5.dp, intensity = 0.5f)
            .background(Palette.SurfaceElev, RoundedCornerShape(12.dp))
            .border(1.dp, Palette.RuleStrong, RoundedCornerShape(12.dp))
            .clickable(onClick = onPickCustom)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .swatchHalo(Color(effective), cornerRadius = 20.dp)
                .background(Color(effective), CircleShape)
                .border(1.5.dp, Color.White.copy(alpha = 0.15f), CircleShape),
        )
        Spacer(Modifier.size(14.dp))
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                color = Palette.Fg,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
            )
            if (isNew) {
                Spacer(Modifier.size(8.dp))
                Box(
                    modifier = Modifier
                        .background(accent.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                        .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        "NEW",
                        color = accent,
                        fontSize = 9.sp,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.size(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isOverridden) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(onClick = onResetToGlobal),
                        contentAlignment = Alignment.Center,
                    ) {
                        ResetIcon(Palette.FgMuted)
                    }
                    Spacer(Modifier.size(4.dp))
                }
                Text(
                    if (isOverridden) "CUSTOM" else "GLOBAL",
                    color = if (isOverridden) accent.copy(alpha = 0.95f) else Palette.FgSubtle,
                    fontSize = 9.sp,
                    letterSpacing = 1.4.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "#%08X".format(effective),
                color = Palette.FgMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/* ── ToggleItem: labelled switch row ──────────────────────────────────── */

@Composable
fun ToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cardGlow(accent, elevation = 5.dp, intensity = 0.5f)
            .background(Palette.SurfaceElev, RoundedCornerShape(12.dp))
            .border(1.dp, Palette.RuleStrong, RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Palette.Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            Text(subtitle, color = Palette.FgMuted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Spacer(Modifier.size(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accent,
                checkedBorderColor = accent,
                uncheckedThumbColor = Palette.FgMuted,
                uncheckedTrackColor = Palette.Bg,
                uncheckedBorderColor = Palette.RuleStrong,
            ),
        )
    }
}

/* ── MenuRow: tap-to-launch row ────────────────────────────────────────── */

@Composable
fun MenuRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cardGlow(accent, elevation = 5.dp, intensity = 0.5f)
            .background(Palette.SurfaceElev, RoundedCornerShape(12.dp))
            .border(1.dp, Palette.RuleStrong, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Palette.Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            Text(subtitle, color = Palette.FgMuted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Spacer(Modifier.size(10.dp))
        ChevronIcon(Palette.FgMuted)
    }
}

/* ── SliderItem: labelled slider with value display ───────────────────── */

@Composable
fun SliderItem(
    title: String,
    subtitle: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    valueLabel: (Float) -> String,
) {
    val accent = LocalAccent.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .cardGlow(accent, elevation = 5.dp, intensity = 0.5f)
            .background(Palette.SurfaceElev, RoundedCornerShape(12.dp))
            .border(1.dp, Palette.RuleStrong, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Palette.Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
                Text(subtitle, color = Palette.FgMuted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Spacer(Modifier.size(8.dp))
            Text(valueLabel(value), color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
                inactiveTrackColor = Palette.Rule,
            ),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/* ── Util ───────────────────────────────────────────────────────────────── */

fun luminance(argb: Int): Float {
    val r = android.graphics.Color.red(argb) / 255f
    val g = android.graphics.Color.green(argb) / 255f
    val b = android.graphics.Color.blue(argb) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

/* ── Restart WhatsApp button (sticky bottom on every screen) ───────────── */

/** Sticky bottom button that restarts WhatsApp: both kill paths always run, then a plain launcher intent, so auto-rotate stays untouched. */
@Composable
fun RestartWhatsAppButton(
    accent: Color = LocalAccent.current,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val onAccent = if (relativeLuminance(accent) > 0.55f)
        Color(0xFF101010)
    else
        Color.White

    // Vertical gradient: slightly brighter at top -> slightly muted at bottom.
    val topShade = accent.copy(alpha = 1f)
    val bottomShade = accent.copy(red = (accent.red * 0.88f).coerceIn(0f, 1f),
                                    green = (accent.green * 0.88f).coerceIn(0f, 1f),
                                    blue = (accent.blue * 0.88f).coerceIn(0f, 1f),
                                    alpha = 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.Bg)
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = accent.copy(alpha = 0.85f),
                    spotColor = accent.copy(alpha = 0.85f),
                )
                .background(
                    Brush.verticalGradient(listOf(topShade, bottomShade)),
                    RoundedCornerShape(24.dp),
                )
                .clickable {
                    RestartWhatsApp.restart(context) { result ->
                        val msg = when (result) {
                            RestartWhatsApp.Result.SUCCESS -> "Restarting WhatsApp…"
                            RestartWhatsApp.Result.NOT_INSTALLED -> "WhatsApp not installed."
                            RestartWhatsApp.Result.LAUNCH_FAILED -> "Could not launch WhatsApp."
                        }
                        onMessage(msg)
                    }
                }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RestartGlyph(onAccent)
            Spacer(Modifier.size(9.dp))
            Text(
                "Restart WhatsApp",
                color = onAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            )
        }
    }
}

@Composable
private fun RestartGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(15.dp)) {
        val w = size.width; val h = size.height
        val s = 1.6.dp.toPx()
        drawArc(
            color = tint,
            startAngle = 30f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(w * 0.15f, h * 0.15f),
            size = Size(w * 0.7f, h * 0.7f),
            style = Stroke(s),
        )
        // Arrow head at the open end of the arc (~30° = top-right)
        val tipX = w * 0.78f; val tipY = h * 0.32f
        drawLine(tint, Offset(tipX, tipY),
            Offset(tipX - 5.dp.toPx(), tipY - 1.dp.toPx()), s)
        drawLine(tint, Offset(tipX, tipY),
            Offset(tipX + 1.dp.toPx(), tipY + 5.dp.toPx()), s)
    }
}

internal fun relativeLuminance(c: Color): Float =
    0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

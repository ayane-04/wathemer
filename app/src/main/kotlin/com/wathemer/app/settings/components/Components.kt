// The settings app's rows, bars and controls, drawn in WhatsApp's own grammar: flat lists, one label, a value where there is one.
package com.wathemer.app.settings.components

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wathemer.app.R
import com.wathemer.app.settings.PendingRestart
import com.wathemer.app.util.RestartWhatsApp

// ── Shared design tokens ──────────────────────────────────────────────────

object Palette {
    val Bg = Color(0xFF0A0A0A)
    val Surface = Color(0xFF141414)
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

private val RowPadding = 16.dp
private val IconSlot = 24.dp
private val IconGap = 20.dp

// ── Chrome icons ────────────────────────────────────────────────────────
// Generated bitmaps tinted at draw time; the dots stay drawn because three circles need no bitmap.
@Composable
fun BitmapIcon(@DrawableRes id: Int, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Image(
        bitmap = ImageBitmap.imageResource(id),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
        filterQuality = FilterQuality.High,
    )
}

/** A Home row's leading icon. */
@Composable
fun RowIcon(@DrawableRes id: Int) = BitmapIcon(id, Palette.FgMuted, 22.dp)

@Composable
private fun BackIcon(tint: Color, size: Dp = 22.dp) = BitmapIcon(R.drawable.ic_ui_back, tint, size)

/** Disclosure chevron. Rotate 90° for the expand/collapse caret. */
@Composable
internal fun ChevronIcon(tint: Color, size: Dp = 14.dp, degrees: Float = 0f) =
    BitmapIcon(R.drawable.ic_ui_chevron, tint, size, Modifier.rotate(degrees))

/** Reset to default: a circular arrow. */
@Composable
private fun ResetIcon(tint: Color, size: Dp = 16.dp) = BitmapIcon(R.drawable.ic_ui_reset, tint, size)

@Composable
internal fun CloseIcon(tint: Color, size: Dp = 14.dp) = BitmapIcon(R.drawable.ic_ui_close, tint, size)

@Composable
internal fun CheckIcon(tint: Color, size: Dp = 15.dp) = BitmapIcon(R.drawable.ic_ui_check, tint, size)

/** The three dots of an overflow menu. */
@Composable
internal fun OverflowIcon(tint: Color, size: Dp = 22.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val r = 2.dp.toPx()
        drawCircle(tint, r, Offset(w * 0.5f, h * 0.24f))
        drawCircle(tint, r, Offset(w * 0.5f, h * 0.50f))
        drawCircle(tint, r, Offset(w * 0.5f, h * 0.76f))
    }
}

// ── NavTopBar: the screen's bar ─────────────────────────────────────────

/** Top bar for any screen; [onBack] = null at the root removes the back arrow. */
@Composable
fun NavTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.Bg)
            .height(56.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier.size(48.dp).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                BackIcon(Palette.Fg)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            color = Palette.Fg,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 0.dp),
        )
        trailing()
    }
}

/** An overflow button with its menu; [items] are label and action. */
@Composable
fun TopBarMenu(items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier.size(48.dp).clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            OverflowIcon(Palette.Fg)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = Palette.Surface,
        ) {
            items.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label, color = Palette.Fg, fontSize = 15.sp) },
                    onClick = { open = false; action() },
                )
            }
        }
    }
}

// ── PreviewPanel ─────────────────────────────────────────────────────────

/** Preview frame, deliberately unlabelled: an untouched mock is itself the signal the hook is not working. */
@Composable
fun PreviewPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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

// ── SectionHeader: the group label ──────────────────────────────────────

/** Sentence case, in the accent. */
@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        color = LocalAccent.current,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = RowPadding, end = RowPadding, top = 18.dp, bottom = 4.dp),
    )
}

// ── Row primitives ───────────────────────────────────────────────────────

@Composable
private fun RowDivider(inset: Dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(1.dp)
            .background(Palette.Rule),
    )
}

/** The list row every other row is: a leading slot, a label with an optional second line, a trailing slot. */
@Composable
private fun ListRow(
    label: String,
    secondary: String = "",
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = if (secondary.isBlank()) 56.dp else 64.dp)
                .padding(horizontal = RowPadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Box(Modifier.size(IconSlot), contentAlignment = Alignment.Center) { leading() }
                Spacer(Modifier.width(IconGap))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = Palette.Fg, fontSize = 16.sp, lineHeight = 22.sp)
                if (secondary.isNotBlank()) {
                    Text(
                        secondary,
                        color = Palette.FgMuted,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            trailing()
        }
        if (divider) RowDivider(if (leading != null) RowPadding + IconSlot + IconGap else RowPadding)
    }
}

/** Muted value text on the right of a row. */
@Composable
private fun ValueText(text: String) {
    Text(text, color = Palette.FgMuted, fontSize = 14.sp, maxLines = 1)
}

// ── CategoryRow: tap-to-enter row ───────────────────────────────────────

/** A row that leads somewhere or does something. [description] draws under the label only when given; [trailingHint] is the value shown before the chevron. */
@Composable
fun CategoryRow(
    label: String,
    description: String = "",
    leading: (@Composable () -> Unit)? = null,
    trailingHint: String? = null,
    divider: Boolean = true,
    onClick: () -> Unit,
) {
    ListRow(label = label, secondary = description, leading = leading, onClick = onClick, divider = divider) {
        if (!trailingHint.isNullOrBlank()) {
            ValueText(trailingHint)
            Spacer(Modifier.width(8.dp))
        }
        ChevronIcon(Palette.FgMuted)
    }
}


// ── ToggleItem: labelled switch row ────────────────────────────────────

@Composable
fun ToggleItem(
    title: String,
    subtitle: String = "",
    checked: Boolean,
    divider: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val accent = LocalAccent.current
    ListRow(label = title, secondary = subtitle, onClick = { onCheckedChange(!checked) }, divider = divider) {
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

// ── SliderItem: label, track, value on one line ────────────────────────

/** The compact control: the label on the left, a thin track, the value on the right. */
@Composable
fun SliderItem(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    divider: Boolean = true,
    onValueChange: (Float) -> Unit,
    valueLabel: (Float) -> String,
) {
    val accent = LocalAccent.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = RowPadding, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = Palette.Fg,
                fontSize = 16.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(136.dp),
            )
            ThinSlider(
                value = value,
                range = range,
                steps = steps,
                accent = accent,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(
                valueLabel(value),
                color = Palette.Fg,
                fontSize = 15.sp,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(52.dp),
            )
        }
        if (divider) RowDivider(RowPadding)
    }
}

/** A hairline track and a round thumb, the slider WhatsApp itself draws. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    accent: Color,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors(
        thumbColor = accent,
        activeTrackColor = accent,
        inactiveTrackColor = Palette.RuleStrong,
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        steps = steps,
        interactionSource = interaction,
        colors = colors,
        modifier = modifier,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interaction,
                colors = colors,
                thumbSize = DpSize(18.dp, 18.dp),
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                colors = colors,
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = 0.dp,
                drawStopIndicator = null,
                modifier = Modifier.height(3.dp),
            )
        },
    )
}

// ── Swatch rows ─────────────────────────────────────────────────────────

@Composable
private fun Swatch(color: Int, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(Color(color), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
    )
}

/** A colour that is always set: the swatch is the value. */
@Composable
fun TokenRow(
    name: String,
    color: Int,
    divider: Boolean = true,
    onClick: () -> Unit,
) {
    ListRow(label = name, leading = { Swatch(color, 24.dp) }, onClick = onClick, divider = divider) {
        ChevronIcon(Palette.FgMuted)
    }
}

/** A colour that may follow the global: 0 shows the global's swatch and says so, a value shows its own and offers the way back. */
@Composable
fun OverrideRow(
    name: String,
    overrideValue: Int,
    globalValue: Int,
    onPickCustom: () -> Unit,
    onResetToGlobal: () -> Unit,
    divider: Boolean = true,
) {
    val isOverridden = overrideValue != 0
    val effective = if (isOverridden) overrideValue else globalValue
    ListRow(label = name, leading = { Swatch(effective, 20.dp) }, onClick = onPickCustom, divider = divider) {
        if (isOverridden) {
            Box(
                modifier = Modifier.size(32.dp).clickable(onClick = onResetToGlobal),
                contentAlignment = Alignment.Center,
            ) {
                ResetIcon(Palette.FgMuted)
            }
        } else {
            ValueText("Global")
        }
    }
}

// ── ExpandableSection: a group that opens on tap ────────────────────────

@Composable
private fun ExpandableSection(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    swatchColor: Int = 0,
    trailingText: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ListRow(
            label = label,
            leading = if (swatchColor != 0) ({ Swatch(swatchColor, 20.dp) }) else null,
            onClick = onToggle,
        ) {
            if (!trailingText.isNullOrBlank()) {
                ValueText(trailingText)
                Spacer(Modifier.width(8.dp))
            }
            ChevronIcon(Palette.FgMuted, degrees = if (expanded) 90f else 0f)
        }
        if (expanded) {
            // Indented a step, so the rows read as the group's and the next group row reads as a sibling.
            Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp)) { content() }
        }
    }
}

/** A group that opens on tap and remembers whether it is open across rotation. Collapsed to begin with. */
@Composable
fun ExpandGroup(
    label: String,
    swatchColor: Int = 0,
    trailingText: String? = null,
    onOpen: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    ExpandableSection(
        label = label,
        expanded = open,
        onToggle = {
            open = !open
            if (open) onOpen?.invoke()
        },
        swatchColor = swatchColor,
        trailingText = trailingText,
        content = content,
    )
}

// ── NoteText: one plain line under a list ───────────────────────────────

@Composable
fun NoteText(text: String) {
    Text(
        text,
        color = Palette.FgMuted,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = RowPadding, vertical = 14.dp),
    )
}

// ── Util ─────────────────────────────────────────────────────────────────

/** Perceived luminance, for picking dark or light text on a coloured background. */
fun luminance(argb: Int): Float {
    val r = android.graphics.Color.red(argb) / 255f
    val g = android.graphics.Color.green(argb) / 255f
    val b = android.graphics.Color.blue(argb) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

// ── RestartBar: shown only while a change waits for WhatsApp to restart ──

/** Restarts WhatsApp and reports the outcome; a success also clears the pending flag. */
fun restartWhatsApp(context: Context, onMessage: (String) -> Unit) {
    RestartWhatsApp.restart(context) { result ->
        val msg = when (result) {
            RestartWhatsApp.Result.SUCCESS -> {
                PendingRestart.clear(context)
                "Restarting WhatsApp"
            }
            RestartWhatsApp.Result.NOT_INSTALLED -> "WhatsApp is not installed."
            RestartWhatsApp.Result.LAUNCH_FAILED -> "Could not launch WhatsApp."
        }
        onMessage(msg)
    }
}

/** One line and one action, at the bottom of every screen, only while a change is pending. */
@Composable
fun RestartBar(
    accent: Color = LocalAccent.current,
    onMessage: (String) -> Unit,
) {
    val pending by PendingRestart.pending
    if (!pending) return
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.Surface)
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Rule))
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = RowPadding, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Changes apply the next time WhatsApp starts.",
                color = Palette.FgMuted,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Restart",
                color = accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable { restartWhatsApp(context, onMessage) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

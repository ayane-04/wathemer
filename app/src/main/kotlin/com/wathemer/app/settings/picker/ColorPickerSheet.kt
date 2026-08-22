package com.wathemer.app.settings.picker

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.wathemer.app.settings.components.CloseIcon
import com.wathemer.app.util.HexColor
import kotlin.math.roundToInt

/** Colour picker bottom sheet. State is local; [onApply] fires only on Apply, Cancel discards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerSheet(
    title: String,
    subtitle: String,
    initialColor: Int,
    recents: List<Int>,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val rule = Color.White.copy(alpha = 0.09f)
    val fgMuted = Color.White.copy(alpha = 0.55f)
    val fgSubtle = Color.White.copy(alpha = 0.32f)

    // HSV state
    val initHsv = remember(initialColor) { argbToHsv(initialColor) }
    var hue by remember { mutableFloatStateOf(initHsv[0]) }
    var sat by remember { mutableFloatStateOf(initHsv[1]) }
    var value by remember { mutableFloatStateOf(initHsv[2]) }
    var alphaFraction by remember { mutableFloatStateOf(AndroidColor.alpha(initialColor) / 255f) }

    /** Exact ARGB the user stated: HSV does not round-trip every 8-bit triple, so prefer this until an HSV control calls [clearExact]. */
    var exactArgb by remember(initialColor) { mutableStateOf<Int?>(null) }
    val clearExact = { exactArgb = null }

    val currentArgb by remember {
        derivedStateOf { exactArgb ?: hsvToArgb(hue, sat, value, alphaFraction) }
    }
    val currentColor = Color(currentArgb)
    val pureHueColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = Color.White,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        // Scrolls: the square SV area alone is as tall as the sheet is wide, so the body can outgrow the screen.
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(42.dp).height(4.dp)
                    .background(rule, RoundedCornerShape(4.dp))
            )

            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        subtitle.uppercase(),
                        color = fgMuted, fontSize = 9.sp, letterSpacing = 1.6.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .border(1.dp, rule, RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    CloseIcon(Color.White)
                }
            }

            // SV area
            SvArea(
                hueColor = pureHueColor,
                sat = sat, value = value,
                onChange = { s, v -> clearExact(); sat = s; value = v },
                rule = rule,
            )

            // Hue slider
            LabeledSlider(label = "Hue", valueText = "${hue.toInt()}°", rule = rule, fgMuted = fgMuted) {
                HueTrack(hue = hue, onChange = { clearExact(); hue = it })
            }

            // Saturation slider
            LabeledSlider(label = "Saturation", valueText = "${(sat * 100).toInt()}%", rule = rule, fgMuted = fgMuted) {
                SatTrack(sat = sat, hueColor = pureHueColor, onChange = { clearExact(); sat = it })
            }

            // Opacity slider
            LabeledSlider(label = "Opacity", valueText = "${(alphaFraction * 100).toInt()}%", rule = rule, fgMuted = fgMuted) {
                AlphaTrack(alpha = alphaFraction, opaqueColor = Color(currentArgb or 0xFF000000.toInt()), onChange = { clearExact(); alphaFraction = it })
            }

            // Recents
            if (recents.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Text("RECENT", color = fgMuted, fontSize = 9.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${recents.size} colour${if (recents.size == 1) "" else "s"}", color = fgSubtle, fontSize = 9.sp, letterSpacing = 0.8.sp)
                    }
                    // One swatch per recent; dedup happens at write time in Prefs.pushRecent.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        recents.forEach { swatchArgb ->
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(Color(swatchArgb), CircleShape)
                                    .border(1.dp, rule, CircleShape)
                                    .clickable {
                                        // A recent is an exact value too; skip the HSV round trip that can shift it.
                                        val hsv = argbToHsv(swatchArgb)
                                        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                                        alphaFraction = AndroidColor.alpha(swatchArgb) / 255f
                                        exactArgb = swatchArgb
                                    },
                            )
                        }
                    }
                }
            }

            // HEX + new/old preview
            HexPreviewRow(
                currentColor = currentColor,
                oldColor = Color(initialColor),
                rule = rule,
                fgMuted = fgMuted,
                onHexParsed = { argb ->
                    // HSV positions the controls; exactArgb keeps the applied colour exactly as typed.
                    val hsv = argbToHsv(argb)
                    hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                    alphaFraction = AndroidColor.alpha(argb) / 255f
                    exactArgb = argb
                },
            )

        }

        // Outside the scroll: these stay reachable however tall the body gets.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 20.dp),
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, rule),
            ) { Text("Cancel", color = Color.White, fontWeight = FontWeight.SemiBold) }
            Button(
                onClick = { onApply(currentArgb) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = currentColor,
                    contentColor = if (luminance(currentArgb) > 0.5f) Color.Black else Color.White,
                ),
            ) { Text("Apply colour", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun SvArea(
    hueColor: Color,
    sat: Float, value: Float,
    onChange: (s: Float, v: Float) -> Unit,
    rule: Color,
) {
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .border(1.dp, rule, RoundedCornerShape(8.dp))
            .background(Brush.horizontalGradient(listOf(Color.White, hueColor)), RoundedCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)), RoundedCornerShape(8.dp))
            .onSizeChanged { areaSize = it }
            .pointerInput(Unit) { detectTapGestures(onPress = { offset ->
                val w = areaSize.width.coerceAtLeast(1)
                val h = areaSize.height.coerceAtLeast(1)
                val s = (offset.x / w).coerceIn(0f, 1f)
                val v = 1f - (offset.y / h).coerceIn(0f, 1f)
                onChange(s, v)
            }) }
            .pointerInput(Unit) { detectDragGestures(
                onDragStart = { offset ->
                    val w = areaSize.width.coerceAtLeast(1)
                    val h = areaSize.height.coerceAtLeast(1)
                    val s = (offset.x / w).coerceIn(0f, 1f)
                    val v = 1f - (offset.y / h).coerceIn(0f, 1f)
                    onChange(s, v)
                }
            ) { change, _ ->
                change.consume()
                val w = areaSize.width.coerceAtLeast(1)
                val h = areaSize.height.coerceAtLeast(1)
                val s = (change.position.x / w).coerceIn(0f, 1f)
                val v = 1f - (change.position.y / h).coerceIn(0f, 1f)
                onChange(s, v)
            } }
    ) {
        // Marker
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val x = sat * size.width
            val y = (1f - value) * size.height
            val outerR = 8.dp.toPx()
            drawCircle(Color.White, radius = outerR, center = Offset(x, y), style = Stroke(2.dp.toPx()))
            drawCircle(Color.Black, radius = outerR + 1.dp.toPx(), center = Offset(x, y), style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String, valueText: String, rule: Color, fgMuted: Color,
    track: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(label.uppercase(), color = fgMuted, fontSize = 9.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(valueText, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .border(1.dp, rule, RoundedCornerShape(8.dp))
        ) { track() }
    }
}

@Composable
private fun HueTrack(hue: Float, onChange: (Float) -> Unit) {
    val hueColors = remember {
        listOf(
            Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
            Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
        )
    }
    SliderTrack(
        fraction = (hue / 360f).coerceIn(0f, 1f),
        background = Brush.horizontalGradient(hueColors),
        onChangeFraction = { onChange((it * 360f).coerceIn(0f, 359.99f)) },
    )
}

@Composable
private fun SatTrack(sat: Float, hueColor: Color, onChange: (Float) -> Unit) {
    SliderTrack(
        fraction = sat.coerceIn(0f, 1f),
        background = Brush.horizontalGradient(listOf(Color(0xFF7A7A7A), hueColor)),
        onChangeFraction = { onChange(it.coerceIn(0f, 1f)) },
    )
}

@Composable
private fun AlphaTrack(alpha: Float, opaqueColor: Color, onChange: (Float) -> Unit) {
    // Clip the Box or the checkerboard runs past the rounded corners and reads as odd padding.
    Box(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        // Checkerboard backing
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val tile = 4.dp.toPx()
            val cols = (size.width / tile).toInt() + 1
            val rows = (size.height / tile).toInt() + 1
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    if ((row + col) % 2 == 0) {
                        drawRect(
                            color = Color(0xFF555555),
                            topLeft = Offset(col * tile, row * tile),
                            size = Size(tile, tile),
                        )
                    } else {
                        drawRect(
                            color = Color(0xFF888888),
                            topLeft = Offset(col * tile, row * tile),
                            size = Size(tile, tile),
                        )
                    }
                }
            }
        }
        SliderTrack(
            fraction = alpha.coerceIn(0f, 1f),
            background = Brush.horizontalGradient(listOf(Color.Transparent, opaqueColor)),
            onChangeFraction = { onChange(it.coerceIn(0f, 1f)) },
        )
    }
}

@Composable
private fun SliderTrack(
    fraction: Float,
    background: Brush,
    onChangeFraction: (Float) -> Unit,
) {
    var w by remember { mutableIntStateOf(1) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .background(background, RoundedCornerShape(8.dp))
            .onSizeChanged { w = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures(onPress = { offset ->
                onChangeFraction((offset.x / w).coerceIn(0f, 1f))
            }) }
            .pointerInput(Unit) { detectDragGestures(
                onDragStart = { onChangeFraction((it.x / w).coerceIn(0f, 1f)) }
            ) { change, _ ->
                change.consume()
                onChangeFraction((change.position.x / w).coerceIn(0f, 1f))
            } },
    ) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val x = fraction * size.width
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(x - 4.dp.toPx(), -3.dp.toPx()),
                size = Size(8.dp.toPx(), size.height + 6.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.4f),
                topLeft = Offset(x - 4.dp.toPx(), -3.dp.toPx()),
                size = Size(8.dp.toPx(), size.height + 6.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(1.dp.toPx()),
            )
        }
    }
}

@Composable
private fun HexPreviewRow(
    currentColor: Color, oldColor: Color,
    rule: Color, fgMuted: Color,
    onHexParsed: (Int) -> Unit,
) {
    val hex = HexColor.toHex(currentColor.toArgb()).uppercase()
    // A dialog, not inline editing: the IME buries or squashes the sheet, and the window manager lifts a dialog clear of it.
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        HexEntryDialog(
            initial = hex,
            rule = rule,
            fgMuted = fgMuted,
            onDismiss = { editing = false },
            onConfirm = { argb -> editing = false; onHexParsed(argb) },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, rule, RoundedCornerShape(8.dp))
            .clickable { editing = true }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("HEX", color = fgMuted, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
        Text(
            hex,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            modifier = Modifier.weight(1f),
        )
        Text("EDIT", color = fgMuted, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
        // NEW|OLD preview: clip the whole row so the two half swatches read as one split pill.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, rule, RoundedCornerShape(12.dp))
                .height(26.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(28.dp).height(26.dp)
                    .background(currentColor),
                contentAlignment = Alignment.Center,
            ) {
                Text("NEW",
                    color = if (luminance(currentColor.toArgb()) > 0.5f) Color.Black.copy(0.7f) else Color.White.copy(0.85f),
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            }
            Box(
                modifier = Modifier
                    .width(28.dp).height(26.dp)
                    .background(oldColor),
                contentAlignment = Alignment.Center,
            ) {
                Text("OLD",
                    color = if (luminance(oldColor.toArgb()) > 0.5f) Color.Black.copy(0.7f) else Color.White.copy(0.85f),
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            }
        }
    }
}

/** Hex entry dialog, AARRGGBB. Eight digits required: a shorter code is a valid prefix that parses as a different colour. */
@Composable
private fun HexEntryDialog(
    initial: String,
    rule: Color,
    fgMuted: Color,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(initial.removePrefix("#")) }
    val digits = text.trim().removePrefix("#")
    val valid = digits.length == 8 && digits.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    val parsed = if (valid) HexColor.fromHex(digits) else 0
    val focus = remember { FocusRequester() }
    // Nothing else opens the keyboard here, so the field asks for focus as the dialog appears.
    LaunchedEffect(Unit) { focus.requestFocus() }
    val confirm = { if (valid && parsed != 0) onConfirm(parsed) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(16.dp))
                .border(1.dp, rule, RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Hex colour", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "AARRGGBB, eight digits, alpha first.",
                color = fgMuted, fontSize = 11.sp,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, rule, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("#", color = fgMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                BasicTextField(
                    value = text,
                    // Filter as you type: hex only, max eight, so the field never holds what Set would refuse.
                    onValueChange = { raw ->
                        text = raw.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                            .take(8)
                            .uppercase()
                    },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    textStyle = TextStyle(
                        color = Color.White, fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
                    ),
                    cursorBrush = SolidColor(Color.White),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                )
                // Live swatch: check the full code before setting it.
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            if (valid) Color(parsed) else Color.Transparent,
                            RoundedCornerShape(6.dp),
                        )
                        .border(1.dp, rule, RoundedCornerShape(6.dp)),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, rule),
                ) { Text("Cancel", color = Color.White, fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = confirm,
                    enabled = valid && parsed != 0,
                    modifier = Modifier.weight(1f),
                ) { Text("Set", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/* ── HSV math helpers ─────────────────────────────────────── */

private fun argbToHsv(argb: Int): FloatArray {
    val hsv = FloatArray(3)
    AndroidColor.RGBToHSV(
        AndroidColor.red(argb),
        AndroidColor.green(argb),
        AndroidColor.blue(argb),
        hsv,
    )
    return hsv
}

private fun hsvToArgb(h: Float, s: Float, v: Float, alpha: Float): Int =
    // roundToInt, not toInt: 138 becomes 137.99998 and truncation drops the alpha by one.
    AndroidColor.HSVToColor(
        (alpha * 255).roundToInt().coerceIn(0, 255),
        floatArrayOf(h, s, v),
    )

/** Perceived luminance (0..1), for picking dark/light text on a coloured background. */
private fun luminance(argb: Int): Float {
    val r = AndroidColor.red(argb) / 255f
    val g = AndroidColor.green(argb) / 255f
    val b = AndroidColor.blue(argb) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

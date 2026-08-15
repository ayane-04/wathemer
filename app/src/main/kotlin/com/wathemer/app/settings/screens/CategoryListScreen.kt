package com.wathemer.app.settings.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wathemer.app.settings.components.AppAccent
import com.wathemer.app.settings.components.CategoryRow
import com.wathemer.app.settings.components.MenuRow
import com.wathemer.app.settings.components.NavTopBar
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.nav.Screen
import com.wathemer.app.settings.prefs.Prefs
import com.wathemer.app.settings.preview.LocalThemeSnapshot

/** Root screen: the top-level categories. No back button. */
@Composable
fun CategoryListScreen(nav: NavController, prefs: Prefs) {
    val snapshot = LocalThemeSnapshot.current
    var themed by remember { mutableStateOf(prefs.hasGlobalTheme()) }
    val previewName = detectPresetName(prefs.primary, prefs.background, prefs.text)

    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(
                title = "WaThemer",
                subtitle = if (themed) previewName else "Nothing themed yet",
                onBack = null,
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // A revert, not a toggle: there is no "on" state, only accumulated choices to clear.
                if (themed) {
                    MenuRow(
                        title = "Reset to stock WhatsApp",
                        subtitle = "Clears your colours. Shapes, icons, font and wallpaper stay.",
                        onClick = {
                            prefs.resetToStock()
                            themed = false
                            snapshot.value = com.wathemer.app.settings.preview.snapshotFromPrefs(prefs)
                        },
                    )
                    Spacer(Modifier.size(4.dp))
                }

                CategoryRow(
                    label = "Global colours",
                    description = "The three colours behind everything in WhatsApp.",
                    leading = { GlobalColorsBadge(prefs.primary, prefs.background, prefs.text) },
                    onClick = { nav.push(Screen.GlobalColors) },
                )
                CategoryRow(
                    label = "Homescreen",
                    description = "The screen WhatsApp opens on.",
                    leading = { CategoryTile(AppAccent) { HomescreenIcon(it) } },
                    onClick = { nav.push(Screen.Homescreen) },
                )
                CategoryRow(
                    label = "Chat screen",
                    description = "Everything inside a conversation.",
                    leading = { CategoryTile(AppAccent) { ChatScreenIcon(it) } },
                    onClick = { nav.push(Screen.Chat) },
                )
                CategoryRow(
                    label = "Wallpaper & Glass",
                    description = "A chat wallpaper, and the frosted glass over it.",
                    leading = { CategoryTile(AppAccent) { BackdropIcon(it) } },
                    onClick = { nav.push(Screen.Backdrop) },
                )
                CategoryRow(
                    label = "Extras",
                    description = "Row dividers, overlay effects and fonts.",
                    leading = { CategoryTile(AppAccent) { ExtrasIcon(it) } },
                    onClick = { nav.push(Screen.Extras) },
                )

                Spacer(Modifier.size(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Palette.Rule, RoundedCornerShape(8.dp))
                        .padding(14.dp),
                ) {
                    Column {
                        Text("APPLYING CHANGES", color = Palette.FgMuted, fontSize = 9.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "WhatsApp reads your settings when it starts. Restart it below.",
                            color = Palette.FgMuted, fontSize = 11.sp, lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlobalColorsBadge(primary: Int, background: Int, text: Int) {
    Row(
        modifier = Modifier
            .border(1.dp, Palette.Rule, RoundedCornerShape(8.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Box(Modifier.size(width = 14.dp, height = 42.dp).background(Color(primary), RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)))
        Box(Modifier.size(width = 14.dp, height = 42.dp).background(Color(background)))
        Box(Modifier.size(width = 14.dp, height = 42.dp).background(Color(text), RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)))
    }
}

/** Category tile. Icons are drawn in Canvas, not font glyphs, so each depicts its category. */
@Composable
private fun CategoryTile(accent: Color, icon: @Composable (Color) -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .background(Palette.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, Palette.Rule, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        icon(accent)
    }
}

/** A phone with a list in it. */
@Composable
private fun HomescreenIcon(tint: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val w = size.width; val h = size.height
        val s = 1.5.dp.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.18f, 0f),
            size = Size(w * 0.64f, h),
            cornerRadius = CornerRadius(2.5.dp.toPx()),
            style = Stroke(s),
        )
        for (i in 0..2) {
            val y = h * (0.32f + i * 0.19f)
            drawLine(tint, Offset(w * 0.30f, y), Offset(w * 0.70f, y), s * 0.8f)
        }
    }
}

/** Two conversation bubbles. */
@Composable
private fun ChatScreenIcon(tint: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val w = size.width; val h = size.height
        val s = 1.5.dp.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(0f, h * 0.10f),
            size = Size(w * 0.62f, h * 0.42f),
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = Stroke(s),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.38f, h * 0.48f),
            size = Size(w * 0.62f, h * 0.42f),
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = Stroke(s),
        )
    }
}

/** A framed image with a soft band across it: picture plus the glass over it. */
@Composable
private fun BackdropIcon(tint: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val w = size.width; val h = size.height
        val s = 1.5.dp.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(0f, h * 0.08f),
            size = Size(w, h * 0.84f),
            cornerRadius = CornerRadius(2.5.dp.toPx()),
            style = Stroke(s),
        )
        drawCircle(tint, w * 0.07f, Offset(w * 0.28f, h * 0.30f))
        drawLine(tint, Offset(w * 0.10f, h * 0.72f), Offset(w * 0.38f, h * 0.44f), s)
        drawLine(tint, Offset(w * 0.38f, h * 0.44f), Offset(w * 0.62f, h * 0.66f), s)
        // the glass band
        drawRect(tint.copy(alpha = 0.35f), Offset(0f, h * 0.52f), Size(w, h * 0.16f))
    }
}

/** Falling dots over a baseline: the effects. */
@Composable
private fun ExtrasIcon(tint: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val w = size.width; val h = size.height
        drawCircle(tint, w * 0.06f, Offset(w * 0.25f, h * 0.18f))
        drawCircle(tint, w * 0.05f, Offset(w * 0.62f, h * 0.32f))
        drawCircle(tint, w * 0.06f, Offset(w * 0.40f, h * 0.52f))
        drawCircle(tint, w * 0.05f, Offset(w * 0.75f, h * 0.66f))
        drawLine(tint, Offset(w * 0.15f, h * 0.86f), Offset(w * 0.85f, h * 0.86f), 1.5.dp.toPx())
    }
}

internal fun detectPresetName(primary: Int, background: Int, text: Int): String = when {
    primary == Prefs.DEFAULT_PRIMARY && background == Prefs.DEFAULT_BACKGROUND && text == Prefs.DEFAULT_TEXT -> "Stock"
    primary == 0xFFC95548.toInt() && background == 0xFF0A0A0A.toInt() && text == 0xFFFFFFFF.toInt() -> "Coral"
    primary == 0xFFC8A878.toInt() && background == 0xFF1A0E0F.toInt() && text == 0xFFF0E5D6.toInt() -> "Bordeaux"
    primary == 0xFFA993CC.toInt() && background == 0xFF09080C.toInt() && text == 0xFFFFFFFF.toInt() -> "Lavender"
    else -> "Custom"
}

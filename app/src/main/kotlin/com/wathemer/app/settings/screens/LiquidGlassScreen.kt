package com.wathemer.app.settings.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wathemer.app.settings.components.MenuRow
import com.wathemer.app.settings.components.NavTopBar
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.components.SectionHeader
import com.wathemer.app.settings.components.SliderItem
import com.wathemer.app.settings.components.StubNote
import com.wathemer.app.settings.components.ToggleItem
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.prefs.GlassDefaults
import com.wathemer.app.settings.prefs.Prefs
import kotlin.math.roundToInt

/** Deliberately not wired to the live preview: glass samples WhatsApp's real backdrop, so a mock would only be a drawing.
 *  Values are read once at hook install; every control here needs a WhatsApp restart. */
@Composable
fun LiquidGlassScreen(nav: NavController, prefs: Prefs) {
    val context = LocalContext.current
    // Ready needs both flags: enabled with no image picked leaves the injector nothing to inject.
    val wallpaperReady = prefs.wallpaperEnabled && !prefs.wallpaperPath.isNullOrBlank()
    var enabled by remember { mutableStateOf(prefs.glassEnabled) }
    var merge by remember { mutableStateOf(prefs.glassBubbleMerge) }
    var blur by remember { mutableIntStateOf(prefs.glassBlur) }
    var tint by remember { mutableIntStateOf(prefs.glassTint) }
    var displace by remember { mutableIntStateOf(prefs.glassDisplace) }
    var bevel by remember { mutableIntStateOf(prefs.glassBevel) }
    var radius by remember { mutableIntStateOf(prefs.glassRadius) }
    var gamma by remember { mutableIntStateOf(prefs.glassGamma) }
    var rim by remember { mutableIntStateOf(prefs.glassRim) }
    var rimWidth by remember { mutableIntStateOf(prefs.glassRimWidth) }
    var rimAngle by remember { mutableIntStateOf(prefs.glassRimAngle) }

    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(
                title = "Liquid Glass",
                subtitle = if (enabled) "Enabled" else "Disabled",
                onBack = { nav.pop() },
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionHeader(
                    title = "Liquid Glass",
                    subtitle = "Frosted glass behind the cards, bars and buttons.",
                )
                ToggleItem(
                    title = "Enable",
                    subtitle = if (wallpaperReady) {
                        "Frosts the surfaces across WhatsApp."
                    } else {
                        "Set a wallpaper first. Glass has nothing to show without one."
                    },
                    checked = enabled,
                    onCheckedChange = { want ->
                        // Glass without a wallpaper is broken, so refuse the enable here where the user learns why; turning off is always allowed.
                        if (want && !wallpaperReady) {
                            // Keep the toast short: Android caps it at two lines and truncates the rest.
                            Toast.makeText(
                                context,
                                "Set a wallpaper first. Glass has nothing to show without one.",
                                Toast.LENGTH_LONG,
                            ).show()
                            return@ToggleItem
                        }
                        enabled = want
                        prefs.glassEnabled = want
                    },
                )

                SectionHeader(
                    title = "Tuning",
                    subtitle = "Blur and tint decide how much shows through; displacement and bevel " +
                        "decide how much the edges bend it.",
                )
                SliderItem(
                    title = "Backdrop blur",
                    subtitle = "How soft the wallpaper is behind every glass surface.",
                    value = blur.toFloat(),
                    range = 4f..40f,
                    steps = 35,
                    // roundToInt, never toInt (snap lands under the tick, truncation stalls the handle); valueLabel's toInt is exact.
                    onValueChange = { blur = it.roundToInt().also { v -> prefs.glassBlur = v } },
                    valueLabel = { "${it.toInt()} dp" },
                )
                SliderItem(
                    title = "Tint strength",
                    // The copy must describe both ends: resolveGlassTint slides white to black on the wallpaper's mean luma.
                    subtitle = "A haze over the glass: light over a dark wallpaper, dark over a light one. Too much looks solid.",
                    value = tint.toFloat(),
                    range = 0f..80f,
                    steps = 79,
                    onValueChange = { tint = it.roundToInt().also { v -> prefs.glassTint = v } },
                    valueLabel = { "${it.toInt()}/255" },
                )
                SliderItem(
                    title = "Edge refraction",
                    subtitle = "How far the edges bend what is behind them. 0 turns the lensing " +
                        "off and leaves a flat frosted panel.",
                    value = displace.toFloat(),
                    range = 0f..60f,
                    steps = 59,
                    onValueChange = { displace = it.roundToInt().also { v -> prefs.glassDisplace = v } },
                    valueLabel = { if (it.toInt() == 0) "off" else "${it.toInt()} dp" },
                )
                SliderItem(
                    title = "Bevel width",
                    subtitle = "How much of each surface is edge rather than flat centre. " +
                        "Capped by the corner radius, so a small radius limits this.",
                    value = bevel.toFloat(),
                    range = 5f..40f,
                    steps = 34,
                    onValueChange = { bevel = it.roundToInt().also { v -> prefs.glassBevel = v } },
                    valueLabel = { "${it.toInt()}%" },
                )
                SliderItem(
                    title = "Corner radius",
                    subtitle = "Corner roundness.",
                    value = radius.toFloat(),
                    range = 0f..40f,
                    steps = 39,
                    onValueChange = { radius = it.roundToInt().also { v -> prefs.glassRadius = v } },
                    valueLabel = { "${it.toInt()} dp" },
                )

                SliderItem(
                    title = "Backdrop contrast",
                    subtitle = "Lifts the darks in what shows through. Lower keeps more structure; " +
                        "100 is off and reads flattest, the frosted-sheet look.",
                    value = gamma.toFloat(),
                    range = 30f..100f,
                    steps = 69,
                    onValueChange = { gamma = it.roundToInt().also { v -> prefs.glassGamma = v } },
                    valueLabel = { if (it.toInt() == 100) "off" else "${it.toInt()}%" },
                )

                SectionHeader(
                    title = "Edge highlight",
                    subtitle = "A hard line on the rim, lit on one side and fading out on the other. " +
                        "Separate from the bevel, which is a wide soft band.",
                )
                SliderItem(
                    title = "Highlight strength",
                    subtitle = "0 draws nothing at all.",
                    value = rim.toFloat(),
                    range = 0f..100f,
                    steps = 99,
                    onValueChange = { rim = it.roundToInt().also { v -> prefs.glassRim = v } },
                    valueLabel = { if (it.toInt() == 0) "off" else "${it.toInt()}%" },
                )
                SliderItem(
                    title = "Highlight width",
                    subtitle = "Thin is the point. Wide stops reading as an edge.",
                    value = rimWidth.toFloat(),
                    range = 1f..4f,
                    steps = 2,
                    onValueChange = { rimWidth = it.roundToInt().also { v -> prefs.glassRimWidth = v } },
                    valueLabel = { "${it.toInt()} dp" },
                )
                SliderItem(
                    title = "Highlight angle",
                    subtitle = "Which side of every surface catches the light.",
                    value = rimAngle.toFloat(),
                    range = 0f..360f,
                    steps = 71,
                    onValueChange = { rimAngle = it.roundToInt().also { v -> prefs.glassRimAngle = v } },
                    valueLabel = { "${it.toInt()}deg" },
                )

                MenuRow(
                    title = "Restore recommended values",
                    subtitle = "Back to the defaults.",
                    onClick = {
                        // Through GlassDefaults, or this button and the shipped values drift apart.
                        blur = GlassDefaults.BLUR; prefs.glassBlur = blur
                        tint = GlassDefaults.TINT; prefs.glassTint = tint
                        displace = GlassDefaults.DISPLACE; prefs.glassDisplace = displace
                        bevel = GlassDefaults.BEVEL; prefs.glassBevel = bevel
                        radius = GlassDefaults.RADIUS; prefs.glassRadius = radius
                        gamma = GlassDefaults.GAMMA; prefs.glassGamma = gamma
                        rim = GlassDefaults.RIM; prefs.glassRim = rim
                        rimWidth = GlassDefaults.RIM_WIDTH; prefs.glassRimWidth = rimWidth
                        rimAngle = GlassDefaults.RIM_ANGLE; prefs.glassRimAngle = rimAngle
                        merge = GlassDefaults.BUBBLE_MERGE; prefs.glassBubbleMerge = merge
                    },
                )

                SectionHeader(
                    title = "Bubbles",
                    subtitle = "The glass chat bubbles.",
                )
                ToggleItem(
                    title = "Merge grouped messages",
                    subtitle = "Messages in a run square their corner toward the one above.",
                    checked = merge,
                    onCheckedChange = { merge = it; prefs.glassBubbleMerge = it },
                )

                StubNote(
                    text = "Best on a dark theme. On a light one the white tint all but disappears.",
                )
            }
        }
    }
}

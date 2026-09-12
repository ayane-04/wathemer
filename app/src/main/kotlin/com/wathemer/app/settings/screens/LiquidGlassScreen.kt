package com.wathemer.app.settings.screens

import android.widget.Toast
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
import com.wathemer.app.settings.components.ExpandGroup
import com.wathemer.app.settings.components.NavTopBar
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.components.SectionHeader
import com.wathemer.app.settings.components.SliderItem
import com.wathemer.app.settings.components.NoteText
import com.wathemer.app.settings.components.ToggleItem
import com.wathemer.app.settings.components.TopBarMenu
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.prefs.GlassDefaults
import com.wathemer.app.settings.prefs.Prefs
import kotlin.math.roundToInt

/** Not wired to the live preview: glass samples WhatsApp's real backdrop, and the hook reads these values once at install. */
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
    var saturation by remember { mutableIntStateOf(prefs.glassSaturation) }
    var hued by remember { mutableStateOf(prefs.glassHuedTint) }
    var linearCopy by remember { mutableStateOf(prefs.glassLinearCopy) }
    var edgeShadow by remember { mutableIntStateOf(prefs.glassEdgeShadow) }
    var glow by remember { mutableIntStateOf(prefs.glassGlow) }
    var clarity by remember { mutableIntStateOf(prefs.glassEdgeClarity) }
    var liveClarity by remember { mutableStateOf(prefs.glassLiveClarity) }
    var navDroplet by remember { mutableStateOf(prefs.glassNavDroplet) }
    var assemble by remember { mutableStateOf(prefs.glassAssemble) }
    var oneBlur by remember { mutableStateOf(prefs.glassOneBlur) }
    var smallOptics by remember { mutableStateOf(prefs.glassSmallOptics) }
    var popupMorph by remember { mutableStateOf(prefs.glassPopupMorph) }
    var rowOptics by remember { mutableStateOf(prefs.glassRowOptics) }
    var rim by remember { mutableIntStateOf(prefs.glassRim) }
    var rimWidth by remember { mutableIntStateOf(prefs.glassRimWidth) }
    var rimAngle by remember { mutableIntStateOf(prefs.glassRimAngle) }

    fun restoreDefaults() {
        // Through GlassDefaults, or this action and the shipped values drift apart.
        blur = GlassDefaults.BLUR; prefs.glassBlur = blur
        tint = GlassDefaults.TINT; prefs.glassTint = tint
        displace = GlassDefaults.DISPLACE; prefs.glassDisplace = displace
        bevel = GlassDefaults.BEVEL; prefs.glassBevel = bevel
        radius = GlassDefaults.RADIUS; prefs.glassRadius = radius
        gamma = GlassDefaults.GAMMA; prefs.glassGamma = gamma
        saturation = GlassDefaults.SATURATION; prefs.glassSaturation = saturation
        rim = GlassDefaults.RIM; prefs.glassRim = rim
        rimWidth = GlassDefaults.RIM_WIDTH; prefs.glassRimWidth = rimWidth
        rimAngle = GlassDefaults.RIM_ANGLE; prefs.glassRimAngle = rimAngle
        merge = GlassDefaults.BUBBLE_MERGE; prefs.glassBubbleMerge = merge
        hued = GlassDefaults.HUED_TINT; prefs.glassHuedTint = hued
        linearCopy = GlassDefaults.LINEAR_COPY; prefs.glassLinearCopy = linearCopy
        edgeShadow = GlassDefaults.EDGE_SHADOW; prefs.glassEdgeShadow = edgeShadow
        glow = GlassDefaults.GLOW; prefs.glassGlow = glow
        clarity = GlassDefaults.EDGE_CLARITY; prefs.glassEdgeClarity = clarity
        liveClarity = GlassDefaults.LIVE_CLARITY; prefs.glassLiveClarity = liveClarity
        navDroplet = GlassDefaults.NAV_DROPLET; prefs.glassNavDroplet = navDroplet
        assemble = GlassDefaults.ASSEMBLE; prefs.glassAssemble = assemble
        oneBlur = GlassDefaults.ONE_BLUR; prefs.glassOneBlur = oneBlur
        smallOptics = GlassDefaults.SMALL_OPTICS; prefs.glassSmallOptics = smallOptics
        popupMorph = GlassDefaults.POPUP_MORPH; prefs.glassPopupMorph = popupMorph
        rowOptics = GlassDefaults.ROW_OPTICS; prefs.glassRowOptics = rowOptics
    }

    fun offOr(v: Float, unit: String) = if (v.toInt() == 0) "Off" else "${v.toInt()}$unit"

    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(
                title = "Liquid Glass",
                onBack = { nav.pop() },
                trailing = { TopBarMenu(listOf("Restore recommended values" to { restoreDefaults() })) },
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
            ) {
                ToggleItem(
                    title = "Enable",
                    subtitle = if (wallpaperReady) "" else "Needs a wallpaper",
                    checked = enabled,
                    onCheckedChange = { want ->
                        // Glass without a wallpaper is broken, so refuse the enable here where the user learns why; turning off is always allowed.
                        if (want && !wallpaperReady) {
                            Toast.makeText(context, "Set a wallpaper first.", Toast.LENGTH_LONG).show()
                            return@ToggleItem
                        }
                        enabled = want
                        prefs.glassEnabled = want
                    },
                )

                ExpandGroup("Surface") {
                    SliderItem(
                        title = "Backdrop blur",
                        value = blur.toFloat(),
                        range = 4f..40f,
                        steps = 35,
                        // roundToInt, never toInt (snap lands under the tick, truncation stalls the handle); valueLabel's toInt is exact.
                        onValueChange = { blur = it.roundToInt().also { v -> prefs.glassBlur = v } },
                        valueLabel = { "${it.toInt()}" },
                    )
                    SliderItem(
                        title = "Tint strength",
                        value = tint.toFloat(),
                        range = 0f..80f,
                        steps = 79,
                        onValueChange = { tint = it.roundToInt().also { v -> prefs.glassTint = v } },
                        // Shown as a share of the slider, not of an alpha byte.
                        valueLabel = { "${(it / 80f * 100f).roundToInt()}%" },
                    )
                    ToggleItem(
                        title = "Tint follows the wallpaper's colour",
                        checked = hued,
                        onCheckedChange = { hued = it; prefs.glassHuedTint = it },
                    )
                    SliderItem(
                        title = "Corner radius",
                        value = radius.toFloat(),
                        range = 0f..40f,
                        steps = 39,
                        onValueChange = { radius = it.roundToInt().also { v -> prefs.glassRadius = v } },
                        valueLabel = { "${it.toInt()}" },
                    )
                }

                ExpandGroup("Edges") {
                    SliderItem(
                        title = "Edge refraction",
                        value = displace.toFloat(),
                        range = 0f..60f,
                        steps = 59,
                        onValueChange = { displace = it.roundToInt().also { v -> prefs.glassDisplace = v } },
                        valueLabel = { offOr(it, "") },
                    )
                    SliderItem(
                        title = "Edge clarity",
                        value = clarity.toFloat(),
                        range = 0f..100f,
                        steps = 99,
                        onValueChange = { clarity = it.roundToInt().also { v -> prefs.glassEdgeClarity = v } },
                        valueLabel = { offOr(it, "%") },
                    )
                    SliderItem(
                        title = "Highlight strength",
                        value = rim.toFloat(),
                        range = 0f..100f,
                        steps = 99,
                        onValueChange = { rim = it.roundToInt().also { v -> prefs.glassRim = v } },
                        valueLabel = { offOr(it, "%") },
                    )
                }

                ExpandGroup("Advanced", trailingText = "16") {
                    SliderItem(
                        title = "Bevel width",
                        value = bevel.toFloat(),
                        range = 5f..40f,
                        steps = 34,
                        onValueChange = { bevel = it.roundToInt().also { v -> prefs.glassBevel = v } },
                        valueLabel = { "${it.toInt()}%" },
                    )
                    SliderItem(
                        title = "Backdrop contrast",
                        value = gamma.toFloat(),
                        range = 30f..100f,
                        steps = 69,
                        onValueChange = { gamma = it.roundToInt().also { v -> prefs.glassGamma = v } },
                        valueLabel = { if (it.toInt() == 100) "Off" else "${it.toInt()}%" },
                    )
                    SliderItem(
                        title = "Colour",
                        value = saturation.toFloat(),
                        range = 100f..200f,
                        steps = 99,
                        onValueChange = { saturation = it.roundToInt().also { v -> prefs.glassSaturation = v } },
                        valueLabel = { if (it.toInt() == 100) "Off" else "${it.toInt()}%" },
                    )
                    SliderItem(
                        title = "Edge shadow",
                        value = edgeShadow.toFloat(),
                        range = 0f..30f,
                        steps = 29,
                        onValueChange = { edgeShadow = it.roundToInt().also { v -> prefs.glassEdgeShadow = v } },
                        valueLabel = { offOr(it, "%") },
                    )
                    SliderItem(
                        title = "Glow",
                        value = glow.toFloat(),
                        range = 0f..100f,
                        steps = 99,
                        onValueChange = { glow = it.roundToInt().also { v -> prefs.glassGlow = v } },
                        valueLabel = { offOr(it, "%") },
                    )
                    ToggleItem(
                        title = "Blur in linear light",
                        checked = linearCopy,
                        onCheckedChange = { linearCopy = it; prefs.glassLinearCopy = it },
                    )
                    SliderItem(
                        title = "Highlight width",
                        value = rimWidth.toFloat(),
                        range = 1f..4f,
                        steps = 2,
                        onValueChange = { rimWidth = it.roundToInt().also { v -> prefs.glassRimWidth = v } },
                        valueLabel = { "${it.toInt()}" },
                    )
                    SliderItem(
                        title = "Highlight angle",
                        value = rimAngle.toFloat(),
                        range = 0f..360f,
                        steps = 71,
                        onValueChange = { rimAngle = it.roundToInt().also { v -> prefs.glassRimAngle = v } },
                        valueLabel = { "${it.toInt()}°" },
                    )
                    ToggleItem(
                        title = "Edge clarity on live panels",
                        subtitle = "Costs more to draw",
                        checked = liveClarity,
                        onCheckedChange = { liveClarity = it; prefs.glassLiveClarity = it },
                    )
                    ToggleItem(
                        title = "One blur for every surface",
                        checked = oneBlur,
                        onCheckedChange = { oneBlur = it; prefs.glassOneBlur = it },
                    )
                    ToggleItem(
                        title = "Merge grouped messages",
                        checked = merge,
                        onCheckedChange = { merge = it; prefs.glassBubbleMerge = it },
                    )
                    SectionHeader("Experiments")
                    ToggleItem(
                        title = "The tab pill flows",
                        checked = navDroplet,
                        onCheckedChange = { navDroplet = it; prefs.glassNavDroplet = it },
                    )
                    ToggleItem(
                        title = "Surfaces assemble as they appear",
                        checked = assemble,
                        onCheckedChange = { assemble = it; prefs.glassAssemble = it },
                    )
                    ToggleItem(
                        title = "Optics on the small controls",
                        checked = smallOptics,
                        onCheckedChange = { smallOptics = it; prefs.glassSmallOptics = it },
                    )
                    ToggleItem(
                        title = "Menus grow from their button",
                        checked = popupMorph,
                        onCheckedChange = { popupMorph = it; prefs.glassPopupMorph = it },
                    )
                    ToggleItem(
                        title = "Optics on the selected chat",
                        checked = rowOptics,
                        onCheckedChange = { rowOptics = it; prefs.glassRowOptics = it },
                    )
                }

                // Warning only: tokens still save, glass-owned surfaces ignore them.
                NoteText("Glass surfaces keep their own look; your colours do not reach them.")
            }
        }
    }
}

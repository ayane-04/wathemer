package com.wathemer.app.settings.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wathemer.app.settings.components.CategoryRow
import com.wathemer.app.settings.components.NavTopBar
import com.wathemer.app.settings.components.OverrideRow
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.components.PreviewPanel
import com.wathemer.app.settings.components.SectionHeader
import com.wathemer.app.settings.components.ToggleItem
import com.wathemer.app.settings.components.TokenRow
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.nav.Screen
import com.wathemer.app.settings.picker.ColorPickerSheet
import com.wathemer.app.settings.prefs.Prefs
import com.wathemer.app.settings.preview.LocalThemeSnapshot
import com.wathemer.app.settings.preview.WaPreview
import com.wathemer.app.settings.preview.WaPreviewTab
import com.wathemer.app.settings.preview.elevateInt
import com.wathemer.app.settings.preview.lumaArgb

/** Global colours root: quick presets, four sub-screens, the icon pack toggle, and a reset link. */
@Composable
fun GlobalColorsScreen(
    nav: NavController,
    prefs: Prefs,
    onMessage: (String) -> Unit,
) {
    val snapshot = LocalThemeSnapshot.current
    var primary by remember { mutableIntStateOf(prefs.primary) }
    var background by remember { mutableIntStateOf(prefs.background) }
    var text by remember { mutableIntStateOf(prefs.text) }
    var iconPack by remember { mutableStateOf(prefs.iosIconPack) }
    val activePresetName = detectPresetName(primary, background, text)

    fun applyPreset(p: Preset) {
        // Set globals and clear every override in one commit, keyed to Prefs.ALL_COLOR_OVERRIDE_KEYS so it cannot drift.
        prefs.applyPresetFull(p.primary, p.background, p.text)
        primary = p.primary; background = p.background; text = p.text

        // Unread circle cascades from the accent; the digit takes whichever colour reads best over it.
        prefs.unreadAccent = 0                              // cascade -> primary
        prefs.unreadCountText = onAccentFor(p.primary, p.text)

        // Overrides for the surfaces where a plain cascade looks bad.
        prefs.setOverride(Prefs.OVR_SEARCH_INNER_BG, elevateInt(p.background, 0.06f))
        prefs.setOverride(Prefs.OVR_NAVBAR_DIVIDER,  elevateInt(p.background, 0.05f))
        prefs.setOverride(Prefs.OVR_MINI_FAB_BG,     p.miniFabBg ?: elevateInt(p.background, 0.14f))
        prefs.setOverride(Prefs.OVR_FAB_ICON,        onAccentFor(p.primary, p.text))
        // The active pill defaults to primary; Bordeaux swaps in another colour to keep icons readable.
        if (p.activePill != p.primary) {
            prefs.setOverride(Prefs.OVR_TAB_ACTIVE_PILL, p.activePill)
        }

        // Preset bubble defaults.
        val accentRgb = p.primary and 0x00FFFFFF
        prefs.setOverride(Prefs.BUBBLE_RIGHT_BG, 0x28FFFFFF)
        prefs.setOverride(Prefs.BUBBLE_LEFT_BG,  0x33000000 or accentRgb)

        // Reseed the shared snapshot so every preview repaints instantly.
        snapshot.value = com.wathemer.app.settings.preview.snapshotFromPrefs(prefs)

        onMessage("${p.label} applied.")
    }

    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(
                title = "Global colours",
                subtitle = "$activePresetName preset",
                onBack = { nav.pop() },
            )

            PreviewPanel(modifier = Modifier.weight(0.4f).fillMaxWidth()) {
                // The homescreen mock exercises the widest cross-section of tokens; rebuilt from prefs each recompose.
                WaPreview(WaPreviewTab.Home, prefs)
            }

            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SectionHeader(title = "Quick presets")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESETS.forEach { p ->
                        PresetChip(p, active = activePresetName == p.label, onClick = { applyPreset(p) })
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryRow(
                        label = "Colours",
                        description = "Accent, background and text.",
                        onClick = { nav.push(Screen.GlobalColorsTokens) },
                    )
                    CategoryRow(
                        label = "Unread badges",
                        description = "The unread-count badges.",
                        onClick = { nav.push(Screen.GlobalColorsUnread) },
                    )
                    CategoryRow(
                        label = "Selection mode",
                        description = "The long-press toolbar that appears when selecting chat rows or messages. Shared across Home and Conversation.",
                        onClick = { nav.push(Screen.GlobalColorsToolbar) },
                    )
                    // Wallpaper and Liquid Glass live under Screen.Backdrop; Status bar stays here as a colour feature.
                    CategoryRow(
                        label = "Status bar",
                        description = "Match the phone's status bar to your theme.",
                        onClick = { nav.push(Screen.StatusBar) },
                    )
                }

                ToggleItem(
                    title = "Use iOS icon pack",
                    // The preview above draws its own glyphs and does not change with this toggle.
                    subtitle = "Swap WhatsApp's icons for an iOS-style set. Your colours still apply.",
                    checked = iconPack,
                    onCheckedChange = { iconPack = it; prefs.iosIconPack = it },
                )

                Spacer(Modifier.size(4.dp))
                Text(
                    "Reset to stock WhatsApp",
                    color = Palette.FgMuted,
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp,
                    // Clears rather than writes: an unset global means "substitute nothing", so WhatsApp goes back to stock.
                    modifier = Modifier.clickable {
                        prefs.resetToStock()
                        primary = prefs.primary; background = prefs.background; text = prefs.text
                        snapshot.value = com.wathemer.app.settings.preview.snapshotFromPrefs(prefs)
                        onMessage("Reset to stock.")
                    }.padding(top = 6.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/* ── Shared sub-screen scaffold ─────────────────────────────────────────── */

@Composable
private fun GlobalSubScreenScaffold(
    nav: NavController,
    title: String,
    subtitle: String,
    preview: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(title = title, subtitle = subtitle, onBack = { nav.pop() })
            PreviewPanel(modifier = Modifier.weight(0.4f).fillMaxWidth()) { preview() }
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
            }
        }
    }
}

/* ── 1. Global Colors -> Colours (Accent/Background/Text) ────────────────── */

@Composable
fun GlobalColorsTokensScreen(nav: NavController, prefs: Prefs) {
    val snapshot = LocalThemeSnapshot.current
    var primary by remember { mutableIntStateOf(prefs.primary) }
    var background by remember { mutableIntStateOf(prefs.background) }
    var text by remember { mutableIntStateOf(prefs.text) }
    var picking by remember { mutableStateOf<CoreToken?>(null) }
    val recents = remember(snapshot.value) { prefs.recents }
    GlobalSubScreenScaffold(
        nav, title = "Colours", subtitle = "Global · Colours",
        preview = { WaPreview(WaPreviewTab.Home, prefs) },
    ) {
        TokenRow(icon = "◐", name = "Accent",     color = primary,    onClick = { picking = CoreToken.PRIMARY })
        TokenRow(icon = "■", name = "Background", color = background, onClick = { picking = CoreToken.BACKGROUND })
        TokenRow(icon = "A", name = "Text",       color = text,       onClick = { picking = CoreToken.TEXT })
    }
    picking?.let { tok ->
        ColorPickerSheet(
            title = tok.label,
            subtitle = "Global · ${tok.label}",
            initialColor = when (tok) { CoreToken.PRIMARY -> primary; CoreToken.BACKGROUND -> background; CoreToken.TEXT -> text },
            recents = recents,
            onDismiss = { picking = null },
            onApply = { picked ->
                when (tok) {
                    CoreToken.PRIMARY    -> { primary    = picked; prefs.primary    = picked }
                    CoreToken.BACKGROUND -> { background = picked; prefs.background = picked }
                    CoreToken.TEXT       -> { text       = picked; prefs.text       = picked }
                }
                prefs.pushRecent(picked)
                snapshot.value = com.wathemer.app.settings.preview.snapshotFromPrefs(prefs)
                picking = null
            },
        )
    }
}

/* ── 2. Global Colors -> Unread badges ───────────────────────────────────── */

@Composable
fun GlobalColorsUnreadScreen(nav: NavController, prefs: Prefs) {
    val snapshot = LocalThemeSnapshot.current
    var unreadAccent by remember { mutableIntStateOf(prefs.unreadAccent) }
    var unreadCountText by remember { mutableIntStateOf(prefs.unreadCountText) }
    var picking by remember { mutableStateOf<UnreadToken?>(null) }
    val recents = remember(snapshot.value) { prefs.recents }
    val effAcc = if (unreadAccent != 0) unreadAccent else prefs.primary
    val effCnt = if (unreadCountText != 0) unreadCountText else prefs.text
    GlobalSubScreenScaffold(
        nav, title = "Unread badges", subtitle = "Global · Unread badges",
        preview = { WaPreview(WaPreviewTab.Home, prefs) },
    ) {
        TokenRow(icon = "●", name = "Unread accent",     color = effAcc, onClick = { picking = UnreadToken.ACCENT })
        TokenRow(icon = "#", name = "Unread count text", color = effCnt, onClick = { picking = UnreadToken.COUNT })
    }
    picking?.let { tok ->
        ColorPickerSheet(
            title = tok.label,
            subtitle = "Global · ${tok.label}",
            initialColor = if (tok == UnreadToken.ACCENT) effAcc else effCnt,
            recents = recents,
            onDismiss = { picking = null },
            onApply = { picked ->
                when (tok) {
                    UnreadToken.ACCENT -> { unreadAccent = picked; prefs.unreadAccent = picked }
                    UnreadToken.COUNT  -> { unreadCountText = picked; prefs.unreadCountText = picked }
                }
                prefs.pushRecent(picked)
                snapshot.value = com.wathemer.app.settings.preview.snapshotFromPrefs(prefs)
                picking = null
            },
        )
    }
}

/* ── 3. Global Colors -> Selection mode (long-press toolbar tokens) ──────── */

@Composable
fun GlobalColorsToolbarScreen(nav: NavController, prefs: Prefs) {
    // Action-mode tokens stay here: one shared id applies across both Home and Chat.
    var amBg          by remember { mutableIntStateOf(prefs.getOverride(Prefs.OVR_ACTION_MODE_BG)) }
    var amIcons       by remember { mutableIntStateOf(prefs.getOverride(Prefs.OVR_ACTION_MODE_ICONS)) }
    var amTitle       by remember { mutableIntStateOf(prefs.getOverride(Prefs.OVR_ACTION_MODE_TITLE)) }
    var amCloseRipple by remember { mutableIntStateOf(prefs.getOverride(Prefs.OVR_ACTION_MODE_CLOSE_RIPPLE)) }
    GlobalSubScreenScaffold(
        nav, title = "Selection mode", subtitle = "Global · Selection",
        preview = { WaPreview(WaPreviewTab.Selection, prefs) },
    ) {
        SectionHeader(title = "Selection mode", subtitle = "The long-press toolbar that appears when selecting chat rows or messages. Shared across Home and Conversation.")
        // Pass subtitle explicitly on all four or the pickers caption themselves "Global · Toolbar".
        val crumb = "Global · Selection"
        ToolbarRow("Selection bar background", amBg,          Prefs.OVR_ACTION_MODE_BG,           prefs.background, prefs, subtitle = crumb) { amBg = it }
        ToolbarRow("Selection bar icons",      amIcons,       Prefs.OVR_ACTION_MODE_ICONS,        prefs.text,       prefs, subtitle = crumb) { amIcons = it }
        ToolbarRow("Selection count text",     amTitle,       Prefs.OVR_ACTION_MODE_TITLE,        prefs.text,       prefs, subtitle = crumb) { amTitle = it }
        ToolbarRow("Selection close ripple",   amCloseRipple, Prefs.OVR_ACTION_MODE_CLOSE_RIPPLE, prefs.primary,    prefs, subtitle = crumb) { amCloseRipple = it }
    }
}

/** Toolbar token row with state hoisted to caller for live preview re-render. */
@Composable
private fun ToolbarRow(
    name: String,
    value: Int,
    prefKey: String,
    fallback: Int,
    prefs: Prefs,
    isNew: Boolean = false,
    subtitle: String = "Global · Toolbar",
    onChange: (Int) -> Unit,
) {
    val snapshot = LocalThemeSnapshot.current
    var showPicker by remember { mutableStateOf(false) }
    val recents = remember(snapshot.value) { prefs.recents }
    OverrideRow(
        name = name,
        overrideValue = value,
        globalValue = fallback,
        onPickCustom = { showPicker = true },
        onResetToGlobal = {
            onChange(0)
            prefs.setOverride(prefKey, 0)
            snapshot.value = com.wathemer.app.settings.preview.snapshotFromPrefs(prefs)
        },
        isNew = isNew,
    )
    if (showPicker) {
        ColorPickerSheet(
            title = name,
            subtitle = subtitle,
            initialColor = if (value != 0) value else fallback,
            recents = recents,
            onDismiss = { showPicker = false },
            onApply = { picked ->
                onChange(picked)
                prefs.setOverride(prefKey, picked)
                prefs.pushRecent(picked)
                snapshot.value = com.wathemer.app.settings.preview.snapshotFromPrefs(prefs)
                showPicker = false
            },
        )
    }
}

/* ── Global Colours -> Status bar ────────────────────────────────────────── */

@Composable
fun StatusBarScreen(nav: NavController, prefs: Prefs) {
    var enabled by remember { mutableStateOf(prefs.systemBarsEnabled) }
    var autoIcons by remember { mutableStateOf(prefs.systemBarAutoIcons) }
    val barsSnapshot = LocalThemeSnapshot.current
    var statusBg by remember { mutableIntStateOf(prefs.getOverride(Prefs.OVR_STATUS_BAR_BG)) }
    // Shown when the override is 0; must match the SystemBars cascade: status, then home toolbar bg, then accent.
    val statusFallback = prefs.getOverride(Prefs.OVR_TOOLBAR_BG).takeIf { it != 0 } ?: prefs.primary
    GlobalSubScreenScaffold(
        nav, title = "Status bar", subtitle = "Global · Status bar",
        preview = { WaPreview(WaPreviewTab.Home, prefs) },
    ) {
        ToggleItem(
            title = "Theme status bar",
            subtitle = "Match the status bar to your theme. Off while a wallpaper is set.",
            checked = enabled,
            onCheckedChange = { enabled = it; prefs.systemBarsEnabled = it
                // Rebuild the snapshot here or the preview stays stale: this path writes only to prefs.
                barsSnapshot.value = com.wathemer.app.settings.preview.snapshotFromPrefs(prefs) },
        )
        SectionHeader(
            title = "Bar colour",
            subtitle = "Follows your toolbar colour unless you set one here.",
        )
        ToolbarRow("Status bar", statusBg, Prefs.OVR_STATUS_BAR_BG, statusFallback, prefs, subtitle = "Global · Status bar") { statusBg = it }
        ToggleItem(
            title = "Auto icon contrast",
            subtitle = "Keep the clock and battery icons readable on your colour.",
            checked = autoIcons,
            onCheckedChange = { autoIcons = it; prefs.systemBarAutoIcons = it },
        )
    }
}

/* ── Shared bits ────────────────────────────────────────────────────────── */

@Composable
private fun PresetChip(preset: Preset, active: Boolean, onClick: () -> Unit) {
    val borderColor = if (active) Color(preset.primary) else Palette.Rule
    val borderWidth = if (active) 1.5.dp else 1.dp
    Row(
        modifier = Modifier
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.size(width = 8.dp, height = 18.dp).background(Color(preset.primary), RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)))
            Box(Modifier.size(width = 8.dp, height = 18.dp).background(Color(preset.background)))
            Box(Modifier.size(width = 8.dp, height = 18.dp).background(Color(preset.text), RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
        }
        Spacer(Modifier.size(10.dp))
        Text(
            preset.label,
            color = if (active) Color.White else Palette.FgMuted,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
            letterSpacing = (-0.1).sp,
        )
    }
}

private enum class CoreToken(val label: String) {
    PRIMARY("Accent"), BACKGROUND("Background"), TEXT("Text"),
}

private enum class UnreadToken(val label: String) {
    ACCENT("Unread accent"), COUNT("Unread count text"),
}

internal data class Preset(
    val label: String,
    val primary: Int,
    val background: Int,
    val text: Int,
    /** Navbar active pill, defaults to [primary]; Bordeaux goes burgundy because brass reads poorly on cream icons. */
    val activePill: Int = primary,
    /** Mini-fab background, defaults to a derived lift of [background]; Bordeaux swaps in a palette burgundy. */
    val miniFabBg: Int? = null,
)

private val PRESETS = listOf(
    // Matte Coral: a warm, dark coral, nothing neon.
    Preset("Coral",    0xFFC95548.toInt(), 0xFF0A0A0A.toInt(), 0xFFFFFFFF.toInt()),
    // Bordeaux Study: deep oxblood ground, brass accent, cream text.
    Preset(
        label = "Bordeaux",
        primary = 0xFFC8A878.toInt(),     // brass
        background = 0xFF1A0E0F.toInt(),  // deep oxblood
        text = 0xFFF0E5D6.toInt(),        // warm cream
        activePill = 0xFF6B2228.toInt(),  // burgundy red, contrasts with cream icons
        miniFabBg = 0xFF3A1F22.toInt(),   // mid-tone burgundy from palette
    ),
    // Matte Lavender: a desaturated soft purple.
    Preset("Lavender", 0xFFA993CC.toInt(), 0xFF09080C.toInt(), 0xFFFFFFFF.toInt()),
)

/** Foreground that reads over [accent]. The dark branch does fire; do not "fix" this to always return [text]. */
private fun onAccentFor(accent: Int, text: Int): Int =
    if (lumaArgb(accent) > 0.55f) 0xFF101010.toInt() else text

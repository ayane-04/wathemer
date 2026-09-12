package com.wathemer.app.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.wathemer.app.settings.components.ExpandGroup
import com.wathemer.app.settings.components.LocalAccent
import com.wathemer.app.settings.components.OverrideRow
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.components.ToggleItem
import com.wathemer.app.settings.components.TokenRow
import com.wathemer.app.settings.components.TopBarMenu
import com.wathemer.app.settings.components.luminance
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.nav.Screen
import com.wathemer.app.settings.picker.ColorPickerSheet
import com.wathemer.app.settings.prefs.Prefs
import com.wathemer.app.settings.preview.LocalThemeSnapshot
import com.wathemer.app.settings.preview.WaPreview
import com.wathemer.app.settings.preview.WaPreviewKind
import com.wathemer.app.settings.preview.elevateInt
import com.wathemer.app.settings.preview.snapshotFromPrefs

/** Colours root: the presets, the three globals as swatches, the three pages beside them and the icon pack. */
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
    var picking by remember { mutableStateOf<CoreToken?>(null) }
    val recents = remember(snapshot.value) { prefs.recents }
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
        snapshot.value = snapshotFromPrefs(prefs)

        onMessage("${p.label} applied.")
    }

    SubScreenScaffold(
        nav, title = "Colours",
        preview = { WaPreview(WaPreviewKind.HomeFull, prefs) },
        trailing = {
            TopBarMenu(
                listOf(
                    // Clears rather than writes: an unset global means "substitute nothing", so WhatsApp goes back to stock.
                    "Reset to stock WhatsApp" to {
                        prefs.resetToStock()
                        primary = prefs.primary; background = prefs.background; text = prefs.text
                        snapshot.value = snapshotFromPrefs(prefs)
                        onMessage("Reset to stock.")
                    },
                ),
            )
        },
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PRESETS.forEach { p ->
                PresetChip(p, active = activePresetName == p.label, onClick = { applyPreset(p) })
            }
        }
        ExpandGroup("Colours") {
            TokenRow(name = "Accent",     color = primary,    onClick = { picking = CoreToken.PRIMARY })
            TokenRow(name = "Background", color = background, onClick = { picking = CoreToken.BACKGROUND })
            TokenRow(name = "Text",       color = text,       onClick = { picking = CoreToken.TEXT })
        }
        ExpandGroup("More") {
            CategoryRow(label = "Unread badges", onClick = { nav.push(Screen.GlobalColorsUnread) })
            CategoryRow(label = "Selection mode", onClick = { nav.push(Screen.GlobalColorsToolbar) })
            // Status bar stays a colour feature; wallpaper and glass have their own pages.
            CategoryRow(label = "Status bar", onClick = { nav.push(Screen.StatusBar) })
            // The preview above draws its own glyphs and does not change with this toggle.
            ToggleItem(
                title = "Use iOS icon pack",
                checked = iconPack,
                onCheckedChange = { iconPack = it; prefs.iosIconPack = it },
            )
        }
    }

    picking?.let { tok ->
        ColorPickerSheet(
            title = tok.label,
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
                snapshot.value = snapshotFromPrefs(prefs)
                picking = null
            },
        )
    }
}

// ── Colours -> Unread badges ─────────────────────────────────────────────

@Composable
fun GlobalColorsUnreadScreen(nav: NavController, prefs: Prefs) {
    val snapshot = LocalThemeSnapshot.current
    var unreadAccent by remember { mutableIntStateOf(prefs.unreadAccent) }
    var unreadCountText by remember { mutableIntStateOf(prefs.unreadCountText) }
    var picking by remember { mutableStateOf<UnreadToken?>(null) }
    val recents = remember(snapshot.value) { prefs.recents }
    val effAcc = if (unreadAccent != 0) unreadAccent else prefs.primary
    val effCnt = if (unreadCountText != 0) unreadCountText else prefs.text
    SubScreenScaffold(
        nav, title = "Unread badges",
        preview = { WaPreview(WaPreviewKind.HomeFull, prefs) },
    ) {
        TokenRow(name = "Unread accent", color = effAcc, onClick = { picking = UnreadToken.ACCENT })
        TokenRow(name = "Unread count text", color = effCnt, divider = false, onClick = { picking = UnreadToken.COUNT })
    }
    picking?.let { tok ->
        ColorPickerSheet(
            title = tok.label,
            initialColor = if (tok == UnreadToken.ACCENT) effAcc else effCnt,
            recents = recents,
            onDismiss = { picking = null },
            onApply = { picked ->
                when (tok) {
                    UnreadToken.ACCENT -> { unreadAccent = picked; prefs.unreadAccent = picked }
                    UnreadToken.COUNT  -> { unreadCountText = picked; prefs.unreadCountText = picked }
                }
                prefs.pushRecent(picked)
                snapshot.value = snapshotFromPrefs(prefs)
                picking = null
            },
        )
    }
}

// ── Colours -> Selection mode (long-press toolbar tokens) ────────────────

@Composable
fun GlobalColorsToolbarScreen(nav: NavController, prefs: Prefs) {
    // Action-mode tokens stay here: one shared id applies across both Home and Chat.
    var amBg          by remember { mutableIntStateOf(prefs.getOverride(Prefs.OVR_ACTION_MODE_BG)) }
    var amIcons       by remember { mutableIntStateOf(prefs.getOverride(Prefs.OVR_ACTION_MODE_ICONS)) }
    var amTitle       by remember { mutableIntStateOf(prefs.getOverride(Prefs.OVR_ACTION_MODE_TITLE)) }
    var amCloseRipple by remember { mutableIntStateOf(prefs.getOverride(Prefs.OVR_ACTION_MODE_CLOSE_RIPPLE)) }
    SubScreenScaffold(
        nav, title = "Selection mode",
        preview = { WaPreview(WaPreviewKind.Selection, prefs) },
    ) {
        ToolbarRow("Selection bar background", amBg,          Prefs.OVR_ACTION_MODE_BG,           prefs.background, prefs) { amBg = it }
        ToolbarRow("Selection bar icons", amIcons,       Prefs.OVR_ACTION_MODE_ICONS,        prefs.text,       prefs) { amIcons = it }
        ToolbarRow("Selection count text", amTitle,       Prefs.OVR_ACTION_MODE_TITLE,        prefs.text,       prefs) { amTitle = it }
        ToolbarRow("Selection close ripple", amCloseRipple, Prefs.OVR_ACTION_MODE_CLOSE_RIPPLE, prefs.primary,    prefs, divider = false) { amCloseRipple = it }
    }
}

/** Override row with state hoisted to the caller for live preview re-render. */
@Composable
private fun ToolbarRow(
    name: String,
    value: Int,
    prefKey: String,
    fallback: Int,
    prefs: Prefs,
    divider: Boolean = true,
    onChange: (Int) -> Unit,
) {
    val snapshot = LocalThemeSnapshot.current
    var showPicker by remember { mutableStateOf(false) }
    val recents = remember(snapshot.value) { prefs.recents }
    OverrideRow(
        name = name,
        overrideValue = value,
        globalValue = fallback,
        divider = divider,
        onPickCustom = { showPicker = true },
        onResetToGlobal = {
            onChange(0)
            prefs.setOverride(prefKey, 0)
            snapshot.value = snapshotFromPrefs(prefs)
        },
    )
    if (showPicker) {
        ColorPickerSheet(
            title = name,
            initialColor = if (value != 0) value else fallback,
            recents = recents,
            onDismiss = { showPicker = false },
            onApply = { picked ->
                onChange(picked)
                prefs.setOverride(prefKey, picked)
                prefs.pushRecent(picked)
                snapshot.value = snapshotFromPrefs(prefs)
                showPicker = false
            },
        )
    }
}

// ── Colours -> Status bar ────────────────────────────────────────────────

@Composable
fun StatusBarScreen(nav: NavController, prefs: Prefs) {
    var enabled by remember { mutableStateOf(prefs.systemBarsEnabled) }
    var autoIcons by remember { mutableStateOf(prefs.systemBarAutoIcons) }
    val barsSnapshot = LocalThemeSnapshot.current
    var statusBg by remember { mutableIntStateOf(prefs.getOverride(Prefs.OVR_STATUS_BAR_BG)) }
    // Shown when the override is 0; must match the SystemBars cascade: status, then home toolbar bg, then accent.
    val statusFallback = prefs.getOverride(Prefs.OVR_TOOLBAR_BG).takeIf { it != 0 } ?: prefs.primary
    SubScreenScaffold(
        nav, title = "Status bar",
        preview = { WaPreview(WaPreviewKind.HomeFull, prefs) },
    ) {
        ToggleItem(
            title = "Theme status bar",
            subtitle = if (barsSnapshot.value.wallpaperEnabled) "Off while a wallpaper is set" else "",
            checked = enabled,
            onCheckedChange = { enabled = it; prefs.systemBarsEnabled = it
                // Rebuild the snapshot here or the preview stays stale: this path writes only to prefs.
                barsSnapshot.value = snapshotFromPrefs(prefs) },
        )
        ToolbarRow("Bar colour", statusBg, Prefs.OVR_STATUS_BAR_BG, statusFallback, prefs) { statusBg = it }
        ToggleItem(
            title = "Auto icon contrast",
            checked = autoIcons,
            divider = false,
            onCheckedChange = { autoIcons = it; prefs.systemBarAutoIcons = it },
        )
    }
}

// ── Shared bits ──────────────────────────────────────────────────────────

@Composable
private fun PresetChip(preset: Preset, active: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier
            .border(1.dp, if (active) accent else Palette.RuleStrong, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.size(width = 7.dp, height = 16.dp).background(Color(preset.primary), RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)))
            Box(Modifier.size(width = 7.dp, height = 16.dp).background(Color(preset.background)))
            Box(Modifier.size(width = 7.dp, height = 16.dp).background(Color(preset.text), RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
        }
        Spacer(Modifier.size(8.dp))
        Text(
            preset.label,
            color = if (active) Palette.Fg else Palette.FgMuted,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
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

internal val PRESETS = listOf(
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
    if (luminance(accent) > 0.55f) 0xFF101010.toInt() else text

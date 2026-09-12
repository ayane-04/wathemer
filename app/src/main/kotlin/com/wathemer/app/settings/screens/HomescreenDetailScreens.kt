// The Home screen page: every override of WhatsApp's home, in sections, under one pinned preview.
package com.wathemer.app.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.wathemer.app.settings.components.ExpandGroup
import com.wathemer.app.settings.components.OverrideRow
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.picker.ColorPickerSheet
import com.wathemer.app.settings.prefs.Prefs
import com.wathemer.app.settings.preview.LocalThemeSnapshot
import com.wathemer.app.settings.preview.WaPreview
import com.wathemer.app.settings.preview.WaPreviewKind
import com.wathemer.app.settings.preview.snapshotFromPrefs

/** Stateful OverrideRow wrapper; re-seeds [LocalThemeSnapshot] after every write so the preview repaints live. */
@Composable
private fun PrefsOverrideRow(
    name: String,
    prefKey: String,
    globalValue: Int,
    prefs: Prefs,
    divider: Boolean = true,
) {
    val snapshot = LocalThemeSnapshot.current
    var override by remember { mutableIntStateOf(prefs.getOverride(prefKey)) }
    var showPicker by remember { mutableStateOf(false) }
    val recents = remember(snapshot.value) { prefs.recents }
    OverrideRow(
        name = name,
        overrideValue = override,
        globalValue = globalValue,
        divider = divider,
        onPickCustom = { showPicker = true },
        onResetToGlobal = {
            override = 0
            prefs.setOverride(prefKey, 0)
            snapshot.value = snapshotFromPrefs(prefs)
        },
    )
    if (showPicker) {
        ColorPickerSheet(
            title = name,
            initialColor = if (override != 0) override else globalValue,
            recents = recents,
            onDismiss = { showPicker = false },
            onApply = { picked ->
                override = picked
                prefs.setOverride(prefKey, picked)
                prefs.pushRecent(picked)
                snapshot.value = snapshotFromPrefs(prefs)
                showPicker = false
            },
        )
    }
}

@Composable
fun HomescreenScreen(nav: NavController, prefs: Prefs) {
    SubScreenScaffold(
        nav = nav,
        title = "Home screen",
        preview = { WaPreview(WaPreviewKind.HomeFull, prefs) },
    ) {
        ExpandGroup("Chat list") {
            PrefsOverrideRow("Chat row background", Prefs.OVR_CHATLIST_BG,   prefs.background, prefs)
            PrefsOverrideRow("Contact name", Prefs.OVR_ROW_NAME,      prefs.text,       prefs)
            PrefsOverrideRow("Preview message", Prefs.OVR_ROW_PREVIEW,   prefs.text,       prefs)
            PrefsOverrideRow("Timestamp", Prefs.OVR_ROW_TIMESTAMP, prefs.text,       prefs)
        }
        ExpandGroup("Search bar") {
            PrefsOverrideRow("Outer background", Prefs.OVR_SEARCH_BAR_BG,   prefs.background, prefs)
            PrefsOverrideRow("Inner pill", Prefs.OVR_SEARCH_INNER_BG, prefs.background, prefs)
            PrefsOverrideRow("Magnifier icon", Prefs.OVR_SEARCH_ICON,     prefs.text,       prefs)
            PrefsOverrideRow("Text + hint", Prefs.OVR_SEARCH_TEXT,     prefs.text,       prefs)
        }
        ExpandGroup("Bottom navigation") {
            PrefsOverrideRow("Bar background", Prefs.OVR_NAVBAR_BG,          prefs.background, prefs)
            PrefsOverrideRow("Divider",        Prefs.OVR_NAVBAR_DIVIDER,     prefs.background, prefs)
            PrefsOverrideRow("Active pill",    Prefs.OVR_TAB_ACTIVE_PILL,    prefs.primary,    prefs)
            PrefsOverrideRow("Tab icons", Prefs.OVR_TAB_ICON,           prefs.text,       prefs)
            PrefsOverrideRow("Active label",   Prefs.OVR_TAB_ACTIVE_LABEL,   prefs.text,       prefs)
            PrefsOverrideRow("Inactive label", Prefs.OVR_TAB_INACTIVE_LABEL, prefs.text,       prefs)
        }
        ExpandGroup("Header") {
            // Home-only rows plus the ContactInfoActivity legacy fallback; the Conversation toolbar lives under Chats.
            PrefsOverrideRow("Toolbar background", Prefs.OVR_TOOLBAR_BG,    prefs.background, prefs)
            PrefsOverrideRow("Toolbar icons", Prefs.OVR_TOOLBAR_ICONS, prefs.text,       prefs)
            PrefsOverrideRow("WhatsApp logo", Prefs.OVR_WHATSAPP_LOGO, prefs.text,       prefs)
        }
        ExpandGroup("FAB") {
            PrefsOverrideRow("Background", Prefs.OVR_FAB_BG,   prefs.primary, prefs)
            PrefsOverrideRow("Icon",       Prefs.OVR_FAB_ICON, prefs.text,    prefs)
        }
        ExpandGroup("Meta AI mini-fab") {
            PrefsOverrideRow("Background", Prefs.OVR_MINI_FAB_BG,    prefs.background, prefs)
            PrefsOverrideRow("Label", Prefs.OVR_MINI_FAB_LABEL, prefs.text,       prefs)
        }
    }
}

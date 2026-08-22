// Per-element override pages under Homescreen; every group is its own tap-to-enter sub-page.
package com.wathemer.app.settings.screens

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
import androidx.compose.ui.unit.dp
import com.wathemer.app.settings.components.CategoryRow
import com.wathemer.app.settings.components.NavTopBar
import com.wathemer.app.settings.components.OverrideRow
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.components.PreviewPanel
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.nav.Screen
import com.wathemer.app.settings.picker.ColorPickerSheet
import com.wathemer.app.settings.prefs.Prefs
import com.wathemer.app.settings.preview.LocalThemeSnapshot
import com.wathemer.app.settings.preview.WaPreview
import com.wathemer.app.settings.preview.WaPreviewKind

/* ── Shared scaffolding ─────────────────────────────────────────────────── */

@Composable
private fun OverrideScreenScaffold(
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

/** Stateful OverrideRow wrapper; re-seeds [LocalThemeSnapshot] after every write so previews repaint live. */
@Composable
private fun PrefsOverrideRow(
    name: String,
    prefKey: String,
    globalValue: Int,
    prefs: Prefs,
    pickerTitleSubtitle: String,
) {
    val snapshot = LocalThemeSnapshot.current
    var override by remember { mutableIntStateOf(prefs.getOverride(prefKey)) }
    var showPicker by remember { mutableStateOf(false) }
    val recents = remember(snapshot.value) { prefs.recents }
    OverrideRow(
        name = name,
        overrideValue = override,
        globalValue = globalValue,
        onPickCustom = { showPicker = true },
        onResetToGlobal = {
            override = 0
            prefs.setOverride(prefKey, 0)
            snapshot.value = com.wathemer.app.settings.preview.snapshotFromPrefs(prefs)
        },
    )
    if (showPicker) {
        ColorPickerSheet(
            title = name,
            subtitle = pickerTitleSubtitle,
            initialColor = if (override != 0) override else globalValue,
            recents = recents,
            onDismiss = { showPicker = false },
            onApply = { picked ->
                override = picked
                prefs.setOverride(prefKey, picked)
                prefs.pushRecent(picked)
                snapshot.value = com.wathemer.app.settings.preview.snapshotFromPrefs(prefs)
                showPicker = false
            },
        )
    }
}

/* ── 1. Chat list ───────────────────────────────────────────────────────── */

@Composable
fun HomescreenChatListScreen(nav: NavController, prefs: Prefs) {
    OverrideScreenScaffold(
        nav = nav,
        title = "Chat list",
        subtitle = "Home · Chat list",
        preview = { WaPreview(WaPreviewKind.HomeFull, prefs) },
    ) {
        CategoryRow(
            label = "Rows",
            description = "Row colours.",
            onClick = { nav.push(Screen.HomescreenChatListRows) },
        )
        CategoryRow(
            label = "Search bar",
            description = "The search bar.",
            onClick = { nav.push(Screen.HomescreenChatListSearch) },
        )
    }
}

@Composable
fun HomescreenChatListRowsScreen(nav: NavController, prefs: Prefs) {
    OverrideScreenScaffold(
        nav = nav,
        title = "Rows",
        subtitle = "Home · Chat list · Rows",
        preview = { WaPreview(WaPreviewKind.HomeFull, prefs) },
    ) {
        PrefsOverrideRow("Chat row background", Prefs.OVR_CHATLIST_BG, prefs.background, prefs, "Rows")
        PrefsOverrideRow("Contact name",        Prefs.OVR_ROW_NAME,    prefs.text,       prefs, "Rows")
        PrefsOverrideRow("Preview message",     Prefs.OVR_ROW_PREVIEW, prefs.text,       prefs, "Rows")
        PrefsOverrideRow("Timestamp",           Prefs.OVR_ROW_TIMESTAMP, prefs.text,     prefs, "Rows")
    }
}

@Composable
fun HomescreenChatListSearchScreen(nav: NavController, prefs: Prefs) {
    OverrideScreenScaffold(
        nav = nav,
        title = "Search bar",
        subtitle = "Home · Chat list · Search bar",
        preview = { WaPreview(WaPreviewKind.HomeFull, prefs) },
    ) {
        PrefsOverrideRow("Outer background", Prefs.OVR_SEARCH_BAR_BG,   prefs.background, prefs, "Search bar")
        PrefsOverrideRow("Inner pill",       Prefs.OVR_SEARCH_INNER_BG, prefs.background, prefs, "Search bar")
        PrefsOverrideRow("Magnifier icon",   Prefs.OVR_SEARCH_ICON,     prefs.text,       prefs, "Search bar")
        PrefsOverrideRow("Text + hint",      Prefs.OVR_SEARCH_TEXT,     prefs.text,       prefs, "Search bar")
    }
}

/* ── 2. Bottom navigation ───────────────────────────────────────────────── */

@Composable
fun HomescreenTabBarScreen(nav: NavController, prefs: Prefs) {
    OverrideScreenScaffold(
        nav = nav,
        title = "Bottom navigation",
        subtitle = "Home · Tab bar",
        preview = { WaPreview(WaPreviewKind.HomeFull, prefs) },
    ) {
        CategoryRow(
            label = "Bar",
            description = "Bar and divider.",
            onClick = { nav.push(Screen.HomescreenTabBarBar) },
        )
        CategoryRow(
            label = "Tab items",
            description = "Pill, icons and labels.",
            onClick = { nav.push(Screen.HomescreenTabBarItems) },
        )
    }
}

@Composable
fun HomescreenTabBarBarScreen(nav: NavController, prefs: Prefs) {
    OverrideScreenScaffold(
        nav = nav,
        title = "Bar",
        subtitle = "Home · Tab bar · Bar",
        preview = { WaPreview(WaPreviewKind.HomeFull, prefs) },
    ) {
        PrefsOverrideRow("Bar background", Prefs.OVR_NAVBAR_BG,      prefs.background, prefs, "Bar")
        PrefsOverrideRow("Divider",        Prefs.OVR_NAVBAR_DIVIDER, prefs.background, prefs, "Bar")
    }
}

@Composable
fun HomescreenTabBarItemsScreen(nav: NavController, prefs: Prefs) {
    OverrideScreenScaffold(
        nav = nav,
        title = "Tab items",
        subtitle = "Home · Tab bar · Tab items",
        preview = { WaPreview(WaPreviewKind.HomeFull, prefs) },
    ) {
        PrefsOverrideRow("Active pill",    Prefs.OVR_TAB_ACTIVE_PILL,    prefs.primary, prefs, "Tab items")
        PrefsOverrideRow("Tab icons",      Prefs.OVR_TAB_ICON,           prefs.text,    prefs, "Tab items")
        PrefsOverrideRow("Active label",   Prefs.OVR_TAB_ACTIVE_LABEL,   prefs.text,    prefs, "Tab items")
        PrefsOverrideRow("Inactive label", Prefs.OVR_TAB_INACTIVE_LABEL, prefs.text,    prefs, "Tab items")
    }
}

/* ── 3. Header ──────────────────────────────────────────────────────────── */

@Composable
fun HomescreenHeaderScreen(nav: NavController, prefs: Prefs) {
    OverrideScreenScaffold(
        nav = nav,
        title = "Header",
        subtitle = "Home · Header",
        preview = { WaPreview(WaPreviewKind.HomeFull, prefs) },
    ) {
        // Home-only rows plus the ContactInfoActivity legacy fallback; the Conversation toolbar lives under Chat.
        PrefsOverrideRow("Toolbar background", Prefs.OVR_TOOLBAR_BG,    prefs.background, prefs, "Header")
        PrefsOverrideRow("Toolbar icons",      Prefs.OVR_TOOLBAR_ICONS, prefs.text,       prefs, "Header")
        PrefsOverrideRow("WhatsApp logo",      Prefs.OVR_WHATSAPP_LOGO, prefs.text,       prefs, "Header")
    }
}

/* ── 4. FAB ─────────────────────────────────────────────────────────────── */

@Composable
fun HomescreenFabScreen(nav: NavController, prefs: Prefs) {
    OverrideScreenScaffold(
        nav = nav,
        title = "FAB",
        subtitle = "Home · FAB",
        preview = { WaPreview(WaPreviewKind.HomeFull, prefs) },
    ) {
        CategoryRow(
            label = "Main FAB",
            description = "The + button.",
            onClick = { nav.push(Screen.HomescreenFabMain) },
        )
        CategoryRow(
            label = "Meta AI mini-fab",
            description = "The Meta AI pill.",
            onClick = { nav.push(Screen.HomescreenFabMiniFab) },
        )
    }
}

@Composable
fun HomescreenFabMainScreen(nav: NavController, prefs: Prefs) {
    OverrideScreenScaffold(
        nav = nav,
        title = "Main FAB",
        subtitle = "Home · FAB · Main",
        preview = { WaPreview(WaPreviewKind.HomeFull, prefs) },
    ) {
        PrefsOverrideRow("Background", Prefs.OVR_FAB_BG,   prefs.primary, prefs, "Main FAB")
        PrefsOverrideRow("Icon",       Prefs.OVR_FAB_ICON, prefs.text,    prefs, "Main FAB")
    }
}

@Composable
fun HomescreenFabMiniFabScreen(nav: NavController, prefs: Prefs) {
    OverrideScreenScaffold(
        nav = nav,
        title = "Meta AI mini-fab",
        subtitle = "Home · FAB · Meta AI",
        preview = { WaPreview(WaPreviewKind.HomeFull, prefs) },
    ) {
        PrefsOverrideRow("Background", Prefs.OVR_MINI_FAB_BG,    prefs.background, prefs, "Meta AI mini-fab")
        PrefsOverrideRow("Label",      Prefs.OVR_MINI_FAB_LABEL, prefs.text,       prefs, "Meta AI mini-fab")
    }
}

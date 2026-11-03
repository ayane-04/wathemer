package com.wathemer.app.settings.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wathemer.app.BuildConfig
import com.wathemer.app.R
import com.wathemer.app.settings.components.CategoryRow
import com.wathemer.app.settings.components.NavTopBar
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.components.PreviewPanel
import com.wathemer.app.settings.components.RowIcon
import com.wathemer.app.settings.components.SectionHeader
import com.wathemer.app.settings.components.TopBarMenu
import com.wathemer.app.settings.components.restartWhatsApp
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.nav.Screen
import com.wathemer.app.settings.prefs.Prefs
import com.wathemer.app.settings.prefs.ThemeLibrary
import com.wathemer.app.settings.preview.LocalThemeSnapshot
import com.wathemer.app.settings.preview.WaPreview
import com.wathemer.app.settings.preview.WaPreviewKind
import com.wathemer.app.settings.preview.snapshotFromPrefs
import com.wathemer.app.store.StoreConfig
import com.wathemer.app.store.StoreFiles
import com.wathemer.app.update.isNewerVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Root screen: the live preview, then every destination with its state. No back button. */
@Composable
fun CategoryListScreen(nav: NavController, prefs: Prefs, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val snapshot = LocalThemeSnapshot.current
    val snap by snapshot
    var themed by remember { mutableStateOf(prefs.hasGlobalTheme()) }
    var themeCount by remember { mutableIntStateOf(-1) }
    var storeHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val (count, hint) = withContext(Dispatchers.IO) {
            val slots = runCatching { ThemeLibrary.list(context) }.getOrDefault(emptyList())
            slots.size to storeHintOf(context, slots)
        }
        themeCount = count
        storeHint = hint
    }
    val presetName = detectPresetName(snap.primary, snap.background, snap.text)
    val hasImage = !snap.wallpaperPath.isNullOrBlank()
    val wallpaperOn = snap.wallpaperEnabled && hasImage
    val glassOn = prefs.glassEnabled
    // From the last check only; opening this screen never goes to the network.
    val newer = isNewerVersion(prefs.updateVersion, BuildConfig.VERSION_NAME)

    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(
                title = "WaThemer",
                trailing = {
                    val items = mutableListOf<Pair<String, () -> Unit>>()
                    items += "Restart WhatsApp" to { restartWhatsApp(context, onMessage) }
                    items += "Updates" to { nav.push(Screen.Updates) }
                    // A reset, not a toggle: there is no "on" state, only accumulated choices to clear.
                    if (themed) items += "Reset to stock WhatsApp" to {
                        prefs.resetToStock()
                        themed = false
                        snapshot.value = snapshotFromPrefs(prefs)
                        onMessage("Reset to stock.")
                    }
                    TopBarMenu(items)
                },
            )
            PreviewPanel(modifier = Modifier.weight(0.4f).fillMaxWidth()) {
                WaPreview(WaPreviewKind.HomeFull, snap)
            }
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
            ) {
                SectionHeader("Appearance")
                CategoryRow(
                    label = "Colours",
                    leading = { RowIcon(R.drawable.ic_row_colours) },
                    trailingHint = presetName,
                    onClick = { nav.push(Screen.GlobalColors) },
                )
                CategoryRow(
                    label = "Home screen",
                    leading = { RowIcon(R.drawable.ic_row_homescreen) },
                    onClick = { nav.push(Screen.Homescreen) },
                )
                CategoryRow(
                    label = "Chats",
                    leading = { RowIcon(R.drawable.ic_row_chats) },
                    onClick = { nav.push(Screen.Chat) },
                )
                CategoryRow(
                    label = "Wallpaper",
                    leading = { RowIcon(R.drawable.ic_row_wallpaper) },
                    trailingHint = when {
                        wallpaperOn -> "On"
                        hasImage -> "Off"
                        else -> "Not set"
                    },
                    onClick = { nav.push(Screen.Wallpaper) },
                )
                CategoryRow(
                    label = "Liquid Glass",
                    leading = { RowIcon(R.drawable.ic_row_glass) },
                    // The dependency in the row itself, before the tap.
                    trailingHint = when {
                        glassOn -> "On"
                        wallpaperOn -> "Off"
                        else -> "Needs a wallpaper"
                    },
                    onClick = { nav.push(Screen.LiquidGlass) },
                )
                CategoryRow(
                    label = "Extras",
                    leading = { RowIcon(R.drawable.ic_row_extras) },
                    divider = false,
                    onClick = { nav.push(Screen.Extras) },
                )
                SectionHeader("Themes")
                CategoryRow(
                    label = "Saved themes",
                    leading = { RowIcon(R.drawable.ic_row_themes) },
                    trailingHint = when {
                        themeCount < 0 -> null
                        themeCount == 0 -> "None"
                        else -> "$themeCount"
                    },
                    onClick = { nav.push(Screen.Themes) },
                )
                CategoryRow(
                    label = "Theme store",
                    leading = { RowIcon(R.drawable.ic_row_themes) },
                    trailingHint = storeHint,
                    divider = newer,
                    onClick = { nav.push(Screen.ThemeStore) },
                )
                if (newer) {
                    CategoryRow(
                        label = "Update available",
                        leading = { RowIcon(R.drawable.ic_row_updates) },
                        trailingHint = prefs.updateVersion,
                        divider = false,
                        onClick = { nav.push(Screen.Updates) },
                    )
                }
            }
        }
    }
}

/** From the summary the last store visit left, never the network: updates waiting for saved themes, else the store's size. */
private fun storeHintOf(context: Context, slots: List<ThemeLibrary.Slot>): String? {
    val summary = StoreFiles.readSummary(context, StoreConfig.TARGET.storeId) ?: return null
    val held = HashMap<String, Int>()
    for (slot in slots) slot.doc.store?.let { ref -> held[ref.id] = maxOf(ref.version, held[ref.id] ?: 0) }
    val updates = held.count { (id, version) -> (summary.latest[id] ?: 0) > version }
    return when {
        updates == 1 -> "1 update"
        updates > 1 -> "$updates updates"
        summary.themes > 0 -> "${summary.themes}"
        else -> null
    }
}

internal fun detectPresetName(primary: Int, background: Int, text: Int): String = when {
    primary == Prefs.DEFAULT_PRIMARY && background == Prefs.DEFAULT_BACKGROUND && text == Prefs.DEFAULT_TEXT -> "Stock"
    else -> PRESETS.firstOrNull { it.primary == primary && it.background == background && it.text == text }?.label ?: "Custom"
}

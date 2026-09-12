package com.wathemer.app.settings.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.wathemer.app.R
import com.wathemer.app.settings.components.BitmapIcon
import com.wathemer.app.settings.components.CategoryRow
import com.wathemer.app.settings.components.CheckIcon
import com.wathemer.app.settings.components.CloseIcon
import com.wathemer.app.settings.components.ExpandGroup
import com.wathemer.app.settings.components.LocalAccent
import com.wathemer.app.settings.components.NavTopBar
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.components.ToggleItem
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.prefs.FontLibrary
import com.wathemer.app.settings.prefs.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dividers, overlay effects and the font swap. Everything here is read once at hook install, so changes need a WhatsApp restart. */
@Composable
fun ExtrasScreen(nav: NavController, prefs: Prefs, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var divider by remember { mutableStateOf(prefs.chatListDivider) }
    var snow by remember { mutableStateOf(prefs.effectSnow) }
    var fontChoice by remember { mutableStateOf(prefs.customFont) }
    var fontMono by remember { mutableStateOf(prefs.fontMapMonospace) }
    var library by remember { mutableStateOf(FontLibrary.list(context, prefs)) }
    var fontUserName by remember { mutableStateOf(prefs.fontUserName) }
    var pickerOpen by remember { mutableStateOf(false) }

    val selectedLabel = when {
        fontChoice.isBlank() -> "System default"
        fontChoice == "user" -> fontUserName.ifBlank { "Your font" }
        else -> FONT_CHOICES.firstOrNull { it.id == fontChoice }?.label ?: fontChoice
    }

    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Off main: the picker accepts any file of any size, and the copy plus the Typeface parse both block.
        if (importing) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { FontLibrary.import(context, prefs, uri) }
            importing = false
            when (result) {
                is FontLibrary.ImportResult.Unreadable ->
                    onMessage("Couldn't read that font file. TTF, OTF and TTC work.")
                is FontLibrary.ImportResult.TooLarge ->
                    onMessage("That file is over 32 MB, the limit for a font here.")
                is FontLibrary.ImportResult.Done -> {
                    val outcome = result.outcome
                    if (outcome.duplicate) {
                        onMessage("${outcome.entry.name} is already in your fonts.")
                    } else {
                        library = FontLibrary.list(context, prefs)
                        onMessage("${outcome.entry.name} added to your fonts.")
                    }
                }
            }
        }
    }

    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(title = "Extras", onBack = { nav.pop() })
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
            ) {
                ExpandGroup("Chat list") {
                    ToggleItem(
                        title = "Row dividers",
                        checked = divider,
                        onCheckedChange = {
                            divider = it
                            prefs.chatListDivider = it
                        },
                    )
                }
                ExpandGroup("Effects") {
                    ToggleItem(
                        title = "Snowfall",
                        subtitle = "Uses battery while it shows",
                        checked = snow,
                        onCheckedChange = {
                            snow = it
                            prefs.effectSnow = it
                        },
                    )
                }
                ExpandGroup("Fonts", trailingText = selectedLabel) {
                    CategoryRow(
                        label = "Typeface",
                        trailingHint = selectedLabel,
                        onClick = { pickerOpen = true },
                    )
                    ToggleItem(
                        title = "Also restyle monospace",
                        checked = fontMono,
                        onCheckedChange = { fontMono = it; prefs.fontMapMonospace = it },
                    )
                }
            }
        }
    }

    if (pickerOpen) {
        FontPickerDialog(
            context = context,
            fontChoice = fontChoice,
            fontUserFile = prefs.fontUserFile,
            library = library,
            onPick = { choice, entry ->
                when {
                    entry != null -> {
                        FontLibrary.select(prefs, entry)
                        fontChoice = "user"
                        fontUserName = entry.name
                        onMessage("${entry.name} applied.")
                    }
                    choice.isBlank() -> {
                        fontChoice = ""; prefs.customFont = ""
                        onMessage("Font reset.")
                    }
                    else -> {
                        fontChoice = choice; prefs.customFont = choice
                        onMessage("${FONT_CHOICES.first { it.id == choice }.label} applied.")
                    }
                }
                pickerOpen = false
            },
            onAdd = { fontPicker.launch("*/*") },
            onRemove = { entry ->
                val wasSelected = FontLibrary.remove(context, prefs, entry)
                library = FontLibrary.list(context, prefs)
                if (wasSelected) {
                    fontChoice = ""; fontUserName = ""
                }
                onMessage(if (wasSelected) "${entry.name} removed. Font reset." else "${entry.name} removed.")
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/** Bundled ofl fonts. `id` must match res/font/<id>_regular.ttf because the hook resolves by name. */
private data class FontChoice(val id: String, val label: String, val res: Int)

private val FONT_CHOICES = listOf(
    FontChoice("inter",   "Inter",         R.font.inter_regular),
    FontChoice("poppins", "Poppins",       R.font.poppins_regular),
    FontChoice("nunito",  "Nunito",        R.font.nunito_regular),
    FontChoice("rubik",   "Rubik",         R.font.rubik_regular),
    FontChoice("lato",    "Lato",          R.font.lato_regular),
    FontChoice("lora",    "Lora (serif)",  R.font.lora_regular),
    FontChoice("arvo",    "Arvo (serif)",  R.font.arvo_regular),
)

private const val FONT_SAMPLE = "The quick brown fox 1234"

/** Searchable list of every selectable face: stock, bundled, then the user's imports with a remove control, and the way to add one. */
@Composable
private fun FontPickerDialog(
    context: Context,
    fontChoice: String,
    fontUserFile: String,
    library: List<FontLibrary.Entry>,
    onPick: (choice: String, entry: FontLibrary.Entry?) -> Unit,
    onAdd: () -> Unit,
    onRemove: (FontLibrary.Entry) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var pendingRemove by remember { mutableStateOf<FontLibrary.Entry?>(null) }
    val q = query.trim()
    fun matches(s: String) = q.isEmpty() || s.contains(q, ignoreCase = true)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.Surface, RoundedCornerShape(16.dp))
                .padding(vertical = 16.dp),
        ) {
            Text("Font", color = Palette.Fg, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .border(1.dp, Palette.RuleStrong, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchIcon(Palette.FgMuted)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(color = Palette.Fg, fontSize = 15.sp),
                    cursorBrush = SolidColor(Palette.Fg),
                    singleLine = true,
                    decorationBox = { innerField ->
                        Box {
                            if (query.isEmpty()) {
                                Text("Search fonts", color = Palette.FgSubtle, fontSize = 15.sp)
                            }
                            innerField()
                        }
                    },
                )
                if (query.isNotEmpty()) {
                    Box(Modifier.size(20.dp).clickable { query = "" }, contentAlignment = Alignment.Center) {
                        CloseIcon(Palette.FgMuted, size = 11.dp)
                    }
                }
            }
            val userMatches = library.filter { matches(it.name) }
            val builtinMatches = FONT_CHOICES.filter { matches(it.label) }
            val stockMatches = matches("System default")
            LazyColumn(
                // Weight as well as the cap: the cap alone overruns a short screen and the list loses its bottom.
                modifier = Modifier.weight(1f, fill = false).heightIn(max = 420.dp),
            ) {
                if (stockMatches) {
                    item(key = "stock") {
                        FontRow(
                            label = "System default",
                            sample = "WhatsApp stock typeface",
                            fontFamily = null,
                            selected = fontChoice.isBlank(),
                            onClick = { onPick("", null) },
                        )
                    }
                }
                items(builtinMatches, key = { it.id }) { fc ->
                    val fam = remember(fc.id) { FontFamily(Font(fc.res)) }
                    FontRow(
                        label = fc.label,
                        sample = FONT_SAMPLE,
                        fontFamily = fam,
                        selected = fontChoice == fc.id,
                        onClick = { onPick(fc.id, null) },
                    )
                }
                if (userMatches.isNotEmpty()) {
                    item(key = "user-header") {
                        Text(
                            "Your fonts",
                            color = LocalAccent.current, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                        )
                    }
                    items(userMatches, key = { it.stamp }) { entry ->
                        val fam = remember(entry.stamp) {
                            FontLibrary.typeface(context, entry)?.let { FontFamily(it) }
                        }
                        FontRow(
                            label = entry.name,
                            sample = FONT_SAMPLE,
                            fontFamily = fam,
                            selected = fontChoice == "user" && fontUserFile == entry.file,
                            onClick = { onPick("user", entry) },
                            trailing = {
                                Box(
                                    Modifier.size(32.dp).clickable { pendingRemove = entry },
                                    contentAlignment = Alignment.Center,
                                ) { CloseIcon(Palette.FgSubtle, size = 12.dp) }
                            },
                        )
                    }
                }
                if (!stockMatches && builtinMatches.isEmpty() && userMatches.isEmpty()) {
                    item(key = "empty") {
                        Text("No fonts match.", color = Palette.FgMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                    }
                }
                item(key = "add") {
                    // Import-only control; the pick lands in the library and the list, never straight onto WhatsApp.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onAdd)
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlusIcon(LocalAccent.current)
                        Spacer(Modifier.size(14.dp))
                        Text("Add a font file", color = LocalAccent.current, fontSize = 16.sp)
                    }
                }
            }
        }
    }

    pendingRemove?.let { entry ->
        Dialog(onDismissRequest = { pendingRemove = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.Surface, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Remove ${entry.name}?", color = Palette.Fg, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { pendingRemove = null },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, Palette.RuleStrong),
                    ) { Text("Cancel", color = Palette.Fg) }
                    Button(
                        onClick = { onRemove(entry); pendingRemove = null },
                        modifier = Modifier.weight(1f),
                    ) { Text("Remove", fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

/** Selectable font row. Renders its label and sample line in the font it represents. */
@Composable
private fun FontRow(
    label: String,
    sample: String,
    fontFamily: FontFamily?,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Palette.Fg, fontSize = 16.sp, fontFamily = fontFamily)
            Text(sample, color = Palette.FgMuted, fontSize = 13.sp, fontFamily = fontFamily, modifier = Modifier.padding(top = 2.dp))
        }
        if (selected) {
            Spacer(Modifier.size(10.dp))
            CheckIcon(accent)
        }
        if (trailing != null) {
            Spacer(Modifier.size(6.dp))
            trailing()
        }
    }
}

@Composable
private fun PlusIcon(tint: Color, size: Dp = 16.dp) = BitmapIcon(R.drawable.ic_ui_plus, tint, size)

@Composable
private fun SearchIcon(tint: Color, size: Dp = 16.dp) = BitmapIcon(R.drawable.ic_ui_search, tint, size)

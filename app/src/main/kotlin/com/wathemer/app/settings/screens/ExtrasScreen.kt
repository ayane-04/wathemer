package com.wathemer.app.settings.screens

import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.wathemer.app.settings.components.CheckIcon
import com.wathemer.app.settings.components.ChevronIcon
import com.wathemer.app.settings.components.CloseIcon
import com.wathemer.app.settings.components.ExpandableSection
import com.wathemer.app.settings.components.LocalAccent
import com.wathemer.app.settings.components.NavTopBar
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.components.SectionHeader
import com.wathemer.app.settings.components.StubNote
import com.wathemer.app.settings.components.ToggleItem
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.prefs.FontLibrary
import com.wathemer.app.settings.prefs.Prefs

/** Dividers, overlay effects and the font swap. Everything here is read once at hook install, so changes need a WhatsApp restart. */
@Composable
fun ExtrasScreen(nav: NavController, prefs: Prefs, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    var divider by remember { mutableStateOf(prefs.chatListDivider) }
    var snow by remember { mutableStateOf(prefs.effectSnow) }
    var fontChoice by remember { mutableStateOf(prefs.customFont) }
    var fontExpanded by remember { mutableStateOf(false) }
    var fontMono by remember { mutableStateOf(prefs.fontMapMonospace) }

    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(
                title = "Extras",
                subtitle = if (divider || snow || fontChoice.isNotBlank()) "On" else "Off",
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
                    title = "Chat list",
                    subtitle = "Small touches on the home list.",
                )
                ToggleItem(
                    title = "Row dividers",
                    subtitle = "A hairline between chats, the iOS list look.",
                    checked = divider,
                    onCheckedChange = {
                        divider = it
                        prefs.chatListDivider = it
                    },
                )

                SectionHeader(
                    title = "Effects",
                    subtitle = "Drawn over WhatsApp. Taps pass straight through.",
                )
                ToggleItem(
                    title = "Snowfall",
                    subtitle = "Gentle snow over every screen. Costs battery while visible.",
                    checked = snow,
                    onCheckedChange = {
                        snow = it
                        prefs.effectSnow = it
                    },
                )

                ExpandableSection(
                    label = "Fonts",
                    expanded = fontExpanded,
                    onToggle = { fontExpanded = !fontExpanded },
                ) {
                    Text(
                        "Replace WhatsApp's font. Added files join the list under their own name.",
                        color = Palette.FgMuted, fontSize = 11.sp, lineHeight = 15.sp,
                    )
                    var library by remember { mutableStateOf(FontLibrary.list(context, prefs)) }
                    var fontUserName by remember { mutableStateOf(prefs.fontUserName) }
                    var fontUserStamp by remember { mutableStateOf(prefs.fontUserStamp) }
                    var pickerOpen by remember { mutableStateOf(false) }

                    val selectedLabel = when {
                        fontChoice.isBlank() -> "System default"
                        fontChoice == "user" -> fontUserName.ifBlank { "Your font" }
                        else -> FONT_CHOICES.firstOrNull { it.id == fontChoice }?.label ?: fontChoice
                    }
                    val selectedFamily: FontFamily? = remember(fontChoice, fontUserStamp, library) {
                        when {
                            fontChoice.isBlank() -> null
                            fontChoice == "user" -> library.firstOrNull { it.stamp == fontUserStamp }
                                ?.let { FontLibrary.typeface(context, it) }?.let { FontFamily(it) }
                            else -> FONT_CHOICES.firstOrNull { it.id == fontChoice }?.let { FontFamily(Font(it.res)) }
                        }
                    }
                    FontRow(
                        label = selectedLabel,
                        sample = FONT_SAMPLE,
                        fontFamily = selectedFamily,
                        selected = false,
                        onClick = { pickerOpen = true },
                        overline = "TYPEFACE",
                        trailing = { ChevronIcon(Palette.FgMuted) },
                    )
                    val fontPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                        ActivityResultContracts.GetContent(),
                    ) { uri ->
                        if (uri == null) return@rememberLauncherForActivityResult
                        val outcome = FontLibrary.import(context, prefs, uri)
                        when {
                            outcome == null -> onMessage("Couldn't read that font file. TTF, OTF and TTC work.")
                            outcome.duplicate -> onMessage("${outcome.entry.name} is already in your fonts.")
                            else -> {
                                library = FontLibrary.list(context, prefs)
                                onMessage("${outcome.entry.name} added to your fonts.")
                            }
                        }
                    }
                    AddFontRow(onClick = { fontPicker.launch("*/*") })
                    ToggleItem(
                        title = "Also restyle monospace",
                        subtitle = "Also restyle fixed-width text, like codes and OTPs.",
                        checked = fontMono,
                        onCheckedChange = { fontMono = it; prefs.fontMapMonospace = it },
                    )

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
                                        fontUserStamp = entry.stamp
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
                            onRemove = { entry ->
                                val wasSelected = FontLibrary.remove(context, prefs, entry)
                                library = FontLibrary.list(context, prefs)
                                if (wasSelected) {
                                    fontChoice = ""; fontUserName = ""; fontUserStamp = 0
                                }
                                onMessage(if (wasSelected) "${entry.name} removed. Font reset." else "${entry.name} removed.")
                            },
                            onDismiss = { pickerOpen = false },
                        )
                    }
                }

                Spacer(Modifier.size(8.dp))
                StubNote("Applied when WhatsApp starts. Restart it from the main screen.")
            }
        }
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

/** Searchable list of every selectable face: stock, bundled, then the user's imports with a remove control. */
@Composable
private fun FontPickerDialog(
    context: Context,
    fontChoice: String,
    fontUserFile: String,
    library: List<FontLibrary.Entry>,
    onPick: (choice: String, entry: FontLibrary.Entry?) -> Unit,
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
                .background(Palette.Bg, RoundedCornerShape(16.dp))
                .border(1.dp, Palette.RuleStrong, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Typeface", color = Palette.Fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Palette.Rule, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchIcon(Palette.FgMuted)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(color = Palette.Fg, fontSize = 14.sp),
                    cursorBrush = SolidColor(Palette.Fg),
                    singleLine = true,
                    decorationBox = { innerField ->
                        Box {
                            if (query.isEmpty()) {
                                Text("Search fonts", color = Palette.FgSubtle, fontSize = 14.sp)
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                            "YOUR FONTS",
                            color = Palette.FgMuted, fontSize = 9.sp,
                            letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp),
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
                                    Modifier.size(22.dp).clickable { pendingRemove = entry },
                                    contentAlignment = Alignment.Center,
                                ) { CloseIcon(Palette.FgSubtle, size = 11.dp) }
                            },
                        )
                    }
                }
                if (!stockMatches && builtinMatches.isEmpty() && userMatches.isEmpty()) {
                    item(key = "empty") {
                        Text("No fonts match.", color = Palette.FgMuted, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
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
                    .background(Palette.Bg, RoundedCornerShape(16.dp))
                    .border(1.dp, Palette.RuleStrong, RoundedCornerShape(16.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Remove ${entry.name}?", color = Palette.Fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    "The file leaves your fonts list. If it is the active font, WhatsApp goes back to its stock typeface.",
                    color = Palette.FgMuted, fontSize = 11.sp, lineHeight = 15.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { pendingRemove = null },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, Palette.Rule),
                    ) { Text("Cancel", color = Palette.Fg, fontWeight = FontWeight.SemiBold) }
                    Button(
                        onClick = { onRemove(entry); pendingRemove = null },
                        modifier = Modifier.weight(1f),
                    ) { Text("Remove", fontWeight = FontWeight.Bold) }
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
    overline: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.SurfaceElev, RoundedCornerShape(12.dp))
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) accent.copy(alpha = 0.7f) else Palette.RuleStrong,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (overline != null) {
                Text(overline, color = accent.copy(alpha = 0.85f), fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
            }
            Text(label, color = Palette.Fg, fontSize = 15.sp, fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            Text(sample, color = Palette.FgMuted, fontSize = 13.sp, fontFamily = fontFamily, modifier = Modifier.padding(top = 2.dp))
        }
        if (selected) {
            Spacer(Modifier.size(10.dp))
            CheckIcon(accent)
        }
        if (trailing != null) {
            Spacer(Modifier.size(10.dp))
            trailing()
        }
    }
}

/** Import-only control; the pick lands in the library and the searchable list, never straight onto WhatsApp. */
@Composable
private fun AddFontRow(onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.SurfaceElev, RoundedCornerShape(12.dp))
            .border(1.dp, Palette.RuleStrong, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlusIcon(accent)
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Add a font file", color = Palette.Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            Text("TTF, OTF or TTC from your storage. It joins the list above.", color = Palette.FgMuted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun PlusIcon(tint: Color, size: Dp = 15.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val s = 1.7.dp.toPx()
        drawLine(tint, Offset(w * 0.5f, h * 0.14f), Offset(w * 0.5f, h * 0.86f), s)
        drawLine(tint, Offset(w * 0.14f, h * 0.5f), Offset(w * 0.86f, h * 0.5f), s)
    }
}

@Composable
private fun SearchIcon(tint: Color, size: Dp = 14.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val s = 1.6.dp.toPx()
        drawCircle(tint, radius = w * 0.30f, center = Offset(w * 0.42f, h * 0.42f), style = Stroke(s))
        drawLine(tint, Offset(w * 0.64f, h * 0.64f), Offset(w * 0.88f, h * 0.88f), s)
    }
}

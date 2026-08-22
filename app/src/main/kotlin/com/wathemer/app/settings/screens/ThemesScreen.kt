// Saved themes: keep what you have, share it, or take someone else's.
// Every file touch runs off the main thread; a theme carrying a wallpaper is megabytes, not bytes.
package com.wathemer.app.settings.screens

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.wathemer.app.settings.components.CategoryRow
import com.wathemer.app.settings.components.MenuRow
import com.wathemer.app.settings.components.NavTopBar
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.components.SectionHeader
import com.wathemer.app.settings.components.StubNote
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.prefs.Prefs
import com.wathemer.app.settings.prefs.ThemeDoc
import com.wathemer.app.settings.prefs.ThemeFile
import com.wathemer.app.settings.prefs.ThemeLibrary
import com.wathemer.app.settings.prefs.WallpaperAsset
import com.wathemer.app.settings.preview.LocalThemeSnapshot
import com.wathemer.app.settings.preview.snapshotFromPrefs
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_THEME_NAME = 48

/** The themes list, and everything you can do to one. Liquid Glass stays out of a theme, so a shared file never changes it. */
@Composable
fun ThemesScreen(nav: NavController, prefs: Prefs, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot = LocalThemeSnapshot.current

    var slots by remember { mutableStateOf<List<ThemeLibrary.Slot>>(emptyList()) }
    var undo by remember { mutableStateOf<ThemeDoc?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ThemeLibrary.Slot?>(null) }
    var acting by remember { mutableStateOf<ThemeLibrary.Slot?>(null) }
    var applying by remember { mutableStateOf<ThemeLibrary.Slot?>(null) }
    var deleting by remember { mutableStateOf<ThemeLibrary.Slot?>(null) }
    var undoing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<List<String>>(emptyList()) }
    var exporting by remember { mutableStateOf<ThemeLibrary.Slot?>(null) }

    LaunchedEffect(reload) {
        val loaded = withContext(Dispatchers.IO) {
            ThemeLibrary.list(context) to ThemeLibrary.undoable(context)
        }
        slots = loaded.first
        undo = loaded.second
    }

    // One gate for every file action: nothing overlaps, and the list is re-read when it is done.
    fun work(block: suspend () -> Unit) {
        // Dropping a tap silently is how a button comes to look broken; say why instead.
        if (busy) { onMessage("Still finishing the last one."); return }
        scope.launch {
            busy = true
            try { block() } finally { busy = false; reload++ }
        }
    }

    fun applyTheme(slot: ThemeLibrary.Slot, keepUndo: Boolean) = work {
        val outcome = withContext(Dispatchers.IO) {
            ThemeLibrary.apply(context, prefs, slot.file, keepUndo)
        }
        when (outcome) {
            is ThemeLibrary.Outcome.Applied -> {
                // Bump the stamp by hand as well: the wallpaper path can be unchanged while the bytes behind it are not.
                snapshot.value = snapshotFromPrefs(prefs)
                    .copy(wallpaperStamp = snapshot.value.wallpaperStamp + 1)
                if (!keepUndo) ThemeLibrary.clearUndo(context)
                onMessage("${outcome.name} applied. Restart WhatsApp to see it.")
                if (outcome.problems.isNotEmpty()) notice = outcome.problems
            }
            is ThemeLibrary.Outcome.Failed -> onMessage(outcome.reason)
            ThemeLibrary.Outcome.NeedsStorageAccess -> {
                onMessage("That theme has a wallpaper, so it needs all-files access.")
                WallpaperAsset.requestAllFilesAccess(context)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        // Any type: most phones have no idea what a .wathemer file is, and a filter would hide it.
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        work {
            when (val result = withContext(Dispatchers.IO) { ThemeLibrary.importFile(context, uri) }) {
                is ThemeLibrary.Import.Added -> {
                    onMessage("${result.slot.doc.name} added to your themes.")
                    // Two dialogs at once reads as a mess; when there is something to say, say it and let them tap the row.
                    if (result.problems.isNotEmpty()) notice = result.problems else applying = result.slot
                }
                is ThemeLibrary.Import.Failed -> onMessage(result.reason)
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val slot = exporting
        exporting = null
        if (uri == null) return@rememberLauncherForActivityResult
        // Lost if the activity was rebuilt behind the picker; say so rather than doing nothing.
        if (slot == null) {
            onMessage("Choose the theme again to save it.")
            return@rememberLauncherForActivityResult
        }
        work {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { ThemeLibrary.exportTo(slot, it) } ?: false
                }.getOrDefault(false)
            }
            onMessage(if (ok) "${slot.doc.name} saved." else "That file could not be written.")
        }
    }

    fun share(slot: ThemeLibrary.Slot) = work {
        val staged = withContext(Dispatchers.IO) { ThemeLibrary.stageForShare(context, slot) }
        val uri = staged?.let {
            runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }.getOrNull()
        }
        if (uri == null) {
            onMessage("Could not prepare that theme for sharing.")
            return@work
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = ThemeFile.MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, slot.doc.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, "Share theme")) }
            .onFailure { onMessage("Nothing on this phone can share a file.") }
    }

    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(
                title = "Themes",
                // Said in the subtitle, not in a row: a row that appears and vanishes shifts everything under it.
                subtitle = when {
                    busy -> "Working"
                    slots.isEmpty() -> "None saved"
                    else -> "${slots.size} saved"
                },
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
                    title = "Your themes",
                    subtitle = "A theme carries your colours, bubble shapes, icons, font and wallpaper.",
                )
                MenuRow(
                    title = "Save what you have now",
                    subtitle = "Adds your current look to the list below.",
                    onClick = { naming = true },
                )
                MenuRow(
                    title = "Import a theme file",
                    subtitle = "Open a theme someone sent you.",
                    onClick = {
                        runCatching { importLauncher.launch(arrayOf("*/*")) }
                            .onFailure { onMessage("No file picker on this phone.") }
                    },
                )
                undo?.let { doc ->
                    MenuRow(
                        title = "Undo the last change",
                        subtitle = "Puts back the theme you had before, from ${whenText(context, doc.createdAt)}.",
                        onClick = { undoing = true },
                    )
                }

                if (slots.isEmpty()) {
                    StubNote("Nothing saved yet. Save what you have, or import a theme someone shared.")
                } else {
                    slots.forEach { slot ->
                        ThemeSlotRow(context, slot) { acting = slot }
                    }
                }

                Spacer(Modifier.size(4.dp))
                StubNote("Liquid Glass is not part of a theme. Yours stays as you set it.")
                StubNote("Applied when WhatsApp starts. Restart it from the button below.")
            }
        }
    }

    if (naming) {
        NameDialog(
            title = "Name this theme",
            initial = "",
            confirmLabel = "Save",
            onConfirm = { name ->
                naming = false
                work {
                    val saved = withContext(Dispatchers.IO) { ThemeLibrary.saveCurrent(context, prefs, name) }
                    onMessage(if (saved != null) "Saved as ${saved.doc.name}." else "Could not save that theme.")
                }
            },
            onDismiss = { naming = false },
        )
    }

    renaming?.let { slot ->
        NameDialog(
            title = "Rename",
            initial = slot.doc.name,
            confirmLabel = "Rename",
            onConfirm = { name ->
                renaming = null
                work {
                    val ok = withContext(Dispatchers.IO) { ThemeLibrary.rename(context, slot, name) }
                    if (!ok) onMessage("Could not rename that theme.")
                }
            },
            onDismiss = { renaming = null },
        )
    }

    acting?.let { slot ->
        ActionsDialog(
            context = context,
            slot = slot,
            onApply = { acting = null; applying = slot },
            onShare = { acting = null; share(slot) },
            onExport = {
                acting = null
                exporting = slot
                runCatching { exportLauncher.launch(ThemeFile.suggestedFileName(slot.doc.name)) }
                    .onFailure { exporting = null; onMessage("No file picker on this phone.") }
            },
            onRename = { acting = null; renaming = slot },
            onDelete = { acting = null; deleting = slot },
            onDismiss = { acting = null },
        )
    }

    applying?.let { slot ->
        val carriesWallpaper = slot.doc.wallpaper?.hasImage == true
        ConfirmDialog(
            title = "Apply ${slot.doc.name}?",
            body = buildString {
                append("Your colours, bubble shapes, icons and font are replaced by this theme's.")
                if (carriesWallpaper) append(" Your current wallpaper image is replaced too.")
                append(" You can undo it straight afterwards.")
            },
            confirmLabel = "Apply",
            onConfirm = { applying = null; applyTheme(slot, keepUndo = true) },
            onDismiss = { applying = null },
        )
    }

    deleting?.let { slot ->
        ConfirmDialog(
            title = "Delete ${slot.doc.name}?",
            body = "It leaves your list. Whatever is on WhatsApp right now stays as it is.",
            confirmLabel = "Delete",
            onConfirm = {
                deleting = null
                work { withContext(Dispatchers.IO) { ThemeLibrary.delete(slot) } }
            },
            onDismiss = { deleting = null },
        )
    }

    if (undoing) {
        val previous = undo
        ConfirmDialog(
            title = "Undo the last change?",
            body = "Your colours and wallpaper go back to how they were before the last theme was applied. This can only be done once.",
            confirmLabel = "Undo",
            onConfirm = {
                undoing = false
                // Stamp 0 is a placeholder: the undo copy is not a slot, and apply only reads the file.
                if (previous != null) {
                    applyTheme(ThemeLibrary.Slot(ThemeLibrary.undoFile(context), 0, previous), keepUndo = false)
                }
            },
            onDismiss = { undoing = false },
        )
    }

    if (notice.isNotEmpty()) {
        NoticeDialog(notice) { notice = emptyList() }
    }
}

/* ── Rows ───────────────────────────────────────────────────────────────── */

@Composable
private fun ThemeSlotRow(context: Context, slot: ThemeLibrary.Slot, onClick: () -> Unit) {
    val doc = slot.doc
    CategoryRow(
        label = doc.name,
        description = describe(context, doc),
        leading = {
            ThemeBadge(
                primary = doc.colors?.get(Prefs.KEY_PRIMARY) ?: Prefs.DEFAULT_PRIMARY,
                background = doc.colors?.get(Prefs.KEY_BACKGROUND) ?: Prefs.DEFAULT_BACKGROUND,
                text = doc.colors?.get(Prefs.KEY_TEXT) ?: Prefs.DEFAULT_TEXT,
            )
        },
        onClick = onClick,
    )
}

/** The three globals side by side, the same shorthand the root screen uses for the live theme. */
@Composable
private fun ThemeBadge(primary: Int, background: Int, text: Int) {
    Row(
        modifier = Modifier
            .border(1.dp, Palette.Rule, RoundedCornerShape(8.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Box(Modifier.size(width = 13.dp, height = 40.dp).background(Color(primary), RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)))
        Box(Modifier.size(width = 13.dp, height = 40.dp).background(Color(background)))
        Box(Modifier.size(width = 13.dp, height = 40.dp).background(Color(text), RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)))
    }
}

/* ── Dialogs ────────────────────────────────────────────────────────────── */

@Composable
private fun ThemeDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.Bg, RoundedCornerShape(16.dp))
                .border(1.dp, Palette.RuleStrong, RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
private fun ActionsDialog(
    context: Context,
    slot: ThemeLibrary.Slot,
    onApply: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ThemeDialog(onDismiss) {
        Text(slot.doc.name, color = Palette.Fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(describe(context, slot.doc), color = Palette.FgMuted, fontSize = 11.sp, lineHeight = 15.sp)
        // Body scrolls and the dismiss stays outside it, or a tall font scale pushes the last row off the window.
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                Text("Apply this theme", fontWeight = FontWeight.Bold)
            }
            MenuRow("Share", "Send this theme to someone.", onShare)
            MenuRow("Save to a file", "Keep a copy wherever you choose.", onExport)
            MenuRow("Rename", "Change what it is called here.", onRename)
            MenuRow("Delete", "Remove it from your list.", onDelete)
        }
        DialogDismiss("Close", onDismiss)
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ThemeDialog(onDismiss) {
        Text(title, color = Palette.Fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(body, color = Palette.FgMuted, fontSize = 12.sp, lineHeight = 17.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, Palette.Rule),
            ) { Text("Cancel", color = Palette.Fg, fontWeight = FontWeight.SemiBold) }
            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                Text(confirmLabel, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    ThemeDialog(onDismiss) {
        Text(title, color = Palette.Fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Palette.Rule, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                // Filtered at the source: one line, capped where the row can still show it whole.
                onValueChange = { raw -> value = raw.filterNot { it == '\n' || it == '\r' }.take(MAX_THEME_NAME) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = Palette.Fg, fontSize = 14.sp),
                cursorBrush = SolidColor(Palette.Fg),
                singleLine = true,
                decorationBox = { innerField ->
                    Box {
                        if (value.isEmpty()) Text("My theme", color = Palette.FgSubtle, fontSize = 14.sp)
                        innerField()
                    }
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, Palette.Rule),
            ) { Text("Cancel", color = Palette.Fg, fontWeight = FontWeight.SemiBold) }
            Button(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text(confirmLabel, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun NoticeDialog(lines: List<String>, onDismiss: () -> Unit) {
    ThemeDialog(onDismiss) {
        Text("Worth knowing", color = Palette.Fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            lines.forEach { Text(it, color = Palette.FgMuted, fontSize = 12.sp, lineHeight = 17.sp) }
        }
        DialogDismiss("Got it", onDismiss)
    }
}

@Composable
private fun DialogDismiss(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Palette.FgMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 2.dp, bottom = 2.dp),
    )
}

/* ── Text ───────────────────────────────────────────────────────────────── */

/** One line under the name: whether it carries a wallpaper, how many colours, and when it was made. */
private fun describe(context: Context, doc: ThemeDoc): String {
    val parts = mutableListOf<String>()
    parts += if (doc.wallpaper?.hasImage == true) "With wallpaper" else "No wallpaper"
    when (val n = doc.colors?.size ?: 0) {
        0 -> parts += "Stock colours"
        1 -> parts += "1 colour"
        else -> parts += "$n colours"
    }
    if (doc.createdAt > 0L) parts += whenText(context, doc.createdAt)
    return parts.joinToString(" · ")
}

private fun whenText(context: Context, millis: Long): String =
    if (millis <= 0L) "earlier" else DateFormat.getMediumDateFormat(context).format(Date(millis))

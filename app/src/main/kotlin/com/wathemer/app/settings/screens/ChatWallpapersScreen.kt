// Chat wallpapers: the chats with their own picture, an editor per entry, and the picker a WhatsApp hand-off opens.
// Entries are only ever created from WhatsApp's chat menu; this app cannot list chats itself.
package com.wathemer.app.settings.screens

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.wathemer.app.hooks.wallpaper.BitmapDecoder
import com.wathemer.app.hooks.wallpaper.WallpaperImage
import com.wathemer.app.settings.components.CategoryRow
import com.wathemer.app.settings.components.ChevronIcon
import com.wathemer.app.settings.components.NavTopBar
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.components.SliderItem
import com.wathemer.app.settings.components.NoteText
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.prefs.ChatWallpaperLibrary
import com.wathemer.app.settings.prefs.Prefs
import com.yalantis.ucrop.UCrop
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The chat WhatsApp's menu handed over: its jid as the chat's own Intent carries it, and its title for the list. */
class ChatRequest(val jid: String, val name: String)

/** Set by MainActivity from the hand-off Intent, taken once by the screen. Process-level, so an Activity recreation keeps it. */
internal val pendingChatRequest = mutableStateOf<ChatRequest?>(null)

private const val LOGTAG = "WaThemer.ChatWP"

@Composable
fun ChatWallpapersScreen(nav: NavController, prefs: Prefs, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(ChatWallpaperLibrary.list(context, prefs)) }
    var editing by remember { mutableStateOf<ChatWallpaperLibrary.Entry?>(null) }
    var removing by remember { mutableStateOf<ChatWallpaperLibrary.Entry?>(null) }
    // The chat a pick is for: set before the picker opens, read when the crop comes back.
    var target by remember { mutableStateOf<ChatRequest?>(null) }
    var saving by remember { mutableStateOf(false) }
    val wallpaperOn = prefs.wallpaperEnabled && !prefs.wallpaperPath.isNullOrBlank()

    fun refresh() { entries = ChatWallpaperLibrary.list(context, prefs) }

    val cropLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val forChat = target
        target = null
        when {
            result.resultCode == Activity.RESULT_OK && result.data != null -> {
                val cropped = UCrop.getOutput(result.data!!)
                if (cropped == null || forChat == null) {
                    Log.w(LOGTAG, "crop returned OK with no output Uri or no target chat")
                    onMessage("Couldn't read the cropped image.")
                } else {
                    saving = true
                    scope.launch {
                        // Off main, and NonCancellable: leaving the screen must not save the file and skip the list write.
                        withContext(NonCancellable) {
                            val app = context.applicationContext
                            val outcome = withContext(Dispatchers.IO) {
                                ChatWallpaperLibrary.put(app, prefs, forChat.jid, forChat.name) {
                                    app.contentResolver.openInputStream(cropped)
                                }.also { cropped.path?.let { p -> File(p).delete() } }
                            }
                            saving = false
                            when (outcome) {
                                is ChatWallpaperLibrary.PutResult.Done -> {
                                    refresh()
                                    editing = outcome.entry
                                    onMessage("Wallpaper set for ${outcome.entry.name}.")
                                }
                                ChatWallpaperLibrary.PutResult.Unreadable -> onMessage("Couldn't read that image.")
                                ChatWallpaperLibrary.PutResult.TooLarge ->
                                    onMessage("That image is over 24 MB, the limit for a chat wallpaper.")
                                ChatWallpaperLibrary.PutResult.Full ->
                                    onMessage("You have ${ChatWallpaperLibrary.MAX_ENTRIES} chat wallpapers already. Remove one first.")
                            }
                        }
                    }
                }
            }
            // UCrop shows nothing itself on failure; without this arm a bad image closes the crop screen unexplained.
            result.resultCode == UCrop.RESULT_ERROR -> {
                Log.w(LOGTAG, "crop failed: ${result.data?.let { UCrop.getError(it) }}")
                onMessage("Couldn't crop that image. Try another one.")
            }
            forChat != null && entries.none { it.jid == forChat.jid } -> onMessage("No image chosen for ${forChat.name}.")
        }
    }
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) {
            val t = target
            target = null
            if (t != null && entries.none { it.jid == t.jid }) onMessage("No image chosen for ${t.name}.")
            return@rememberLauncherForActivityResult
        }
        // Sweep leftover crops first: a pick that never came back left its temp behind.
        context.cacheDir.listFiles()?.forEach { if (it.name.startsWith("ucrop_chat_")) it.delete() }
        val tempDest = File(context.cacheDir, "ucrop_chat_${System.currentTimeMillis()}.png")
        val dm = context.resources.displayMetrics
        val cropIntent = UCrop.of(uri, Uri.fromFile(tempDest))
            .withAspectRatio(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
            .withMaxResultSize(dm.widthPixels, dm.heightPixels)
            .getIntent(context)
        cropLauncher.launch(cropIntent)
    }
    fun pickFor(chat: ChatRequest) {
        target = chat
        runCatching { pickLauncher.launch("image/*") }.onFailure { target = null; onMessage("No image picker on this phone.") }
    }

    // The hand-off, taken once: a chat already here opens its editor, a new one goes straight to the picker.
    val pending by pendingChatRequest
    LaunchedEffect(pending) {
        val request = pending ?: return@LaunchedEffect
        pendingChatRequest.value = null
        val existing = entries.firstOrNull { it.jid == request.jid }
        if (existing != null) editing = existing else pickFor(request)
    }

    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(title = "Chat wallpapers", onBack = { nav.pop() })
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
            ) {
                if (!wallpaperOn) {
                    NoteText("These show once the wallpaper is on.")
                }
                when {
                    saving -> NoteText("Saving")
                    entries.isEmpty() -> NoteText("None yet. Open a chat in WhatsApp, tap its menu, then Chat wallpaper.")
                    else -> entries.forEachIndexed { i, entry ->
                        ChatWallpaperRow(context, entry, divider = i < entries.lastIndex) { editing = entry }
                    }
                }
            }
        }
    }

    editing?.let { entry ->
        EditorDialog(
            context = context,
            entry = entry,
            prefs = prefs,
            onReplace = {
                editing = null
                pickFor(ChatRequest(entry.jid, entry.name))
            },
            onRemove = { removing = entry },
            onChanged = { updated ->
                editing = updated
                refresh()
            },
            onDismiss = { editing = null },
        )
    }

    removing?.let { entry ->
        ConfirmDialog(
            title = "Use the wallpaper for ${entry.name}?",
            body = "The chat's own picture is deleted.",
            confirmLabel = "Remove",
            onConfirm = {
                removing = null
                editing = null
                scope.launch {
                    withContext(Dispatchers.IO) { ChatWallpaperLibrary.remove(context, prefs, entry) }
                    refresh()
                    onMessage("${entry.name} shows the wallpaper again.")
                }
            },
            onDismiss = { removing = null },
        )
    }
}

// ── Rows ─────────────────────────────────────────────────────────────────

@Composable
private fun ChatWallpaperRow(context: Context, entry: ChatWallpaperLibrary.Entry, divider: Boolean, onClick: () -> Unit) {
    val file = ChatWallpaperLibrary.fileOf(context, entry)
    // Keyed on the file name: a replaced image has a new stamp and so a new name.
    val bitmap = remember(entry.file) { BitmapDecoder.decodeScaled(file, 240, 240) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.Surface),
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    if (entry.dim > 0) {
                        Box(Modifier.fillMaxSize().background(Color(WallpaperImage.DIM_COLOR).copy(alpha = entry.dim / 100f)))
                    }
                }
            }
            Spacer(Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, color = Palette.Fg, fontSize = 16.sp)
                Text(
                    "Dim ${entry.dim}% · Blur ${entry.blur}",
                    color = Palette.FgMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            ChevronIcon(Palette.FgMuted)
        }
        if (divider) Box(Modifier.fillMaxWidth().padding(start = 72.dp).height(1.dp).background(Palette.Rule))
    }
}

// ── Dialogs ──────────────────────────────────────────────────────────────

@Composable
private fun EditorDialog(
    context: Context,
    entry: ChatWallpaperLibrary.Entry,
    prefs: Prefs,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    onChanged: (ChatWallpaperLibrary.Entry) -> Unit,
    onDismiss: () -> Unit,
) {
    var dim by remember(entry.stamp) { mutableStateOf(entry.dim) }
    var blur by remember(entry.stamp) { mutableStateOf(entry.blur) }
    val file = ChatWallpaperLibrary.fileOf(context, entry)
    val bitmap = remember(entry.file) { BitmapDecoder.decodeScaled(file, 600, 600) }
    ChatDialog(onDismiss) {
        Text(entry.name, color = Palette.Fg, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp))
        Text(ChatWallpaperLibrary.displayJid(entry.jid), color = Palette.FgMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
        // The body scrolls and the dismiss stays outside it: a tall dialog on a small screen must never lose its button.
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.Bg),
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Chat wallpaper preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    if (dim > 0) {
                        Box(Modifier.fillMaxSize().background(Color(WallpaperImage.DIM_COLOR).copy(alpha = dim / 100f)))
                    }
                }
            }
            SliderItem(
                title = "Dim",
                value = dim.toFloat(),
                range = 0f..100f,
                steps = 99,
                onValueChange = { v ->
                    dim = v.roundToInt()
                    onChanged(ChatWallpaperLibrary.setDimBlur(prefs, entry, dim, blur))
                },
                valueLabel = { "${it.toInt()}%" },
            )
            // Range 0-150 matches the global blur slider and the hook.
            SliderItem(
                title = "Blur",
                value = blur.toFloat(),
                range = 0f..150f,
                steps = 149,
                onValueChange = { v ->
                    blur = v.roundToInt()
                    onChanged(ChatWallpaperLibrary.setDimBlur(prefs, entry, dim, blur))
                },
                valueLabel = { "${it.toInt()}" },
            )
            CategoryRow(label = "Replace the picture", onClick = onReplace)
            CategoryRow(label = "Use the wallpaper instead", divider = false, onClick = onRemove)
        }
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            border = BorderStroke(1.dp, Palette.RuleStrong),
        ) { Text("Done", color = Palette.Fg) }
    }
}

@Composable
private fun ChatDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.Surface, RoundedCornerShape(16.dp))
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
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
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.Surface, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, color = Palette.Fg, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(body, color = Palette.FgMuted, fontSize = 14.sp, lineHeight = 20.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, Palette.RuleStrong),
                ) { Text("Cancel", color = Palette.Fg) }
                Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                    Text(confirmLabel, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

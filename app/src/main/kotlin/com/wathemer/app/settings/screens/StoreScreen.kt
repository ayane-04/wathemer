// The theme store: everyone's themes by category, a page for each, and Get, Update or Apply.
// The store's state lives in StoreSession, so leaving this screen loses neither the list nor a download.
package com.wathemer.app.settings.screens

import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wathemer.app.settings.components.ConfirmDialog
import com.wathemer.app.settings.components.LocalAccent
import com.wathemer.app.settings.components.NavTopBar
import com.wathemer.app.settings.components.NoteText
import com.wathemer.app.settings.components.NoticeDialog
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.components.TopBarMenu
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.prefs.Prefs
import com.wathemer.app.settings.prefs.ThemeDoc
import com.wathemer.app.settings.prefs.ThemeLibrary
import com.wathemer.app.settings.preview.LocalThemeSnapshot
import com.wathemer.app.settings.preview.ThemeSnapshot
import com.wathemer.app.settings.preview.WaPreview
import com.wathemer.app.settings.preview.WaPreviewKind
import com.wathemer.app.settings.preview.snapshotFromPrefs
import com.wathemer.app.store.StoreAsset
import com.wathemer.app.store.StoreImages
import com.wathemer.app.store.StoreIndex
import com.wathemer.app.store.StoreInstall
import com.wathemer.app.store.StorePreview
import com.wathemer.app.store.StoreSession
import com.wathemer.app.store.StoreTheme
import com.wathemer.app.store.storeContents
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The store's list, or one theme's page. */
@Composable
fun StoreScreen(nav: NavController, prefs: Prefs, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot = LocalThemeSnapshot.current
    val view by StoreSession.view
    val index = view.loaded?.index?.takeIf { view.status != StoreSession.Status.NewerSchema }

    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<Map<String, ThemeLibrary.Slot>>(emptyMap()) }
    var reloadSaved by remember { mutableIntStateOf(0) }
    var applying by remember { mutableStateOf<ThemeLibrary.Slot?>(null) }
    var updating by remember { mutableStateOf<StoreTheme?>(null) }
    var notice by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        StoreSession.watch(true)
        onDispose { StoreSession.watch(false) }
    }
    LaunchedEffect(Unit) { StoreSession.open(context) }
    LaunchedEffect(reloadSaved) { saved = withContext(Dispatchers.IO) { savedByStoreId(context) } }

    // The system closes a cached app's sockets soon after it leaves the screen, so the screen stays on while one runs.
    val running by remember { derivedStateOf { StoreSession.downloads.values.any { it is StoreSession.Download.Running } } }
    val hostView = LocalView.current
    DisposableEffect(running) {
        hostView.keepScreenOn = running
        onDispose { hostView.keepScreenOn = false }
    }

    val finishedId by remember {
        derivedStateOf { StoreSession.downloads.entries.firstOrNull { it.value is StoreSession.Download.Finished }?.key }
    }
    LaunchedEffect(finishedId) {
        val id = finishedId ?: return@LaunchedEffect
        val done = StoreSession.downloads[id] as? StoreSession.Download.Finished ?: return@LaunchedEffect
        StoreSession.consume(id)
        StoreSession.messageFor(done.theme, done.result, onScreen = true)?.let(onMessage)
        val added = done.result as? StoreInstall.Result.Added ?: return@LaunchedEffect
        reloadSaved++
        // Two dialogs at once reads as a mess; when there is something to say, say it and let them tap Apply.
        if (added.problems.isNotEmpty()) notice = added.problems else applying = added.slot
    }

    fun apply(slot: ThemeLibrary.Slot) {
        if (busy) {
            onMessage("Still finishing the last one.")
            return
        }
        busy = true
        scope.launch {
            try {
                val report = applyThemeFile(context, prefs, snapshot, slot.file, keepUndo = true)
                onMessage(report.message)
                if (report.notes.isNotEmpty()) notice = report.notes
            } finally {
                busy = false
            }
        }
    }

    // After process death the list may not be back yet, or the theme may have left the store: the list shows instead.
    val theme = openId?.let { id -> index?.themes?.firstOrNull { it.id == id } }
    // One back for the arrow and the gesture alike: the page closes first, then the store.
    val onBack: () -> Unit = { if (theme != null) openId = null else nav.pop() }
    BackHandler(enabled = theme != null) { openId = null }

    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(
                title = theme?.name ?: "Theme store",
                onBack = onBack,
                trailing = { TopBarMenu(listOf("Refresh" to { StoreSession.refresh(context) })) },
            )
            if (theme != null) {
                ThemePage(
                    context = context,
                    prefs = prefs,
                    theme = theme,
                    saved = saved[theme.id],
                    download = StoreSession.downloads[theme.id],
                    onGet = { if (saved[theme.id] != null) updating = theme else StoreSession.get(context, theme) },
                    onApply = { applying = it },
                    onCancel = { StoreSession.cancel(theme.id) },
                )
            } else {
                StoreList(context, view, index, category, { category = it }, saved) { openId = it.id }
            }
        }
    }

    applying?.let { slot ->
        ConfirmDialog(
            title = "Apply ${slot.doc.name}?",
            body = applyBody(slot.doc),
            confirmLabel = "Apply",
            onConfirm = { applying = null; apply(slot) },
            onDismiss = { applying = null },
        )
    }

    updating?.let { t ->
        val held = saved[t.id]?.doc?.store?.version
        ConfirmDialog(
            title = "Update ${t.name}?",
            body = held?.let { "Replaces version $it." } ?: "Replaces your saved copy.",
            confirmLabel = "Update",
            onConfirm = { updating = null; StoreSession.get(context, t) },
            onDismiss = { updating = null },
        )
    }

    if (notice.isNotEmpty()) {
        NoticeDialog(notice) { notice = emptyList() }
    }
}

// ── The list ─────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.StoreList(
    context: Context,
    view: StoreSession.View,
    index: StoreIndex?,
    category: String?,
    onCategory: (String) -> Unit,
    saved: Map<String, ThemeLibrary.Slot>,
    onOpen: (StoreTheme) -> Unit,
) {
    statusLine(context, view, index)?.let { NoteText(it) }
    if (index == null) return
    if (index.themes.isEmpty()) {
        NoteText("No themes yet.")
        return
    }
    val current = index.categories.firstOrNull { it.id == category } ?: index.categories.first()
    PillTabs(index.categories.map { it.id to it.name }, current.id, onCategory)
    val themes = index.themes.filter { it.category == current.id }
    if (themes.isEmpty()) {
        NoteText("No ${current.name} themes yet.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(themes, key = { it.id }, contentType = { "theme" }) { t ->
            ThemeTile(t, saved[t.id], StoreSession.downloads[t.id]) { onOpen(t) }
        }
    }
}

/** A row of pills, one selected: the store's categories, and the preview's Home and Chat. */
@Composable
private fun PillTabs(
    tabs: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
) {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(padding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((id, label) in tabs) {
            val on = id == selected
            Text(
                label,
                color = if (on) Palette.Bg else Palette.Fg,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) accent else Palette.Surface)
                    .clickable { onSelect(id) }
                    .semantics { this.selected = on }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ThemeTile(theme: StoreTheme, saved: ThemeLibrary.Slot?, download: StoreSession.Download?, onClick: () -> Unit) {
    val state = stateOf(theme, saved, download)
    val marks = marksOf(theme)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${theme.name} by ${theme.author}" + (state?.let { ", $it" } ?: "")
            },
    ) {
        StoreImage(theme.thumb, Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp)), ContentScale.Crop)
        Text(
            theme.name,
            color = Palette.Fg,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 8.dp),
        )
        Text(
            "by ${theme.author}",
            color = Palette.FgMuted,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        val line = state ?: marks
        if (line.isNotEmpty()) {
            Text(
                line,
                color = if (state != null) LocalAccent.current else Palette.FgSubtle,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

// ── One theme ────────────────────────────────────────────────────────────

/** A store theme's header, and the snapshot it would give over this phone's settings; null when it cannot be had. */
private class PreviewState(val doc: ThemeDoc, val snapshot: ThemeSnapshot?)

@Composable
private fun ThemePage(
    context: Context,
    prefs: Prefs,
    theme: StoreTheme,
    saved: ThemeLibrary.Slot?,
    download: StoreSession.Download?,
    onGet: () -> Unit,
    onApply: (ThemeLibrary.Slot) -> Unit,
    onCancel: () -> Unit,
) {
    val pages = theme.shots.ifEmpty { listOf(theme.thumb) }
    val pager = rememberPagerState { pages.size }
    val pagerHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    val preview by produceState<PreviewState?>(null, theme.id, theme.version) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                StorePreview.load(context, theme)?.let { ready ->
                    val after = ThemeLibrary.previewPrefs(prefs, ready.doc, ready.wallpaper?.absolutePath)
                    PreviewState(ready.doc, after?.let { snapshotFromPrefs(it) })
                }
            }.getOrNull()
        }
    }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 48.dp),
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(pagerHeight),
        ) { page ->
            StoreImage(
                pages[page],
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .semantics { contentDescription = "Screenshot ${page + 1} of ${pages.size}" },
                ContentScale.Fit,
            )
        }
        if (pages.size > 1) PageDots(pages.size, pager.currentPage)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("by ${theme.author}", color = Palette.FgMuted, fontSize = 14.sp)
            if (theme.description.isNotEmpty()) {
                Text(theme.description, color = Palette.Fg, fontSize = 15.sp, lineHeight = 22.sp)
            }
            ContentsChips(storeContents(preview?.doc, theme.carries), theme.id)
            Text(factsOf(context, theme), color = Palette.FgMuted, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(8.dp))
            ThemeAction(theme, saved, download, onGet, onApply, onCancel)
            preview?.snapshot?.let { PreviewPanel(theme.id, it) }
        }
    }
}

/** What the theme brings, as chips; past [MAX_CONTENTS] the rest wait behind "+N more". */
@Composable
private fun ContentsChips(items: List<String>, key: String) {
    if (items.isEmpty()) return
    var all by rememberSaveable(key) { mutableStateOf(false) }
    val shown = if (all || items.size <= MAX_CONTENTS) items else items.take(MAX_CONTENTS - 1)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (item in shown) Chip(item, Palette.Fg)
        if (shown.size < items.size) Chip("+${items.size - shown.size} more", LocalAccent.current) { all = true }
    }
}

@Composable
private fun Chip(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.Surface)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** The theme over this phone's settings, Home or Chat, as Apply would leave it. */
@Composable
private fun PreviewPanel(key: String, snapshot: ThemeSnapshot) {
    var tab by rememberSaveable(key) { mutableStateOf("home") }
    Spacer(Modifier.height(8.dp))
    PillTabs(listOf("home" to "Home", "chat" to "Chat"), tab, { tab = it }, PaddingValues(vertical = 4.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height((LocalConfiguration.current.screenHeightDp * 0.45f).dp)
            .clip(RoundedCornerShape(12.dp)),
    ) {
        WaPreview(if (tab == "home") WaPreviewKind.HomeFull else WaPreviewKind.ChatBubbles, snapshot, Modifier.fillMaxSize())
    }
    Text("No glass or font in this preview.", color = Palette.FgSubtle, fontSize = 12.sp)
}

private const val MAX_CONTENTS = 4

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(6.dp)
                    .background(if (i == current) Palette.Fg else Palette.FgSubtle, CircleShape),
            )
        }
    }
}

@Composable
private fun ThemeAction(
    theme: StoreTheme,
    saved: ThemeLibrary.Slot?,
    download: StoreSession.Download?,
    onGet: () -> Unit,
    onApply: (ThemeLibrary.Slot) -> Unit,
    onCancel: () -> Unit,
) {
    val held = saved?.doc?.store?.version ?: 0
    when {
        download is StoreSession.Download.Running -> {
            val fraction = if (download.size > 0) (download.received.toFloat() / download.size).coerceIn(0f, 1f) else 0f
            val percent = (fraction * 100).toInt()
            LinearProgressIndicator(
                progress = { fraction },
                color = LocalAccent.current,
                trackColor = Palette.RuleStrong,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Downloading, $percent percent" },
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$percent%", color = Palette.FgMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(
                    "Cancel",
                    color = LocalAccent.current,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(onClick = onCancel).padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        !theme.usable -> Text("Needs a newer WaThemer.", color = Palette.FgMuted, fontSize = 14.sp)
        saved != null && held >= theme.version -> {
            Button(onClick = { onApply(saved) }, modifier = Modifier.fillMaxWidth()) {
                Text("Apply", fontWeight = FontWeight.Medium)
            }
        }
        saved != null -> {
            Button(onClick = onGet, modifier = Modifier.fillMaxWidth()) {
                Text("Update", fontWeight = FontWeight.Medium)
            }
            Text("You have version $held.", color = Palette.FgMuted, fontSize = 13.sp)
        }
        else -> {
            Button(onClick = onGet, modifier = Modifier.fillMaxWidth()) {
                Text("Get", fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ── Pictures ─────────────────────────────────────────────────────────────

/** A store picture decoded at the size it is drawn; a plain surface until it arrives, and if it never does. */
@Composable
private fun StoreImage(asset: StoreAsset, modifier: Modifier, scale: ContentScale) {
    val context = LocalContext.current
    BoxWithConstraints(modifier.background(Palette.Surface)) {
        val w = if (constraints.hasBoundedWidth) constraints.maxWidth else 360
        val h = if (constraints.maxHeight != Constraints.Infinity) constraints.maxHeight else w * 16 / 9
        val bitmap by produceState(StoreImages.cached(asset, w), asset.sha256, w) {
            if (value == null) value = StoreImages.load(context, asset, w, h)
        }
        bitmap?.let { b ->
            val image = remember(b) { b.asImageBitmap() }
            Image(image, contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxSize())
        }
    }
}

// ── Words ────────────────────────────────────────────────────────────────

private fun statusLine(context: Context, view: StoreSession.View, index: StoreIndex?): String? {
    val loaded = view.loaded
    return when (view.status) {
        StoreSession.Status.NewerSchema -> "Update WaThemer to see these themes."
        StoreSession.Status.NotAvailable -> "Store not available right now."
        StoreSession.Status.Busy -> "Store busy. Try again in a few minutes."
        StoreSession.Status.Unreachable ->
            if (loaded == null) "Couldn't reach the store."
            else "Couldn't reach the store. Showing the list from ${dayOf(context, loaded.checkedAt)}."
        StoreSession.Status.Unverified ->
            if (loaded == null) "Couldn't verify the store's list."
            else "Couldn't verify the newest list. Showing the last good one."
        StoreSession.Status.Checking, StoreSession.Status.Idle -> when {
            loaded == null -> "Loading"
            // Informational only: a phone's clock is not trusted to refuse anything.
            index != null && index.expires.isNotEmpty() && LocalDate.now().toString() > index.expires ->
                runCatching { "Not updated since ${dayOf(context, Instant.parse(index.generated).toEpochMilli())}." }
                    .getOrDefault("Not updated for a while.")
            else -> null
        }
    }
}

/** What a tile or the page says about a theme's state here; null when it is simply there to get. */
private fun stateOf(theme: StoreTheme, saved: ThemeLibrary.Slot?, download: StoreSession.Download?): String? {
    val held = saved?.doc?.store?.version ?: 0
    return when {
        download is StoreSession.Download.Running ->
            if (download.size > 0) "${download.received * 100 / download.size}%" else "Downloading"
        !theme.usable -> "Needs a newer WaThemer"
        saved != null && held >= theme.version -> "Saved"
        saved != null -> "Update"
        else -> null
    }
}

private fun marksOf(theme: StoreTheme): String = listOfNotNull(
    "Wallpaper".takeIf { "wallpaper" in theme.carries },
    "Font".takeIf { "font" in theme.carries },
    "Liquid Glass".takeIf { "glass" in theme.carries },
).joinToString(" · ")

private fun factsOf(context: Context, theme: StoreTheme): String {
    val parts = mutableListOf<String>()
    parts += "Version ${theme.version}"
    parts += Formatter.formatShortFileSize(context, theme.fileSize)
    if (theme.updated.isNotEmpty()) {
        runCatching { LocalDate.parse(theme.updated) }.getOrNull()?.let {
            parts += "Updated " + DateFormat.getMediumDateFormat(context).format(Date.from(it.atStartOfDay(ZoneOffset.UTC).toInstant()))
        }
    }
    return parts.joinToString(" · ")
}

private fun dayOf(context: Context, millis: Long): String = DateFormat.getMediumDateFormat(context).format(Date(millis))

/** The newest saved copy of each store theme, by its store id. */
private fun savedByStoreId(context: Context): Map<String, ThemeLibrary.Slot> {
    val out = HashMap<String, ThemeLibrary.Slot>()
    for (slot in ThemeLibrary.list(context)) {
        val ref = slot.doc.store ?: continue
        if (ref.version > (out[ref.id]?.doc?.store?.version ?: 0)) out[ref.id] = slot
    }
    return out
}

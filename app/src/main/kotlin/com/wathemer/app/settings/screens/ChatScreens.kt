package com.wathemer.app.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wathemer.app.settings.components.AppAccent
import com.wathemer.app.settings.components.CategoryRow
import com.wathemer.app.settings.components.ExpandableSection
import com.wathemer.app.settings.components.NavTopBar
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.components.PreviewPanel
import com.wathemer.app.settings.components.StubNote
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.nav.Screen
import com.wathemer.app.settings.prefs.BubbleStyles
import com.wathemer.app.settings.prefs.Prefs
import com.wathemer.app.settings.preview.BubbleThumb
import com.wathemer.app.settings.preview.LocalThemeSnapshot
import com.wathemer.app.settings.preview.SingleBubblePreview
import com.wathemer.app.settings.preview.SnapshotOverrideRow
import com.wathemer.app.settings.preview.WaPreview
import com.wathemer.app.settings.preview.WaPreviewKind
import com.wathemer.app.settings.preview.updateBubbleStyleIncoming
import com.wathemer.app.settings.preview.updateBubbleStyleOutgoing

/** The Chat category tree: Bubbles (with the Custom bubble shape picker), Input, Header, Quote, Misc. */

/* ── Root: Chat ──────────────────────────────────────────── */

@Composable
fun ChatScreen(nav: NavController, prefs: Prefs) {
    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(
                title = "Chat screen",
                subtitle = "5 areas",
                onBack = { nav.pop() },
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CategoryRow(
                    label = "Bubbles",
                    description = "Message bubbles, both sides.",
                    onClick = { nav.push(Screen.ChatBubbles) },
                )
                CategoryRow(
                    label = "Input & compose bar",
                    description = "The compose bar at the bottom.",
                    onClick = { nav.push(Screen.ChatInputBar) },
                )
                CategoryRow(
                    label = "Header & toolbar",
                    description = "The conversation's top bar.",
                    onClick = { nav.push(Screen.ChatHeaderToolbar) },
                )
                CategoryRow(
                    label = "Quote & replies",
                    description = "Quoted replies.",
                    onClick = { nav.push(Screen.ChatQuoteReplies) },
                )
                CategoryRow(
                    label = "Other misc settings",
                    description = "Ticks, forwarded labels, captions and links.",
                    onClick = { nav.push(Screen.ChatMisc) },
                )

                Spacer(Modifier.size(8.dp))
                StubNote("Rows follow your global colours until you change them. ↺ puts one back.")
            }
        }
    }
}

/* ── Chat > Bubbles ──────────────────────────────────────── */

@Composable
fun ChatBubblesScreen(nav: NavController, prefs: Prefs) {
    val snap = LocalThemeSnapshot.current
    var incomingOpen by remember { mutableStateOf(false) }
    var outgoingOpen by remember { mutableStateOf(false) }
    val incomingSwatch = snap.value.bubbleLeftBg.let { if (it != 0) it else prefs.background }
    val outgoingSwatch = snap.value.bubbleRightBg.let { if (it != 0) it else prefs.primary }
    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(title = "Bubbles", subtitle = "Chat · Bubbles", onBack = { nav.pop() })
            PreviewPanel(modifier = Modifier.weight(0.4f).fillMaxWidth()) {
                WaPreview(
                    WaPreviewKind.ChatBubbles,
                    LocalThemeSnapshot.current.value,
                )
            }
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ExpandableSection(label = "Incoming bubbles", expanded = incomingOpen, onToggle = { incomingOpen = !incomingOpen }, swatchColor = incomingSwatch) {
                    SnapshotOverrideRow(
                        "Bubble background", prefs, Prefs.BUBBLE_LEFT_BG, prefs.background, "Incoming",
                        getter = { bubbleLeftBg }, copier = { copy(bubbleLeftBg = it) },
                    )
                    SnapshotOverrideRow(
                        "Message text", prefs, Prefs.BUBBLE_LEFT_TEXT, prefs.text, "Incoming",
                        getter = { bubbleLeftText }, copier = { copy(bubbleLeftText = it) },
                    )
                    SnapshotOverrideRow(
                        "Timestamp", prefs, Prefs.BUBBLE_LEFT_DATE, prefs.text, "Incoming",
                        getter = { bubbleLeftDate }, copier = { copy(bubbleLeftDate = it) },
                    )
                }
                ExpandableSection(label = "Outgoing bubbles", expanded = outgoingOpen, onToggle = { outgoingOpen = !outgoingOpen }, swatchColor = outgoingSwatch) {
                    SnapshotOverrideRow(
                        "Bubble background", prefs, Prefs.BUBBLE_RIGHT_BG, prefs.primary, "Outgoing",
                        getter = { bubbleRightBg }, copier = { copy(bubbleRightBg = it) },
                    )
                    SnapshotOverrideRow(
                        "Message text", prefs, Prefs.BUBBLE_RIGHT_TEXT, prefs.text, "Outgoing",
                        getter = { bubbleRightText }, copier = { copy(bubbleRightText = it) },
                    )
                    SnapshotOverrideRow(
                        "Timestamp", prefs, Prefs.BUBBLE_RIGHT_DATE, prefs.text, "Outgoing",
                        getter = { bubbleRightDate }, copier = { copy(bubbleRightDate = it) },
                    )
                }
                CategoryRow(
                    label = "Custom bubble",
                    description = "Pick a bubble shape per side. The first " +
                        "${BubbleStyles.FIRST_COLOUR_STYLE - 1} take your colour.",
                    onClick = { nav.push(Screen.ChatBubblesCustom) },
                )
            }
        }
    }
}

/* ── Chat > Bubbles > Custom bubble ──────────────────────────────────────── */

/** Shape picker: one thumbnail grid with a side switch; the drawing, not the number label, is the identifier. */
@Composable
fun ChatBubbleShapesScreen(nav: NavController, prefs: Prefs) {
    val snap = LocalThemeSnapshot.current
    var editingOutgoing by remember { mutableStateOf(false) }

    val current = if (editingOutgoing) snap.value.bubbleStyleOutgoing else snap.value.bubbleStyleIncoming
    // Unset falls back to stock dark bubble colours to match the hook; do not cascade to the global tokens.
    val tintInt = if (editingOutgoing) {
        snap.value.bubbleRightBg.let { if (it != 0) it else BubbleStyles.STOCK_DARK_OUTGOING }
    } else {
        snap.value.bubbleLeftBg.let { if (it != 0) it else BubbleStyles.STOCK_DARK_INCOMING }
    }

    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(
                title = "Bubble shape",
                subtitle = (if (editingOutgoing) "Outgoing" else "Incoming") +
                    " · 1-${BubbleStyles.FIRST_COLOUR_STYLE - 1} take your colour, " +
                    "${BubbleStyles.FIRST_COLOUR_STYLE}+ are fixed artwork",
                onBack = { nav.pop() },
            )
            PreviewPanel(modifier = Modifier.height(150.dp).fillMaxWidth()) {
                SingleBubblePreview(isOutgoing = editingOutgoing, style = current, tint = Color(tintInt))
            }
            Spacer(Modifier.weight(1f))
            SideSwitch(
                editingOutgoing = editingOutgoing,
                onSelect = { editingOutgoing = it },
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )
            // A fixed bottom strip, two rows scrolling sideways, so the preview keeps the screen.
            LazyHorizontalGrid(
                rows = GridCells.Fixed(2),
                modifier = Modifier.height(206.dp).fillMaxWidth(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(BubbleStyles.NAMES.size) { i ->
                    ShapeCell(
                        index = i,
                        label = BubbleStyles.NAMES[i],
                        isOutgoing = editingOutgoing,
                        tint = Color(tintInt),
                        selected = i == current,
                        onPick = {
                            if (editingOutgoing) snap.updateBubbleStyleOutgoing(prefs, i)
                            else snap.updateBubbleStyleIncoming(prefs, i)
                        },
                    )
                }
            }
        }
    }
}

/** Two-state segmented control for which side the grid is editing. */
@Composable
private fun SideSwitch(editingOutgoing: Boolean, onSelect: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Palette.SurfaceElev, RoundedCornerShape(8.dp))
            .border(1.dp, Palette.Rule, RoundedCornerShape(8.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf(false to "Incoming", true to "Outgoing").forEach { (isOut, text) ->
            val on = editingOutgoing == isOut
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (on) AppAccent.copy(alpha = 0.20f) else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { onSelect(isOut) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text,
                    color = if (on) Palette.Fg else Palette.FgMuted,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** One grid cell, tinted the way WhatsApp will tint it; colour-artwork styles stay deliberately untinted. */
@Composable
private fun ShapeCell(
    index: Int,
    label: String,
    isOutgoing: Boolean,
    tint: Color,
    selected: Boolean,
    onPick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .background(Palette.SurfaceElev, RoundedCornerShape(12.dp))
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) AppAccent else Palette.Rule,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onPick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (index == 0) {
                Text("Stock", color = Palette.FgMuted, fontSize = 13.sp)
            } else {
                BubbleThumb(style = index, isOutgoing = isOutgoing, tint = tint)
            }
        }
        Text(
            label,
            color = if (selected) Palette.Fg else Palette.FgMuted,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/* ── Chat > stub leaves (Input / Header / Quote / Misc) ──── */

@Composable
fun ChatInputBarScreen(nav: NavController, prefs: Prefs) {
    val snapshot = LocalThemeSnapshot.current
    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(title = "Input & compose bar", subtitle = "Chat · Input", onBack = { nav.pop() })
            PreviewPanel(modifier = Modifier.weight(0.4f).fillMaxWidth()) {
                WaPreview(WaPreviewKind.ChatInputBar, snapshot.value)
            }
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                SnapshotOverrideRow(
                    "Bar background", snapshot, prefs, Prefs.COMPOSE_BAR_BG, prefs.background, "Chat · Input",
                    getter = { composeBarBg }, copier = { copy(composeBarBg = it) },
                )
                SnapshotOverrideRow(
                    "Entry text", snapshot, prefs, Prefs.COMPOSE_ENTRY_TEXT, prefs.text, "Chat · Input",
                    getter = { composeEntryText }, copier = { copy(composeEntryText = it) },
                )
                SnapshotOverrideRow(
                    "Send button background", snapshot, prefs, Prefs.COMPOSE_SEND_BG, prefs.primary, "Chat · Input",
                    getter = { composeSendBg }, copier = { copy(composeSendBg = it) },
                )
                SnapshotOverrideRow(
                    "Mic/Send icon", snapshot, prefs, Prefs.COMPOSE_SEND_ICON, prefs.text, "Chat · Input",
                    getter = { composeSendIcon }, copier = { copy(composeSendIcon = it) },
                )
                SnapshotOverrideRow(
                    "Icon colour", snapshot, prefs, Prefs.COMPOSE_ICON_TINT, prefs.text, "Chat · Input",
                    getter = { composeIconTint }, copier = { copy(composeIconTint = it) },
                )
            }
        }
    }
}

@Composable
fun ChatHeaderToolbarScreen(nav: NavController, prefs: Prefs) {
    val snapshot = LocalThemeSnapshot.current
    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(title = "Header & toolbar", subtitle = "Chat · Header", onBack = { nav.pop() })
            PreviewPanel(modifier = Modifier.weight(0.4f).fillMaxWidth()) {
                WaPreview(WaPreviewKind.ChatHeader, snapshot.value)
            }
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SnapshotOverrideRow(
                    "Bar fill", snapshot, prefs, Prefs.CHAT_TOOLBAR_BG, prefs.background, "Chat · Header",
                    getter = { chatToolbarBg }, copier = { copy(chatToolbarBg = it) },
                )
                SnapshotOverrideRow(
                    "Title text", snapshot, prefs, Prefs.CHAT_HEADER_TITLE, prefs.text, "Chat · Header",
                    getter = { chatHeaderTitle }, copier = { copy(chatHeaderTitle = it) },
                )
                SnapshotOverrideRow(
                    "Subtitle text", snapshot, prefs, Prefs.CHAT_HEADER_SUBTITLE, prefs.text, "Chat · Header",
                    getter = { chatHeaderSubtitle }, copier = { copy(chatHeaderSubtitle = it) },
                )
                SnapshotOverrideRow(
                    "Action icons", snapshot, prefs, Prefs.CHAT_TOOLBAR_ICONS, prefs.text, "Chat · Header",
                    getter = { chatToolbarIcons }, copier = { copy(chatToolbarIcons = it) },
                )
            }
        }
    }
}

@Composable
fun ChatQuoteRepliesScreen(nav: NavController, prefs: Prefs) {
    val snapshot = LocalThemeSnapshot.current
    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(title = "Quote & replies", subtitle = "Chat · Quote", onBack = { nav.pop() })
            PreviewPanel(modifier = Modifier.weight(0.4f).fillMaxWidth()) {
                WaPreview(WaPreviewKind.ChatQuote, snapshot.value)
            }
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SnapshotOverrideRow(
                    "Quote bar (accent stripe)", prefs, Prefs.QUOTE_BAR_COLOR, prefs.primary, "Quote",
                    getter = { quoteBarColor }, copier = { copy(quoteBarColor = it) },
                )
                SnapshotOverrideRow(
                    "Quote background", prefs, Prefs.QUOTE_BG_COLOR, prefs.background, "Quote",
                    getter = { quoteBgColor }, copier = { copy(quoteBgColor = it) },
                )
                SnapshotOverrideRow(
                    "Quote text", prefs, Prefs.QUOTE_TEXT_COLOR, prefs.text, "Quote",
                    getter = { quoteTextColor }, copier = { copy(quoteTextColor = it) },
                )
            }
        }
    }
}

@Composable
fun ChatMiscScreen(nav: NavController, prefs: Prefs) {
    val snapshot = LocalThemeSnapshot.current
    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(title = "Other misc settings", subtitle = "Chat · Misc", onBack = { nav.pop() })
            PreviewPanel(modifier = Modifier.weight(0.4f).fillMaxWidth()) {
                WaPreview(WaPreviewKind.ChatMisc, snapshot.value)
            }
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SnapshotOverrideRow(
                    "Read tick (blue ✓✓)", prefs, Prefs.TICK_SEEN_COLOR, prefs.primary, "Misc",
                    getter = { tickSeenColor }, copier = { copy(tickSeenColor = it) },
                )
                SnapshotOverrideRow(
                    "Delivered tick (grey)", prefs, Prefs.TICK_UNSEEN_COLOR, prefs.text, "Misc",
                    getter = { tickUnseenColor }, copier = { copy(tickUnseenColor = it) },
                )
                SnapshotOverrideRow(
                    "Forwarded label", prefs, Prefs.FORWARDED_LABEL_COLOR, prefs.text, "Misc",
                    getter = { forwardedLabelColor }, copier = { copy(forwardedLabelColor = it) },
                )
                SnapshotOverrideRow(
                    "Media caption", prefs, Prefs.MEDIA_CAPTION_COLOR, prefs.text, "Misc",
                    getter = { mediaCaptionColor }, copier = { copy(mediaCaptionColor = it) },
                )
                SnapshotOverrideRow(
                    "Link colour", prefs, Prefs.LINK_COLOR, prefs.primary, "Misc",
                    getter = { linkColor }, copier = { copy(linkColor = it) },
                )
            }
        }
    }
}


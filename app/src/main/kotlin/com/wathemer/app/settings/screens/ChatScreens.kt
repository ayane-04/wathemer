// The Chats page: every override of a conversation, in sections, under a preview that follows the section in hand.
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.wathemer.app.settings.components.ExpandGroup
import com.wathemer.app.settings.components.NavTopBar
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.components.PreviewPanel
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.nav.Screen
import com.wathemer.app.settings.prefs.BubbleStyles
import com.wathemer.app.settings.prefs.Prefs
import com.wathemer.app.settings.preview.BubbleThumb
import com.wathemer.app.settings.preview.LocalThemeSnapshot
import com.wathemer.app.settings.preview.SingleBubblePreview
import com.wathemer.app.settings.preview.SnapshotOverrideRow
import com.wathemer.app.settings.preview.ThemeSnapshot
import com.wathemer.app.settings.preview.WaPreview
import com.wathemer.app.settings.preview.WaPreviewKind
import com.wathemer.app.settings.preview.updateBubbleStyleIncoming
import com.wathemer.app.settings.preview.updateBubbleStyleOutgoing

@Composable
fun ChatScreen(nav: NavController, prefs: Prefs) {
    val snapshot = LocalThemeSnapshot.current
    val snap = snapshot.value
    // The preview shows the part of the conversation the last touched row belongs to.
    var kind by remember { mutableStateOf<WaPreviewKind>(WaPreviewKind.ChatBubbles) }

    @Composable
    fun row(
        name: String,
        key: String,
        fallback: Int,
        focus: WaPreviewKind,
        divider: Boolean = true,
        getter: ThemeSnapshot.() -> Int,
        copier: ThemeSnapshot.(Int) -> ThemeSnapshot,
    ) {
        SnapshotOverrideRow(
            name = name, prefs = prefs, prefKey = key, fallback = fallback,
            divider = divider, onOpen = { kind = focus }, getter = getter, copier = copier,
        )
    }

    val shapeHint = listOf(snap.bubbleStyleIncoming, snap.bubbleStyleOutgoing)
        .map { BubbleStyles.NAMES.getOrNull(it) ?: "Stock" }
        .let { (i, o) -> if (i == o) i else "$i · $o" }

    // The group rows carry the bubble each side actually shows.
    val incomingSwatch = snap.bubbleLeftBg.let { if (it != 0) it else prefs.background }
    val outgoingSwatch = snap.bubbleRightBg.let { if (it != 0) it else prefs.primary }

    SubScreenScaffold(
        nav = nav,
        title = "Chats",
        preview = { WaPreview(kind, snap) },
    ) {
        ExpandGroup("Incoming bubbles", swatchColor = incomingSwatch, onOpen = { kind = WaPreviewKind.ChatBubbles }) {
            row("Bubble background", Prefs.BUBBLE_LEFT_BG,   prefs.background, WaPreviewKind.ChatBubbles, getter = { bubbleLeftBg },   copier = { copy(bubbleLeftBg = it) })
            row("Message text", Prefs.BUBBLE_LEFT_TEXT, prefs.text,       WaPreviewKind.ChatBubbles, getter = { bubbleLeftText }, copier = { copy(bubbleLeftText = it) })
            row("Timestamp", Prefs.BUBBLE_LEFT_DATE, prefs.text,       WaPreviewKind.ChatBubbles, getter = { bubbleLeftDate }, copier = { copy(bubbleLeftDate = it) })
        }
        ExpandGroup("Outgoing bubbles", swatchColor = outgoingSwatch, onOpen = { kind = WaPreviewKind.ChatBubbles }) {
            row("Bubble background", Prefs.BUBBLE_RIGHT_BG,   prefs.primary, WaPreviewKind.ChatBubbles, getter = { bubbleRightBg },   copier = { copy(bubbleRightBg = it) })
            row("Message text", Prefs.BUBBLE_RIGHT_TEXT, prefs.text,    WaPreviewKind.ChatBubbles, getter = { bubbleRightText }, copier = { copy(bubbleRightText = it) })
            row("Timestamp", Prefs.BUBBLE_RIGHT_DATE, prefs.text,    WaPreviewKind.ChatBubbles, getter = { bubbleRightDate }, copier = { copy(bubbleRightDate = it) })
        }
        CategoryRow(
            label = "Bubble shape",
            trailingHint = shapeHint,
            onClick = { nav.push(Screen.ChatBubbleShapes) },
        )
        ExpandGroup("Input & compose bar", onOpen = { kind = WaPreviewKind.ChatInputBar }) {
            row("Bar background", Prefs.COMPOSE_BAR_BG,     prefs.background, WaPreviewKind.ChatInputBar, getter = { composeBarBg },     copier = { copy(composeBarBg = it) })
            row("Entry text", Prefs.COMPOSE_ENTRY_TEXT, prefs.text,       WaPreviewKind.ChatInputBar, getter = { composeEntryText }, copier = { copy(composeEntryText = it) })
            row("Send button background", Prefs.COMPOSE_SEND_BG,    prefs.primary,    WaPreviewKind.ChatInputBar, getter = { composeSendBg },    copier = { copy(composeSendBg = it) })
            row("Mic/Send icon", Prefs.COMPOSE_SEND_ICON,  prefs.text,       WaPreviewKind.ChatInputBar, getter = { composeSendIcon },  copier = { copy(composeSendIcon = it) })
            row("Icon colour", Prefs.COMPOSE_ICON_TINT,  prefs.text,       WaPreviewKind.ChatInputBar, getter = { composeIconTint },  copier = { copy(composeIconTint = it) })
        }
        ExpandGroup("Header & toolbar", onOpen = { kind = WaPreviewKind.ChatHeader }) {
            row("Bar fill", Prefs.CHAT_TOOLBAR_BG,      prefs.background, WaPreviewKind.ChatHeader, getter = { chatToolbarBg },      copier = { copy(chatToolbarBg = it) })
            row("Title text", Prefs.CHAT_HEADER_TITLE,    prefs.text,       WaPreviewKind.ChatHeader, getter = { chatHeaderTitle },    copier = { copy(chatHeaderTitle = it) })
            row("Subtitle text", Prefs.CHAT_HEADER_SUBTITLE, prefs.text,       WaPreviewKind.ChatHeader, getter = { chatHeaderSubtitle }, copier = { copy(chatHeaderSubtitle = it) })
            row("Action icons", Prefs.CHAT_TOOLBAR_ICONS,   prefs.text,       WaPreviewKind.ChatHeader, getter = { chatToolbarIcons },   copier = { copy(chatToolbarIcons = it) })
        }
        ExpandGroup("Quote & replies", onOpen = { kind = WaPreviewKind.ChatQuote }) {
            row("Quote bar (accent stripe)", Prefs.QUOTE_BAR_COLOR,  prefs.primary,    WaPreviewKind.ChatQuote, getter = { quoteBarColor },  copier = { copy(quoteBarColor = it) })
            row("Quote background", Prefs.QUOTE_BG_COLOR,   prefs.background, WaPreviewKind.ChatQuote, getter = { quoteBgColor },   copier = { copy(quoteBgColor = it) })
            row("Quote text", Prefs.QUOTE_TEXT_COLOR, prefs.text,       WaPreviewKind.ChatQuote, getter = { quoteTextColor }, copier = { copy(quoteTextColor = it) })
        }
        ExpandGroup("Misc", onOpen = { kind = WaPreviewKind.ChatMisc }) {
            row("Read tick (blue ✓✓)", Prefs.TICK_SEEN_COLOR,       prefs.primary, WaPreviewKind.ChatMisc, getter = { tickSeenColor },       copier = { copy(tickSeenColor = it) })
            row("Delivered tick (grey)", Prefs.TICK_UNSEEN_COLOR,     prefs.text,    WaPreviewKind.ChatMisc, getter = { tickUnseenColor },     copier = { copy(tickUnseenColor = it) })
            row("Forwarded label", Prefs.FORWARDED_LABEL_COLOR, prefs.text,    WaPreviewKind.ChatMisc, getter = { forwardedLabelColor }, copier = { copy(forwardedLabelColor = it) })
            row("Media caption",   Prefs.MEDIA_CAPTION_COLOR,   prefs.text,    WaPreviewKind.ChatMisc, getter = { mediaCaptionColor },   copier = { copy(mediaCaptionColor = it) })
            row("Link colour", Prefs.LINK_COLOR,            prefs.primary, WaPreviewKind.ChatMisc, getter = { linkColor },           copier = { copy(linkColor = it) })
        }
    }
}

// ── Chats > Bubble shape ─────────────────────────────────────────────────

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
            NavTopBar(title = "Bubble shape", onBack = { nav.pop() })
            PreviewPanel(modifier = Modifier.height(150.dp).fillMaxWidth()) {
                SingleBubblePreview(isOutgoing = editingOutgoing, style = current, tint = Color(tintInt))
            }
            Spacer(Modifier.weight(1f))
            SideSwitch(
                editingOutgoing = editingOutgoing,
                onSelect = { editingOutgoing = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            // A fixed bottom strip, two rows scrolling sideways, so the preview keeps the screen.
            LazyHorizontalGrid(
                rows = GridCells.Fixed(2),
                modifier = Modifier.height(206.dp).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
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
            .border(1.dp, Palette.RuleStrong, RoundedCornerShape(20.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf(false to "Incoming", true to "Outgoing").forEach { (isOut, text) ->
            val on = editingOutgoing == isOut
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (on) AppAccent.copy(alpha = 0.20f) else Color.Transparent, RoundedCornerShape(17.dp))
                    .clickable { onSelect(isOut) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text,
                    color = if (on) Palette.Fg else Palette.FgMuted,
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
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
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

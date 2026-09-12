// Per-scope live preview dispatcher, driven by the observable ThemeSnapshot.
// Read the snapshot, not SharedPreferences; prefs are downstream persistence and never update live.
package com.wathemer.app.settings.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wathemer.app.R
import com.wathemer.app.settings.components.BitmapIcon
import com.wathemer.app.settings.prefs.Prefs

// ── Mock icons ──────────────────────────────────────────────────────────
// Generated bitmaps tinted at draw time; the dots and ticks stay drawn.
@Composable
private fun SendArrow(tint: Color) = BitmapIcon(R.drawable.ic_wa_send, tint, 15.dp)

/** Reply / forward arrow. [mirrored] flips it for "forwarded". */
@Composable
private fun ReplyArrow(tint: Color, size: Dp = 13.dp, mirrored: Boolean = false) =
    BitmapIcon(R.drawable.ic_wa_reply, tint, size, Modifier.scale(if (mirrored) -1f else 1f, 1f))

@Composable
private fun StarIcon(tint: Color) = BitmapIcon(R.drawable.ic_wa_star, tint, 13.dp)

@Composable
private fun TrashIcon(tint: Color) = BitmapIcon(R.drawable.ic_wa_trash, tint, 13.dp)

@Composable
private fun OverflowIcon(tint: Color) {
    Canvas(modifier = Modifier.size(width = 4.dp, height = 15.dp)) {
        val w = size.width; val h = size.height
        val r = w * 0.5f
        drawCircle(tint, r, Offset(w * 0.5f, h * 0.2f))
        drawCircle(tint, r, Offset(w * 0.5f, h * 0.5f))
        drawCircle(tint, r, Offset(w * 0.5f, h * 0.8f))
    }
}

/** Neutral photo placeholder: the framed picture, the universal "image" mark. */
@Composable
private fun PhotoPlaceholder(tint: Color) = BitmapIcon(R.drawable.ic_row_wallpaper, tint, 34.dp)

/** Fine-grained for the chat scopes; every surface that previews Home shares HomeFull. */
sealed class WaPreviewKind {
    object HomeFull : WaPreviewKind()

    object ChatBubbles : WaPreviewKind()
    object ChatHeader : WaPreviewKind()
    object ChatInputBar : WaPreviewKind()
    object ChatQuote : WaPreviewKind()
    object ChatMisc : WaPreviewKind()

    object Selection : WaPreviewKind()
}

/** Legacy entry: delegates to [LocalThemeSnapshot] for live updates. */
@Composable
fun WaPreview(kind: WaPreviewKind, prefs: Prefs, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_PARAMETER") prefs
    WaPreview(kind, LocalThemeSnapshot.current.value, modifier)
}

/** The canonical entry: pass the snapshot from `LocalThemeSnapshot` so updates land live. */
@Composable
fun WaPreview(kind: WaPreviewKind, snapshot: ThemeSnapshot, modifier: Modifier = Modifier) {
    when (kind) {
        WaPreviewKind.HomeFull       -> WaHomePreview(snapshot.toTokens(), HomeFocus.None, modifier)
        WaPreviewKind.ChatBubbles    -> BubblesScope(snapshot, modifier)
        WaPreviewKind.ChatHeader     -> ChatHeaderScope(snapshot, modifier)
        WaPreviewKind.ChatInputBar   -> ComposeScope(snapshot, modifier)
        WaPreviewKind.ChatQuote      -> QuoteScope(snapshot, modifier)
        WaPreviewKind.ChatMisc       -> MiscScope(snapshot, modifier)
        WaPreviewKind.Selection      -> SelectionScope(snapshot, modifier)
    }
}

// ── Chat scopes: derive palette from snapshot fields with fallback cascade ───────────────────

@Composable
private fun BubblesScope(snap: ThemeSnapshot, modifier: Modifier = Modifier) {
    val pal = chatPalette(snap)
    Box(modifier = modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) {
        ChatWallpaper(baseColor = pal.bg, doodleColor = pal.text, snap = snap)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                DatePill(pal.surface, pal.subtle)
            }
            ChatRow(end = false) { IncomingBubble(pal, "Hey, are you home?", "10:14") }
            ChatRow(end = true)  { OutgoingBubble(pal, "Yes! Just got back.", "10:14", TickKind.Read) }
            ChatRow(end = false) { IncomingBubble(pal, "Want me to bring dinner?", "10:15") }
            ChatRow(end = true)  { OutgoingBubble(pal, "Yes please. Pasta?", "10:15", TickKind.Delivered) }
        }
    }
}

@Composable
private fun ChatHeaderScope(snap: ThemeSnapshot, modifier: Modifier = Modifier) {
    val pal = chatPalette(snap)
    val hBarBg = snap.chatToolbarBg.toColorOr(snap.homeToolbarBg.toColorOr(pal.surface))
    val hIcons = snap.chatToolbarIcons.toColorOr(snap.homeToolbarIcons.toColorOr(pal.text.copy(alpha = 0.72f)))
    val hTitle = snap.chatHeaderTitle.toColorOr(pal.text)
    val hSubtitle = snap.chatHeaderSubtitle.toColorOr(pal.subtle)

    Column(modifier = modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) {
        HeaderRow(pal, hBarBg, hIcons, hTitle, hSubtitle, subtitleText = "online")
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            ChatWallpaper(baseColor = pal.bg, doodleColor = pal.text, snap = snap)
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ChatRow(end = false) { IncomingBubble(pal, "Hey, are you home?", "10:14") }
                ChatRow(end = true)  { OutgoingBubble(pal, "Yes! Just got back.", "10:14", TickKind.Read) }
            }
        }
    }
}

@Composable
private fun ComposeScope(snap: ThemeSnapshot, modifier: Modifier = Modifier) {
    val pal = chatPalette(snap)
    val barBg    = snap.composeBarBg.toColorOr(pal.surface)
    val entryTxt = snap.composeEntryText.toColorOr(pal.text)
    val sendBg   = snap.composeSendBg.toColorOr(pal.accent)
    val sendIcon = snap.composeSendIcon.toColorOr(pal.onAccent)
    val iconTint = snap.composeIconTint.toColorOr(pal.subtle)
    val hBarBg = snap.chatToolbarBg.toColorOr(snap.homeToolbarBg.toColorOr(pal.surface))
    val hIcons = snap.chatToolbarIcons.toColorOr(snap.homeToolbarIcons.toColorOr(pal.text.copy(alpha = 0.72f)))
    val hTitle = snap.chatHeaderTitle.toColorOr(pal.text)
    val hSubtitle = snap.chatHeaderSubtitle.toColorOr(pal.accent)

    Column(modifier = modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) {
        HeaderRow(pal, hBarBg, hIcons, hTitle, hSubtitle, subtitleText = "typing…")
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            ChatWallpaper(baseColor = pal.bg, doodleColor = pal.text, snap = snap)
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ChatRow(end = true)  { OutgoingBubble(pal, "Yes please. Pasta?", "10:15", TickKind.Read) }
                ChatRow(end = false) { TypingBubble(pal) }
            }
        }
        ThinRule(pal.text.copy(alpha = 0.05f))
        Row(
            modifier = Modifier.fillMaxWidth().background(pal.bg).padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .background(barBg, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EmojiIcon(iconTint)
                Spacer(Modifier.size(8.dp))
                Text("Sounds good!", color = entryTxt, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.size(8.dp))
                AttachIcon(iconTint)
                Spacer(Modifier.size(8.dp))
                CameraIcon(iconTint)
            }
            Spacer(Modifier.size(6.dp))
            Box(modifier = Modifier.size(32.dp).background(sendBg, CircleShape), contentAlignment = Alignment.Center) {
                SendArrow(sendIcon)
            }
        }
    }
}

/** Quote chain: outgoing plain, incoming quoting it, outgoing quoting that. */
@Composable
private fun QuoteScope(snap: ThemeSnapshot, modifier: Modifier = Modifier) {
    val pal = chatPalette(snap)
    Box(modifier = modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) {
        ChatWallpaper(baseColor = pal.bg, doodleColor = pal.text, snap = snap)
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChatRow(end = true) {
                OutgoingBubble(pal, "Yes! Just got back.", "10:14", TickKind.Read)
            }
            ChatRow(end = false) {
                IncomingQuotedBubble(pal, "You", "Yes! Just got back.", "Want me to bring dinner?", "10:15")
            }
            ChatRow(end = true) {
                OutgoingQuotedBubble(pal, "Mom", "Want me to bring dinner?", "Yes please. Pasta?", "10:15", TickKind.Delivered)
            }
        }
    }
}

@Composable
private fun SelectionScope(snap: ThemeSnapshot, modifier: Modifier = Modifier) {
    val pal = chatPalette(snap)
    // Stock fill #12181C is also ColorSeeds.BACKGROUND[3]: a set background token rewrites it on device, unset it survives.
    val amBg     = snap.actionModeBg.toColorOr(
        if (snap.backgroundTokenSet) pal.bg else Color(0xFF12181C),
    )
    val amIcons  = snap.actionModeIcons.toColorOr(pal.text)
    val amTitle  = snap.actionModeTitle.toColorOr(pal.text)
    // Ripple is its own token; cascade to amIcons when unset, matching ActionModeColors, or its row edits with no feedback.
    val amRipple = snap.actionModeCloseRipple.toColorOr(amIcons)

    Column(modifier = modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) {
        Row(
            modifier = Modifier.fillMaxWidth().background(amBg).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The back glyph sits on a disc of its ripple colour, so the token is actually visible.
            Box(
                modifier = Modifier
                    .background(amRipple.copy(alpha = 0.22f), CircleShape)
                    .padding(6.dp),
            ) {
                BackArrow(amIcons)
            }
            Spacer(Modifier.size(12.dp))
            Text("1", color = amTitle, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .background(elevate(snap.actionModeBg.takeIf { it != 0 } ?: snap.background, 0.10f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StarIcon(amIcons)
                TrashIcon(amIcons)
                ReplyArrow(amIcons)
                OverflowIcon(amIcons)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            ChatWallpaper(baseColor = pal.bg, doodleColor = pal.text, snap = snap)
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                SelRow(end = false, selected = false, accent = pal.accent) { IncomingBubble(pal, "Hey, are you home?", "10:14") }
                SelRow(end = true,  selected = true,  accent = pal.accent) { OutgoingBubble(pal, "Yes! Just got back.", "10:14", TickKind.Read) }
                SelRow(end = false, selected = true,  accent = pal.accent) { IncomingBubble(pal, "Want me to bring dinner?", "10:15") }
                SelRow(end = true,  selected = false, accent = pal.accent) { OutgoingBubble(pal, "Yes please. Pasta?", "10:15", TickKind.Delivered) }
            }
        }
    }
}

/** Demonstrates the five Misc tokens, one bubble each. */
@Composable
private fun MiscScope(snap: ThemeSnapshot, modifier: Modifier = Modifier) {
    val pal = chatPalette(snap)
    Box(modifier = modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) {
        ChatWallpaper(baseColor = pal.bg, doodleColor = pal.text, snap = snap)
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Read message: tick uses pal.tickSeen (the user's "Read tick" pick)
            ChatRow(end = true) { OutgoingBubble(pal, "Sounds good, see you there.", "10:14", TickKind.Read) }
            // Delivered message: tick uses pal.tickUnseen
            ChatRow(end = true) { OutgoingBubble(pal, "On my way.", "10:18", TickKind.Delivered) }
            // Forwarded incoming
            ChatRow(end = false) { ForwardedIncomingBubble(pal, "Check this out 🚀", "10:20") }
            // Outgoing image bubble with media caption
            ChatRow(end = true) { CaptionedMediaBubble(pal, "Sunset from the office 🌇", "10:22") }
            // Incoming with a link: the link substring is rendered in pal.linkColor
            ChatRow(end = false) { LinkBubble(pal, "Recipe: ", "wa.me/recipes/pasta", "10:25") }
        }
    }
}

@Composable
private fun ForwardedIncomingBubble(pal: ChatPalette, body: String, time: String) {
    Box(
        modifier = Modifier
            .widthIn(min = 90.dp, max = 240.dp)
            .background(pal.leftBg, RoundedCornerShape(14.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReplyArrow(pal.forwardedLabel, size = 11.dp, mirrored = true)
                Spacer(Modifier.size(3.dp))
                Text("Forwarded", color = pal.forwardedLabel, fontSize = 9.sp, fontWeight = FontWeight.Medium)
            }
            Text(body, color = pal.leftText, fontSize = 11.sp, lineHeight = 14.sp)
            Text(time, color = pal.leftDate, fontSize = 8.sp, modifier = Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun CaptionedMediaBubble(pal: ChatPalette, caption: String, time: String) {
    Box(
        modifier = Modifier
            .widthIn(min = 110.dp, max = 240.dp)
            .background(pal.rightBg, RoundedCornerShape(14.dp))
            .padding(3.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Fake media thumbnail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp)
                    .background(pal.text.copy(alpha = 0.1f), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                PhotoPlaceholder(pal.mediaCaption)
            }
            Text(caption, color = pal.mediaCaption, fontSize = 10.5.sp, lineHeight = 13.sp,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
            Row(modifier = Modifier.align(Alignment.End).padding(end = 5.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(time, color = pal.rightDate, fontSize = 8.sp)
                Spacer(Modifier.size(4.dp))
                TickGlyph(TickKind.Read, defaultColor = pal.tickUnseen, accent = pal.tickSeen)
            }
        }
    }
}

@Composable
private fun LinkBubble(pal: ChatPalette, prefix: String, url: String, time: String) {
    Box(
        modifier = Modifier
            .widthIn(min = 90.dp, max = 240.dp)
            .background(pal.leftBg, RoundedCornerShape(14.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            // Render the link substring in pal.linkColor with underline (matches WA's link styling)
            Text(
                linkStyledText(prefix, url, pal.leftText, pal.linkColor),
                fontSize = 11.sp, lineHeight = 14.sp,
            )
            Text(time, color = pal.leftDate, fontSize = 8.sp, modifier = Modifier.align(Alignment.End).padding(top = 1.dp))
        }
    }
}

private fun linkStyledText(
    prefix: String, link: String, baseColor: Color, linkColor: Color,
): AnnotatedString {
    val b = AnnotatedString.Builder()
    b.pushStyle(SpanStyle(color = baseColor))
    b.append(prefix)
    b.pop()
    b.pushStyle(SpanStyle(
        color = linkColor,
        textDecoration = TextDecoration.Underline,
    ))
    b.append(link)
    b.pop()
    return b.toAnnotatedString()
}

// ── Shared chat palette + primitives ─────────────────────────────────────────────────────────

internal data class ChatPalette(
    val bg: Color, val surface: Color, val accent: Color, val onAccent: Color,
    val text: Color, val subtle: Color,
    val leftBg: Color, val rightBg: Color, val leftText: Color, val rightText: Color,
    val leftDate: Color, val rightDate: Color,
    // Quote/reply tokens
    val quoteBar: Color, val quoteBg: Color, val quoteText: Color,
    // Misc tokens
    val tickSeen: Color, val tickUnseen: Color,
    val forwardedLabel: Color, val mediaCaption: Color, val linkColor: Color,
)

@Composable
private fun chatPalette(snap: ThemeSnapshot): ChatPalette {
    val bg = Color(snap.background)
    val accent = Color(snap.primary)
    val text = Color(snap.text)
    val surface = elevate(snap.background, 0.06f)
    val onAccent = onColorFor(snap.primary)
    val subtle = text.copy(alpha = 0.40f)
    val onAccentSubtle = onAccent.copy(alpha = 0.55f)

    return ChatPalette(
        bg, surface, accent, onAccent, text, subtle,
        leftBg    = snap.bubbleLeftBg.toColorOr(surface),
        rightBg   = snap.bubbleRightBg.toColorOr(accent),
        leftText  = snap.bubbleLeftText.toColorOr(text),
        // Falls back to the text token, never the accent: unset, BubbleColors abstains and WA's text survives.
        rightText = snap.bubbleRightText.toColorOr(text),
        leftDate  = snap.bubbleLeftDate.toColorOr(subtle),
        rightDate = snap.bubbleRightDate.toColorOr(subtle),
        quoteBar  = snap.quoteBarColor.toColorOr(accent),
        quoteBg   = snap.quoteBgColor.toColorOr(surface),
        quoteText = snap.quoteTextColor.toColorOr(text),
        // Never fall back to accent: unset ticks must preview WA's stock seen-blue #53BDEB, the hook's first KNOWN_BLUES entry.
        tickSeen  = snap.tickSeenColor.toColorOr(Color(0xFF53BDEB)),
        // Deliberate: unset, WA draws its own muted tick; a subtle on-accent tone approximates it.
        tickUnseen= snap.tickUnseenColor.toColorOr(onAccentSubtle),
        forwardedLabel = snap.forwardedLabelColor.toColorOr(subtle),
        mediaCaption   = snap.mediaCaptionColor.toColorOr(text),
        linkColor      = snap.linkColor.toColorOr(Color(0xFF027EB5)),  // WA's actual default link blue
    )
}

internal fun Int.toColorOr(fallback: Color): Color = if (this != 0) Color(this) else fallback

@Composable
private fun ChatRow(end: Boolean, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) {
        content()
    }
}

@Composable
private fun SelRow(end: Boolean, selected: Boolean, accent: Color, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) {
        if (selected) Box(modifier = Modifier.border(2.dp, accent, RoundedCornerShape(10.dp)).padding(2.dp)) { content() }
        else content()
    }
}

@Composable
private fun DatePill(surface: Color, subtle: Color) {
    Box(modifier = Modifier.background(surface, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text("TODAY", color = subtle, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    }
}

internal enum class TickKind { Delivered, Read }

@Composable
private fun TickGlyph(kind: TickKind, defaultColor: Color, accent: Color) {
    val color = if (kind == TickKind.Read) accent else defaultColor
    DoubleTick(color)
}

@Composable
internal fun IncomingBubble(pal: ChatPalette, text: String, time: String) {
    Row(
        modifier = Modifier
            .widthIn(min = 50.dp, max = 240.dp)
            .background(pal.leftBg, RoundedCornerShape(14.dp))
            .padding(start = 11.dp, end = 9.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(text, color = pal.leftText, fontSize = 11.sp, lineHeight = 14.sp)
        Spacer(Modifier.size(8.dp))
        Text(time, color = pal.leftDate, fontSize = 8.sp)
    }
}

@Composable
internal fun OutgoingBubble(pal: ChatPalette, text: String, time: String, tickKind: TickKind = TickKind.Delivered) {
    Row(
        modifier = Modifier
            .widthIn(min = 50.dp, max = 240.dp)
            .background(pal.rightBg, RoundedCornerShape(14.dp))
            .padding(start = 11.dp, end = 9.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(text, color = pal.rightText, fontSize = 11.sp, lineHeight = 14.sp)
        Spacer(Modifier.size(8.dp))
        Text(time, color = pal.rightDate, fontSize = 8.sp)
        Spacer(Modifier.size(4.dp))
        // Read = tickSeen, Delivered and Sent = tickUnseen
        TickGlyph(tickKind, defaultColor = pal.tickUnseen, accent = pal.tickSeen)
    }
}

@Composable
private fun IncomingQuotedBubble(pal: ChatPalette, quoteAuthor: String, quoteText: String, body: String, time: String) {
    Box(
        modifier = Modifier
            .widthIn(min = 90.dp, max = 250.dp)
            .background(pal.leftBg, RoundedCornerShape(14.dp))
            .padding(4.dp),
    ) {
        Column {
            // Quote header: quoteBg wraps the strip, quoteBar is the vertical accent, quoteText colours author and body.
            Row(
                modifier = Modifier
                    .background(pal.quoteBg, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.width(3.dp).height(22.dp).background(pal.quoteBar))
                Spacer(Modifier.size(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(quoteAuthor, color = pal.quoteBar, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Text(quoteText, color = pal.quoteText.copy(alpha = 0.7f), fontSize = 9.sp)
                }
            }
            Spacer(Modifier.size(3.dp))
            Text(body, color = pal.leftText, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(horizontal = 6.dp))
            Text(time, color = pal.leftDate, fontSize = 8.sp, modifier = Modifier.align(Alignment.End).padding(end = 6.dp, top = 1.dp))
        }
    }
}

@Composable
private fun OutgoingQuotedBubble(pal: ChatPalette, quoteAuthor: String, quoteText: String, body: String, time: String, tickKind: TickKind) {
    Box(
        modifier = Modifier
            .widthIn(min = 90.dp, max = 250.dp)
            .background(pal.rightBg, RoundedCornerShape(14.dp))
            .padding(4.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .background(pal.quoteBg, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.width(3.dp).height(22.dp).background(pal.quoteBar))
                Spacer(Modifier.size(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(quoteAuthor, color = pal.quoteBar, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Text(quoteText, color = pal.quoteText.copy(alpha = 0.75f), fontSize = 9.sp)
                }
            }
            Spacer(Modifier.size(3.dp))
            Text(body, color = pal.rightText, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(horizontal = 6.dp))
            Row(modifier = Modifier.align(Alignment.End).padding(end = 6.dp, top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(time, color = pal.rightDate, fontSize = 8.sp)
                Spacer(Modifier.size(4.dp))
                TickGlyph(tickKind, defaultColor = pal.tickUnseen, accent = pal.tickSeen)
            }
        }
    }
}

@Composable
private fun TypingBubble(pal: ChatPalette) {
    Box(
        modifier = Modifier
            .background(pal.leftBg, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { Box(modifier = Modifier.size(5.dp).background(pal.subtle, CircleShape)) }
        }
    }
}

@Composable
private fun HeaderRow(pal: ChatPalette, bg: Color, icons: Color, title: Color, subtitle: Color, subtitleText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) { BackArrow(icons) }
        Box(modifier = Modifier.size(28.dp).background(pal.accent, CircleShape), contentAlignment = Alignment.Center) {
            Text("M", color = pal.onAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Mom", color = title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitleText, color = subtitle, fontSize = 9.sp, fontWeight = FontWeight.Medium)
        }
        VideoIcon(icons); Spacer(Modifier.size(12.dp))
        PhoneIcon(icons); Spacer(Modifier.size(12.dp))
        MenuDotsIcon(icons)
    }
}

// ── Snapshot -> Tokens (for WaHomePreview which still uses the Tokens facade) ────────────────

internal fun ThemeSnapshot.toTokens(): Tokens = Tokens(
    background = background,
    primary = primary,
    text = text,
    chatlistBg = if (chatlistBg != 0) chatlistBg else background,
    rowName = if (rowName != 0) rowName else text,
    rowPreview = if (rowPreview != 0) rowPreview else text,
    rowTimestamp = if (rowTimestamp != 0) rowTimestamp else text,
    searchBarBg = if (searchBarBg != 0) searchBarBg else background,
    searchInnerBg = if (searchInnerBg != 0) searchInnerBg else elevateInt(background, 0.06f),
    searchIcon = if (searchIcon != 0) searchIcon else text,
    searchText = if (searchText != 0) searchText else text,
    navbarBg = if (navbarBg != 0) navbarBg else background,
    navbarDivider = if (navbarDivider != 0) navbarDivider else text,
    tabActivePill = if (tabActivePill != 0) tabActivePill else primary,
    tabIcon = if (tabIcon != 0) tabIcon else text,
    tabActiveLabel = if (tabActiveLabel != 0) tabActiveLabel else text,
    tabInactiveLabel = if (tabInactiveLabel != 0) tabInactiveLabel else text,
    toolbarBg = if (homeToolbarBg != 0) homeToolbarBg else background,
    toolbarIcons = if (homeToolbarIcons != 0) homeToolbarIcons else text,
    whatsappLogo = if (whatsappLogo != 0) whatsappLogo else text,
    fabBg = if (fabBg != 0) fabBg else primary,
    // Three states, matching HomeActivityHook: FAB bg set with glyph unset forces the glyph white.
    fabIcon = if (fabIcon != 0) fabIcon else if (fabBg != 0) 0xFFFFFFFF.toInt() else text,
    miniFabBg = if (miniFabBg != 0) miniFabBg else background,
    miniFabLabel = if (miniFabLabel != 0) miniFabLabel else text,
    unreadAccent = if (unreadAccent != 0) unreadAccent else primary,
    unreadCountText = if (unreadCountText != 0) unreadCountText else text,
    // Cascade matches SystemBars.resolveStatusColor's code, not its comment: explicit, home toolbar bg, primary.
    statusBarBg = if (statusBarBg != 0) statusBarBg
        else if (homeToolbarBg != 0) homeToolbarBg
        else primary,
    systemBarsEnabled = systemBarsEnabled,
    // Same test the hook makes, in the same order: enabled, and an actual image.
    wallpaperOwnsBars = wallpaperEnabled && !wallpaperPath.isNullOrBlank(),
)

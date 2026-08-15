package com.wathemer.app.settings.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Skeleton home preview; every element reads its own [Tokens] field independently, no ambient palette smoothing. */
@Composable
fun WaHomePreview(
    tokens: Tokens,
    focus: HomeFocus = HomeFocus.None,
    modifier: Modifier = Modifier,
) {
    val rootBg = Color(tokens.background)

    Column(modifier = modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(rootBg)) {

        // ── System status bar ────────────────────────────────────────
        // Mirrors the hook's gate: feature off previews as nothing, and a wallpaper beats the toggle.
        if (tokens.systemBarsEnabled && !tokens.wallpaperOwnsBars) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Color(tokens.statusBarBg)),
            )
        }

        // ── Top toolbar: toolbarBg + whatsappLogo + toolbarIcons ─────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(tokens.toolbarBg))
                .alpha(focus.alphaFor(HomeFocus.Header))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // WhatsApp wordmark
            Text(
                "WhatsApp",
                color = Color(tokens.whatsappLogo),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            // Toolbar action icons (camera / search / overflow)
            ToolbarGlyph(IconKind.Camera,   Color(tokens.toolbarIcons))
            Spacer(Modifier.size(10.dp))
            ToolbarGlyph(IconKind.Search,   Color(tokens.toolbarIcons))
            Spacer(Modifier.size(10.dp))
            ToolbarGlyph(IconKind.Overflow, Color(tokens.toolbarIcons))
        }

        // ── Search pill ──────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(tokens.searchBarBg))    // outer
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(tokens.searchInnerBg), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchIcon(Color(tokens.searchIcon))
                Spacer(Modifier.size(8.dp))
                Text("Ask Meta AI or Search", color = Color(tokens.searchText).copy(alpha = 0.75f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                MetaSparkleIcon(Color(tokens.searchIcon))
            }
        }

        // ── Filter chips (cosmetic; uses primary + text from globals) ─
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(tokens.chatlistBg))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip("All", active = true, accent = Color(tokens.primary), bg = Color(tokens.searchInnerBg), text = Color(tokens.text))
            FilterChip("Unread", active = false, accent = Color(tokens.primary), bg = Color(tokens.searchInnerBg), text = Color(tokens.text))
            FilterChip("Favs", active = false, accent = Color(tokens.primary), bg = Color(tokens.searchInnerBg), text = Color(tokens.text))
            FilterChip("Groups", active = false, accent = Color(tokens.primary), bg = Color(tokens.searchInnerBg), text = Color(tokens.text))
        }

        // ── Chat list body + floating FABS ────────────────────────────
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color(tokens.chatlistBg))) {
            Column(modifier = Modifier.fillMaxSize().alpha(focus.alphaFor(HomeFocus.ChatList))) {
                ChatListRowReal(tokens, name = "Mom", preview = "Sure 🙂", time = "10:16", unread = 5, initial = "M")
            }

            // Main FAB: fabBg + fabIcon (the "+" glyph), with the mini-FAB above it.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 8.dp)
                    .alpha(focus.alphaFor(HomeFocus.Fab)),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Mini-FAB corners stay square: installExtendedMiniFabHook sets a plain ColorDrawable, so the real one is square.
                Row(
                    modifier = Modifier.background(Color(tokens.miniFabBg)).padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Message",
                        color = Color(tokens.miniFabLabel),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(tokens.fabBg), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    PlusGlyph(Color(tokens.fabIcon))
                }
            }
        }

        // ── Bottom-nav divider ───────────────────────────────────────
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(tokens.navbarDivider).copy(alpha = 0.30f)))

        // ── Bottom navigation ────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(focus.alphaFor(HomeFocus.TabBar))
                .background(Color(tokens.navbarBg))
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavTabReal(label = "Chats",       icon = { tint -> ChatsTabIcon(tint) },       active = true,  tokens = tokens, badge = 12, modifier = Modifier.weight(1f))
            NavTabReal(label = "Updates",     icon = { tint -> UpdatesTabIcon(tint) },     active = false, tokens = tokens, badge = 0,  modifier = Modifier.weight(1f))
            NavTabReal(label = "Communities", icon = { tint -> CommunitiesTabIcon(tint) }, active = false, tokens = tokens, badge = 0,  modifier = Modifier.weight(1f))
            NavTabReal(label = "Calls",       icon = { tint -> CallsTabIcon(tint) },       active = false, tokens = tokens, badge = 0,  modifier = Modifier.weight(1f))
        }

        // OVR_NAVBAR_BG themes WhatsApp's own tab bar, not the phone's navigation strip.
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, accent: Color, bg: Color, text: Color) {
    // toArgb(), never value.toLong().toInt(): Compose packs argb shl 32, so the low 32 bits are always zero.
    val onAccent = onColorFor(accent.toArgb())
    Box(
        modifier = Modifier
            .background(if (active) accent else bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            color = if (active) onAccent else text.copy(alpha = 0.7f),
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun NavTabReal(
    label: String,
    icon: @Composable (tint: Color) -> Unit,
    active: Boolean,
    tokens: Tokens,
    badge: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .let {
                        if (active) it.background(Color(tokens.tabActivePill), RoundedCornerShape(50))
                        else it
                    }
                    .padding(horizontal = if (active) 14.dp else 0.dp, vertical = if (active) 4.dp else 0.dp),
                contentAlignment = Alignment.Center,
            ) {
                icon(Color(tokens.tabIcon))
            }
            if (badge > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(start = 18.dp)
                        .background(Color(tokens.unreadAccent), CircleShape)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$badge", color = Color(tokens.unreadCountText), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.size(2.dp))
        Text(
            label,
            color = Color(if (active) tokens.tabActiveLabel else tokens.tabInactiveLabel),
            fontSize = 9.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ChatListRowReal(tokens: Tokens, name: String, preview: String, time: String, unread: Int, initial: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(tokens.chatlistBg))
            .padding(start = 14.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(34.dp).background(elevate(tokens.chatlistBg, 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initial, color = Color(tokens.text), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = Color(tokens.rowName), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(preview, color = Color(tokens.rowPreview).copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1)
        }
        Spacer(Modifier.size(10.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(time, color = Color(tokens.rowTimestamp), fontSize = 9.sp, fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Normal)
            if (unread > 0) {
                Box(
                    modifier = Modifier.background(Color(tokens.unreadAccent), CircleShape).padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text("$unread", color = Color(tokens.unreadCountText), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Spacer(Modifier.size(16.dp))
            }
        }
    }
}

/* ── Toolbar / icon glyphs ─────────────────────────────────────────────── */

private enum class IconKind { Camera, Search, Overflow }

@Composable
private fun ToolbarGlyph(kind: IconKind, tint: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val w = size.width; val h = size.height
        when (kind) {
            IconKind.Camera -> {
                // Simplified camera body
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.10f, h * 0.30f),
                    size = Size(w * 0.80f, h * 0.55f),
                    cornerRadius = CornerRadius(w * 0.10f),
                    style = Stroke(1.4.dp.toPx()),
                )
                drawCircle(tint, radius = w * 0.16f, center = Offset(w * 0.5f, h * 0.575f), style = Stroke(1.4.dp.toPx()))
            }
            IconKind.Search -> {
                drawCircle(tint, radius = w * 0.32f, center = Offset(w * 0.42f, h * 0.42f), style = Stroke(1.4.dp.toPx()))
                drawLine(tint, Offset(w * 0.66f, h * 0.66f), Offset(w * 0.95f, h * 0.95f), 1.6.dp.toPx())
            }
            IconKind.Overflow -> {
                drawCircle(tint, radius = 1.6.dp.toPx(), center = Offset(w * 0.5f, h * 0.22f))
                drawCircle(tint, radius = 1.6.dp.toPx(), center = Offset(w * 0.5f, h * 0.50f))
                drawCircle(tint, radius = 1.6.dp.toPx(), center = Offset(w * 0.5f, h * 0.78f))
            }
        }
    }
}

@Composable
private fun PlusGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width; val h = size.height
        val s = 2.dp.toPx()
        drawLine(tint, Offset(w * 0.5f, h * 0.20f), Offset(w * 0.5f, h * 0.80f), s)
        drawLine(tint, Offset(w * 0.20f, h * 0.5f), Offset(w * 0.80f, h * 0.5f), s)
    }
}

/* ── Tab icons ─────────────────────────────────────────────────────────── */

@Composable
private fun ChatsTabIcon(tint: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.1f, h * 0.15f),
            size = Size(w * 0.8f, h * 0.58f),
            cornerRadius = CornerRadius(w * 0.18f),
        )
        val tailPath = Path().apply {
            moveTo(w * 0.28f, h * 0.72f)
            lineTo(w * 0.18f, h * 0.9f)
            lineTo(w * 0.42f, h * 0.72f)
            close()
        }
        drawPath(tailPath, tint)
    }
}

@Composable
private fun UpdatesTabIcon(tint: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val cx = w * 0.5f; val cy = h * 0.5f
        drawCircle(tint, radius = w * 0.4f, center = Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
        drawCircle(tint, radius = w * 0.13f, center = Offset(cx, cy))
    }
}

@Composable
private fun CommunitiesTabIcon(tint: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val sq = w * 0.34f
        val r = CornerRadius(w * 0.07f)
        val gap = w * 0.04f
        drawRoundRect(tint, topLeft = Offset(w * 0.13f, h * 0.13f), size = Size(sq, sq), cornerRadius = r)
        drawRoundRect(tint, topLeft = Offset(w * 0.13f + sq + gap, h * 0.13f), size = Size(sq, sq), cornerRadius = r)
        drawRoundRect(tint, topLeft = Offset(w * 0.13f, h * 0.13f + sq + gap), size = Size(sq, sq), cornerRadius = r)
        drawRoundRect(tint, topLeft = Offset(w * 0.13f + sq + gap, h * 0.13f + sq + gap), size = Size(sq, sq), cornerRadius = r)
    }
}

@Composable
private fun CallsTabIcon(tint: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        drawLine(tint, Offset(w * 0.25f, h * 0.75f), Offset(w * 0.75f, h * 0.25f), 3.5.dp.toPx())
        drawCircle(tint, radius = 2.2.dp.toPx(), center = Offset(w * 0.25f, h * 0.75f))
        drawCircle(tint, radius = 2.2.dp.toPx(), center = Offset(w * 0.75f, h * 0.25f))
    }
}

@Composable
private fun SearchIcon(tint: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val w = size.width; val h = size.height
        drawCircle(tint, radius = w * 0.32f, center = Offset(w * 0.42f, h * 0.42f), style = Stroke(1.2.dp.toPx()))
        drawLine(tint, Offset(w * 0.66f, h * 0.66f), Offset(w * 0.95f, h * 0.95f), 1.4.dp.toPx())
    }
}

@Composable
private fun MetaSparkleIcon(tint: Color) {
    Canvas(modifier = Modifier.size(13.dp)) {
        val w = size.width; val h = size.height
        val cx = w * 0.5f; val cy = h * 0.5f
        val star = Path().apply {
            moveTo(cx, cy - h * 0.42f)
            lineTo(cx + w * 0.12f, cy - h * 0.12f)
            lineTo(cx + w * 0.42f, cy)
            lineTo(cx + w * 0.12f, cy + h * 0.12f)
            lineTo(cx, cy + h * 0.42f)
            lineTo(cx - w * 0.12f, cy + h * 0.12f)
            lineTo(cx - w * 0.42f, cy)
            lineTo(cx - w * 0.12f, cy - h * 0.12f)
            close()
        }
        drawPath(star, tint)
    }
}

// The home screen as this WhatsApp draws it: an icon pill over a large title, the search pill, Archived, the rows,
// the Meta AI circle over the new-chat button, and the tab bar with its pill. Every element reads its own token.
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wathemer.app.R
import com.wathemer.app.settings.components.BitmapIcon

@Composable
fun WaHomePreview(
    tokens: Tokens,
    focus: HomeFocus = HomeFocus.None,
    modifier: Modifier = Modifier,
) {
    val rootBg = Color(tokens.background)
    val icons = Color(tokens.toolbarIcons)

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

        // ── Header: the icon pill, then the title ───────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(tokens.toolbarBg))
                .alpha(focus.alphaFor(HomeFocus.Header))
                .padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 4.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Row(
                    modifier = Modifier
                        .background(elevate(tokens.toolbarBg, 0.10f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RupeeGlyph(icons)
                    CameraIcon(icons)
                    MenuDotsIcon(icons)
                }
            }
            Text(
                "Chats",
                color = Color(tokens.whatsappLogo),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // ── Search pill ──────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(tokens.searchBarBg))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(tokens.searchInnerBg), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchIcon(Color(tokens.searchIcon))
                Spacer(Modifier.size(10.dp))
                Text("Ask Meta AI or Search", color = Color(tokens.searchText).copy(alpha = 0.75f), fontSize = 11.sp)
            }
        }

        // ── Chat list body + the two floating buttons ─────────────────
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color(tokens.chatlistBg))) {
            Column(modifier = Modifier.fillMaxSize().alpha(focus.alphaFor(HomeFocus.ChatList))) {
                ArchivedRow(tokens)
                ChatListRow(tokens, name = "Mom", preview = "Sure, see you at 8", time = "10:16", unread = 2, initial = "M", ticks = 0)
                ChatListRow(tokens, name = "Dad", preview = "Photo", time = "Yesterday", unread = 0, initial = "D", ticks = 2)
                ChatListRow(tokens, name = "Work", preview = "Priya: On my way", time = "Yesterday", unread = 0, initial = "W", ticks = 0)
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 8.dp)
                    .alpha(focus.alphaFor(HomeFocus.Fab)),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // The Meta AI button: a circle with its ring glyph.
                Box(
                    modifier = Modifier.size(28.dp).background(Color(tokens.miniFabBg), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    MetaRingGlyph(Color(tokens.miniFabLabel))
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(tokens.fabBg), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    NewChatGlyph(Color(tokens.fabIcon))
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
            NavTab(label = "Chats",       icon = { tint -> ChatsTabIcon(tint) },       active = true,  tokens = tokens, badge = 1, modifier = Modifier.weight(1f))
            NavTab(label = "Updates",     icon = { tint -> UpdatesTabIcon(tint) },     active = false, tokens = tokens, badge = 0, modifier = Modifier.weight(1f))
            NavTab(label = "Communities", icon = { tint -> CommunitiesTabIcon(tint) }, active = false, tokens = tokens, badge = 0, modifier = Modifier.weight(1f))
            NavTab(label = "Calls",       icon = { tint -> CallsTabIcon(tint) },       active = false, tokens = tokens, badge = 0, modifier = Modifier.weight(1f))
        }

        // OVR_NAVBAR_BG themes WhatsApp's own tab bar, not the phone's navigation strip.
    }
}

@Composable
private fun ArchivedRow(tokens: Tokens) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            ArchiveGlyph(Color(tokens.rowName).copy(alpha = 0.7f))
        }
        Spacer(Modifier.size(12.dp))
        Text("Archived", color = Color(tokens.rowName).copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NavTab(
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
private fun ChatListRow(tokens: Tokens, name: String, preview: String, time: String, unread: Int, initial: String, ticks: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(tokens.chatlistBg))
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(30.dp).background(elevate(tokens.chatlistBg, 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initial, color = Color(tokens.text), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = Color(tokens.rowName), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ticks > 0) {
                    DoubleTick(Color(tokens.rowPreview).copy(alpha = 0.7f))
                    Spacer(Modifier.size(4.dp))
                }
                Text(preview, color = Color(tokens.rowPreview).copy(alpha = 0.85f), fontSize = 10.5.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.size(10.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(time, color = Color(tokens.rowTimestamp).copy(alpha = if (unread > 0) 1f else 0.75f), fontSize = 9.sp)
            if (unread > 0) {
                Box(
                    modifier = Modifier.background(Color(tokens.unreadAccent), CircleShape).padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text("$unread", color = Color(tokens.unreadCountText), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Spacer(Modifier.size(14.dp))
            }
        }
    }
}

// ── Glyphs ──────────────────────────────────────────────────────────────

@Composable
private fun RupeeGlyph(tint: Color) = BitmapIcon(R.drawable.ic_wa_rupee, tint, 13.dp)

@Composable
private fun SearchIcon(tint: Color) = BitmapIcon(R.drawable.ic_ui_search, tint, 14.dp)

@Composable
private fun ArchiveGlyph(tint: Color) = BitmapIcon(R.drawable.ic_wa_archive, tint, 15.dp)

/** The Meta AI mark: a ring drawn as six short arcs. */
@Composable
private fun MetaRingGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(15.dp)) {
        val s = 2.2.dp.toPx()
        val inset = s
        for (i in 0 until 6) {
            drawArc(
                color = tint,
                startAngle = i * 60f + 8f,
                sweepAngle = 40f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - 2 * inset, size.height - 2 * inset),
                style = Stroke(s),
            )
        }
    }
}

/** The new-chat button's icon: a bubble with a plus. */
@Composable
private fun NewChatGlyph(tint: Color) = BitmapIcon(R.drawable.ic_wa_newchat, tint, 17.dp)

// ── Tab icons ───────────────────────────────────────────────────────────

@Composable
private fun ChatsTabIcon(tint: Color) = BitmapIcon(R.drawable.ic_wa_tab_chats_fill, tint, 18.dp)

@Composable
private fun UpdatesTabIcon(tint: Color) = BitmapIcon(R.drawable.ic_wa_tab_updates, tint, 18.dp)

@Composable
private fun CommunitiesTabIcon(tint: Color) = BitmapIcon(R.drawable.ic_wa_tab_communities, tint, 18.dp)

@Composable
private fun CallsTabIcon(tint: Color) = BitmapIcon(R.drawable.ic_wa_phone, tint, 18.dp)

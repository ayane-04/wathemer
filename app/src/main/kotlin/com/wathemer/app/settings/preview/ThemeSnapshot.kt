// Live-update state: picker writes go to the snapshot first (recomposes readers), then to prefs.
// Reading SharedPreferences straight from a composable does not update live; the snapshot does.
package com.wathemer.app.settings.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.wathemer.app.settings.components.OverrideRow
import com.wathemer.app.settings.picker.ColorPickerSheet
import com.wathemer.app.settings.prefs.Prefs

/** One shared snapshot for every settings screen; a pick anywhere updates every preview reading that field. */
val LocalThemeSnapshot = compositionLocalOf<MutableState<ThemeSnapshot>> {
    error("LocalThemeSnapshot not provided; wrap your content in ThemeSnapshotHost")
}

/** Mount this once at the app root so the snapshot is shared by every screen. */
@Composable
fun ThemeSnapshotHost(prefs: Prefs, content: @Composable () -> Unit) {
    val snapshot = remember { mutableStateOf(snapshotFromPrefs(prefs)) }
    CompositionLocalProvider(LocalThemeSnapshot provides snapshot) { content() }
}

/** All themable tokens in one immutable record; 0 = unset, the caller derives a fallback from globals. */
@Immutable
data class ThemeSnapshot(
    // ── Globals ──
    val primary: Int,
    val background: Int,
    val text: Int,

    // ── Bubble (chat) ──
    val bubbleLeftBg: Int,
    val bubbleRightBg: Int,
    val bubbleLeftText: Int,
    val bubbleRightText: Int,
    val bubbleLeftDate: Int,
    val bubbleRightDate: Int,
    val bubbleStyleIncoming: Int,   // 0 = stock shape; else 1-based index into BubbleStyles.ALL
    val bubbleStyleOutgoing: Int,

    // ── Compose / input bar ──
    val composeBarBg: Int,
    val composeEntryText: Int,
    val composeSendBg: Int,
    val composeSendIcon: Int,
    val composeIconTint: Int,

    // ── Chat header (Conversation activity) ──
    val chatToolbarBg: Int,
    val chatToolbarIcons: Int,
    val chatHeaderTitle: Int,
    val chatHeaderSubtitle: Int,

    // ── Home toolbar (shared OVR_TOOLBAR_*) ──
    val homeToolbarBg: Int,
    val homeToolbarIcons: Int,
    val whatsappLogo: Int,

    // ── Home chat list ──
    val chatlistBg: Int,
    val rowName: Int,
    val rowPreview: Int,
    val rowTimestamp: Int,

    // ── Home search bar ──
    val searchBarBg: Int,
    val searchInnerBg: Int,
    val searchIcon: Int,
    val searchText: Int,

    // ── Home bottom nav ──
    val navbarBg: Int,
    val navbarDivider: Int,
    val tabActivePill: Int,
    val tabIcon: Int,
    val tabActiveLabel: Int,
    val tabInactiveLabel: Int,

    // ── Home FAB ──
    val fabBg: Int,
    val fabIcon: Int,
    val miniFabBg: Int,
    val miniFabLabel: Int,

    // ── Unread (cross-surface) ──
    val unreadAccent: Int,
    val unreadCountText: Int,

    // ── Selection / action mode (cross-surface) ──
    val actionModeBg: Int,
    val actionModeIcons: Int,
    val actionModeTitle: Int,
    val actionModeCloseRipple: Int,

    // ── Quote/Reply ──
    val quoteBarColor: Int,
    val quoteBgColor: Int,
    val quoteTextColor: Int,

    // ── Misc ──
    val tickSeenColor: Int,
    val tickUnseenColor: Int,
    val forwardedLabelColor: Int,
    val mediaCaptionColor: Int,
    val linkColor: Int,

    /** Whether the background token is set; [background] cannot say, it resolves to DEFAULT_BACKGROUND when unset. */
    val backgroundTokenSet: Boolean,

    // ── Wallpaper ──
    val wallpaperEnabled: Boolean,
    val wallpaperPath: String?,
    /** Bumped on wallpaper replacement; the picker reuses the filename, so an unchanged path never recomposes. */
    val wallpaperStamp: Long = 0L,
    val wallpaperDim: Int,
    val wallpaperBlur: Int,

    // ── System bars (raw overrides, 0 = cascade; the cascade itself lives in toTokens) ──
    val systemBarsEnabled: Boolean,
    val statusBarBg: Int,
)

/** Read every token from prefs once. Cheap: ~50 int reads from a memory-resident SharedPreferences. */
fun snapshotFromPrefs(prefs: Prefs): ThemeSnapshot = ThemeSnapshot(
    primary = prefs.primary,
    background = prefs.background,
    text = prefs.text,
    backgroundTokenSet = prefs.hasBackgroundToken(),

    bubbleLeftBg = prefs.getOverride(Prefs.BUBBLE_LEFT_BG),
    bubbleRightBg = prefs.getOverride(Prefs.BUBBLE_RIGHT_BG),
    bubbleLeftText = prefs.getOverride(Prefs.BUBBLE_LEFT_TEXT),
    bubbleRightText = prefs.getOverride(Prefs.BUBBLE_RIGHT_TEXT),
    bubbleLeftDate = prefs.getOverride(Prefs.BUBBLE_LEFT_DATE),
    bubbleRightDate = prefs.getOverride(Prefs.BUBBLE_RIGHT_DATE),
    bubbleStyleIncoming = prefs.getOverride(Prefs.BUBBLE_STYLE_INCOMING),
    bubbleStyleOutgoing = prefs.getOverride(Prefs.BUBBLE_STYLE_OUTGOING),

    composeBarBg = prefs.getOverride(Prefs.COMPOSE_BAR_BG),
    composeEntryText = prefs.getOverride(Prefs.COMPOSE_ENTRY_TEXT),
    composeSendBg = prefs.getOverride(Prefs.COMPOSE_SEND_BG),
    composeSendIcon = prefs.getOverride(Prefs.COMPOSE_SEND_ICON),
    composeIconTint = prefs.getOverride(Prefs.COMPOSE_ICON_TINT),

    chatToolbarBg = prefs.getOverride(Prefs.CHAT_TOOLBAR_BG),
    chatToolbarIcons = prefs.getOverride(Prefs.CHAT_TOOLBAR_ICONS),
    chatHeaderTitle = prefs.getOverride(Prefs.CHAT_HEADER_TITLE),
    chatHeaderSubtitle = prefs.getOverride(Prefs.CHAT_HEADER_SUBTITLE),

    homeToolbarBg = prefs.getOverride(Prefs.OVR_TOOLBAR_BG),
    homeToolbarIcons = prefs.getOverride(Prefs.OVR_TOOLBAR_ICONS),
    whatsappLogo = prefs.getOverride(Prefs.OVR_WHATSAPP_LOGO),

    chatlistBg = prefs.getOverride(Prefs.OVR_CHATLIST_BG),
    rowName = prefs.getOverride(Prefs.OVR_ROW_NAME),
    rowPreview = prefs.getOverride(Prefs.OVR_ROW_PREVIEW),
    rowTimestamp = prefs.getOverride(Prefs.OVR_ROW_TIMESTAMP),

    searchBarBg = prefs.getOverride(Prefs.OVR_SEARCH_BAR_BG),
    searchInnerBg = prefs.getOverride(Prefs.OVR_SEARCH_INNER_BG),
    searchIcon = prefs.getOverride(Prefs.OVR_SEARCH_ICON),
    searchText = prefs.getOverride(Prefs.OVR_SEARCH_TEXT),

    navbarBg = prefs.getOverride(Prefs.OVR_NAVBAR_BG),
    navbarDivider = prefs.getOverride(Prefs.OVR_NAVBAR_DIVIDER),
    tabActivePill = prefs.getOverride(Prefs.OVR_TAB_ACTIVE_PILL),
    tabIcon = prefs.getOverride(Prefs.OVR_TAB_ICON),
    tabActiveLabel = prefs.getOverride(Prefs.OVR_TAB_ACTIVE_LABEL),
    tabInactiveLabel = prefs.getOverride(Prefs.OVR_TAB_INACTIVE_LABEL),

    fabBg = prefs.getOverride(Prefs.OVR_FAB_BG),
    fabIcon = prefs.getOverride(Prefs.OVR_FAB_ICON),
    miniFabBg = prefs.getOverride(Prefs.OVR_MINI_FAB_BG),
    miniFabLabel = prefs.getOverride(Prefs.OVR_MINI_FAB_LABEL),

    unreadAccent = prefs.unreadAccent,
    unreadCountText = prefs.unreadCountText,

    actionModeBg = prefs.getOverride(Prefs.OVR_ACTION_MODE_BG),
    actionModeIcons = prefs.getOverride(Prefs.OVR_ACTION_MODE_ICONS),
    actionModeTitle = prefs.getOverride(Prefs.OVR_ACTION_MODE_TITLE),
    actionModeCloseRipple = prefs.getOverride(Prefs.OVR_ACTION_MODE_CLOSE_RIPPLE),

    quoteBarColor = prefs.getOverride(Prefs.QUOTE_BAR_COLOR),
    quoteBgColor = prefs.getOverride(Prefs.QUOTE_BG_COLOR),
    quoteTextColor = prefs.getOverride(Prefs.QUOTE_TEXT_COLOR),

    tickSeenColor = prefs.getOverride(Prefs.TICK_SEEN_COLOR),
    tickUnseenColor = prefs.getOverride(Prefs.TICK_UNSEEN_COLOR),
    forwardedLabelColor = prefs.getOverride(Prefs.FORWARDED_LABEL_COLOR),
    mediaCaptionColor = prefs.getOverride(Prefs.MEDIA_CAPTION_COLOR),
    linkColor = prefs.getOverride(Prefs.LINK_COLOR),

    systemBarsEnabled = prefs.systemBarsEnabled,
    statusBarBg = prefs.getOverride(Prefs.OVR_STATUS_BAR_BG),
    wallpaperEnabled = prefs.wallpaperEnabled,
    wallpaperPath = prefs.wallpaperPath,
    wallpaperDim = prefs.wallpaperDim,
    wallpaperBlur = prefs.wallpaperBlur,
)

// ── Wallpaper updaters ───────────────────────────────────────────────────────
// Wallpaper prefs are non-Int so they don't fit SnapshotOverrideRow; rows call these directly.
fun MutableState<ThemeSnapshot>.updateWallpaperEnabled(prefs: Prefs, v: Boolean) {
    value = value.copy(wallpaperEnabled = v)
    prefs.wallpaperEnabled = v
}
fun MutableState<ThemeSnapshot>.updateWallpaperPath(prefs: Prefs, v: String?) {
    // Bump the stamp every time; re-picking writes an equal path, which alone would not recompose.
    value = value.copy(wallpaperPath = v, wallpaperStamp = value.wallpaperStamp + 1)
    prefs.wallpaperPath = v
}
fun MutableState<ThemeSnapshot>.updateWallpaperDim(prefs: Prefs, v: Int) {
    value = value.copy(wallpaperDim = v.coerceIn(0, 100))
    prefs.wallpaperDim = v
}
// The 0..150 clamp must match Prefs.wallpaperBlur and WallpaperImage or the slider lies about its state.
fun MutableState<ThemeSnapshot>.updateWallpaperBlur(prefs: Prefs, v: Int) {
    value = value.copy(wallpaperBlur = v.coerceIn(0, 150))
    prefs.wallpaperBlur = v
}

// ── Bubble shapes: per-side 1-based index into BubbleStyles.ALL (0 = off) ──────
fun MutableState<ThemeSnapshot>.updateBubbleStyleIncoming(prefs: Prefs, v: Int) {
    value = value.copy(bubbleStyleIncoming = v)
    prefs.setOverride(Prefs.BUBBLE_STYLE_INCOMING, v)
}
fun MutableState<ThemeSnapshot>.updateBubbleStyleOutgoing(prefs: Prefs, v: Int) {
    value = value.copy(bubbleStyleOutgoing = v)
    prefs.setOverride(Prefs.BUBBLE_STYLE_OUTGOING, v)
}

// ── Snapshot-aware row helper ───────────────────────────────────────────────
// Every update writes both the observable snapshot and prefs.

/** Pulls the snapshot from [LocalThemeSnapshot] and hands it to the explicit overload below. [onOpen] fires on any touch of the row. */
@Composable
fun SnapshotOverrideRow(
    name: String,
    prefs: Prefs,
    prefKey: String,
    fallback: Int,
    divider: Boolean = true,
    onOpen: (() -> Unit)? = null,
    getter: ThemeSnapshot.() -> Int,
    copier: ThemeSnapshot.(Int) -> ThemeSnapshot,
) {
    SnapshotOverrideRow(
        name = name,
        snapshot = LocalThemeSnapshot.current,
        prefs = prefs,
        prefKey = prefKey,
        fallback = fallback,
        divider = divider,
        onOpen = onOpen,
        getter = getter,
        copier = copier,
    )
}

@Composable
fun SnapshotOverrideRow(
    name: String,
    snapshot: MutableState<ThemeSnapshot>,
    prefs: Prefs,
    prefKey: String,
    fallback: Int,
    divider: Boolean = true,
    onOpen: (() -> Unit)? = null,
    getter: ThemeSnapshot.() -> Int,
    copier: ThemeSnapshot.(Int) -> ThemeSnapshot,
) {
    val current = snapshot.value.getter()
    var showPicker by remember { mutableStateOf(false) }
    val recents = remember(snapshot.value) { prefs.recents }
    OverrideRow(
        name = name,
        overrideValue = current,
        globalValue = fallback,
        divider = divider,
        onPickCustom = { onOpen?.invoke(); showPicker = true },
        onResetToGlobal = {
            onOpen?.invoke()
            snapshot.value = snapshot.value.copier(0)
            prefs.setOverride(prefKey, 0)
        },
    )
    if (showPicker) {
        ColorPickerSheet(
            title = name,
            initialColor = if (current != 0) current else fallback,
            recents = recents,
            onDismiss = { showPicker = false },
            onApply = { picked ->
                snapshot.value = snapshot.value.copier(picked)
                prefs.setOverride(prefKey, picked)
                prefs.pushRecent(picked)
                showPicker = false
            },
        )
    }
}

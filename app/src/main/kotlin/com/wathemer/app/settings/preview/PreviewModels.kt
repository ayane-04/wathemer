package com.wathemer.app.settings.preview

import androidx.compose.ui.graphics.Color

/** Which home-preview sub-element a detail page highlights; the rest dim via [alphaFor]. */
enum class HomeFocus {
    None,
    Header,
    TabBar,
    ChatList,
    Fab,
}

/** 1f for the focused element and when nothing is focused; 0.30f dims the rest. */
fun HomeFocus.alphaFor(element: HomeFocus): Float =
    if (this == HomeFocus.None || this == element) 1f else 0.30f

/** Perceived luminance, for picking dark/light text on a coloured background. */
internal fun lumaArgb(argb: Int): Float {
    val r = android.graphics.Color.red(argb) / 255f
    val g = android.graphics.Color.green(argb) / 255f
    val b = android.graphics.Color.blue(argb) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

/** Return Black if the color is light, White if dark, for legible text/icons on accent. */
internal fun onColorFor(argb: Int): Color =
    if (lumaArgb(argb) > 0.55f) Color(0xFF101010) else Color.White

/** A slightly lighter (or darker) variant of a base color for surface elevation. */
internal fun elevate(argb: Int, amount: Float = 0.06f): Color {
    val r = android.graphics.Color.red(argb)
    val g = android.graphics.Color.green(argb)
    val b = android.graphics.Color.blue(argb)
    val isDark = lumaArgb(argb) < 0.5f
    val delta = if (isDark) (255 * amount).toInt() else -(255 * amount).toInt()
    return Color(
        red = ((r + delta).coerceIn(0, 255)) / 255f,
        green = ((g + delta).coerceIn(0, 255)) / 255f,
        blue = ((b + delta).coerceIn(0, 255)) / 255f,
        alpha = 1f,
    )
}

/** Same as [elevate] but returns the ARGB int directly. */
internal fun elevateInt(argb: Int, amount: Float = 0.06f): Int =
    elevate(argb, amount).let {
        android.graphics.Color.argb(
            (it.alpha * 255).toInt(),
            (it.red * 255).toInt(),
            (it.green * 255).toInt(),
            (it.blue * 255).toInt(),
        )
    }

/** Effective preview colours: each field is the user's override when non-zero, else the global token. */
data class Tokens(
    // Globals
    val background: Int,
    val primary: Int,
    val text: Int,
    // Chatlist
    val chatlistBg: Int,
    val rowName: Int,
    val rowPreview: Int,
    val rowTimestamp: Int,
    // Search bar
    val searchBarBg: Int,
    val searchInnerBg: Int,
    val searchIcon: Int,
    val searchText: Int,
    // Bottom nav
    val navbarBg: Int,
    val navbarDivider: Int,
    val tabActivePill: Int,
    val tabIcon: Int,
    val tabActiveLabel: Int,
    val tabInactiveLabel: Int,
    // Toolbar / header
    val toolbarBg: Int,
    val toolbarIcons: Int,
    val whatsappLogo: Int,
    // FAB
    val fabBg: Int,
    val fabIcon: Int,
    val miniFabBg: Int,
    val miniFabLabel: Int,
    // Unread badges
    val unreadAccent: Int,
    val unreadCountText: Int,
    // Status bar. Cascade mirrors SystemBars.resolveStatusColor: status, explicit, home toolbar bg, primary.
    val statusBarBg: Int,
    /** False -> the strip is not drawn at all, matching a hook that installs nothing. */
    val systemBarsEnabled: Boolean,
    /** True when a wallpaper is set; SystemBars.shouldTheme then draws no strip whatever the toggle says. */
    val wallpaperOwnsBars: Boolean,
)


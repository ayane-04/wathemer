package com.wathemer.app.settings.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList

/** Every settings screen. Keep this exhaustive: MainActivity's when over it flags any screen missing a destination. */
enum class Screen {
    // Root
    CategoryList,

    // Global colors: root category list + 4 sub-screens
    GlobalColors,
    GlobalColorsTokens,         // Accent + Background + Text
    GlobalColorsUnread,         // Unread accent + Unread count text
    GlobalColorsToolbar,        // Selection-mode (action-mode) bar: bg + icons + count text + close ripple
    StatusBar,                  // system status-bar theming only

    // Backdrop tree: wallpaper and glass share it because glass refracts the wallpaper and needs one to draw.
    Backdrop,
    Wallpaper,                  // custom wallpaper: toggle / pick / dim / blur
    LiquidGlass,                // the glass engine: master toggle + tuning
    ChatWallpapers,             // one chat, its own image, dim and blur; entries arrive from WhatsApp's chat menu

    // Homescreen tree
    Homescreen,
    HomescreenChatList,
    HomescreenChatListRows,     // sub-category: chat row bg + name + preview + timestamp
    HomescreenChatListSearch,   // sub-category: 4 search-bar overrides
    HomescreenTabBar,
    HomescreenTabBarBar,        // sub-category: bar bg + divider
    HomescreenTabBarItems,      // sub-category: 4 per-tab-item overrides
    HomescreenHeader,
    HomescreenFab,
    HomescreenFabMain,          // sub-category: FAB bg + icon
    HomescreenFabMiniFab,       // sub-category: mini-fab bg + label

    // Chat Screen tree
    Chat,
    ChatBubbles,
    ChatBubblesCustom,          // combined per-side bubble-shape picker (tap-to-enter)
    ChatInputBar,
    ChatHeaderToolbar,
    ChatQuoteReplies,
    ChatMisc,

    // Extras tree
    Extras,

    // Saved themes: export, import, and the library of slots
    Themes,

    // Version check and the download it offers
    Updates,
}

/** In-memory nav stack on a SnapshotStateList. Must stay behind [rememberNavController]'s saver or rotation drops the user back to the root. */
class NavController(initial: List<Screen> = listOf(Screen.CategoryList)) {
    // Never empty (current reads last()): a garbage restore falls back to the root instead of throwing.
    private val _stack: SnapshotStateList<Screen> =
        mutableStateListOf(*initial.ifEmpty { listOf(Screen.CategoryList) }.toTypedArray())

    val current: Screen get() = _stack.last()

    fun canPop(): Boolean = _stack.size > 1

    fun push(screen: Screen) {
        _stack.add(screen)
    }

    /** True when a screen came off the stack and back is consumed; false at the root. */
    fun pop(): Boolean {
        if (!canPop()) return false
        _stack.removeAt(_stack.lastIndex)
        return true
    }

    /** Replaces the whole stack: a hand-off from WhatsApp must not land on top of wherever the user last was. */
    fun replaceAll(screens: List<Screen>) {
        val next = screens.ifEmpty { listOf(Screen.CategoryList) }
        _stack.clear()
        _stack.addAll(next)
    }

    /** Snapshot for [NavSaver]. A copy, so the saver cannot hand out the live list. */
    internal fun snapshot(): List<Screen> = _stack.toList()
}

/** Saves screen names, never ordinals: enum edits shift ordinals and a stale bundle would restore somewhere else. Unknown names are dropped. */
private val NavSaver: Saver<NavController, List<String>> = Saver(
    save = { it.snapshot().map(Screen::name) },
    restore = { names ->
        NavController(names.mapNotNull { n -> Screen.entries.firstOrNull { it.name == n } })
    },
)

@Composable
fun rememberNavController(): NavController = rememberSaveable(saver = NavSaver) { NavController() }

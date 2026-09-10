package com.wathemer.app.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wathemer.app.settings.components.CategoryRow
import com.wathemer.app.settings.components.NavTopBar
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.components.StubNote
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.nav.Screen
import com.wathemer.app.settings.prefs.ChatWallpaperLibrary
import com.wathemer.app.settings.prefs.Prefs

/** One category on purpose: glass hard-depends on the wallpaper, without one GlassHook draws and samples nothing.
 *  System bars stay under Global colours deliberately; they are exclusive with the wallpaper, not dependent on it. */
@Composable
fun BackdropScreen(nav: NavController, prefs: Prefs) {
    val hasImage = !prefs.wallpaperPath.isNullOrBlank()
    val wallpaperOn = prefs.wallpaperEnabled && hasImage
    val glassOn = prefs.glassEnabled
    val chatCount = ChatWallpaperLibrary.list(LocalContext.current, prefs).size

    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(
                title = "Wallpaper & Glass",
                // State summary, not a repeat of the title.
                subtitle = when {
                    wallpaperOn && glassOn -> "Wallpaper + glass on"
                    wallpaperOn -> "Wallpaper on"
                    hasImage -> "Off"
                    else -> "No wallpaper set"
                },
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
                    label = "Custom wallpaper",
                    description = "A background image behind the chat. Bubbles, header and input " +
                        "bar become translucent so it shows through.",
                    trailingHint = if (wallpaperOn) "ON" else if (hasImage) "OFF" else "NOT SET",
                    onClick = { nav.push(Screen.Wallpaper) },
                )
                CategoryRow(
                    label = "Liquid Glass",
                    description = "Frosted glass across WhatsApp, refracting your wallpaper.",
                    // Show the dependency in the row itself, before the user commits to a tap.
                    trailingHint = if (glassOn) "ON" else if (wallpaperOn) "OFF" else "NEEDS WALLPAPER",
                    onClick = { nav.push(Screen.LiquidGlass) },
                )
                CategoryRow(
                    label = "Chat wallpapers",
                    description = "One chat, its own picture. Set it from the chat's menu in WhatsApp.",
                    trailingHint = when {
                        !wallpaperOn -> "NEEDS WALLPAPER"
                        chatCount == 0 -> "NONE SET"
                        else -> "$chatCount SET"
                    },
                    onClick = { nav.push(Screen.ChatWallpapers) },
                )
                Spacer(Modifier.size(8.dp))
                StubNote(
                    if (wallpaperOn) {
                        "Glass refracts the wallpaper, so the two are tuned together."
                    } else {
                        "Set a wallpaper first. Glass has nothing to show without one."
                    }
                )
                // Warning only: tokens still save, glass-owned surfaces ignore them.
                StubNote(
                    "Not recommended: other theming while Liquid Glass is active. " +
                        "Liquid Glass does not support theming, so colours will not show on the surfaces it owns."
                )
            }
        }
    }
}

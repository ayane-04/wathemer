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
import androidx.compose.ui.unit.dp
import com.wathemer.app.settings.components.CategoryRow
import com.wathemer.app.settings.components.NavTopBar
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.components.StubNote
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.nav.Screen
import com.wathemer.app.settings.prefs.Prefs

/* ── Homescreen ─────────────────────────────────────────── */

@Composable
fun HomescreenScreen(nav: NavController, prefs: Prefs) {
    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(title = "Homescreen", subtitle = "4 areas", onBack = { nav.pop() })
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CategoryRow(
                    label = "Chat list",
                    description = "Rows and the search bar.",
                    onClick = { nav.push(Screen.HomescreenChatList) },
                )
                CategoryRow(
                    label = "Bottom navigation",
                    description = "The bottom tab bar.",
                    onClick = { nav.push(Screen.HomescreenTabBar) },
                )
                CategoryRow(
                    label = "Header",
                    description = "The WhatsApp wordmark.",
                    onClick = { nav.push(Screen.HomescreenHeader) },
                )
                CategoryRow(
                    label = "FAB",
                    description = "The + button and the Meta AI pill.",
                    onClick = { nav.push(Screen.HomescreenFab) },
                )
                Spacer(Modifier.size(8.dp))
                StubNote("Rows follow your global colours until you change them. ↺ puts one back.")
            }
        }
    }
}

// Re-add Updates and Calls only when real controls exist behind them.

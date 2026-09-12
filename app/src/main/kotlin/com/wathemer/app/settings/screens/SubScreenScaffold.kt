// The shape the token pages share: a bar, the preview pinned above, the rows scrolling below.
package com.wathemer.app.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wathemer.app.settings.components.NavTopBar
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.components.PreviewPanel
import com.wathemer.app.settings.nav.NavController

@Composable
internal fun SubScreenScaffold(
    nav: NavController,
    title: String,
    preview: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(title = title, onBack = { nav.pop() }, trailing = trailing)
            PreviewPanel(modifier = Modifier.weight(0.4f).fillMaxWidth()) { preview() }
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
            ) {
                content()
            }
        }
    }
}

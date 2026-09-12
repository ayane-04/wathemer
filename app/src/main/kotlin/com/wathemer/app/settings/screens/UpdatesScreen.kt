package com.wathemer.app.settings.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.wathemer.app.BuildConfig
import com.wathemer.app.settings.components.CategoryRow
import com.wathemer.app.settings.components.NavTopBar
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.components.SectionHeader
import com.wathemer.app.settings.components.NoteText
import com.wathemer.app.settings.components.ToggleItem
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.prefs.Prefs
import com.wathemer.app.update.Release
import com.wathemer.app.update.Updates
import com.wathemer.app.update.isNewerVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The cached release, so the screen opens with an answer rather than a spinner. */
private fun cachedRelease(prefs: Prefs): Release? =
    prefs.updateVersion.takeIf { it.isNotBlank() }?.let {
        Release(it, prefs.updateNotes, prefs.updateUrl, prefs.updateAsset, prefs.updateSize.toLong())
    }

@Composable
fun UpdatesScreen(nav: NavController, prefs: Prefs, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var latest by remember { mutableStateOf(cachedRelease(prefs)) }
    var checking by remember { mutableStateOf(false) }
    // Read from the process, not remembered here: a rotation rebuilds this screen while the copy is still running.
    val downloading = Updates.inFlight.value != null
    var auto by remember { mutableStateOf(prefs.updateAutoCheck) }
    // Re-read per recomposition: the file is gone once the installer takes it or the cache is cleared.
    val ready = latest?.let { Updates.downloaded(context, it) }

    val newer = latest?.let { isNewerVersion(it.version, BuildConfig.VERSION_NAME) } == true

    // Asked only when a download starts, and never blocking: the row below reports the same state.
    val askNotify = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun check(manual: Boolean) {
        if (checking) return
        checking = true
        scope.launch {
            val found = withContext(Dispatchers.IO) { Updates.latest() }
            checking = false
            if (found == null) {
                if (manual) onMessage("Could not reach GitHub.")
                return@launch
            }
            prefs.updateLastCheck = System.currentTimeMillis()
            prefs.updateVersion = found.version
            prefs.updateNotes = found.notes
            prefs.updateUrl = found.assetUrl
            prefs.updateAsset = found.assetName
            prefs.updateSize = found.size.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            latest = found
            if (manual && !isNewerVersion(found.version, BuildConfig.VERSION_NAME)) {
                onMessage("You are on the newest build.")
            }
        }
    }

    // One automatic check per visit, and only when the throttle in Prefs has expired.
    LaunchedEffect(Unit) {
        if (prefs.updateAutoCheck && System.currentTimeMillis() - prefs.updateLastCheck > DAY_MS) {
            check(manual = false)
        }
    }

    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(title = "Updates", onBack = { nav.pop() })
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
            ) {
                CategoryRow(
                    label = when {
                        checking -> "Checking"
                        newer -> "Version ${latest?.version} is out"
                        else -> "Up to date"
                    },
                    description = "You have ${BuildConfig.VERSION_NAME}",
                    onClick = { check(manual = true) },
                )

                if (newer) {
                    CategoryRow(
                        label = when {
                            ready != null -> "Install ${latest?.version}"
                            downloading -> "Downloading"
                            else -> "Download ${latest?.version}"
                        },
                        onClick = {
                            val release = latest ?: return@CategoryRow
                            val have = Updates.downloaded(context, release)
                            if (have != null) {
                                context.startActivity(Updates.installIntent(context, have))
                                return@CategoryRow
                            }
                            if (downloading) return@CategoryRow
                            if (Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            // Process-scoped: the copy, the toast and the notification outlive this screen and a rotation.
                            Updates.startDownload(context, release)
                        },
                    )
                }

                ToggleItem(
                    title = "Check automatically",
                    checked = auto,
                    divider = false,
                    onCheckedChange = { auto = it; prefs.updateAutoCheck = it },
                )

                val notes = latest?.notes.orEmpty()
                if (newer && notes.isNotBlank()) {
                    SectionHeader("What changed")
                    NoteText(notes.take(NOTES_LIMIT))
                }

                SectionHeader("Telegram")
                CategoryRow(
                    label = "Updates channel",
                    description = "@wathemer",
                    onClick = { openLink(context, "https://t.me/wathemer", onMessage) },
                )
                CategoryRow(
                    label = "Chat group",
                    description = "@wathemer_chat",
                    divider = false,
                    onClick = { openLink(context, "https://t.me/wathemer_chat", onMessage) },
                )
            }
        }
    }
}

/** An https t.me link, so it opens in Telegram when it is installed and in a browser when it is not. */
private fun openLink(context: Context, url: String, onMessage: (String) -> Unit) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { onMessage("Nothing on this device opens links.") }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Release notes can run to pages; the rest is on the releases page. */
private const val NOTES_LIMIT = 1500

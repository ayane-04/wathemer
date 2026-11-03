// Applying a saved theme, shared by Saved themes and the theme store so the two can never say different things.
package com.wathemer.app.settings.screens

import android.content.Context
import androidx.compose.runtime.MutableState
import com.wathemer.app.settings.prefs.Prefs
import com.wathemer.app.settings.prefs.ThemeDoc
import com.wathemer.app.settings.prefs.ThemeLibrary
import com.wathemer.app.settings.prefs.WallpaperAsset
import com.wathemer.app.settings.preview.ThemeSnapshot
import com.wathemer.app.settings.preview.snapshotFromPrefs
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One line to show, and the notes worth a dialog. */
class ApplyReport(val message: String, val notes: List<String>)

/** Applies [file] off the main thread and settles the preview and the undo copy; the caller shows the report. */
suspend fun applyThemeFile(
    context: Context,
    prefs: Prefs,
    snapshot: MutableState<ThemeSnapshot>,
    file: File,
    keepUndo: Boolean,
): ApplyReport = when (val outcome = withContext(Dispatchers.IO) { ThemeLibrary.apply(context, prefs, file, keepUndo) }) {
    is ThemeLibrary.Outcome.Applied -> {
        // Bump the stamp by hand as well: the wallpaper path can be unchanged while the bytes behind it are not.
        snapshot.value = snapshotFromPrefs(prefs)
            .copy(wallpaperStamp = snapshot.value.wallpaperStamp + 1)
        if (!keepUndo) ThemeLibrary.clearUndo(context)
        ApplyReport("${outcome.name} applied.", outcome.problems)
    }
    is ThemeLibrary.Outcome.Failed -> ApplyReport(outcome.reason, emptyList())
    ThemeLibrary.Outcome.NeedsStorageAccess -> {
        WallpaperAsset.requestAllFilesAccess(context)
        ApplyReport("Allow all-files access for its wallpaper, then apply again.", emptyList())
    }
}

/** What the apply dialog says a theme replaces. */
fun applyBody(doc: ThemeDoc): String = buildString {
    // A file from before themes carried the glass switch replaces less, and says so.
    if (doc.glass?.enabled != null) {
        append("Replaces your current look.")
    } else {
        append("Replaces your colours, bubble shapes, icons and font.")
        if (doc.glass != null) append(" Glass settings too.")
        if (doc.wallpaper?.hasImage == true) append(" Wallpaper too.")
    }
    append(" You can undo it.")
}

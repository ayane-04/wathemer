// Get and Update: fetch a theme, hold it to the catalogue's checksum, and add it to Saved themes.
package com.wathemer.app.store

import android.content.Context
import android.util.Log
import com.wathemer.app.settings.prefs.ThemeLibrary
import com.wathemer.app.settings.prefs.ThemeStoreRef
import java.io.File

object StoreInstall {

    private const val TAG = "WaThemer.Store"

    sealed interface Result {
        /** [replaced] counts the older copies of the same theme taken out after it was added. */
        data class Added(val slot: ThemeLibrary.Slot, val problems: List<String>, val replaced: Int) : Result
        data object AlreadySaved : Result
        data object NeedsNewerApp : Result
        data object NoSpace : Result
        data object Missing : Result
        data class Busy(val retryAfterSeconds: Long?) : Result
        data object Interrupted : Result
        data object Cancelled : Result
        /** The file matched the catalogue, but this app's reader would not take it; [reason] is the reader's own words. */
        data class Refused(val reason: String) : Result
        data class Failed(val reason: String) : Result
    }

    fun get(context: Context, theme: StoreTheme, http: StoreHttp, job: StoreJob, progress: (Long) -> Unit): Result {
        if (!theme.usable) return Result.NeedsNewerApp
        if ((ThemeLibrary.storeVersions(context)[theme.id] ?: 0) >= theme.version) return Result.AlreadySaved
        val part = StoreFiles.downloadPart(context, theme.fileSha256)
        val have = if (part.isFile) part.length() else 0L
        // Room for the download, the rebuilt slot and a staged font, less what has already arrived.
        if (context.cacheDir.usableSpace < 3 * theme.fileSize - have) return Result.NoSpace
        return when (val d = http.download(theme.fileUrl, theme.fileSize, theme.fileSha256, part, job, progress)) {
            is StoreHttp.Downloaded.Done -> install(context, theme, d.file)
            StoreHttp.Downloaded.Missing -> Result.Missing
            is StoreHttp.Downloaded.Busy -> Result.Busy(d.retryAfterSeconds)
            StoreHttp.Downloaded.Interrupted -> Result.Interrupted
            StoreHttp.Downloaded.Cancelled -> Result.Cancelled
            is StoreHttp.Downloaded.Failed -> Result.Failed(d.reason)
        }
    }

    private fun install(context: Context, theme: StoreTheme, file: File): Result {
        // The reference comes from the signed catalogue, never from the downloaded file.
        val imported = ThemeLibrary.importFrom(context, ThemeStoreRef(theme.id, theme.version)) { file.inputStream() }
        if (imported is ThemeLibrary.Import.Failed) return Result.Refused(imported.reason)
        file.delete()
        val added = imported as ThemeLibrary.Import.Added
        // Only after the new slot is written, so a failure in between leaves two copies rather than none.
        val replaced = ThemeLibrary.removeOlder(context, theme.id, theme.version)
        Log.i(TAG, "got ${theme.id} v${theme.version} as ${added.slot.file.name}; $replaced older copy(ies) removed")
        return Result.Added(added.slot, added.problems, replaced)
    }
}

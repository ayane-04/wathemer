// Where the wallpaper lives and the staged write that puts it there. Downloads is mandatory: WhatsApp can only read a media file across apps.
// The picker and the theme importer both come through here, so there is one road and it cannot drift.
package com.wathemer.app.settings.prefs

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object WallpaperAsset {

    private const val TAG = "WaThemer.Wallpaper"

    /** The public copy WhatsApp reads; its path is what [Prefs.wallpaperPath] holds. */
    fun activeFile(): File = File(publicDir(), "wallpaper.png")

    /** The private master copy. Survives anything except an uninstall. */
    fun masterFile(context: Context): File = File(context.filesDir, "wallpaper_master.png")

    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    fun requestAllFilesAccess(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    }

    /** Write the bytes to the public copy and mirror them to the master. [openStream] is called twice, so it must reopen. */
    fun persist(context: Context, openStream: () -> InputStream?): File? {
        return try {
            val waThemerDir = publicDir().apply { mkdirs() }
            val dest = activeFile()
            // Stage the write, do not write `dest` directly: a truncated half-copy still reads, defeating self-repair.
            val staging = File(waThemerDir, "wallpaper.png.part")
            val copied = openStream()?.use { input ->
                FileOutputStream(staging).use { out -> input.copyTo(out) }
                true
            } ?: false
            if (!copied) {
                Log.w(TAG, "persist: the source stream could not be opened")
                staging.delete()
                return null
            }
            // Swap only once the bytes are on disk; rename can fail on some storage layers, hence the fallback copy.
            dest.delete()
            if (!staging.renameTo(dest)) {
                Log.w(TAG, "persist: rename ${staging.name} -> ${dest.name} failed; copying")
                staging.inputStream().use { input ->
                    FileOutputStream(dest).use { out -> input.copyTo(out) }
                }
                staging.delete()
            }
            // Private master in filesDir: cleaners can delete the shared copy, this one lets the self-repair restore it.
            runCatching {
                openStream()?.use { input ->
                    FileOutputStream(masterFile(context)).use { out -> input.copyTo(out) }
                }
            }.onFailure {
                // Non-fatal on purpose: a failed backup must not fail the pick; logged because self-repair is now unarmed.
                Log.w(TAG, "persist: private master copy failed, self-repair is now unarmed: $it")
            }
            dest.takeIf { it.canRead() }?.also { f ->
                // Fire and forget: the async MediaStore index is what WallpaperResolver Strategy 3 falls back on.
                MediaScannerConnection.scanFile(
                    context, arrayOf(f.absolutePath), arrayOf("image/png"), null,
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "persist: failed to save the wallpaper: $t")
            null
        }
    }

    /** Restore the wallpaper or clear a dead pref. Never prune without all-files access: real files read as missing. */
    fun reconcile(context: Context, prefs: Prefs): String? {
        val path = prefs.wallpaperPath?.takeIf { it.isNotBlank() } ?: return null
        if (File(path).canRead()) return path
        val master = masterFile(context)
        if (master.canRead()) {
            val restored = runCatching {
                val dest = File(path)
                dest.parentFile?.mkdirs()
                master.inputStream().use { input ->
                    FileOutputStream(dest).use { out -> input.copyTo(out) }
                }
                dest.canRead()
            }.getOrDefault(false)
            if (restored) {
                MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("image/png"), null)
                return path
            }
            // Master readable but the copy failed: keep the pref, the next visit retries; never fall through to the prune.
            Log.w(TAG, "reconcile: master is readable but restoring $path failed; keeping the pref")
            return path
        }
        // Only now, and only when the file is provably missing.
        if (!hasAllFilesAccess()) return path
        prefs.wallpaperPath = null
        // The gate both toggles enforce: glass with no wallpaper is blank panes, so it goes with the path.
        if (prefs.glassEnabled) prefs.glassEnabled = false
        return null
    }

    private fun publicDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "WaThemer",
    )
}

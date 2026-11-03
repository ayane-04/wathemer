// A store theme's preview: its header and small wallpaper, fetched and checked like every store file.
package com.wathemer.app.store

import android.content.Context
import android.util.Log
import com.wathemer.app.settings.prefs.ThemeDoc
import com.wathemer.app.settings.prefs.ThemeFile
import java.io.File

object StorePreview {

    private const val TAG = "WaThemer.Store"

    /** [wallpaper] is null when the theme has none or its preview picture could not be had. */
    class Ready(val doc: ThemeDoc, val wallpaper: File?)

    private val http by lazy { StoreConfig.http() }

    fun load(context: Context, theme: StoreTheme): Ready? = load(context, StoreConfig.TARGET, http, theme)

    /** Null at the first step that fails, named in the log; the page then simply has no preview. */
    fun load(context: Context, target: StoreTarget, http: StoreHttp, theme: StoreTheme): Ready? {
        val header = theme.previewHeader ?: return miss(theme, "no header in the catalogue")
        val headerFile = StoreRepository.siteFile(context, target, http, header) ?: return miss(theme, "header not fetched")
        val doc = runCatching { ThemeFile.parseHeader(headerFile.readBytes()) }.getOrNull()
            ?: return miss(theme, "header unreadable")
        val wallpaper = if (doc.wallpaper?.hasImage == true) {
            theme.previewWallpaper?.let { StoreRepository.siteFile(context, target, http, it) }
        } else {
            null
        }
        return Ready(doc, wallpaper)
    }

    private fun miss(theme: StoreTheme, step: String): Ready? {
        Log.w(TAG, "preview of ${theme.id}: $step")
        return null
    }
}

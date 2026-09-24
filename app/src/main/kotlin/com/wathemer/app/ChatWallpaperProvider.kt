// Read-only bridge for the chat wallpaper dir: WhatsApp cannot read another app's private file by path.
// Exported like FontProvider, but these are per-contact pictures, so only WhatsApp and this app are served.
package com.wathemer.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.wathemer.app.util.HostPackages
import java.io.File
import java.io.FileNotFoundException

class ChatWallpaperProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        if (!callerAllowed()) throw SecurityException("not for this caller")
        val name = uri.pathSegments.singleOrNull() ?: throw FileNotFoundException("$uri")
        val dir = File(requireNotNull(context).filesDir, "chatwallpapers")
        val f = File(dir, name)
        // Canonical containment check so a crafted segment cannot escape the wallpaper dir.
        if (!f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) throw FileNotFoundException("$uri")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** A themed host or this app, compared by app id so a cloned host in another user passes too. */
    private fun callerAllowed(): Boolean {
        val caller = Binder.getCallingUid()
        if (caller == Process.myUid()) return true
        val pm = requireNotNull(context).packageManager
        // An uninstalled host throws rather than returning 0, so each lookup stands on its own.
        return HostPackages.ALL.any { pkg ->
            val uid = runCatching { pm.getPackageUid(pkg, 0) }.getOrNull() ?: return@any false
            caller % PER_USER_RANGE == uid % PER_USER_RANGE
        }
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        /** UserHandle.PER_USER_RANGE, hidden; the app id is the uid modulo this. */
        const val PER_USER_RANGE = 100000
    }
}

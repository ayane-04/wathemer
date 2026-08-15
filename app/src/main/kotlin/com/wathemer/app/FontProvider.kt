// Read-only bridge for the fonts dir: WhatsApp cannot read another app's non-media file by path, only media rides Downloads.
package com.wathemer.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

class FontProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val name = uri.pathSegments.singleOrNull() ?: throw FileNotFoundException("$uri")
        val dir = File(requireNotNull(context).filesDir, "fonts")
        val f = File(dir, name)
        // Canonical containment check so a crafted segment cannot escape the fonts dir.
        if (!f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) throw FileNotFoundException("$uri")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/octet-stream"

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
}

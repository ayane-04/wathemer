// User font library in the settings app's private fonts dir; FontProvider serves the files to WhatsApp.
package com.wathemer.app.settings.prefs

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import com.wathemer.app.util.FontNames
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object FontLibrary {

    private const val TAG = "WaThemer.FontLib"

    /** One imported font. [file] lives in [dir]; [stamp] is unique forever, WhatsApp keys its cache on it. */
    data class Entry(val file: String, val name: String, val stamp: Int)

    /** [duplicate] means the pick matched an existing entry and nothing was written. */
    data class ImportOutcome(val entry: Entry, val duplicate: Boolean)

    /** Why a pick produced no entry, kept apart so the message never blames the file for the wrong reason. */
    sealed class ImportResult {
        class Done(val outcome: ImportOutcome) : ImportResult()
        object Unreadable : ImportResult()
        class TooLarge(val bytes: Long) : ImportResult()
    }

    /** The copy stops here: the largest CJK collections fit under it, a mistaken video pick does not. */
    const val MAX_FONT_BYTES = 32L * 1024 * 1024

    fun dir(context: Context): File = File(context.filesDir, "fonts")

    fun fileOf(context: Context, entry: Entry): File = File(dir(context), entry.file)

    /** Library entries whose file still exists; a missing file renders and resolves as nothing useful. */
    fun list(context: Context, prefs: Prefs): List<Entry> =
        parse(prefs.fontUserLibrary).filter { fileOf(context, it).canRead() }

    /** Typeface for previews. Null on a corrupt file; callers fall back to the default face. */
    fun typeface(context: Context, entry: Entry): Typeface? =
        runCatching { Typeface.Builder(fileOf(context, entry)).build() }.getOrNull()

    /** Copy the pick into the library, capped at [MAX_FONT_BYTES]; does not change the selection. */
    fun import(context: Context, prefs: Prefs, uri: Uri): ImportResult {
        val tmp = File(context.cacheDir, "font-import.tmp")
        var total = 0L
        var overflow = false
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { out ->
                    // Bounded by hand: the picker takes any file, and a provider need not declare a size up front.
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_FONT_BYTES) { overflow = true; break }
                        out.write(buf, 0, n)
                    }
                }
            }
        }.isSuccess
        if (overflow) { tmp.delete(); return ImportResult.TooLarge(total) }
        if (!copied) { tmp.delete(); return ImportResult.Unreadable }
        val outcome = importFile(context, prefs, tmp, displayNameStem(context, uri))
        tmp.delete()
        return outcome?.let { ImportResult.Done(it) } ?: ImportResult.Unreadable
    }

    /** Remove an entry and its file; a selected entry also resets the font choice to stock. */
    fun remove(context: Context, prefs: Prefs, entry: Entry): Boolean {
        val rest = parse(prefs.fontUserLibrary).filter { it.stamp != entry.stamp }
        prefs.fontUserLibrary = serialize(rest)
        fileOf(context, entry).delete()
        val wasSelected = prefs.customFont == "user" && prefs.fontUserFile == entry.file
        if (wasSelected) {
            prefs.customFont = ""
            prefs.fontUserFile = ""
            prefs.fontUserName = ""
        }
        Log.i(TAG, "removed '${entry.name}' (stamp=${entry.stamp}, selected=$wasSelected)")
        return wasSelected
    }

    fun select(prefs: Prefs, entry: Entry) {
        prefs.fontUserFile = entry.file
        prefs.fontUserName = entry.name
        prefs.fontUserStamp = entry.stamp
        prefs.customFont = "user"
    }

    /** One-shot move of the pre-library Download/WaThemer/font.ttf into the library. */
    fun migrateLegacyDownloadsFont(context: Context, prefs: Prefs): Entry? {
        // Deleting the source on the strength of a write no hook can read would strand the font.
        if (!prefs.moduleStoreActive) return null
        val legacyDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "WaThemer",
        )
        val legacy = File(legacyDir, "font.ttf")
        if (!legacy.canRead()) return null
        val outcome = importFile(context, prefs, legacy, fallbackName = null) ?: return null
        // The old store selected "user" with no filename; hand that selection to the imported entry.
        if (prefs.customFont == "user" && prefs.fontUserFile.isBlank()) select(prefs, outcome.entry)
        legacy.delete()
        File(legacyDir, "font.ttf.part").delete()
        Log.i(TAG, "legacy Downloads font imported as '${outcome.entry.name}'")
        return outcome.entry
    }

    private fun importFile(context: Context, prefs: Prefs, src: File, fallbackName: String?): ImportOutcome? {
        // Builder returns null on an unparseable file where createFromFile would hand back DEFAULT silently.
        runCatching { checkNotNull(Typeface.Builder(src).build()) }.getOrElse { return null }
        val entries = parse(prefs.fontUserLibrary)
        val name = FontNames.familyName(src)
            ?: fallbackName
            ?: "Font ${prefs.fontUserSeq + 1}"
        entries.firstOrNull { it.name == name && fileOf(context, it).length() == src.length() }?.let {
            return ImportOutcome(it, duplicate = true)
        }
        val stamp = prefs.fontUserSeq + 1
        val entry = Entry("$stamp.font", name, stamp)
        val target = fileOf(context, entry)
        val staged = runCatching {
            target.parentFile!!.mkdirs()
            val part = File(target.parentFile, "${target.name}.part")
            src.copyTo(part, overwrite = true)
            if (!part.renameTo(target)) {
                target.delete()
                check(part.renameTo(target))
            }
        }.isSuccess
        if (!staged) { Log.w(TAG, "import copy failed for '$name'"); return null }
        prefs.fontUserSeq = stamp
        prefs.fontUserLibrary = serialize(entries + entry)
        Log.i(TAG, "imported '$name' as ${entry.file} (${target.length()} bytes)")
        return ImportOutcome(entry, duplicate = false)
    }

    private fun displayNameStem(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() } else null
        }
    }.getOrNull()

    private fun parse(json: String): List<Entry> = runCatching {
        val arr = JSONArray(json.ifBlank { "[]" })
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val file = o.optString("file")
            val stamp = o.optInt("stamp", 0)
            if (file.isBlank() || stamp <= 0) return@mapNotNull null
            Entry(file, o.optString("name").ifBlank { "Font $stamp" }, stamp)
        }
    }.onFailure { Log.w(TAG, "parse: unreadable list, reading it as empty: $it") }.getOrDefault(emptyList())

    private fun serialize(entries: List<Entry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().put("file", e.file).put("name", e.name).put("stamp", e.stamp))
        }
        return arr.toString()
    }
}

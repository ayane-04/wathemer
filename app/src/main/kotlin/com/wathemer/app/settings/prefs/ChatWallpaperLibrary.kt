// Chat wallpapers: one JSON list in the store, image files in the settings app's private dir, served to
// WhatsApp by ChatWallpaperProvider. The hook parses the same list through this class, so the shape cannot drift.
package com.wathemer.app.settings.prefs

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

object ChatWallpaperLibrary {

    private const val TAG = "WaThemer.ChatWP"

    /** The hand-off from WhatsApp's chat menu: the chat's jid, its title, and when the tap happened. */
    const val EXTRA_JID = "com.wathemer.app.extra.CHAT_JID"
    const val EXTRA_NAME = "com.wathemer.app.extra.CHAT_NAME"
    const val EXTRA_SENT_AT = "com.wathemer.app.extra.CHAT_SENT_AT"

    /** A hand-off older than this is a task root intent re-delivered from recents, not a tap. */
    const val HANDOFF_MAX_AGE_MS = 2 * 60 * 1000L

    /** Each entry is a full-screen image here and a copy inside WhatsApp; the cap keeps that honest. */
    const val MAX_ENTRIES = 30

    /** The copy stops here: a cropped screen-size image is a couple of megabytes, a mistaken pick is not. */
    const val MAX_IMAGE_BYTES = 24L * 1024 * 1024

    private const val MAX_NAME_CHARS = 48

    /** One chat's wallpaper. [file] lives in [dir]; [stamp] is unique forever, WhatsApp keys its cached copy on it. */
    data class Entry(
        val jid: String,
        val name: String,
        val file: String,
        val stamp: Int,
        val dim: Int,
        val blur: Int,
    )

    sealed class PutResult {
        class Done(val entry: Entry, val replaced: Boolean) : PutResult()
        object Unreadable : PutResult()
        object TooLarge : PutResult()
        object Full : PutResult()
    }

    fun dir(context: Context): File = File(context.filesDir, "chatwallpapers")

    fun fileOf(context: Context, entry: Entry): File = File(dir(context), entry.file)

    /** Every stored entry, file present or not; the hook reads this shape too. */
    fun all(prefs: Prefs): List<Entry> = parse(prefs.chatWallpapers)

    /** Entries whose file still exists; a missing file resolves to the global wallpaper and is not worth a row. */
    fun list(context: Context, prefs: Prefs): List<Entry> =
        all(prefs).filter { fileOf(context, it).canRead() }

    fun find(context: Context, prefs: Prefs, jid: String): Entry? =
        list(context, prefs).firstOrNull { it.jid == jid }

    /** Store a cropped image for [jid]; a replacement keeps its dim and blur but takes a new stamp, or WhatsApp's cache would serve old bytes. */
    fun put(context: Context, prefs: Prefs, jid: String, name: String?, openStream: () -> InputStream?): PutResult {
        val entries = all(prefs)
        val existing = entries.firstOrNull { it.jid == jid }
        if (existing == null && entries.size >= MAX_ENTRIES) return PutResult.Full
        val stamp = prefs.chatWallpaperSeq + 1
        // Unguessable on purpose: the provider is exported, and a counted name would let any app enumerate these.
        val fileName = "$stamp-${randomHex(16)}.jpg"
        val target = File(dir(context), fileName)
        val part = File(dir(context), "$fileName.part")
        var total = 0L
        var overflow = false
        val copied = runCatching {
            target.parentFile!!.mkdirs()
            openStream()!!.use { input ->
                part.outputStream().use { out ->
                    // Bounded by hand: a picker hands over any file and need not declare a size.
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_IMAGE_BYTES) { overflow = true; break }
                        out.write(buf, 0, n)
                    }
                }
            }
        }.isSuccess
        if (overflow) { part.delete(); return PutResult.TooLarge }
        if (!copied || total == 0L || !decodes(part)) { part.delete(); return PutResult.Unreadable }
        if (!part.renameTo(target)) {
            target.delete()
            if (!part.renameTo(target)) { part.delete(); return PutResult.Unreadable }
        }
        // Counter before the list: a crash between the two loses an entry, the other order loses stamp uniqueness.
        prefs.chatWallpaperSeq = stamp
        val entry = Entry(
            jid = jid,
            name = sanitizeName(name, existing?.name, jid),
            file = fileName,
            stamp = stamp,
            dim = existing?.dim ?: prefs.wallpaperDim,
            blur = existing?.blur ?: prefs.wallpaperBlur,
        )
        prefs.chatWallpapers = serialize(entries.filter { it.jid != jid } + entry)
        // Only now: the list no longer points at the old bytes.
        existing?.let { fileOf(context, it).delete() }
        Log.i(TAG, "stored stamp $stamp for a chat (${target.length()} bytes, replaced=${existing != null})")
        return PutResult.Done(entry, existing != null)
    }

    /** Per-chat dim and blur, clamped like the global sliders. */
    fun setDimBlur(prefs: Prefs, entry: Entry, dim: Int, blur: Int): Entry {
        val updated = entry.copy(dim = dim.coerceIn(0, 100), blur = blur.coerceIn(0, 150))
        prefs.chatWallpapers = serialize(all(prefs).map { if (it.jid == entry.jid) updated else it })
        return updated
    }

    fun remove(context: Context, prefs: Prefs, entry: Entry) {
        prefs.chatWallpapers = serialize(all(prefs).filter { it.jid != entry.jid })
        fileOf(context, entry).delete()
        Log.i(TAG, "removed stamp ${entry.stamp}")
    }

    /** One line, bounded, no control characters; the title view can be blank, so the jid's user part stands in. */
    fun sanitizeName(raw: String?, fallback: String?, jid: String): String {
        val clean = raw.orEmpty().map { if (it.isISOControl()) ' ' else it }.joinToString("")
            .replace(Regex("\\s+"), " ").trim().take(MAX_NAME_CHARS)
        if (clean.isNotBlank()) return clean
        if (!fallback.isNullOrBlank()) return fallback
        return displayJid(jid)
    }

    /** The readable half of a jid: the user part, and the kind of chat the server suffix says it is. */
    fun displayJid(jid: String): String {
        val at = jid.indexOf('@')
        if (at <= 0) return jid.take(MAX_NAME_CHARS)
        val user = jid.substring(0, at)
        val kind = when (jid.substring(at + 1)) {
            "g.us" -> "group"
            "newsletter" -> "channel"
            "broadcast" -> "broadcast list"
            "lid" -> "contact"
            "s.whatsapp.net" -> "contact"
            else -> "chat"
        }
        return "$user ($kind)"
    }

    fun parse(json: String): List<Entry> = runCatching {
        val arr = JSONArray(json.ifBlank { "[]" })
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val jid = o.optString("jid")
            val file = o.optString("file")
            val stamp = o.optInt("stamp", 0)
            if (jid.isBlank() || file.isBlank() || stamp <= 0) return@mapNotNull null
            Entry(
                jid = jid,
                name = o.optString("name").ifBlank { displayJid(jid) },
                file = file,
                stamp = stamp,
                dim = o.optInt("dim", 0).coerceIn(0, 100),
                blur = o.optInt("blur", 0).coerceIn(0, 150),
            )
        }
    }.getOrDefault(emptyList())

    fun serialize(entries: List<Entry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject().put("jid", e.jid).put("name", e.name).put("file", e.file)
                    .put("stamp", e.stamp).put("dim", e.dim).put("blur", e.blur),
            )
        }
        return arr.toString()
    }

    private fun decodes(f: File): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        return o.outWidth > 0 && o.outHeight > 0
    }

    private fun randomHex(chars: Int): String {
        val bytes = ByteArray((chars + 1) / 2)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }.take(chars)
    }
}

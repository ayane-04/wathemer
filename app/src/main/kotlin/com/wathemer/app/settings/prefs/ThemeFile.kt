// The .wathemer file: a zip holding theme.json first and, when the theme has one, wallpaper.png.
// Read it defensively. These files arrive from strangers, so everything is capped, checked and whitelisted.
package com.wathemer.app.settings.prefs

import android.graphics.BitmapFactory
import android.util.Log
import com.wathemer.app.util.HexColor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** One theme as it exists in a file. A null section means the file never mentioned it, and an import leaves that part of the store alone. */
data class ThemeDoc(
    val name: String,
    val createdAt: Long,
    val appVersion: String,
    /** Only the colours that are actually set; an empty map is a theme that deliberately carries none. */
    val colors: Map<String, Int>?,
    val wallpaper: ThemeWallpaper?,
    val bubbles: ThemeBubbles?,
    val flags: Map<String, Boolean>,
    val font: ThemeFont?,
    val glass: ThemeGlass?,
)

/** [hasImage] says whether wallpaper.png is in the archive; without it there is nothing to point the store at. */
data class ThemeWallpaper(val hasImage: Boolean, val enabled: Boolean, val dim: Int, val blur: Int)

/** The glass sliders. Carries the look, never [Prefs.KEY_GLASS_ENABLED], which the wallpaper gate still owns. */
data class ThemeGlass(
    val blur: Int,
    val tint: Int,
    val displace: Int,
    val bevel: Int,
    val radius: Int,
    val gamma: Int,
    val rim: Int,
    val rimWidth: Int,
    val rimAngle: Int,
    val bubbleMerge: Boolean,
)

/** Shapes travel as asset names, never as the stored index: the registry is ordered and a removal renumbers it. */
data class ThemeBubbles(val incoming: String?, val outgoing: String?)

/** [id] is a bundled font id, [name] the display name of an imported one; both blank for stock. */
data class ThemeFont(val kind: ThemeFontKind, val id: String, val name: String)

enum class ThemeFontKind { STOCK, BUILTIN, USER }

object ThemeFile {

    private const val TAG = "WaThemer.ThemeFile"

    const val EXTENSION = ".wathemer"

    /** Sent as a zip so receiving apps treat it as a document rather than refusing an unknown type. */
    const val MIME = "application/zip"

    /** Raise only for a change an older reader could not survive; a reader refuses anything above its own. */
    const val FORMAT = 1

    private const val ENTRY_JSON = "theme.json"
    private const val ENTRY_WALLPAPER = "wallpaper.png"

    private const val MAX_JSON_BYTES = 256 * 1024
    private const val MAX_WALLPAPER_BYTES = 32L * 1024 * 1024
    private const val MAX_NAME_CHARS = 48

    // Skipping an entry decompresses it, so an entry nobody reads is still a bomb. Both caps exist for that.
    private const val MAX_ENTRIES = 32
    private const val MAX_SKIPPED_BYTES = 1024 * 1024

    /** [problems] are things the reader coped with; the theme is still usable. */
    sealed interface Load {
        data class Ok(val doc: ThemeDoc, val wallpaper: File?, val problems: List<String>) : Load
        data class Failed(val reason: String) : Load
    }

    /** Write the archive. theme.json goes first so a list screen reads a header without touching the image. */
    fun write(out: OutputStream, doc: ThemeDoc, wallpaper: File?): Boolean {
        val image = wallpaper?.takeIf { it.canRead() && it.length() > 0 }
        // The header follows the file on disk, never the caller's claim, so the two can never disagree.
        val header = doc.copy(wallpaper = doc.wallpaper?.copy(hasImage = image != null))
        return runCatching {
            ZipOutputStream(out.buffered()).use { zos ->
                zos.putNextEntry(ZipEntry(ENTRY_JSON))
                zos.write(toJson(header).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                if (image != null) {
                    zos.putNextEntry(ZipEntry(ENTRY_WALLPAPER))
                    image.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }.onFailure { Log.w(TAG, "write failed: $it") }.isSuccess
    }

    /** Read a whole archive, staging the wallpaper into [stage]. Takes ownership of [input] and closes it. */
    fun load(input: InputStream, stage: File): Load {
        var json: String? = null
        var image: File? = null
        try {
            ZipInputStream(input.buffered()).use { zis ->
                var seen = 0
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (++seen > MAX_ENTRIES) {
                        return failStaged(stage, "That file holds far more than a theme does.")
                    }
                    // Entry names are compared, never joined onto a path, so a crafted name has nowhere to escape to.
                    when (entry.name) {
                        ENTRY_JSON ->
                            json = (readCapped(zis, MAX_JSON_BYTES)
                                ?: return failStaged(stage, "That theme's description is too large to be genuine."))
                                .toString(Charsets.UTF_8)
                        ENTRY_WALLPAPER ->
                            image = copyCapped(zis, stage, MAX_WALLPAPER_BYTES)
                                ?: return failStaged(stage, "That theme's wallpaper is too large to import.")
                        // Read the rest by hand rather than letting closeEntry do it, so the cap applies to them too.
                        else ->
                            if (readCapped(zis, MAX_SKIPPED_BYTES) == null) {
                                return failStaged(stage, "That file carries something far too large to be part of a theme.")
                            }
                    }
                    zis.closeEntry()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "load: unreadable archive: $t")
            return failStaged(stage, "That file is not a WaThemer theme, or it arrived damaged.")
        }

        val text = json ?: return failStaged(stage, "That file is not a WaThemer theme.")
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return failStaged(stage, "That theme's description could not be read.")
        val format = root.optInt("format", 0)
        if (format <= 0) return failStaged(stage, "That file is not a WaThemer theme.")
        if (format > FORMAT) {
            return failStaged(stage, "That theme was made by a newer WaThemer. Update the app and try again.")
        }

        val problems = mutableListOf<String>()
        val doc = parse(root, problems)
        val staged = image
        // A header promising an image with nothing behind it would apply as a theme nobody exported.
        if (doc.wallpaper?.hasImage == true && staged == null) {
            return failStaged(stage, "That theme says it has a wallpaper but the file does not carry one.")
        }
        if (staged != null && doc.wallpaper?.hasImage != true) {
            staged.delete()
            return Load.Ok(doc, null, problems.distinct())
        }
        if (staged != null && !decodes(staged)) {
            return failStaged(stage, "That theme's wallpaper is not an image this phone can read.")
        }
        return Load.Ok(doc, staged, problems.distinct())
    }

    /** Header only. Stops at the first entry, which is the reason theme.json is written first. */
    fun peek(file: File): ThemeDoc? = runCatching {
        ZipInputStream(file.inputStream().buffered()).use { zis ->
            var seen = 0
            while (true) {
                val entry = zis.nextEntry ?: break
                if (++seen > MAX_ENTRIES) return@use null
                if (entry.name != ENTRY_JSON) {
                    // Drained by hand for the same reason the full read does it: closeEntry would decompress it all.
                    if (readCapped(zis, MAX_SKIPPED_BYTES) == null) return@use null
                    zis.closeEntry()
                    continue
                }
                val bytes = readCapped(zis, MAX_JSON_BYTES) ?: return@use null
                val root = JSONObject(bytes.toString(Charsets.UTF_8))
                if (root.optInt("format", 0) !in 1..FORMAT) return@use null
                return@use parse(root, mutableListOf())
            }
            null
        }
    }.getOrNull()

    /** Trim to one printable line and cap it. The name is display text; a slot's filename is its stamp. */
    fun sanitizeName(raw: String?): String = oneLine(raw).ifBlank { "Untitled theme" }

    /** A filename a person would recognise, for the share sheet and the save dialog. */
    fun suggestedFileName(name: String): String {
        val stem = name.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(40)
            .trim('-')
            .ifBlank { "theme" }
        return "$stem$EXTENSION"
    }

    /** One printable line, capped. No fallback: a caller that needs one says so. */
    private fun oneLine(raw: String?): String = raw.orEmpty()
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_NAME_CHARS)

    // ── JSON ──────────────────────────────────────────────────────────────

    private fun toJson(doc: ThemeDoc): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("app", "WaThemer")
        root.put("appVersion", doc.appVersion)
        root.put("name", doc.name)
        root.put("createdAt", doc.createdAt)
        doc.colors?.let { colors ->
            val o = JSONObject()
            // Fixed eight digits; a shorter form drops the alpha byte and the reader cannot tell that it did.
            for ((k, v) in colors) o.put(k, HexColor.toHex(v))
            root.put("colors", o)
        }
        doc.wallpaper?.let { w ->
            root.put(
                "wallpaper",
                JSONObject()
                    .put("image", w.hasImage)
                    .put("enabled", w.enabled)
                    .put("dim", w.dim)
                    .put("blur", w.blur),
            )
        }
        doc.bubbles?.let { b ->
            root.put(
                "bubbles",
                JSONObject()
                    .put("incoming", b.incoming ?: JSONObject.NULL)
                    .put("outgoing", b.outgoing ?: JSONObject.NULL),
            )
        }
        if (doc.flags.isNotEmpty()) {
            val o = JSONObject()
            for ((k, v) in doc.flags) o.put(k, v)
            root.put("flags", o)
        }
        doc.font?.let { f ->
            root.put(
                "font",
                JSONObject()
                    .put("kind", f.kind.name.lowercase())
                    .put("id", f.id)
                    .put("name", f.name),
            )
        }
        // A new section rather than a format bump: an older reader skips what it does not know.
        doc.glass?.let { g ->
            root.put(
                "glass",
                JSONObject()
                    .put("blur", g.blur)
                    .put("tint", g.tint)
                    .put("displace", g.displace)
                    .put("bevel", g.bevel)
                    .put("radius", g.radius)
                    .put("gamma", g.gamma)
                    .put("rim", g.rim)
                    .put("rimWidth", g.rimWidth)
                    .put("rimAngle", g.rimAngle)
                    .put("bubbleMerge", g.bubbleMerge),
            )
        }
        return root.toString(2)
    }

    private fun parse(root: JSONObject, problems: MutableList<String>): ThemeDoc {
        val colors = root.optJSONObject("colors")?.let { o ->
            val out = LinkedHashMap<String, Int>()
            var unknown = 0
            var unreadable = 0
            for (key in o.keys()) {
                if (key !in Prefs.THEME_COLOR_KEYS) { unknown++; continue }
                val argb = parseArgb(o.optString(key))
                if (argb == null) { unreadable++; continue }
                out[key] = argb
            }
            if (unknown > 0) problems += "$unknown colour setting(s) this version does not know were skipped."
            if (unreadable > 0) problems += "$unreadable colour value(s) could not be read and were skipped."
            out
        }

        // Clamped to the ranges Prefs uses; a shared file is not a trusted source of a slider value.
        val wallpaper = root.optJSONObject("wallpaper")?.let { o ->
            ThemeWallpaper(
                hasImage = o.optBoolean("image", false),
                enabled = o.optBoolean("enabled", false),
                dim = o.optInt("dim", 0).coerceIn(0, 100),
                blur = o.optInt("blur", 0).coerceIn(0, 150),
            )
        }

        val bubbles = root.optJSONObject("bubbles")?.let { o ->
            ThemeBubbles(
                incoming = o.optString("incoming").trim().take(64).takeIf { it.isNotBlank() },
                outgoing = o.optString("outgoing").trim().take(64).takeIf { it.isNotBlank() },
            )
        }

        val flags = LinkedHashMap<String, Boolean>()
        root.optJSONObject("flags")?.let { o ->
            for (key in o.keys()) {
                if (key in Prefs.THEME_FLAG_KEYS) flags[key] = o.optBoolean(key, false)
            }
        }

        val font = root.optJSONObject("font")?.let { o ->
            val kind = when (o.optString("kind").trim().lowercase()) {
                "builtin" -> ThemeFontKind.BUILTIN
                "user" -> ThemeFontKind.USER
                else -> ThemeFontKind.STOCK
            }
            ThemeFont(
                kind = kind,
                id = o.optString("id").trim().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(32),
                name = if (kind == ThemeFontKind.USER) oneLine(o.optString("name")) else "",
            )
        }

        // Clamped to the same ranges the Prefs setters use; applyThemeWrite puts ints in raw.
        val glass = root.optJSONObject("glass")?.let { o ->
            ThemeGlass(
                blur = o.optInt("blur", GlassDefaults.BLUR).coerceIn(4, 40),
                tint = o.optInt("tint", GlassDefaults.TINT).coerceIn(0, 80),
                displace = o.optInt("displace", GlassDefaults.DISPLACE).coerceIn(0, 60),
                bevel = o.optInt("bevel", GlassDefaults.BEVEL).coerceIn(5, 40),
                radius = o.optInt("radius", GlassDefaults.RADIUS).coerceIn(0, 40),
                gamma = o.optInt("gamma", GlassDefaults.GAMMA).coerceIn(30, 100),
                rim = o.optInt("rim", GlassDefaults.RIM).coerceIn(0, 100),
                rimWidth = o.optInt("rimWidth", GlassDefaults.RIM_WIDTH).coerceIn(1, 4),
                rimAngle = o.optInt("rimAngle", GlassDefaults.RIM_ANGLE).coerceIn(0, 360),
                bubbleMerge = o.optBoolean("bubbleMerge", GlassDefaults.BUBBLE_MERGE),
            )
        }

        return ThemeDoc(
            name = sanitizeName(root.optString("name")),
            createdAt = root.optLong("createdAt", 0L),
            appVersion = root.optString("appVersion").trim().take(16),
            colors = colors,
            wallpaper = wallpaper,
            bubbles = bubbles,
            flags = flags,
            font = font,
            glass = glass,
        )
    }

    /** Strict: the shape is checked before parsing, so a 0 result is a real transparent black and not a failure. */
    private fun parseArgb(raw: String?): Int? {
        val s = raw?.trim().orEmpty().removePrefix("#")
        when (s.length) {
            3, 4, 6, 8 -> Unit
            else -> return null
        }
        if (!s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return HexColor.fromHex(s)
    }

    // ── Bounded reads ─────────────────────────────────────────────────────

    /** Null once the entry runs past [max]. Callers must stop reading the archive, not skip to the next entry. */
    private fun readCapped(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun copyCapped(input: InputStream, dest: File, max: Long): File? {
        dest.parentFile?.mkdirs()
        var total = 0L
        FileOutputStream(dest).use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > max) return null
                out.write(buf, 0, n)
            }
        }
        return dest
    }

    /** Bounds-only decode; a file that is not an image must never become what the wallpaper pref points at. */
    private fun decodes(f: File): Boolean {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(f.absolutePath, opts) }
        return opts.outWidth > 0 && opts.outHeight > 0
    }

    private fun failStaged(stage: File, reason: String): Load.Failed {
        stage.delete()
        return Load.Failed(reason)
    }
}

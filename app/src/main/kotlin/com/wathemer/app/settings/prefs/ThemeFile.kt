// The .wathemer file: a zip holding theme.json first, then wallpaper.png and font.ttf when the theme has them.
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
    /** Only the colours that are set; an empty map is a theme that deliberately carries none. */
    val colors: Map<String, Int>?,
    val wallpaper: ThemeWallpaper?,
    val bubbles: ThemeBubbles?,
    val flags: Map<String, Boolean>,
    val font: ThemeFont?,
    val glass: ThemeGlass?,
    // Defaulted: the harness builds documents by hand without it.
    val ticks: ThemeTicks? = null,
    val store: ThemeStoreRef? = null,
)

/** [hasImage] says whether wallpaper.png is in the archive; without it there is nothing to point the store at. */
data class ThemeWallpaper(val hasImage: Boolean, val enabled: Boolean, val dim: Int, val blur: Int)

/** The glass sliders and switches, and whether glass is on, which apply holds off while no wallpaper is live. */
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
    // Defaulted for constructor calls written before a field existed; an old file gets the shipped value from parse, not from here.
    val saturation: Int = GlassDefaults.SATURATION,
    val huedTint: Boolean = GlassDefaults.HUED_TINT,
    val linearCopy: Boolean = GlassDefaults.LINEAR_COPY,
    val edgeShadow: Int = GlassDefaults.EDGE_SHADOW,
    val glow: Int = GlassDefaults.GLOW,
    val edgeClarity: Int = GlassDefaults.EDGE_CLARITY,
    val liveClarity: Boolean = GlassDefaults.LIVE_CLARITY,
    val navDroplet: Boolean = GlassDefaults.NAV_DROPLET,
    val assemble: Boolean = GlassDefaults.ASSEMBLE,
    val oneBlur: Boolean = GlassDefaults.ONE_BLUR,
    val smallOptics: Boolean = GlassDefaults.SMALL_OPTICS,
    val popupMorph: Boolean = GlassDefaults.POPUP_MORPH,
    val rowOptics: Boolean = GlassDefaults.ROW_OPTICS,
    val shapedBubbles: Boolean = GlassDefaults.SHAPED_BUBBLES,
    val stillSkipsScroll: Boolean = GlassDefaults.STILL_SKIPS_SCROLL,
    val mediaViewer: Boolean = GlassDefaults.MEDIA_VIEWER,
    val brightCap: Int = GlassDefaults.BRIGHT_CAP,
    val immersiveNav: Boolean = GlassDefaults.IMMERSIVE_NAV,
    /** Whether Liquid Glass is on; null in a file from before themes carried it, which leaves the switch alone. */
    val enabled: Boolean? = null,
)

/** Shapes travel as asset names, never as the stored index: the registry is ordered and a removal renumbers it. */
data class ThemeBubbles(val incoming: String?, val outgoing: String?)

/** The tick style travels as its asset prefix, never the index, for the same reason. */
data class ThemeTicks(val style: String?)

/** The store entry a saved theme came from; only a store install writes it, and an import from a file drops it. */
data class ThemeStoreRef(val id: String, val version: Int)

/** [id] is a bundled font id, [name] an imported one's display name, both blank for stock; [hasFile] says font.ttf is in the archive. */
data class ThemeFont(val kind: ThemeFontKind, val id: String, val name: String, val hasFile: Boolean = false)

enum class ThemeFontKind { STOCK, BUILTIN, USER }

object ThemeFile {

    private const val TAG = "WaThemer.ThemeFile"

    const val EXTENSION = ".wathemer"

    /** Sent as a zip so receiving apps treat it as a document rather than refusing an unknown type. */
    const val MIME = "application/zip"

    /** The highest format this reader takes; a reader refuses anything above its own, so raise it only for what an older one cannot survive. */
    const val FORMAT = 2

    /** A file without a font is written as 1, which every older reader opens. */
    private const val FORMAT_PLAIN = 1

    private const val ENTRY_JSON = "theme.json"
    private const val ENTRY_WALLPAPER = "wallpaper.png"
    private const val ENTRY_FONT = "font.ttf"

    private const val MAX_JSON_BYTES = 256 * 1024
    private const val MAX_WALLPAPER_BYTES = 32L * 1024 * 1024
    private const val MAX_NAME_CHARS = 48

    // A wallpaper is one screen; past these its decode can outgrow what a canvas will draw, which crashes WhatsApp.
    private const val MAX_WALLPAPER_SIDE = 8192
    private const val MAX_WALLPAPER_PIXELS = 8L * 1024 * 1024

    // Skipping an entry decompresses it, so an entry nobody reads is still a bomb. Both caps exist for that.
    private const val MAX_ENTRIES = 32
    private const val MAX_SKIPPED_BYTES = 1024 * 1024

    /** The store's id rule, the same the catalogue enforces. */
    val STORE_ID = Regex("^[a-z0-9][a-z0-9-]{0,39}$")

    /** [problems] are things the reader coped with; the theme is still usable. */
    sealed interface Load {
        data class Ok(val doc: ThemeDoc, val wallpaper: File?, val problems: List<String>, val font: File? = null) : Load
        data class Failed(val reason: String) : Load
    }

    /** Write the archive. theme.json goes first so a list screen reads a header without touching the image or the font. */
    fun write(out: OutputStream, doc: ThemeDoc, wallpaper: File?, font: File? = null): Boolean {
        val image = wallpaper?.takeIf { it.canRead() && it.length() > 0 }
        val fontFile = font?.takeIf { doc.font?.kind == ThemeFontKind.USER && it.canRead() && it.length() > 0 }
        // The header follows the files on disk, never the caller's claim, so the two can never disagree.
        val header = doc.copy(
            wallpaper = doc.wallpaper?.copy(hasImage = image != null),
            font = doc.font?.copy(hasFile = fontFile != null),
        )
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
                if (fontFile != null) {
                    zos.putNextEntry(ZipEntry(ENTRY_FONT))
                    fontFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }.onFailure { Log.w(TAG, "write failed: $it") }.isSuccess
    }

    /** Read a whole archive, staging the wallpaper into [stage] and the font beside it. Takes ownership of [input] and closes it. */
    fun load(input: InputStream, stage: File): Load {
        var header: JSONObject? = null
        var image: File? = null
        var font: File? = null
        try {
            ZipInputStream(input.buffered()).use { zis ->
                var seen = 0
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (++seen > MAX_ENTRIES) {
                        return failStaged(stage, "That file is too big to be a theme.")
                    }
                    // Entry names are compared, never joined onto a path, so a crafted name has nowhere to escape to.
                    when (entry.name) {
                        ENTRY_JSON -> {
                            val text = (readCapped(zis, MAX_JSON_BYTES)
                                ?: return failStaged(stage, "That theme file is too big."))
                                .toString(Charsets.UTF_8)
                            val parsed = runCatching { JSONObject(text) }.getOrNull()
                                ?: return failStaged(stage, "That theme file is damaged.")
                            // Judged before the entries after it, which only a reader of that format knows how to size.
                            if (parsed.optInt("format", 0) > FORMAT) {
                                return failStaged(stage, "Made with a newer WaThemer. Update the app first.")
                            }
                            header = parsed
                        }
                        ENTRY_WALLPAPER ->
                            image = copyCapped(zis, stage, MAX_WALLPAPER_BYTES)
                                ?: return failStaged(stage, "The theme's wallpaper file is too big.")
                        ENTRY_FONT ->
                            font = copyCapped(zis, fontStageOf(stage), FontLibrary.MAX_FONT_BYTES)
                                ?: return failStaged(stage, "The theme's font file is too big.")
                        // Read the rest by hand rather than letting closeEntry do it, so the cap applies to them too.
                        else ->
                            if (readCapped(zis, MAX_SKIPPED_BYTES) == null) {
                                return failStaged(stage, "That file is too big to be a theme.")
                            }
                    }
                    zis.closeEntry()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "load: unreadable archive: $t")
            return failStaged(stage, "Not a WaThemer theme, or the file is damaged.")
        }

        val root = header ?: return failStaged(stage, "Not a WaThemer theme.")
        if (root.optInt("format", 0) <= 0) return failStaged(stage, "Not a WaThemer theme.")

        val problems = mutableListOf<String>()
        val doc = parse(root, problems)
        // A header promising a file with nothing behind it would apply as a theme nobody exported.
        if (doc.wallpaper?.hasImage == true && image == null) {
            return failStaged(stage, "The theme's wallpaper is missing from the file.")
        }
        if (doc.font?.hasFile == true && font == null) {
            return failStaged(stage, "The theme's font is missing from the file.")
        }
        var wallpaper = image
        if (wallpaper != null && doc.wallpaper?.hasImage != true) {
            wallpaper.delete()
            wallpaper = null
        }
        var fontFile = font
        if (fontFile != null && doc.font?.hasFile != true) {
            fontFile.delete()
            fontFile = null
        }
        if (wallpaper != null) {
            val (w, h) = bounds(wallpaper)
                ?: return failStaged(stage, "Can't read the theme's wallpaper.")
            if (maxOf(w, h) > MAX_WALLPAPER_SIDE || w.toLong() * h > MAX_WALLPAPER_PIXELS) {
                return failStaged(stage, "The theme's wallpaper is too big.")
            }
        }
        return Load.Ok(doc, wallpaper, problems.distinct(), fontFile)
    }

    /** Header only. Reads up to theme.json, which is why write puts it first. */
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

    /** A bare theme.json, as the store publishes it for previews; the same caps and format check as a full read. */
    fun parseHeader(bytes: ByteArray): ThemeDoc? = runCatching {
        if (bytes.size > MAX_JSON_BYTES) return@runCatching null
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        if (root.optInt("format", 0) !in 1..FORMAT) return@runCatching null
        parse(root, mutableListOf())
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
    internal fun oneLine(raw: String?): String = raw.orEmpty()
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_NAME_CHARS)

    // ── JSON ──────────────────────────────────────────────────────────────

    private fun toJson(doc: ThemeDoc): String {
        val root = JSONObject()
        // Only a file carrying a font is 2, so an older reader refuses it rather than apply the look without its font.
        root.put("format", if (doc.font?.hasFile == true) FORMAT else FORMAT_PLAIN)
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
        doc.ticks?.let { t ->
            root.put("ticks", JSONObject().put("style", t.style ?: JSONObject.NULL))
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
                    .put("name", f.name)
                    .put("file", f.hasFile),
            )
        }
        doc.store?.let { s -> root.put("store", JSONObject().put("id", s.id).put("version", s.version)) }
        // A new section rather than a format bump: an older reader skips what it does not know.
        doc.glass?.let { g ->
            val o = JSONObject()
            g.enabled?.let { o.put("enabled", it) }
            root.put(
                "glass",
                o
                    .put("blur", g.blur)
                    .put("tint", g.tint)
                    .put("displace", g.displace)
                    .put("bevel", g.bevel)
                    .put("radius", g.radius)
                    .put("gamma", g.gamma)
                    .put("rim", g.rim)
                    .put("rimWidth", g.rimWidth)
                    .put("rimAngle", g.rimAngle)
                    .put("bubbleMerge", g.bubbleMerge)
                    .put("saturation", g.saturation)
                    .put("huedTint", g.huedTint)
                    .put("linearCopy", g.linearCopy)
                    .put("edgeShadow", g.edgeShadow)
                    .put("glow", g.glow)
                    .put("edgeClarity", g.edgeClarity)
                    .put("liveClarity", g.liveClarity)
                    .put("navDroplet", g.navDroplet)
                    .put("assemble", g.assemble)
                    .put("oneBlur", g.oneBlur)
                    .put("smallOptics", g.smallOptics)
                    .put("popupMorph", g.popupMorph)
                    .put("rowOptics", g.rowOptics)
                    .put("shapedBubbles", g.shapedBubbles)
                    .put("stillSkipsScroll", g.stillSkipsScroll)
                    .put("mediaViewer", g.mediaViewer)
                    .put("brightCap", g.brightCap)
                    .put("immersiveNav", g.immersiveNav),
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
            if (unknown > 0) problems += "Skipped $unknown colour setting(s) this version doesn't have."
            if (unreadable > 0) problems += "Skipped $unreadable colour value(s) that couldn't be read."
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

        val ticks = root.optJSONObject("ticks")?.let { o ->
            ThemeTicks(style = o.optString("style").trim().take(64).takeIf { it.isNotBlank() })
        }

        val store = root.optJSONObject("store")?.let { o ->
            // Typed, not coerced: org.json would read the text "3" as the number 3.
            val id = o.opt("id") as? String ?: ""
            val version = o.opt("version") as? Int ?: 0
            if (STORE_ID.matches(id) && version in 1..1_000_000) ThemeStoreRef(id, version) else null
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
                hasFile = kind == ThemeFontKind.USER && o.optBoolean("file", false),
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
                saturation = o.optInt("saturation", GlassDefaults.SATURATION).coerceIn(100, 200),
                huedTint = o.optBoolean("huedTint", GlassDefaults.HUED_TINT),
                linearCopy = o.optBoolean("linearCopy", GlassDefaults.LINEAR_COPY),
                edgeShadow = o.optInt("edgeShadow", GlassDefaults.EDGE_SHADOW).coerceIn(0, 30),
                glow = o.optInt("glow", GlassDefaults.GLOW).coerceIn(0, 100),
                edgeClarity = o.optInt("edgeClarity", GlassDefaults.EDGE_CLARITY).coerceIn(0, 100),
                liveClarity = o.optBoolean("liveClarity", GlassDefaults.LIVE_CLARITY),
                navDroplet = o.optBoolean("navDroplet", GlassDefaults.NAV_DROPLET),
                assemble = o.optBoolean("assemble", GlassDefaults.ASSEMBLE),
                oneBlur = o.optBoolean("oneBlur", GlassDefaults.ONE_BLUR),
                smallOptics = o.optBoolean("smallOptics", GlassDefaults.SMALL_OPTICS),
                popupMorph = o.optBoolean("popupMorph", GlassDefaults.POPUP_MORPH),
                rowOptics = o.optBoolean("rowOptics", GlassDefaults.ROW_OPTICS),
                shapedBubbles = o.optBoolean("shapedBubbles", GlassDefaults.SHAPED_BUBBLES),
                stillSkipsScroll = o.optBoolean("stillSkipsScroll", GlassDefaults.STILL_SKIPS_SCROLL),
                mediaViewer = o.optBoolean("mediaViewer", GlassDefaults.MEDIA_VIEWER),
                brightCap = o.optInt("brightCap", GlassDefaults.BRIGHT_CAP).coerceIn(0, 100),
                immersiveNav = o.optBoolean("immersiveNav", GlassDefaults.IMMERSIVE_NAV),
                // A real boolean or nothing: junk must leave the switch alone, not turn glass off.
                enabled = o.opt("enabled") as? Boolean,
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
            ticks = ticks,
            store = store,
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

    /** Bounds-only decode, width then height; null for a file that is not an image, which must never become what the wallpaper pref points at. */
    private fun bounds(f: File): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(f.absolutePath, opts) }
        return if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }

    /** Where a theme's font stages: beside its wallpaper, so one failure path clears both. */
    private fun fontStageOf(stage: File): File = File(stage.path + ".font")

    private fun failStaged(stage: File, reason: String): Load.Failed {
        stage.delete()
        fontStageOf(stage).delete()
        return Load.Failed(reason)
    }
}

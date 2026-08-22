// Saved themes: one .wathemer per slot in filesDir/themes, filed under a stamp, with the display name inside the file.
// There is no index. The directory is the list, so nothing can go stale against it.
package com.wathemer.app.settings.prefs

import android.content.Context
import android.net.Uri
import android.util.Log
import com.wathemer.app.BuildConfig
import com.wathemer.app.hooks.ColorSeeds
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object ThemeLibrary {

    private const val TAG = "WaThemer.Themes"
    private const val UNDO_FILE = "undo_theme.wathemer"

    /** [stamp] is the filename, one past the highest slot present, so a new slot always sorts newest. Deleting the top one frees its number, which nothing keys on. */
    data class Slot(val file: File, val stamp: Int, val doc: ThemeDoc)

    /** [problems] are things worth telling the user about a theme that still applied. */
    sealed interface Outcome {
        data class Applied(val name: String, val problems: List<String>) : Outcome
        data class Failed(val reason: String) : Outcome
        data object NeedsStorageAccess : Outcome
    }

    sealed interface Import {
        data class Added(val slot: Slot, val problems: List<String>) : Import
        data class Failed(val reason: String) : Import
    }

    /** For these three a stored 0 is a real colour; every other key uses 0 to mean "not overridden". */
    private val ZERO_IS_A_COLOUR = setOf(Prefs.KEY_PRIMARY, Prefs.KEY_BACKGROUND, Prefs.KEY_TEXT)

    fun dir(context: Context): File = File(context.filesDir, "themes")

    /** Every readable slot, newest first. A file that will not parse is skipped rather than repaired. */
    fun list(context: Context): List<Slot> = dir(context).listFiles().orEmpty()
        .mapNotNull { f ->
            val stamp = stampOf(f) ?: return@mapNotNull null
            val doc = ThemeFile.peek(f) ?: return@mapNotNull null
            Slot(f, stamp, doc)
        }
        .sortedByDescending { it.stamp }

    /** Capture the store into a new slot. Null when the file could not be written. */
    fun saveCurrent(context: Context, prefs: Prefs, name: String): Slot? {
        val source = wallpaperSource(context, prefs)
        val doc = capture(prefs, ThemeFile.sanitizeName(name), source != null)
        val stamp = nextStamp(context)
        val dest = File(dir(context), "$stamp${ThemeFile.EXTENSION}")
        if (!writeTo(dest, doc, source)) return null
        Log.i(TAG, "saved '${doc.name}' as ${dest.name} (${dest.length()} bytes)")
        return Slot(dest, stamp, doc)
    }

    /** Copy a picked file into the library once it parses. The archive is rebuilt, so only what this version understands is kept. */
    fun importFile(context: Context, uri: Uri): Import {
        val stage = File(context.cacheDir, "theme_import.png")
        val loaded = runCatching {
            context.contentResolver.openInputStream(uri)?.let { ThemeFile.load(it, stage) }
        }.getOrNull() ?: run {
            stage.delete()
            return Import.Failed("That file could not be opened.")
        }
        val ok = loaded as? ThemeFile.Load.Ok ?: run {
            stage.delete()
            return Import.Failed((loaded as ThemeFile.Load.Failed).reason)
        }
        val stamp = nextStamp(context)
        val dest = File(dir(context), "$stamp${ThemeFile.EXTENSION}")
        val written = writeTo(dest, ok.doc, ok.wallpaper)
        stage.delete()
        if (!written) return Import.Failed("Could not save that theme to your list.")
        Log.i(TAG, "imported '${ok.doc.name}' as ${dest.name}")
        return Import.Added(Slot(dest, stamp, ok.doc), ok.problems)
    }

    /** Rename by rewriting the archive: the name travels inside the file, so renaming the file would not carry. */
    fun rename(context: Context, slot: Slot, name: String): Boolean {
        val stage = File(context.cacheDir, "theme_rename.png")
        val loaded = runCatching { ThemeFile.load(slot.file.inputStream(), stage) }.getOrNull()
        val ok = loaded as? ThemeFile.Load.Ok ?: run { stage.delete(); return false }
        val part = File(slot.file.parentFile, "${slot.file.name}.part")
        val written = runCatching {
            FileOutputStream(part).use {
                ThemeFile.write(it, ok.doc.copy(name = ThemeFile.sanitizeName(name)), ok.wallpaper)
            }
        }.getOrDefault(false)
        stage.delete()
        if (!written) { part.delete(); return false }
        // Keep the original until the replacement is in place, or a failed rename loses the slot.
        val backup = File(slot.file.parentFile, "${slot.file.name}.bak")
        backup.delete()
        if (!slot.file.renameTo(backup)) { part.delete(); return false }
        if (!part.renameTo(slot.file)) { backup.renameTo(slot.file); part.delete(); return false }
        backup.delete()
        return true
    }

    fun delete(slot: Slot) {
        slot.file.delete()
    }

    /** Copy a slot out to wherever the picker chose. */
    fun exportTo(slot: Slot, out: OutputStream): Boolean = runCatching {
        slot.file.inputStream().use { it.copyTo(out) }
    }.onFailure { Log.w(TAG, "export failed: $it") }.isSuccess

    /** A copy under a name a person would recognise; the slot itself is filed under a number. */
    fun stageForShare(context: Context, slot: Slot): File? = runCatching {
        val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
        shareDir.listFiles()?.forEach { it.delete() }
        val dest = File(shareDir, ThemeFile.suggestedFileName(slot.doc.name))
        slot.file.inputStream().use { input -> FileOutputStream(dest).use { input.copyTo(it) } }
        dest
    }.getOrNull()

    // ── Undo ──────────────────────────────────────────────────────────────

    fun undoFile(context: Context): File = File(context.filesDir, UNDO_FILE)

    /** The theme that was in place before the last apply, or null when there is nothing to go back to. */
    fun undoable(context: Context): ThemeDoc? =
        undoFile(context).takeIf { it.canRead() }?.let { ThemeFile.peek(it) }

    fun clearUndo(context: Context) {
        undoFile(context).delete()
    }

    // ── Apply ─────────────────────────────────────────────────────────────

    /** Apply a theme file. Nothing is written until the whole archive has been read and the wallpaper is on disk. */
    fun apply(context: Context, prefs: Prefs, file: File, keepUndo: Boolean): Outcome {
        val stage = File(context.cacheDir, "theme_apply.png")
        val loaded = runCatching { ThemeFile.load(file.inputStream(), stage) }.getOrNull()
            ?: run { stage.delete(); return Outcome.Failed("That theme file could not be opened.") }
        val ok = loaded as? ThemeFile.Load.Ok
            ?: return Outcome.Failed((loaded as ThemeFile.Load.Failed).reason)

        val doc = ok.doc
        val image = ok.wallpaper
        // Asked before anything is touched: the public copy WhatsApp reads cannot be written without it.
        if (image != null && !WallpaperAsset.hasAllFilesAccess()) {
            image.delete()
            return Outcome.NeedsStorageAccess
        }
        val problems = ok.problems.toMutableList()

        if (keepUndo) {
            val source = wallpaperSource(context, prefs)
            val before = capture(prefs, "Before ${doc.name}", source != null)
            if (!writeTo(undoFile(context), before, source)) {
                problems += "Could not save a copy of your current theme, so this cannot be undone."
            }
        }

        // Bytes first, then the pref that points at them, or a failed copy leaves the store aimed at nothing.
        val wallpaperPath = image?.let { staged ->
            val dest = WallpaperAsset.persist(context) { staged.inputStream() }
            staged.delete()
            dest?.absolutePath
                ?: return Outcome.Failed("Could not save that theme's wallpaper. Storage may be full.")
        }

        val clear = ArrayList<String>()
        val values = HashMap<String, Any>()

        doc.colors?.let { colors ->
            // Clear then set in one commit: a colour the theme does not carry has to go, or two themes mix.
            clear += Prefs.THEME_COLOR_KEYS
            for ((key, argb) in colors) {
                values[key] = when (key) {
                    Prefs.KEY_PRIMARY -> ColorSeeds.dodgeCollision(argb, ColorSeeds.PRIMARY)
                    Prefs.KEY_BACKGROUND -> ColorSeeds.dodgeCollision(argb, ColorSeeds.BACKGROUND)
                    Prefs.KEY_TEXT -> ColorSeeds.dodgeCollision(argb, ColorSeeds.TEXT)
                    else -> argb
                }
            }
        }

        for ((key, on) in doc.flags) values[key] = on

        doc.bubbles?.let { b ->
            values[Prefs.BUBBLE_STYLE_INCOMING] = resolveShape(b.incoming, problems)
            values[Prefs.BUBBLE_STYLE_OUTGOING] = resolveShape(b.outgoing, problems)
        }

        doc.wallpaper?.let { w ->
            val on = wallpaperPath != null && w.enabled
            if (wallpaperPath != null) values[Prefs.KEY_WALLPAPER_PATH] = wallpaperPath else clear += Prefs.KEY_WALLPAPER_PATH
            values[Prefs.KEY_WALLPAPER_ENABLED] = on
            values[Prefs.KEY_WALLPAPER_DIM] = w.dim
            values[Prefs.KEY_WALLPAPER_BLUR] = w.blur
            // Glass has nothing to refract without a live wallpaper, and the settings gate cannot see an import.
            if (!on && prefs.glassEnabled) {
                values[Prefs.KEY_GLASS_ENABLED] = false
                problems += "Liquid Glass was switched off; it needs a wallpaper."
            }
        }

        doc.font?.let { f -> applyFont(context, prefs, f, clear, values, problems) }

        if (!prefs.applyThemeWrite(clear, values)) {
            return Outcome.Failed("Your settings could not be saved. The settings file is not writable.")
        }
        Log.i(TAG, "applied '${doc.name}': ${values.size} keys, ${problems.size} problem(s)")
        return Outcome.Applied(doc.name, problems.distinct())
    }

    private fun applyFont(
        context: Context,
        prefs: Prefs,
        font: ThemeFont,
        clear: MutableList<String>,
        values: MutableMap<String, Any>,
        problems: MutableList<String>,
    ) {
        when (font.kind) {
            ThemeFontKind.STOCK -> clear += Prefs.KEY_CUSTOM_FONT
            ThemeFontKind.BUILTIN ->
                if (hasBundledFont(context, font.id)) {
                    values[Prefs.KEY_CUSTOM_FONT] = font.id
                } else {
                    clear += Prefs.KEY_CUSTOM_FONT
                    problems += "This version has no font called \"${font.id}\", so the system one is used."
                }
            ThemeFontKind.USER -> {
                val match = FontLibrary.list(context, prefs).firstOrNull { it.name.equals(font.name, true) }
                if (match != null) {
                    values[Prefs.KEY_CUSTOM_FONT] = "user"
                    values[Prefs.KEY_FONT_USER_FILE] = match.file
                    values[Prefs.KEY_FONT_USER_NAME] = match.name
                    values[Prefs.KEY_FONT_USER_STAMP] = match.stamp
                } else {
                    clear += Prefs.KEY_CUSTOM_FONT
                    problems += "This theme uses \"${font.name}\", which a theme file cannot carry. Add that font under Extras for the same look."
                }
            }
        }
    }

    // ── Capture ───────────────────────────────────────────────────────────

    /** Read the store into a theme. Only keys that are set travel: an unset global must not arm the substitution on someone else's phone. */
    fun capture(prefs: Prefs, name: String, hasWallpaper: Boolean): ThemeDoc {
        val colors = LinkedHashMap<String, Int>()
        for (key in Prefs.THEME_COLOR_KEYS) {
            if (!prefs.isSet(key)) continue
            val v = prefs.getOverride(key)
            if (v == 0 && key !in ZERO_IS_A_COLOUR) continue
            colors[key] = v
        }
        // Built from the key list rather than by hand, so a flag added there cannot be left out of an export.
        val flags = Prefs.THEME_FLAG_KEYS.associateWith { key ->
            when (key) {
                Prefs.KEY_IOS_ICON_PACK -> prefs.iosIconPack
                Prefs.KEY_CHATLIST_DIVIDER -> prefs.chatListDivider
                Prefs.KEY_EFFECT_SNOW -> prefs.effectSnow
                Prefs.KEY_SYSTEM_BARS_ENABLED -> prefs.systemBarsEnabled
                Prefs.KEY_SYSTEM_BAR_AUTO_ICONS -> prefs.systemBarAutoIcons
                Prefs.KEY_FONT_MAP_MONOSPACE -> prefs.fontMapMonospace
                else -> { Log.w(TAG, "capture: no reader for flag $key"); false }
            }
        }
        val choice = prefs.customFont
        val font = when {
            choice.isBlank() -> ThemeFont(ThemeFontKind.STOCK, "", "")
            choice == "user" -> ThemeFont(ThemeFontKind.USER, "", prefs.fontUserName)
            else -> ThemeFont(ThemeFontKind.BUILTIN, choice, "")
        }
        return ThemeDoc(
            name = name,
            createdAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            colors = colors,
            wallpaper = ThemeWallpaper(
                hasImage = hasWallpaper,
                enabled = prefs.wallpaperEnabled,
                dim = prefs.wallpaperDim,
                blur = prefs.wallpaperBlur,
            ),
            bubbles = ThemeBubbles(
                incoming = BubbleStyles.assetPrefix(prefs.getOverride(Prefs.BUBBLE_STYLE_INCOMING)),
                outgoing = BubbleStyles.assetPrefix(prefs.getOverride(Prefs.BUBBLE_STYLE_OUTGOING)),
            ),
            flags = flags,
            font = font,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** The bytes to put in an archive: the public copy WhatsApp reads, else the private master behind it. */
    private fun wallpaperSource(context: Context, prefs: Prefs): File? {
        val path = prefs.wallpaperPath?.takeIf { it.isNotBlank() } ?: return null
        File(path).takeIf { it.canRead() }?.let { return it }
        return WallpaperAsset.masterFile(context).takeIf { it.canRead() }
    }

    /** Asset name back to the stored 1-based index; a name this version does not have falls back to stock and says so. */
    private fun resolveShape(asset: String?, problems: MutableList<String>): Int {
        if (asset.isNullOrBlank()) return 0
        val i = BubbleStyles.ALL.indexOfFirst { it.asset == asset }
        if (i < 0) {
            problems += "This version has no bubble shape from that theme, so those bubbles stay stock."
            return 0
        }
        return i + 1
    }

    /** The same test FontSwap makes inside WhatsApp, so a theme cannot name a face the hook would fail to find. */
    private fun hasBundledFont(context: Context, id: String): Boolean {
        if (id.isBlank()) return false
        return context.resources.getIdentifier("${id}_regular", "font", context.packageName) != 0
    }

    /** Stage through a .part so a half-written slot is never left where the list will read it. */
    private fun writeTo(dest: File, doc: ThemeDoc, wallpaper: File?): Boolean {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, "${dest.name}.part")
        val ok = runCatching { FileOutputStream(part).use { ThemeFile.write(it, doc, wallpaper) } }
            .getOrDefault(false)
        if (!ok) { part.delete(); return false }
        dest.delete()
        if (!part.renameTo(dest)) { part.delete(); return false }
        return true
    }

    private fun nextStamp(context: Context): Int =
        (dir(context).listFiles().orEmpty().mapNotNull { stampOf(it) }.maxOrNull() ?: 0) + 1

    private fun stampOf(f: File): Int? {
        if (!f.isFile || !f.name.endsWith(ThemeFile.EXTENSION)) return null
        return f.name.removeSuffix(ThemeFile.EXTENSION).toIntOrNull()
    }
}

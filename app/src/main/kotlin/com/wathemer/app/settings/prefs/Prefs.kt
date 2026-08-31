package com.wathemer.app.settings.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.wathemer.app.hooks.ColorSeeds
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences wrapper for every setting the module reads. Key names are a cross-process ABI: add, never rename.
 * Writes must stay synchronous commit(), never apply(): a save has to be durable before the setter returns.
 */
class Prefs(
    private val sp: SharedPreferences,
    private val context: Context,
    /** True when the framework redirected this store; false means writes land in a private file no hook reads, and the UI must say so. */
    val moduleStoreActive: Boolean = true,
) {

    /** What [reconcileFreshInstall] did. After a reinstall the file can belong to the old UID with no write bit, hence writeFailed. */
    data class InstallReconcile(
        val cleared: Int,
        val backup: String?,
        val writeFailed: Boolean,
        /** Framework store not visible this run; no marker written, so the check runs again next launch. */
        val deferred: Boolean = false,
    )

    /** One-shot clear of settings that outlive an uninstall: the framework store survives the package, the filesDir marker does not. Backs up to prefs_before_reset.json first. */
    fun reconcileFreshInstall(): InstallReconcile? {
        val marker = File(context.filesDir, "install.marker")
        if (marker.exists()) return null
        // Must defer with no marker while the store is invisible: a marker here suppresses the check forever.
        if (!moduleStoreActive) {
            return InstallReconcile(cleared = 0, backup = null, writeFailed = false, deferred = true)
        }
        val existing = runCatching { sp.all }.getOrNull().orEmpty()
        var backup: String? = null
        var writeFailed = false
        if (existing.isNotEmpty()) {
            backup = runCatching {
                val json = JSONObject()
                for ((k, v) in existing) {
                    // Typed: a restore that turns an Int colour into a String is worse than no backup.
                    val entry = JSONObject()
                    entry.put("type", v?.javaClass?.simpleName ?: "null")
                    entry.put("value", if (v is Set<*>) JSONArray(v.toList()) else v)
                    json.put(k, entry)
                }
                val f = File(context.filesDir, "prefs_before_reset.json")
                f.writeText(json.toString(2))
                f.absolutePath
            }.getOrNull()
            // commit(), not apply(): the return value is the only signal the file is not writable by this UID.
            writeFailed = !runCatching { sp.edit().clear().commit() }.getOrDefault(false)
        }
        // Marker only when the clear worked, or the unwritable-file case is suppressed forever.
        if (!writeFailed) runCatching { marker.writeText("1") }
        return InstallReconcile(existing.size, backup, writeFailed)
    }

    /** One-shot fix for removing mbwa_4: bubble-style prefs are 1-based BubbleStyles.ALL indexes, so any removal needs a migration like this. Defers while moduleStoreActive is false. */
    fun migrateRemovedBubbleStyle4(): Int? {
        if (!moduleStoreActive) return null
        if (sp.getBoolean(KEY_MIGRATED_BUBBLE_STYLE_4, false)) return 0
        var changed = 0
        for (key in listOf(BUBBLE_STYLE_INCOMING, BUBBLE_STYLE_OUTGOING)) {
            val old = sp.getInt(key, 0)
            val new = when {
                old == REMOVED_BUBBLE_STYLE_INDEX -> 0
                old > REMOVED_BUBBLE_STYLE_INDEX -> old - 1
                else -> old
            }
            if (new != old) { commitInt(key, new); changed++ }
        }
        runCatching { sp.edit().putBoolean(KEY_MIGRATED_BUBBLE_STYLE_4, true).commit() }
        return changed
    }

    // The three globals dodge other tokens' seeds on write, or the substitution rewrites them a second time.
    var primary: Int
        get() = sp.getInt(KEY_PRIMARY, DEFAULT_PRIMARY)
        set(value) { commitInt(KEY_PRIMARY, ColorSeeds.dodgeCollision(value, ColorSeeds.PRIMARY)) }

    var background: Int
        get() = sp.getInt(KEY_BACKGROUND, DEFAULT_BACKGROUND)
        set(value) { commitInt(KEY_BACKGROUND, ColorSeeds.dodgeCollision(value, ColorSeeds.BACKGROUND)) }

    var text: Int
        get() = sp.getInt(KEY_TEXT, DEFAULT_TEXT)
        set(value) { commitInt(KEY_TEXT, ColorSeeds.dodgeCollision(value, ColorSeeds.TEXT)) }

    var chatListDivider: Boolean
        get() = sp.getBoolean(KEY_CHATLIST_DIVIDER, false)
        set(value) { commitBoolean(KEY_CHATLIST_DIVIDER, value) }

    var effectSnow: Boolean
        get() = sp.getBoolean(KEY_EFFECT_SNOW, false)
        set(value) { commitBoolean(KEY_EFFECT_SNOW, value) }

    /** Repairs stores from builds that allowed a token equal to another token's seed; idempotent and cheap. */
    fun migrateCollidingTokens(): Int {
        var fixed = 0
        for ((key, own) in listOf(
            KEY_PRIMARY to ColorSeeds.PRIMARY,
            KEY_BACKGROUND to ColorSeeds.BACKGROUND,
            KEY_TEXT to ColorSeeds.TEXT,
        )) {
            if (!sp.contains(key)) continue
            val v = sp.getInt(key, 0)
            val d = ColorSeeds.dodgeCollision(v, own)
            if (d != v) {
                commitInt(key, d)
                fixed++
            }
        }
        return fixed
    }

    /** Global unread accent for the chatlist badge and the navbar BadgeDrawable. 0 = primary token. */
    var unreadAccent: Int
        get() = sp.getInt(KEY_UNREAD_ACCENT, 0)
        set(value) { commitInt(KEY_UNREAD_ACCENT, value) }

    /** Global unread count text colour for both badges. 0 = text token. */
    var unreadCountText: Int
        get() = sp.getInt(KEY_UNREAD_COUNT_TEXT, 0)
        set(value) { commitInt(KEY_UNREAD_COUNT_TEXT, value) }

    // ── Wallpaper ─────────────────────────────────────
    /** Wallpaper master toggle. Activation is sticky per process: turning this off mid-session leaves isWallpaperActive set until WhatsApp restarts. */
    var wallpaperEnabled: Boolean
        get() = sp.getBoolean(KEY_WALLPAPER_ENABLED, false)
        set(value) { commitBoolean(KEY_WALLPAPER_ENABLED, value) }

    /** Absolute path to the cropped wallpaper PNG. Null = no wallpaper picked. */
    var wallpaperPath: String?
        get() = sp.getString(KEY_WALLPAPER_PATH, null)
        set(value) { commitStringOrRemove(KEY_WALLPAPER_PATH, value) }

    /** Dim overlay alpha as percent (0..100). 0 = no overlay. */
    var wallpaperDim: Int
        get() = sp.getInt(KEY_WALLPAPER_DIM, 0)
        set(value) { commitInt(KEY_WALLPAPER_DIM, value.coerceIn(0, 100)) }

    /** RenderEffect blur radius in px (0..150). The ceiling is 150 because under glass this is the frost behind the chrome. */
    var wallpaperBlur: Int
        get() = sp.getInt(KEY_WALLPAPER_BLUR, 0)
        set(value) { commitInt(KEY_WALLPAPER_BLUR, value.coerceIn(0, 150)) }

    /** Liquid Glass master switch. Read once at hook-install time, so it only takes effect on the next WhatsApp start. */
    var glassEnabled: Boolean
        get() = sp.getBoolean(KEY_GLASS_ENABLED, false)
        set(value) { commitBoolean(KEY_GLASS_ENABLED, value) }

    /** Backdrop blur radius in dp (4..40). The screen blur behind every glass surface. */
    var glassBlur: Int
        get() = sp.getInt(KEY_GLASS_BLUR, GlassDefaults.BLUR)
        set(value) { commitInt(KEY_GLASS_BLUR, value.coerceIn(4, 40)) }

    /** Tint alpha 0..80. How much white the glass adds over what shows through. */
    var glassTint: Int
        get() = sp.getInt(KEY_GLASS_TINT, GlassDefaults.TINT)
        set(value) { commitInt(KEY_GLASS_TINT, value.coerceIn(0, 80)) }

    /** Refraction amplitude ceiling in dp (0..60). 0 disables the lensing at the edges. */
    var glassDisplace: Int
        get() = sp.getInt(KEY_GLASS_DISPLACE, GlassDefaults.DISPLACE)
        set(value) { commitInt(KEY_GLASS_DISPLACE, value.coerceIn(0, 60)) }

    /** Bevel band as a percent of the surface's smaller side (5..40). Capped by corner radius. */
    var glassBevel: Int
        get() = sp.getInt(KEY_GLASS_BEVEL, GlassDefaults.BEVEL)
        set(value) { commitInt(KEY_GLASS_BEVEL, value.coerceIn(5, 40)) }

    /** Card corner radius in dp (0..40). */
    var glassRadius: Int
        get() = sp.getInt(KEY_GLASS_RADIUS, GlassDefaults.RADIUS)
        set(value) { commitInt(KEY_GLASS_RADIUS, value.coerceIn(0, 40)) }

    /** Transmitted-backdrop gamma as a percent (30..100). Below 100 lifts the darks; 100 is off and reads flattest. */
    var glassGamma: Int
        get() = sp.getInt(KEY_GLASS_GAMMA, GlassDefaults.GAMMA)
        set(value) { commitInt(KEY_GLASS_GAMMA, value.coerceIn(30, 100)) }

    /** Rim highlight strength 0..100, the alpha of the edge stroke. 0 is off and draws nothing. */
    var glassRim: Int
        get() = sp.getInt(KEY_GLASS_RIM, GlassDefaults.RIM)
        set(value) { commitInt(KEY_GLASS_RIM, value.coerceIn(0, 100)) }

    /** Rim stroke width in dp (1..4). Thin is the point; wide stops reading as an edge. */
    var glassRimWidth: Int
        get() = sp.getInt(KEY_GLASS_RIM_WIDTH, GlassDefaults.RIM_WIDTH)
        set(value) { commitInt(KEY_GLASS_RIM_WIDTH, value.coerceIn(1, 4)) }

    /** Rim gradient angle in degrees (0..360); decides which side of every surface lights up. */
    var glassRimAngle: Int
        get() = sp.getInt(KEY_GLASS_RIM_ANGLE, GlassDefaults.RIM_ANGLE)
        set(value) { commitInt(KEY_GLASS_RIM_ANGLE, value.coerceIn(0, 360)) }

    /** Grouped-message merging on glass bubbles: continuations flatten the top corner on the sender's side. */
    var glassBubbleMerge: Boolean
        get() = sp.getBoolean(KEY_GLASS_BUBBLE_MERGE, GlassDefaults.BUBBLE_MERGE)
        set(value) { commitBoolean(KEY_GLASS_BUBBLE_MERGE, value) }

    /** Look for a new release when the settings app opens. Throttled to one request a day. */
    var updateAutoCheck: Boolean
        get() = sp.getBoolean(KEY_UPDATE_AUTO_CHECK, true)
        set(value) { commitBoolean(KEY_UPDATE_AUTO_CHECK, value) }

    /** Epoch millis of the last answered check; the throttle is the only reader. */
    var updateLastCheck: Long
        get() = sp.getLong(KEY_UPDATE_LAST_CHECK, 0L)
        set(value) { commitLong(KEY_UPDATE_LAST_CHECK, value) }

    /** The newest release seen, cached so the screen opens with an answer instead of a spinner. */
    var updateVersion: String
        get() = sp.getString(KEY_UPDATE_VERSION, "") ?: ""
        set(value) { commitStringOrRemove(KEY_UPDATE_VERSION, value.ifBlank { null }) }

    var updateNotes: String
        get() = sp.getString(KEY_UPDATE_NOTES, "") ?: ""
        set(value) { commitStringOrRemove(KEY_UPDATE_NOTES, value.ifBlank { null }) }

    var updateUrl: String
        get() = sp.getString(KEY_UPDATE_URL, "") ?: ""
        set(value) { commitStringOrRemove(KEY_UPDATE_URL, value.ifBlank { null }) }

    var updateAsset: String
        get() = sp.getString(KEY_UPDATE_ASSET, "") ?: ""
        set(value) { commitStringOrRemove(KEY_UPDATE_ASSET, value.ifBlank { null }) }

    /** App-wide iOS icon pack toggle. Glyphs are colour templates, so tint tokens still colour them. false = stock icons. */
    var iosIconPack: Boolean
        get() = sp.getBoolean(KEY_IOS_ICON_PACK, false)
        set(value) { commitBoolean(KEY_IOS_ICON_PACK, value) }

    /** Global font: "" = stock, a bundled id resolves to res/font/<id>_regular.ttf, "user" = user-supplied file. Structural, so it survives presets. */
    var customFont: String
        get() = sp.getString(KEY_CUSTOM_FONT, "") ?: ""
        set(value) { commitStringOrRemove(KEY_CUSTOM_FONT, value.ifBlank { null }) }

    /** Also remap the monospace family to the custom font. User-gated: it can affect fixed-width surfaces beyond the OTP field. */
    var fontMapMonospace: Boolean
        get() = sp.getBoolean(KEY_FONT_MAP_MONOSPACE, false)
        set(value) { commitBoolean(KEY_FONT_MAP_MONOSPACE, value) }

    /** Selected user font's filename inside the settings app's fonts dir. Blank = none selected. */
    var fontUserFile: String
        get() = sp.getString(KEY_FONT_USER_FILE, "") ?: ""
        set(value) { commitStringOrRemove(KEY_FONT_USER_FILE, value.ifBlank { null }) }

    /** Display name of the selected user font, parsed from its name table at import. */
    var fontUserName: String
        get() = sp.getString(KEY_FONT_USER_NAME, "") ?: ""
        set(value) { commitStringOrRemove(KEY_FONT_USER_NAME, value.ifBlank { null }) }

    /** Import stamp of the selected user font; WhatsApp keys its cached copy on it. */
    var fontUserStamp: Int
        get() = sp.getInt(KEY_FONT_USER_STAMP, 0)
        set(value) { commitInt(KEY_FONT_USER_STAMP, value) }

    /** JSON list of imported fonts; settings-side only, the hook reads the three keys above. */
    var fontUserLibrary: String
        get() = sp.getString(KEY_FONT_USER_LIBRARY, "") ?: ""
        set(value) { commitStringOrRemove(KEY_FONT_USER_LIBRARY, value.ifBlank { null }) }

    /** Monotonic import counter. Removal must never free a stamp: WhatsApp's cache file is named by it. */
    var fontUserSeq: Int
        get() = sp.getInt(KEY_FONT_USER_SEQ, 0)
        set(value) { commitInt(KEY_FONT_USER_SEQ, value) }

    /** Status-bar theming toggle. setStatusBarColor is a no-op on A15 at targetSdk 36, so the inset is painted directly. Mutually exclusive with wallpaper. */
    var systemBarsEnabled: Boolean
        get() = sp.getBoolean(KEY_SYSTEM_BARS_ENABLED, false)
        set(value) { commitBoolean(KEY_SYSTEM_BARS_ENABLED, value) }

    /** Auto-pick light/dark bar icons by luminance, via WindowInsetsController, the one bar API still alive on A15. Default on. */
    var systemBarAutoIcons: Boolean
        get() = sp.getBoolean(KEY_SYSTEM_BAR_AUTO_ICONS, true)
        set(value) { commitBoolean(KEY_SYSTEM_BAR_AUTO_ICONS, value) }

    /** True when the key is actually stored. A getter cannot tell you: it answers with a default, and an unset global means "substitute nothing". */
    fun isSet(key: String): Boolean = sp.contains(key)

    /** Override token by key; 0 means not overridden, which callers read as "use global". */
    fun getOverride(key: String): Int = sp.getInt(key, 0)

    /** Generic setter; 0 clears the override. Cross-process reads ride the framework's xposedsharedprefs redirect, not markWorldReadable. */
    fun setOverride(key: String, value: Int) {
        commitInt(key, value)
    }

    /** Comma-separated list of ARGB ints, most-recent first, capped at MAX_RECENTS. */
    var recents: List<Int>
        get() = sp.getString(KEY_RECENTS, null)
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()
        set(value) { commitString(KEY_RECENTS, value.joinToString(",")) }

    /** Push a new colour to the front of the recents list, dedupe, cap at MAX_RECENTS. */
    fun pushRecent(color: Int) {
        val next = (listOf(color) + recents.filter { it != color }).take(MAX_RECENTS)
        recents = next
    }

    /** Revert colours to stock by removing keys, never writing: any non-zero global re-arms the substitution. Structural prefs stay. */
    fun resetToStock() {
        val ok = sp.edit().apply {
            remove(KEY_PRIMARY); remove(KEY_BACKGROUND); remove(KEY_TEXT)
            remove(KEY_UNREAD_ACCENT); remove(KEY_UNREAD_COUNT_TEXT)
            ALL_COLOR_OVERRIDE_KEYS.forEach { remove(it) }
        }.commit()
        commitChecked("resetToStock", ok)
        markWorldReadable()
        if (ok) Log.i(TAG, "resetToStock: cleared globals + ${ALL_COLOR_OVERRIDE_KEYS.size} overrides")
    }

    /** True when the background token is actually set; [background] falls back to a default, so never compare against that. */
    fun hasBackgroundToken(): Boolean = sp.contains(KEY_BACKGROUND)

    /** True when at least one global token is set, i.e. the app-wide substitution is active. */
    fun hasGlobalTheme(): Boolean =
        sp.contains(KEY_PRIMARY) || sp.contains(KEY_BACKGROUND) || sp.contains(KEY_TEXT)

    /** Set the three globals and clear every ALL_COLOR_OVERRIDE_KEYS entry in one commit. Presets and Reset both funnel through here, so no colour token can go stale. */
    fun applyPresetFull(primary: Int, background: Int, text: Int) {
        val ok = sp.edit().apply {
            putInt(KEY_PRIMARY, primary)
            putInt(KEY_BACKGROUND, background)
            putInt(KEY_TEXT, text)
            remove(KEY_UNREAD_ACCENT)
            remove(KEY_UNREAD_COUNT_TEXT)
            ALL_COLOR_OVERRIDE_KEYS.forEach { remove(it) }
        }.commit()
        commitChecked("applyPresetFull", ok)
        markWorldReadable()
        if (ok) Log.i(TAG, "applyPresetFull: globals set + ${ALL_COLOR_OVERRIDE_KEYS.size} colour overrides cleared (primary=#%08x bg=#%08x text=#%08x)".format(primary, background, text))
    }

    /** Write a whole theme in one commit: [clear] is removed, then [values] written. Keys outside [THEME_WRITABLE_KEYS] are refused, so a shared file cannot reach any other setting. */
    fun applyThemeWrite(clear: Collection<String>, values: Map<String, Any>): Boolean {
        val rejected = (clear + values.keys).filterNot { it in THEME_WRITABLE_KEYS }
        if (rejected.isNotEmpty()) Log.w(TAG, "applyThemeWrite: refused ${rejected.size} key(s) outside the theme set: $rejected")
        val ok = sp.edit().apply {
            // Skip a key that is also being written, so the outcome cannot depend on editor ordering.
            clear.forEach { if (it in THEME_WRITABLE_KEYS && it !in values) remove(it) }
            for ((k, v) in values) {
                if (k !in THEME_WRITABLE_KEYS) continue
                when (v) {
                    is Int -> putInt(k, v)
                    is Boolean -> putBoolean(k, v)
                    is String -> putString(k, v)
                    // Dropping an unexpected type beats writing a wrong one under a key a hook reads.
                    else -> Log.w(TAG, "applyThemeWrite: $k carries ${v.javaClass.simpleName}, not a pref type")
                }
            }
        }.commit()
        commitChecked("applyThemeWrite", ok)
        markWorldReadable()
        if (ok) Log.i(TAG, "applyThemeWrite: cleared ${clear.size}, wrote ${values.size}")
        return ok
    }

    /**
     * Snapshots the store to filesDir so the next build can restore it. The framework store is
     * unreachable once this module stops being a legacy one, and filesDir survives an update.
     */
    fun exportForMigration(): Int? {
        // A snapshot of the private fallback would be empty and would overwrite a good one.
        if (!moduleStoreActive) return null
        return runCatching {
            val all = sp.all
            if (all.isEmpty()) return null
            val json = JSONObject()
            for ((k, v) in all) {
                // Same typed shape as prefs_before_reset.json, so one reader can take either file.
                val entry = JSONObject()
                entry.put("type", v?.javaClass?.simpleName ?: "null")
                entry.put("value", if (v is Set<*>) JSONArray(v.toList()) else v)
                json.put(k, entry)
            }
            val dest = File(context.filesDir, EXPORT_FILE)
            val part = File(context.filesDir, "$EXPORT_FILE.part")
            // Via .part: a kill mid-write must not leave a truncated file where a whole one was.
            part.writeText(json.toString())
            if (!part.renameTo(dest)) {
                dest.delete()
                check(part.renameTo(dest))
            }
            all.size
        }.onFailure { Log.w(TAG, "exportForMigration failed: $it") }.getOrNull()
    }

    /** Logs failed commits: after a reinstall the store can belong to the old UID and writes silently vanish. */
    private fun commitChecked(key: String, ok: Boolean) {
        if (!ok) Log.w(TAG, "commit FAILED for $key: the settings file is not writable by this UID")
    }

    private fun commitInt(key: String, value: Int) {
        commitChecked(key, sp.edit().putInt(key, value).commit())
        markWorldReadable()
        Log.i(TAG, "commitInt $key = #%08x -> ${storeDescription()}".format(value))
    }

    private fun commitLong(key: String, value: Long) {
        commitChecked(key, sp.edit().putLong(key, value).commit())
        markWorldReadable()
    }

    private fun commitString(key: String, value: String) {
        commitChecked(key, sp.edit().putString(key, value).commit())
        markWorldReadable()
    }

    private fun commitBoolean(key: String, value: Boolean) {
        commitChecked(key, sp.edit().putBoolean(key, value).commit())
        markWorldReadable()
        Log.i(TAG, "commitBoolean $key = $value")
    }

    private fun commitStringOrRemove(key: String, value: String?) {
        commitChecked(
            key,
            sp.edit().apply {
                if (value == null) remove(key) else putString(key, value)
            }.commit(),
        )
        markWorldReadable()
        Log.i(TAG, "commitStringOrRemove $key = $value")
    }

    private fun prefsFile(): File = File(File(context.filesDir.parent, "shared_prefs"), "$FILE.xml")

    /** Where a write actually landed. Never log [prefsFile] as the destination: it sits unused while the redirect is active. */
    private fun storeDescription(): String = if (moduleStoreActive) {
        "framework prefs store (redirected)"
    } else {
        val f = prefsFile()
        "${f.absolutePath} (${f.length()} bytes, PRIVATE, no hook reads this)"
    }

    /** Chmod the private prefs dir + file world-readable; only the legacy non-redirected path needs it. */
    private fun markWorldReadable() {
        // The framework's copy is not ours to re-permission; only the legacy private file needs this.
        if (moduleStoreActive) return
        try {
            val sharedDir = File(context.filesDir.parent, "shared_prefs")
            sharedDir.setReadable(true, false)
            sharedDir.setExecutable(true, false)
            val f = prefsFile()
            if (f.exists()) {
                f.setReadable(true, false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "markWorldReadable failed: $t")
        }
    }

    companion object {
        private const val TAG = "WaThemer.Prefs"

        const val FILE = "com.wathemer.app_prefs"

        /** Handover file for the move off the framework store; the next build reads it once and deletes it. */
        const val EXPORT_FILE = "prefs_export.json"

        const val KEY_CHATLIST_DIVIDER = "chatlist_divider"
        const val KEY_EFFECT_SNOW = "effect_snow"

        const val KEY_PRIMARY = "primary_color"
        const val KEY_BACKGROUND = "background_color"
        const val KEY_TEXT = "text_color"
        const val KEY_RECENTS = "recent_colors"

        // Global unread tokens, shared by chatlist + navbar.
        const val KEY_UNREAD_ACCENT = "unread_accent"
        const val KEY_UNREAD_COUNT_TEXT = "unread_count_text"

        // Per-element override keys, 0 = use global. Verify new ids against a runtime UI dump, not layout names.

        // Chat list
        const val OVR_CHATLIST_BG = "override_chatlist_bg"
        const val OVR_ROW_NAME = "override_row_name"
        const val OVR_ROW_PREVIEW = "override_row_preview"
        const val OVR_ROW_TIMESTAMP = "override_row_timestamp"

        // Search bar (chat list header pill)
        const val OVR_SEARCH_BAR_BG = "override_search_bar_bg"       // my_search_bar outer
        const val OVR_SEARCH_INNER_BG = "override_search_bar_inner_bg" // search_bar_inner_layout
        const val OVR_SEARCH_ICON = "override_search_icon"            // WDSIcon magnifier
        const val OVR_SEARCH_TEXT = "override_search_text"            // "Ask Meta AI or Search" text + hint

        // Tab bar (bottom nav)
        const val OVR_NAVBAR_BG = "override_navbar_bg"
        const val OVR_NAVBAR_DIVIDER = "override_navbar_divider"
        const val OVR_TAB_ACTIVE_PILL = "override_tab_active_pill"
        const val OVR_TAB_ICON = "override_tab_icon"
        const val OVR_TAB_ACTIVE_LABEL = "override_tab_active_label"
        const val OVR_TAB_INACTIVE_LABEL = "override_tab_inactive_label"
        // iOS icon pack toggle. Structural, not a colour override, so it survives preset switches.
        const val KEY_IOS_ICON_PACK = "ios_icon_pack"

        // Global font swap. Structural: excluded from ALL_COLOR_OVERRIDE_KEYS so it survives presets.
        const val KEY_CUSTOM_FONT = "custom_font"
        const val KEY_FONT_MAP_MONOSPACE = "font_map_monospace"
        const val KEY_FONT_USER_FILE = "font_user_file"
        const val KEY_FONT_USER_NAME = "font_user_name"
        const val KEY_FONT_USER_STAMP = "font_user_stamp"
        const val KEY_FONT_USER_LIBRARY = "font_user_library"
        const val KEY_FONT_USER_SEQ = "font_user_seq"

        // Status bar. The toggles are structural; OVR_STATUS_BAR_BG is the colour override.
        // The names still say "system_bars" on purpose: they are on-disk pref names, never rename them.
        const val KEY_SYSTEM_BARS_ENABLED = "system_bars_enabled"
        const val KEY_SYSTEM_BAR_AUTO_ICONS = "system_bar_auto_icons"
        const val OVR_STATUS_BAR_BG = "override_status_bar_bg"

        // Header (toolbar)
        const val OVR_TOOLBAR_BG = "override_toolbar_bg"
        const val OVR_TOOLBAR_ICONS = "override_toolbar_icons"
        const val OVR_WHATSAPP_LOGO = "override_whatsapp_logo"

        // FAB + Meta AI mini-fab. Do not add a mini_fab_icon key: tinting that icon never works.
        const val OVR_FAB_BG = "override_fab_bg"
        const val OVR_FAB_ICON = "override_fab_icon"
        const val OVR_MINI_FAB_BG = "override_mini_fab_bg"
        const val OVR_MINI_FAB_LABEL = "override_mini_fab_label"

        // Chat bubbles: bg + text + timestamp, per side.
        const val BUBBLE_LEFT_BG    = "bubble_left"        // incoming bubble bg
        const val BUBBLE_RIGHT_BG   = "bubble_right"       // outgoing bubble bg
        const val BUBBLE_LEFT_TEXT  = "bubble_left_text"   // incoming message text
        const val BUBBLE_RIGHT_TEXT = "bubble_right_text"  // outgoing message text
        const val BUBBLE_LEFT_DATE  = "bubble_left_date"   // incoming timestamp
        const val BUBBLE_RIGHT_DATE = "bubble_right_date"  // outgoing timestamp

        // Bubble shapes: 0 = stock, else a 1-based index into BubbleStyles.ALL. Do not restate the range; the registry owns it.
        const val BUBBLE_STYLE_INCOMING = "bubble_style_incoming"
        const val BUBBLE_STYLE_OUTGOING = "bubble_style_outgoing"

        /** The 1-based index mbwa_4 occupied before it was removed. See [migrateRemovedBubbleStyle4]. */
        private const val REMOVED_BUBBLE_STYLE_INDEX = 4
        private const val KEY_MIGRATED_BUBBLE_STYLE_4 = "migrated_bubble_style_4"

        // Compose/input bar tokens. One shared side-icon tint on purpose: WA itself uses a single attr for them all.
        const val COMPOSE_BAR_BG      = "compose_bar_bg"
        const val COMPOSE_ENTRY_TEXT  = "compose_entry_text"
        const val COMPOSE_SEND_BG     = "compose_send_bg"   // send/mic FAB background
        const val COMPOSE_SEND_ICON   = "compose_send_icon" // mic->send icon swap (voice_note_btn + send)
        const val COMPOSE_ICON_TINT   = "compose_icon_tint" // emoji/attach/camera/payment (side icons only)

        // Quote/reply tokens. QUOTE_BG_COLOR != 0 also arms the quoted_message_frame NinePatch leak kill.
        const val QUOTE_BAR_COLOR       = "quote_bar_color"
        const val QUOTE_BG_COLOR        = "quote_bg_color"
        const val QUOTE_TEXT_COLOR      = "quote_text_color"
        // Misc: the "Other misc settings" Chat screen (5 tokens).
        const val TICK_SEEN_COLOR       = "tick_seen_color"       // blue read ✓✓
        const val TICK_UNSEEN_COLOR     = "tick_unseen_color"     // gray sent ✓ + delivered ✓✓
        const val FORWARDED_LABEL_COLOR = "forwarded_label_color" // conversation_row_top_text_attribute
        const val MEDIA_CAPTION_COLOR   = "media_caption_color"   // caption (image/video bubbles)
        const val LINK_COLOR            = "link_color"            // clickable links, via the span base's updateDrawState hook

        // Conversation header title + subtitle; these two exist only in Conversation.
        const val CHAT_HEADER_TITLE    = "chat_header_title"    // conversation_contact_name (TextEmojiLabel)
        const val CHAT_HEADER_SUBTITLE = "chat_header_subtitle" // conversation_contact_status (TextEmojiLabel)

        // Toolbar tokens split per activity: OVR_TOOLBAR_* is Home (+ContactInfo fallback), CHAT_TOOLBAR_* is Conversation only.
        const val CHAT_TOOLBAR_BG    = "chat_toolbar_bg"     // Conversation toolbar bg
        const val CHAT_TOOLBAR_ICONS = "chat_toolbar_icons"  // Conversation toolbar icons

        // Long-press selection toolbar; the same action_mode_bar id serves Home and Conversation.
        const val OVR_ACTION_MODE_BG           = "override_action_mode_bg"           // action_mode_bar (ActionBarContextView) bg
        const val OVR_ACTION_MODE_ICONS        = "override_action_mode_icons"        // close X + ActionMenuItemView icons
        const val OVR_ACTION_MODE_TITLE        = "override_action_mode_title"        // action_bar_title (the "1"/"5"/"6" count)
        const val OVR_ACTION_MODE_CLOSE_RIPPLE = "override_action_mode_close_ripple" // close button's check-state ripple (WA green by default)

        // ── Wallpaper ─────────────────────────────────
        // Asset lives at /sdcard/Download/WaThemer/wallpaper.png; the MediaScanner scan after save is mandatory for A13+ cross-UID resolution.
        const val KEY_WALLPAPER_ENABLED = "wallpaper_enabled"
        const val KEY_WALLPAPER_PATH    = "wallpaper_path"
        const val KEY_WALLPAPER_DIM     = "wallpaper_dim"
        const val KEY_WALLPAPER_BLUR    = "wallpaper_blur"

        // Liquid Glass
        const val KEY_GLASS_ENABLED  = "glass_enabled"
        const val KEY_GLASS_BLUR     = "glass_blur"
        const val KEY_GLASS_TINT     = "glass_tint"
        const val KEY_GLASS_DISPLACE = "glass_displace"
        const val KEY_GLASS_BEVEL    = "glass_bevel"
        const val KEY_GLASS_RADIUS   = "glass_radius"
        const val KEY_GLASS_GAMMA    = "glass_gamma"
        const val KEY_GLASS_RIM      = "glass_rim"
        const val KEY_GLASS_RIM_WIDTH = "glass_rim_width"
        const val KEY_GLASS_RIM_ANGLE = "glass_rim_angle"
        const val KEY_GLASS_BUBBLE_MERGE = "glass_bubble_merge"

        // ── Updates ───────────────────────────────────
        // Settings-app only; the hook never reads these and a theme file can never write them.
        const val KEY_UPDATE_AUTO_CHECK = "update_auto_check"
        const val KEY_UPDATE_LAST_CHECK = "update_last_check"
        const val KEY_UPDATE_VERSION    = "update_version"
        const val KEY_UPDATE_NOTES      = "update_notes"
        const val KEY_UPDATE_URL        = "update_url"
        const val KEY_UPDATE_ASSET      = "update_asset"

        /** Every colour override key, the one list [applyPresetFull] clears. Every new colour key must be added here; structural keys stay out so presets keep them. */
        val ALL_COLOR_OVERRIDE_KEYS: List<String> = listOf(
            // Chat list
            OVR_CHATLIST_BG, OVR_ROW_NAME, OVR_ROW_PREVIEW, OVR_ROW_TIMESTAMP,
            // Search bar
            OVR_SEARCH_BAR_BG, OVR_SEARCH_INNER_BG, OVR_SEARCH_ICON, OVR_SEARCH_TEXT,
            // Tab bar
            OVR_NAVBAR_BG, OVR_NAVBAR_DIVIDER, OVR_TAB_ACTIVE_PILL, OVR_TAB_ICON,
            OVR_TAB_ACTIVE_LABEL, OVR_TAB_INACTIVE_LABEL,
            // Header (home toolbar)
            OVR_TOOLBAR_BG, OVR_TOOLBAR_ICONS, OVR_WHATSAPP_LOGO,
            // FAB + mini-fab
            OVR_FAB_BG, OVR_FAB_ICON, OVR_MINI_FAB_BG, OVR_MINI_FAB_LABEL,
            // Bubbles (colours only, shape excluded by design)
            BUBBLE_LEFT_BG, BUBBLE_RIGHT_BG, BUBBLE_LEFT_TEXT, BUBBLE_RIGHT_TEXT,
            BUBBLE_LEFT_DATE, BUBBLE_RIGHT_DATE,
            // Compose / input bar
            COMPOSE_BAR_BG, COMPOSE_ENTRY_TEXT,
            COMPOSE_SEND_BG, COMPOSE_SEND_ICON, COMPOSE_ICON_TINT,
            // Chat header + chat toolbar
            CHAT_HEADER_TITLE, CHAT_HEADER_SUBTITLE, CHAT_TOOLBAR_BG, CHAT_TOOLBAR_ICONS,
            // Selection / action mode
            OVR_ACTION_MODE_BG, OVR_ACTION_MODE_ICONS, OVR_ACTION_MODE_TITLE,
            OVR_ACTION_MODE_CLOSE_RIPPLE,
            // Quote / reply
            QUOTE_BAR_COLOR, QUOTE_BG_COLOR, QUOTE_TEXT_COLOR,
            // Misc
            TICK_SEEN_COLOR, TICK_UNSEEN_COLOR, FORWARDED_LABEL_COLOR,
            MEDIA_CAPTION_COLOR, LINK_COLOR,
            // System bars (colours only, enabled / AUTO_ICONS toggles excluded by design)
            OVR_STATUS_BAR_BG,
        )

        /** The colour keys a theme file carries. Derived from the override list, so a new colour key joins it by itself. */
        val THEME_COLOR_KEYS: List<String> =
            listOf(KEY_PRIMARY, KEY_BACKGROUND, KEY_TEXT, KEY_UNREAD_ACCENT, KEY_UNREAD_COUNT_TEXT) +
                ALL_COLOR_OVERRIDE_KEYS

        /** The on/off keys a theme file carries. Glass is out by choice, and so is anything true of one device only. */
        val THEME_FLAG_KEYS: List<String> = listOf(
            KEY_IOS_ICON_PACK, KEY_CHATLIST_DIVIDER, KEY_EFFECT_SNOW,
            KEY_SYSTEM_BARS_ENABLED, KEY_SYSTEM_BAR_AUTO_ICONS, KEY_FONT_MAP_MONOSPACE,
        )

        /** Every key an imported theme may touch. A key missing here is unreachable from a theme file, which is the whole guarantee. */
        val THEME_WRITABLE_KEYS: Set<String> = (
            THEME_COLOR_KEYS + THEME_FLAG_KEYS + listOf(
                BUBBLE_STYLE_INCOMING, BUBBLE_STYLE_OUTGOING,
                KEY_WALLPAPER_ENABLED, KEY_WALLPAPER_PATH, KEY_WALLPAPER_DIM, KEY_WALLPAPER_BLUR,
                KEY_CUSTOM_FONT, KEY_FONT_USER_FILE, KEY_FONT_USER_NAME, KEY_FONT_USER_STAMP,
                // Here only so a theme with no wallpaper can switch glass off; no theme file ever names it.
                KEY_GLASS_ENABLED,
            )
            ).toSet()

        /** WA's own dark-mode colours, mostly UI display fallbacks. Not settings-only: SystemBars and WallpaperImage read them hook-side, so grep before repurposing. */
        const val DEFAULT_PRIMARY = 0xFF00A884.toInt()      // WA standard tint (teal-green)
        const val DEFAULT_BACKGROUND = 0xFF0B141A.toInt()   // WA dark surface
        const val DEFAULT_TEXT = 0xFFE9EDEF.toInt()         // WA primary text

        const val MAX_RECENTS = 12

        /** MODE_WORLD_READABLE is the LSPosed handshake; the framework redirects the store to where hooks read it. SecurityException = module not active, private fallback. */
        @Suppress("DEPRECATION", "WorldReadableFiles")
        fun open(context: Context): Prefs {
            var active = true
            val sp = try {
                context.getSharedPreferences(FILE, Context.MODE_WORLD_READABLE)
            } catch (e: SecurityException) {
                // From here on writes land in a private file no hooked process reads.
                Log.w(TAG, "MODE_WORLD_READABLE rejected; module not active? Falling back to MODE_PRIVATE: $e")
                active = false
                context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            }
            return Prefs(sp, context, moduleStoreActive = active)
        }
    }
}

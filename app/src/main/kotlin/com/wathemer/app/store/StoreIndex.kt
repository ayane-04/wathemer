// The store catalogue, schema 1: the publish tool checks these rules before signing, and the app holds it to them again.
package com.wathemer.app.store

import com.wathemer.app.settings.prefs.ThemeFile
import org.json.JSONArray
import org.json.JSONObject

/** A file on the store's site, fetched only from this path and kept only when it matches [sha256]. */
data class StoreAsset(val path: String, val sha256: String)

data class StoreCategory(val id: String, val name: String)

data class StoreTheme(
    val id: String,
    val version: Int,
    val name: String,
    val author: String,
    val description: String,
    val category: String,
    val added: String,
    val updated: String,
    val format: Int,
    val minApp: Int,
    val carries: Set<String>,
    val tags: List<String>,
    val fileUrl: String,
    val fileSize: Long,
    val fileSha256: String,
    val thumb: StoreAsset,
    val shots: List<StoreAsset>,
    val previewHeader: StoreAsset?,
    val previewWallpaper: StoreAsset?,
    /** Listed either way; false when this build cannot read the file, so the store offers no Get. */
    val usable: Boolean,
)

data class StoreIndex(
    val store: String,
    val serial: Int,
    val generated: String,
    val expires: String,
    val revokeKeys: Set<String>,
    val categories: List<StoreCategory>,
    val themes: List<StoreTheme>,
) {

    sealed interface Parsed {
        data class Ok(val index: StoreIndex) : Parsed
        /** Signed, but in a schema this build cannot read: the app needs updating, not the store. */
        data object NewerSchema : Parsed
        data class Refused(val reason: String) : Parsed
    }

    companion object {
        const val SCHEMA = 1
        const val MAX_BYTES = 4 * 1024 * 1024

        val CARRIES = setOf("wallpaper", "font", "glass")

        private const val MAX_THEMES = 2000
        private const val MAX_FILE_BYTES = 80 * 1024 * 1024
        private const val MAX_SHOTS = 8
        private const val MAX_TAGS = 8
        private const val MAX_CATEGORIES = 8
        private const val MAX_REVOKED = 8
        private const val NAME_MAX = 48
        private const val AUTHOR_MAX = 40
        private const val DESCRIPTION_MAX = 600
        private const val CATEGORY_NAME_MAX = 24

        private val SHORT_ID = Regex("^[a-z0-9][a-z0-9-]{0,23}$")
        private val KEY_ID = Regex("^[0-9a-f]{8}$")
        private val HEX64 = Regex("^[0-9a-f]{64}$")
        private val DATE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
        private val STAMP = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")

        /** The whole catalogue is refused when its frame is wrong; one bad theme is only left out. */
        fun parse(payload: ByteArray, target: StoreTarget): Parsed {
            if (payload.size > MAX_BYTES) return Parsed.Refused("larger than a catalogue may be")
            val root = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()
                ?: return Parsed.Refused("not a catalogue")
            // Typed reads throughout: org.json would turn the text "3" into the number 3.
            val schema = root.opt("schema") as? Int ?: return Parsed.Refused("no schema")
            if (schema > SCHEMA) return Parsed.NewerSchema
            if (schema != SCHEMA) return Parsed.Refused("schema $schema")
            if (root.opt("store") != target.storeId) return Parsed.Refused("a catalogue for another store")
            val serial = (root.opt("serial") as? Int)?.takeIf { it >= 1 } ?: return Parsed.Refused("no serial")
            val revoked = keyIds(root.opt("revokeKeys")) ?: return Parsed.Refused("a bad list of revoked keys")
            val categories = categories(root.opt("categories") as? JSONArray)
            if (categories.isEmpty()) return Parsed.Refused("no categories")
            val list = root.opt("themes") as? JSONArray ?: return Parsed.Refused("no theme list")
            val categoryIds = categories.mapTo(HashSet()) { it.id }
            val seen = HashSet<String>()
            val themes = ArrayList<StoreTheme>()
            for (i in 0 until minOf(list.length(), MAX_THEMES)) {
                val o = list.opt(i) as? JSONObject ?: continue
                val id = o.opt("id") as? String ?: continue
                // The first entry under an id holds it, even when it is the one left out.
                if (!ThemeFile.STORE_ID.matches(id) || !seen.add(id)) continue
                theme(o, id, categoryIds, target)?.let { themes += it }
            }
            val generated = (root.opt("generated") as? String)?.takeIf { STAMP.matches(it) }.orEmpty()
            return Parsed.Ok(StoreIndex(target.storeId, serial, generated, date(root.opt("expires")), revoked, categories, themes))
        }

        private fun theme(o: JSONObject, id: String, categoryIds: Set<String>, target: StoreTarget): StoreTheme? {
            val version = (o.opt("version") as? Int)?.takeIf { it in 1..1_000_000 } ?: return null
            val name = text(o.opt("name"), NAME_MAX).takeIf { it.isNotEmpty() } ?: return null
            val author = text(o.opt("author"), AUTHOR_MAX).takeIf { it.isNotEmpty() } ?: return null
            val category = (o.opt("category") as? String)?.takeIf { it in categoryIds } ?: return null
            val format = (o.opt("format") as? Int)?.takeIf { it >= 1 } ?: return null
            val minApp = (o.opt("minApp") as? Int)?.takeIf { it >= 1 } ?: return null
            val file = o.opt("file") as? JSONObject ?: return null
            // Rebuilt here and compared whole: the catalogue cannot point a download anywhere else.
            val url = "${target.releases}$id-v$version/$id-v$version.wathemer"
            if (file.opt("url") != url) return null
            val size = (file.opt("size") as? Int)?.takeIf { it in 1..MAX_FILE_BYTES } ?: return null
            val sha = hex64(file.opt("sha256")) ?: return null
            val base = "themes/$id/$version/"
            val thumb = asset(o.opt("thumb"), base + "thumb.webp") ?: return null
            val shotList = o.opt("shots") as? JSONArray
            val shots = (0 until minOf(shotList?.length() ?: 0, MAX_SHOTS)).mapNotNull { n ->
                asset(shotList?.opt(n), base + "shot-${n + 1}.webp")
            }
            val preview = o.opt("preview") as? JSONObject
            return StoreTheme(
                id = id,
                version = version,
                name = name,
                author = author,
                description = text(o.opt("description"), DESCRIPTION_MAX),
                category = category,
                added = date(o.opt("added")),
                updated = date(o.opt("updated")),
                format = format,
                minApp = minApp,
                // A value a later store adds is skipped, not a reason to hide the theme.
                carries = strings(o.opt("carries")).filterTo(LinkedHashSet()) { it in CARRIES },
                tags = strings(o.opt("tags")).filter { SHORT_ID.matches(it) }.distinct().take(MAX_TAGS),
                fileUrl = url,
                fileSize = size.toLong(),
                fileSha256 = sha,
                thumb = thumb,
                shots = shots,
                previewHeader = asset(preview?.opt("header"), base + "theme.json"),
                previewWallpaper = asset(preview?.opt("wallpaper"), base + "wallpaper.webp"),
                usable = format <= target.readerFormat && minApp <= target.versionCode,
            )
        }

        private fun categories(list: JSONArray?): List<StoreCategory> {
            val out = ArrayList<StoreCategory>()
            for (i in 0 until (list?.length() ?: 0)) {
                if (out.size == MAX_CATEGORIES) break
                val o = list?.opt(i) as? JSONObject ?: continue
                val id = o.opt("id") as? String ?: continue
                if (!SHORT_ID.matches(id) || out.any { it.id == id }) continue
                val name = text(o.opt("name"), CATEGORY_NAME_MAX)
                if (name.isNotEmpty()) out += StoreCategory(id, name)
            }
            return out
        }

        private fun keyIds(value: Any?): Set<String>? {
            val list = value as? JSONArray ?: return null
            if (list.length() > MAX_REVOKED) return null
            val out = LinkedHashSet<String>()
            for (i in 0 until list.length()) {
                val id = list.opt(i) as? String ?: return null
                if (!KEY_ID.matches(id)) return null
                out += id
            }
            return out
        }

        private fun asset(value: Any?, path: String): StoreAsset? {
            val o = value as? JSONObject ?: return null
            if (o.opt("path") != path) return null
            return hex64(o.opt("sha256"))?.let { StoreAsset(path, it) }
        }

        private fun strings(value: Any?): List<String> {
            val list = value as? JSONArray ?: return emptyList()
            return (0 until list.length()).mapNotNull { list.opt(it) as? String }
        }

        private fun text(value: Any?, limit: Int): String = (value as? String)?.let { cleanText(it, limit) }.orEmpty()

        private fun date(value: Any?): String = (value as? String)?.takeIf { DATE.matches(it) }.orEmpty()

        private fun hex64(value: Any?): String? = (value as? String)?.takeIf { HEX64.matches(it) }
    }
}

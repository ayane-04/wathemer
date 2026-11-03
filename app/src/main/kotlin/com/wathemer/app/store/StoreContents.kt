// What a store theme carries, as short items for its page.
package com.wathemer.app.store

import com.wathemer.app.settings.prefs.BubbleStyles
import com.wathemer.app.settings.prefs.Prefs
import com.wathemer.app.settings.prefs.ThemeDoc
import com.wathemer.app.settings.prefs.ThemeFontKind
import com.wathemer.app.settings.prefs.TickStyles

/** The switches worth naming, with the app's own labels; monospace and auto bar icons are left out as noise. */
private val FLAG_ITEMS = listOf(
    Prefs.KEY_IOS_ICON_PACK to "iOS icon pack",
    Prefs.KEY_EFFECT_SNOW to "Snowfall",
    Prefs.KEY_CHATLIST_DIVIDER to "Row dividers",
    Prefs.KEY_SYSTEM_BARS_ENABLED to "Status bar",
)

/** What a store theme brings, as short items: from its header once fetched, else from the catalogue's marks. */
fun storeContents(doc: ThemeDoc?, carries: Set<String>): List<String> {
    if (doc == null) {
        return listOfNotNull(
            "Wallpaper".takeIf { "wallpaper" in carries },
            "Font".takeIf { "font" in carries },
            "Liquid Glass".takeIf { "glass" in carries },
        )
    }
    val items = ArrayList<String>()
    if (!doc.colors.isNullOrEmpty()) items += "Colours"
    if (doc.wallpaper?.hasImage == true) items += "Wallpaper"
    if (doc.glass?.enabled == true) items += "Liquid Glass"
    doc.font?.let { f ->
        when (f.kind) {
            ThemeFontKind.BUILTIN -> if (f.id.isNotBlank()) items += "Font: " + f.id.replaceFirstChar { it.uppercase() }
            ThemeFontKind.USER -> items += if (f.name.isNotBlank()) "Font: ${f.name}" else "Font"
            ThemeFontKind.STOCK -> Unit
        }
    }
    doc.bubbles?.let { b ->
        val incoming = shapeLabel(b.incoming)
        val outgoing = shapeLabel(b.outgoing)
        when {
            incoming == null && outgoing == null -> Unit
            incoming == null || outgoing == null || incoming == outgoing -> items += "Bubbles: ${incoming ?: outgoing}"
            else -> items += "Bubbles: $incoming / $outgoing"
        }
    }
    TickStyles.styleOf(doc.ticks?.style).takeIf { it > 0 }?.let { items += "Ticks: " + TickStyles.NAMES[it] }
    for ((key, label) in FLAG_ITEMS) if (doc.flags[key] == true) items += label
    return items
}

private fun shapeLabel(asset: String?): String? =
    asset?.let { a -> BubbleStyles.ALL.firstOrNull { it.asset == a }?.label }

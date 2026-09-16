package com.wathemer.app.settings.prefs

/** Registry of the receipt tick styles, stored as a 1-based index with 0 for stock; never renumber, the stored value is the index and docs/TICK-STYLES-SOURCES.md keeps each number's provenance. */
object TickStyles {

    /** How many `tick_NN` sets ship; the import script prints it. */
    const val COUNT = 89

    /** The four receipt states WhatsApp draws; [asset] is the name segment after the prefix. */
    enum class State(val asset: String) { SENT("sent"), DELIVERED("delivered"), READ("read"), PENDING("pending") }

    /** Tallest a tick draws in a message row; larger art is scaled down, smaller art keeps its size. */
    const val MAX_HEIGHT_DP = 16f

    /** On-image art carries its own shadow and runs larger, so it gets more room. */
    const val MAX_MEDIA_HEIGHT_DP = 20f

    /** Asset filename prefix for a stored pref value, or null for off/out-of-range. Digits stay ASCII whatever the locale. */
    fun assetPrefix(styleValue: Int): String? =
        if (styleValue in 1..COUNT) "tick_" + styleValue.toString().padStart(2, '0') else null

    /** `<prefix>_<state>[_media]`, or null when the style is off. */
    fun assetName(styleValue: Int, state: State, media: Boolean): String? =
        assetPrefix(styleValue)?.let { "${it}_${state.asset}${if (media) "_media" else ""}" }

    /** Stored value for the prefix a theme file carries, or 0 when this version has no such style. */
    fun styleOf(prefix: String?): Int {
        if (prefix.isNullOrBlank()) return 0
        return (1..COUNT).firstOrNull { assetPrefix(it) == prefix } ?: 0
    }

    /** Picker labels, index-aligned with the stored pref value (entry 0 = off). */
    val NAMES: List<String> = listOf("Off (stock)") + (1..COUNT).map { "Style $it" }
}

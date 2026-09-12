package com.wathemer.app.settings.prefs

/** Ordered registry of every selectable bubble style, stored as a 1-based index into [ALL] with 0 for stock; read docs/ENGINEERING-NOTES.md before adding or removing one, since imports corrupt assets silently and a removal renumbers stored selections. */
object BubbleStyles {

    data class Style(val asset: String, val label: String)

    val ALL: List<Style> = listOf(
        // ── Plain masks: tinted with your bubble colour ──
        Style("mbwa_0", "Style 1"),
        Style("mbwa_2", "Style 2"),
        Style("mbwa_3", "Style 3"),
        Style("mbwa_5", "Style 4"),
        Style("mbwa_6", "Style 5"),
        Style("mbwa_7", "Style 6"),
        Style("mbwa_8", "Style 7"),
        Style("mbwa_9", "Style 8"),
        Style("mbwa_10", "Style 9"),
        Style("mbwa_11", "Style 10"),
        Style("aranbor", "Style 11"),
        Style("crmessenger", "Style 12"),
        Style("cvios", "Style 13"),
        Style("eclip", "Style 14"),
        Style("fmios_bbm", "Style 15"),
        Style("fmios_bdrop", "Style 16"),
        Style("fmios_chaton", "Style 17"),
        Style("fmios_dual", "Style 18"),
        Style("fmios_gabisqua", "Style 19"),
        Style("fmios_gosms", "Style 20"),
        Style("fmios_mood", "Style 21"),
        Style("fmios_trans", "Style 22"),
        Style("fmios_wapaper", "Style 23"),
        Style("foldv2", "Style 24"),
        Style("gabisqua", "Style 25"),
        Style("ilkhang", "Style 26"),
        Style("mood", "Style 27"),
        Style("popzup", "Style 28"),
        Style("rcburbuja2", "Style 29"),
        Style("rcburbuja5", "Style 30"),
        Style("rcfancy", "Style 31"),
        Style("rcimline", "Style 32"),
        Style("rciosline", "Style 33"),
        Style("rcline", "Style 34"),
        Style("trans", "Style 35"),
        Style("win", "Style 36"),
        // ── Colour artwork: drawn as-is, your bubble colour is ignored ──
        Style("altcr", "Style 37"),
        Style("fold", "Style 38"),
        Style("twitter", "Style 39"),
        Style("mbwa_1", "Style 40"),
        Style("s3d2", "Style 41"),
        Style("apple", "Style 42"),
        Style("bbm", "Style 43"),
        Style("bdrop", "Style 44"),
        Style("bolha", "Style 45"),
        Style("chaton", "Style 46"),
        Style("dual", "Style 47"),
        Style("ed", "Style 48"),
        Style("fbm", "Style 49"),
        Style("gosms", "Style 50"),
        Style("hangouts", "Style 51"),
        Style("kitty", "Style 52"),
        Style("materialized", "Style 53"),
        Style("md", "Style 54"),
        Style("rounded", "Style 55"),
        Style("wapaper", "Style 56"),
    )

    /** Mask tint when a shape is picked but no bubble colour is: WA's own dark bubble colours. Both processes read them, so they live here. */
    const val STOCK_DARK_INCOMING = 0xFF202C33.toInt()
    const val STOCK_DARK_OUTGOING = 0xFF005C4B.toInt()

    /** Asset filename prefix for a stored pref value, or null for off/out-of-range. */
    fun assetPrefix(styleValue: Int): String? =
        if (styleValue <= 0) null else ALL.getOrNull(styleValue - 1)?.asset

    /** Picker labels, index-aligned with the stored pref value (entry 0 = off). */
    val NAMES: List<String> = listOf("Off (stock)") + ALL.map { it.label }
}

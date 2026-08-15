package com.wathemer.app.util

/** Int ARGB to "#aarrggbb" helpers; lowercase hex to match [com.wathemer.app.hooks.ColorMap] keys. */
object HexColor {

    /** Format an ARGB int as `#aarrggbb` (lowercase, always 9 chars). */
    fun toHex(argb: Int): String = "#%08x".format(argb)

    /** Parse `#aarrggbb`, `#rrggbb`, `#argb`, or `#rgb`. Returns 0 if unparseable. */
    fun fromHex(s: String): Int {
        val h = s.trim().removePrefix("#")
        return runCatching {
            when (h.length) {
                8 -> h.toLong(16).toInt()
                6 -> (0xff000000L or h.toLong(16)).toInt()
                4 -> expand4(h)
                3 -> (0xff000000L or expand3(h)).toInt()
                else -> 0
            }
        }.getOrDefault(0)
    }

    /** "argb" -> 0xaarrggbb. */
    private fun expand4(h: String): Int {
        val a = h[0].digitToInt(16) * 0x11
        val r = h[1].digitToInt(16) * 0x11
        val g = h[2].digitToInt(16) * 0x11
        val b = h[3].digitToInt(16) * 0x11
        return ((a shl 24) or (r shl 16) or (g shl 8) or b)
    }

    /** "rgb" -> 0xrrggbb (alpha added by caller). */
    private fun expand3(h: String): Long {
        val r = h[0].digitToInt(16) * 0x11
        val g = h[1].digitToInt(16) * 0x11
        val b = h[2].digitToInt(16) * 0x11
        return ((r shl 16) or (g shl 8) or b).toLong()
    }
}

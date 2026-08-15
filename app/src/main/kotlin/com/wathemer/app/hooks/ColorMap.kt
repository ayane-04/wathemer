package com.wathemer.app.hooks

import de.robv.android.xposed.XposedBridge

/** Substitution map keyed on 24-bit RGB so one seed catches every alpha variant; lookup re-applies the original alpha. */
object ColorMap {

    private const val TAG = "WaThemer"

    /** 24-bit RGB key -> 24-bit RGB value substitution. */
    @Volatile private var rgbMap: Map<Int, Int> = emptyMap()

    /** A token of 0 contributes nothing; that per-token opt-in is what lets one element be themed without repainting the app. */
    fun rebuild(primaryArgb: Int, backgroundArgb: Int, textArgb: Int) {
        // Dodge before seeding: a stale store can still carry a colliding token until the settings app migrates it.
        val p = if (primaryArgb != 0) ColorSeeds.dodgeCollision(primaryArgb, ColorSeeds.PRIMARY) else 0
        val b = if (backgroundArgb != 0) ColorSeeds.dodgeCollision(backgroundArgb, ColorSeeds.BACKGROUND) else 0
        val t = if (textArgb != 0) ColorSeeds.dodgeCollision(textArgb, ColorSeeds.TEXT) else 0
        val m = HashMap<Int, Int>(
            ColorSeeds.PRIMARY.size + ColorSeeds.BACKGROUND.size + ColorSeeds.TEXT.size
        )
        if (p != 0) ColorSeeds.PRIMARY.forEach { m[it] = p and 0xFFFFFF }
        if (b != 0) ColorSeeds.BACKGROUND.forEach { m[it] = b and 0xFFFFFF }
        if (t != 0) ColorSeeds.TEXT.forEach { m[it] = t and 0xFFFFFF }
        rgbMap = m

        XposedBridge.log(
            "[$TAG] ColorMap rebuilt: primary=%s bg=%s text=%s -> size=${m.size}".format(
                if (p != 0) "#%06x".format(p and 0xFFFFFF) else "unset",
                if (b != 0) "#%06x".format(b and 0xFFFFFF) else "unset",
                if (t != 0) "#%06x".format(t and 0xFFFFFF) else "unset",
            )
        )
        warnOnTokenCollision(m)
    }

    /** Tripwire only: the dodge upstream should make this unreachable, and dropping a key would gut the text token. */
    private fun warnOnTokenCollision(m: Map<Int, Int>) {
        val collisions = m.values.toSet().filter { m.containsKey(it) && m[it] != it }
        if (collisions.isEmpty()) return
        XposedBridge.log(
            "[$TAG] TOKEN COLLISION: " + collisions.joinToString { "#%06x -> #%06x".format(it, m[it]) } +
                ". One token's colour is a substitution seed for another, so surfaces can be " +
                "rewritten twice and land on the wrong token. Pick a value that is not a stock " +
                "WhatsApp colour for one of them (pure white and pure black are both seeds)."
        )
    }

    /** True when no global token is set, i.e. nothing should be substituted at all. */
    fun isEmpty(): Boolean = rgbMap.isEmpty()

    /** Replacement for [argb] with the original alpha preserved; unchanged when the RGB is not a seed. */
    fun substitute(argb: Int): Int {
        val map = rgbMap
        if (map.isEmpty()) return argb
        val rgb = argb and 0xFFFFFF
        val newRgb = map[rgb] ?: return argb
        val alphaMask = argb and 0xFF000000.toInt()  // preserve original alpha
        return alphaMask or newRgb
    }

    fun size(): Int = rgbMap.size
}

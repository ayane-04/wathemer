package com.wathemer.app.hooks

/** Stock WhatsApp colours rewritten to user tokens, one list per token. Keys are 24-bit RGB with no alpha; WA varies alpha at runtime. */
object ColorSeeds {

    /** WA brand greens + light-bubble cream. All rewritten to user's `primary` token. */
    val PRIMARY: List<Int> = listOf(
        0x00a884,  // WA standard tint (most-used)
        0x1da457,
        0x21c063,
        0x1daa61,
        0x25d366,  // WA brand
        0x1b864b,
        0x144d37,
        0x1b8755,
        0x15603e,
        0xd9fdd3,  // light bubble bg (incoming bubble in light mode)
        0x008069,  // tab indicator
        0x128c7e,  // legacy WA teal
        0x103529,  // deep green container
    )

    /** WA dark surfaces. Rewritten to user's `background` token. */
    val BACKGROUND: List<Int> = listOf(
        // WaEnhancer's classic set
        0x0b141a,
        0x111b21,
        0x0a1014,
        0x12181c,
        0x20272b,
        0x000000,  // true black (overlays, deep surfaces)
        0x10161a,
        // the high-frequency hits in the build these seeds were read from
        0x182229,  // composer bar
        0x888888,  // mid-grey surface
        0x3e474d,  // elevated container
    )

    /** WA primary text colours. White must stay here, not in BACKGROUND, so translucent-white ripples follow the text token. */
    val TEXT: List<Int> = listOf(
        0xeaedee,
        0xf7f8fa,
        0xffffff,  // pure white text + ripple base
        0xe9edef,  // WA primary text
        0x8696a0,  // secondary text, boosted to primary text
    )

    /** Every seed across the three lists; membership means some pass may rewrite that colour. */
    private val ALL: HashSet<Int> by lazy { (PRIMARY + BACKGROUND + TEXT).toHashSet() }

    /** A token equal to ANOTHER token's seed is rewritten again on a later pass; one blue step off is invisible and ends the cycle. */
    fun dodgeCollision(argb: Int, own: List<Int>): Int {
        var rgb = argb and 0xFFFFFF
        val alpha = argb and 0xFF000000.toInt()
        var guard = 0
        while (guard++ < 8 && rgb !in own && rgb in ALL) rgb = if (rgb < 0x800000) rgb + 1 else rgb - 1
        return alpha or rgb
    }
}

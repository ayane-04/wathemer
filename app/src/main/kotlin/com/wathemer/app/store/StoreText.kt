// Display text from the store, cleaned as the publish tool cleans it before signing.
package com.wathemer.app.store

private const val ZWNJ = 0x200C
private const val ZWJ = 0x200D

/** The publish tool's clean_text, rule for rule, so a signed name reads back unchanged and no bidi or zero-width trick survives. */
fun cleanText(raw: String, limit: Int): String {
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val cp = raw.codePointAt(i)
        i += Character.charCount(cp)
        val type = Character.getType(cp)
        when {
            cp in 0x09..0x0D || cp == 0x85 || type == Character.SPACE_SEPARATOR.toInt() ||
                type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt() -> out.append(' ')
            type == Character.CONTROL.toInt() -> Unit
            // The joiners stay: emoji sequences and Persian and Indic text need them.
            type == Character.FORMAT.toInt() && cp != ZWNJ && cp != ZWJ -> Unit
            else -> out.appendCodePoint(cp)
        }
    }
    val collapsed = out.toString().replace(Regex(" +"), " ").trim(' ')
    if (collapsed.codePointCount(0, collapsed.length) <= limit) return collapsed
    return collapsed.substring(0, collapsed.offsetByCodePoints(0, limit)).trimEnd(' ')
}

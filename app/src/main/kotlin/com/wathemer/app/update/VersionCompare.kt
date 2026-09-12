// Kept free of Android imports so the desktop harness can compile it; see docs/METHODS.md section 7.
package com.wathemer.app.update

/** Component-wise, so a bigger component wins whatever its digit count; only the leading digits count, so "0-beta4" is 0 and "v1" is 1. */
fun isNewerVersion(candidate: String, current: String): Boolean {
    val a = candidate.split('.')
    val b = current.split('.')
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrNull(i)?.trimStart { !it.isDigit() }?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0
        val y = b.getOrNull(i)?.trimStart { !it.isDigit() }?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0
        if (x != y) return x > y
    }
    return false
}

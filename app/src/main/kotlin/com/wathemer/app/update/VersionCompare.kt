// Kept free of Android imports so the desktop harness can compile it; see docs/METHODS.md section 7.
package com.wathemer.app.update

/** Component-wise, so 0.10.0 beats 0.9.9; a part that is not a number counts as 0 rather than throwing. */
fun isNewerVersion(candidate: String, current: String): Boolean {
    val a = candidate.split('.')
    val b = current.split('.')
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrNull(i)?.filter(Char::isDigit)?.toIntOrNull() ?: 0
        val y = b.getOrNull(i)?.filter(Char::isDigit)?.toIntOrNull() ?: 0
        if (x != y) return x > y
    }
    return false
}

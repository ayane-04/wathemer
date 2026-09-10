package com.wathemer.app.glass

import android.graphics.Bitmap

/** Real blurs for the wallpaper copies: shrink-and-stretch leaves a lattice on high-contrast content, a kernel does not. */
internal object BitmapBlur {

    /** Halve by 2x2 averages until the width is at or under source.width / divisor; each halving is a true box average. */
    fun halveTo(source: Bitmap, divisor: Int): Bitmap {
        var cur = source
        val floor = (source.width / divisor).coerceAtLeast(1)
        while (cur.width / 2 >= floor && cur.height / 2 >= 1) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, (cur.height / 2).coerceAtLeast(1), true)
            if (cur !== source) cur.recycle()
            cur = next
        }
        return cur
    }

    /** A separable box blur run [passes] times into a fresh mutable copy; three passes are a close Gaussian. */
    fun boxBlur(source: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = source.width
        val h = source.height
        val out = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        if (w <= 0 || h <= 0) return out
        val px = IntArray(w * h)
        source.getPixels(px, 0, w, 0, 0, w, h)
        if (radius > 0 && passes > 0) {
            val tmp = IntArray(w * h)
            repeat(passes) {
                blurRows(px, tmp, w, h, radius)
                blurColumns(tmp, px, w, h, radius)
            }
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    // Running sums per channel with clamped edges; the ints stay unpremultiplied as getPixels hands them out.
    private fun blurRows(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val n = 2 * r + 1
        val half = n / 2
        for (y in 0 until h) {
            val row = y * w
            var a = 0
            var rr = 0
            var g = 0
            var b = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                a += p ushr 24
                rr += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = (((a + half) / n) shl 24) or (((rr + half) / n) shl 16) or (((g + half) / n) shl 8) or ((b + half) / n)
                val add = src[row + (x + r + 1).coerceAtMost(w - 1)]
                val sub = src[row + (x - r).coerceAtLeast(0)]
                a += (add ushr 24) - (sub ushr 24)
                rr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                g += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                b += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }

    private fun blurColumns(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val n = 2 * r + 1
        val half = n / 2
        for (x in 0 until w) {
            var a = 0
            var rr = 0
            var g = 0
            var b = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                a += p ushr 24
                rr += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = (((a + half) / n) shl 24) or (((rr + half) / n) shl 16) or (((g + half) / n) shl 8) or ((b + half) / n)
                val add = src[(y + r + 1).coerceAtMost(h - 1) * w + x]
                val sub = src[(y - r).coerceAtLeast(0) * w + x]
                a += (add ushr 24) - (sub ushr 24)
                rr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                g += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                b += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }
}

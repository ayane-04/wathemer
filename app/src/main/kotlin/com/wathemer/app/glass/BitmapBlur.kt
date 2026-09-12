package com.wathemer.app.glass

import android.graphics.Bitmap
import kotlin.math.roundToInt

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

    /** The copy shrunk and blurred in linear light; null for a source with transparency, whose alpha this path would write opaque. */
    fun shrinkBlurLinear(source: Bitmap, divisor: Int, radius: Int, passes: Int): Bitmap? {
        val sw = source.width
        val sh = source.height
        if (sw <= 0 || sh <= 0) return null
        val floor = (sw / divisor).coerceAtLeast(1)
        val toLin = TO_LINEAR
        var w = sw
        var h = sh
        var cr: IntArray
        var cg: IntArray
        var cb: IntArray
        var opaque = true
        if (w / 2 >= floor && h / 2 >= 1) {
            // The first halving reads two rows at a time, so a full-size linear copy never exists.
            val hw = w / 2
            val hh = h / 2
            cr = IntArray(hw * hh)
            cg = IntArray(hw * hh)
            cb = IntArray(hw * hh)
            val rows = IntArray(sw * 2)
            for (y in 0 until hh) {
                source.getPixels(rows, 0, sw, 0, 2 * y, sw, 2)
                val o = y * hw
                for (x in 0 until hw) {
                    val x0 = 2 * x
                    val p00 = rows[x0]
                    val p01 = rows[x0 + 1]
                    val p10 = rows[sw + x0]
                    val p11 = rows[sw + x0 + 1]
                    if ((p00 and p01 and p10 and p11) ushr 24 != 0xFF) opaque = false
                    cr[o + x] = (toLin[(p00 shr 16) and 0xFF] + toLin[(p01 shr 16) and 0xFF] +
                        toLin[(p10 shr 16) and 0xFF] + toLin[(p11 shr 16) and 0xFF] + 2) shr 2
                    cg[o + x] = (toLin[(p00 shr 8) and 0xFF] + toLin[(p01 shr 8) and 0xFF] +
                        toLin[(p10 shr 8) and 0xFF] + toLin[(p11 shr 8) and 0xFF] + 2) shr 2
                    cb[o + x] = (toLin[p00 and 0xFF] + toLin[p01 and 0xFF] +
                        toLin[p10 and 0xFF] + toLin[p11 and 0xFF] + 2) shr 2
                }
            }
            w = hw
            h = hh
        } else {
            val px = IntArray(w * h)
            source.getPixels(px, 0, w, 0, 0, w, h)
            cr = IntArray(w * h)
            cg = IntArray(w * h)
            cb = IntArray(w * h)
            for (i in px.indices) {
                val p = px[i]
                if (p ushr 24 != 0xFF) opaque = false
                cr[i] = toLin[(p shr 16) and 0xFF]
                cg[i] = toLin[(p shr 8) and 0xFF]
                cb[i] = toLin[p and 0xFF]
            }
        }
        if (!opaque) return null
        while (w / 2 >= floor && h / 2 >= 1) {
            val hw = w / 2
            val hh = h / 2
            cr = halve(cr, w, hw, hh)
            cg = halve(cg, w, hw, hh)
            cb = halve(cb, w, hw, hh)
            w = hw
            h = hh
        }
        val n = w * h
        if (radius > 0 && passes > 0) {
            val tmp = IntArray(n)
            for (ch in arrayOf(cr, cg, cb)) {
                repeat(passes) {
                    blurRows1(ch, tmp, w, h, radius)
                    blurColumns1(tmp, ch, w, h, radius)
                }
            }
        }
        val px = IntArray(n)
        val fromLin = FROM_LINEAR
        var seed = 0x9E3779B9.toInt()
        for (i in 0 until n) {
            // Two draws make the triangular dither; one step of noise breaks the bands the encode would otherwise draw.
            seed = xorshift(seed)
            val u1 = (seed ushr 8) / 16777216f
            seed = xorshift(seed)
            val u2 = (seed ushr 8) / 16777216f
            val d = u1 + u2 - 1f
            val rr = (fromLin[cr[i]] + d).roundToInt().coerceIn(0, 255)
            val gg = (fromLin[cg[i]] + d).roundToInt().coerceIn(0, 255)
            val bb = (fromLin[cb[i]] + d).roundToInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** sRGB to linear, eight bits in and sixteen out; twelve would leave the darks, where the banding lives, with fewer codes than they had. */
    private val TO_LINEAR: IntArray by lazy {
        IntArray(256) { i ->
            val c = i / 255.0
            val l = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            (l * 65535.0 + 0.5).toInt()
        }
    }

    /** Linear back to display as the exact value before rounding, so the dither can sit on it. */
    private val FROM_LINEAR: FloatArray by lazy {
        FloatArray(65536) { i ->
            val l = i / 65535.0
            val c = if (l <= 0.0031308) l * 12.92 else 1.055 * Math.pow(l, 1.0 / 2.4) - 0.055
            (c * 255.0).toFloat()
        }
    }

    private fun halve(src: IntArray, w: Int, hw: Int, hh: Int): IntArray {
        val dst = IntArray(hw * hh)
        for (y in 0 until hh) {
            val r0 = 2 * y * w
            val r1 = r0 + w
            val o = y * hw
            for (x in 0 until hw) {
                val x0 = 2 * x
                dst[o + x] = (src[r0 + x0] + src[r0 + x0 + 1] + src[r1 + x0] + src[r1 + x0 + 1] + 2) shr 2
            }
        }
        return dst
    }

    private fun xorshift(s: Int): Int {
        var x = s
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        return x
    }

    // The single-channel twins of the packed passes below, for the linear path.
    private fun blurRows1(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val n = 2 * r + 1
        val half = n / 2
        for (y in 0 until h) {
            val row = y * w
            var s = 0
            for (i in -r..r) s += src[row + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = (s + half) / n
                s += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        }
    }

    private fun blurColumns1(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val n = 2 * r + 1
        val half = n / 2
        for (x in 0 until w) {
            var s = 0
            for (i in -r..r) s += src[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = (s + half) / n
                s += src[(y + r + 1).coerceAtMost(h - 1) * w + x] - src[(y - r).coerceAtLeast(0) * w + x]
            }
        }
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

// A bubble pack's silhouette as a texture the fused bubble program reads: the art's own edge, and the distance inward from it for the bevel.
package com.wathemer.app.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import kotlin.math.sqrt

/** One pack silhouette at one size bucket: red is the art's coverage as drawn, green the distance inward from its outline over [rangeTexels]. */
class MaskField(val bitmap: Bitmap, private val rangeTexels: Float) {
    /** Built once; the painter only moves its local matrix. */
    val shader: BitmapShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) it.filterMode = BitmapShader.FILTER_MODE_LINEAR
    }

    /** The inward range in px once the texture is stretched over a bubble [w] px wide. */
    fun rangePxFor(w: Int): Float = rangeTexels * w / bitmap.width
}

/** A field placed on one bubble, at the bubble's full size. */
class MaskRef(val field: MaskField, val w: Int, val h: Int)

/** Rasterises a pack's nine-patch per size bucket: the edge is the art's own alpha, the bevel reads a Euclidean distance inward from the flood-filled outline. */
object BubbleMaskFields {

    /** Sizes round up to this, so a paragraph one line taller shares its neighbour's texture. */
    private const val BUCKET = 8

    /** Inward distance a full green stands for, in texture px; past it the surface is flat anyway. */
    private const val RANGE = 64f

    /** Above this many bubble px the texture itself is built at half size; a media bubble's soft edge is under its picture. */
    private const val FULL_RES_LIMIT = 900_000

    /** The distance grid is this many texture px per cell; the bevel needs no finer and the transform is a quarter of the work. */
    private const val GRID = 2

    private const val FAR = 1e7f

    private val cache = object : LruCache<Long, MaskField>(24 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: MaskField): Int = value.bitmap.allocationByteCount
    }

    /** A pack that drew nothing at a size is remembered, or every frame would rasterise it again. */
    private val empty = HashSet<Long>()

    /** The field for a bubble of [w] by [h] px drawn with [nine], keyed by [packKey]; null when the pack draws nothing. */
    fun fieldFor(packKey: Int, nine: Drawable, w: Int, h: Int): MaskField? {
        if (w <= 0 || h <= 0) return null
        val bw = roundUp(w)
        val bh = roundUp(h)
        val key = (packKey.toLong() shl 48) or (bw.toLong() shl 24) or bh.toLong()
        cache.get(key)?.let { return it }
        if (key in empty) return null
        val f = build(nine, bw, bh)
        if (f == null) empty.add(key) else cache.put(key, f)
        return f
    }

    private fun roundUp(v: Int): Int = (v + BUCKET - 1) / BUCKET * BUCKET

    private fun build(nine: Drawable, bw: Int, bh: Int): MaskField? {
        val down = if (bw * bh > FULL_RES_LIMIT) 2 else 1
        val tw = maxOf(1, bw / down)
        val th = maxOf(1, bh / down)
        val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(tw.toFloat() / bw, th.toFloat() / bh)
        nine.setBounds(0, 0, bw, bh)
        nine.draw(c)
        val n = tw * th
        val px = IntArray(n)
        bmp.getPixels(px, 0, tw, 0, 0, tw, th)
        var any = false
        for (i in 0 until n) if ((px[i] ushr 24) >= 128) { any = true; break }
        if (!any) return null

        // The distance grid: a cell is inside when its centre texel's alpha is, then enclosed holes count as inside too.
        val gw = maxOf(1, tw / GRID)
        val gh = maxOf(1, th / GRID)
        val inside = BooleanArray(gw * gh)
        for (gy in 0 until gh) {
            val sy = minOf(th - 1, gy * GRID + GRID / 2)
            for (gx in 0 until gw) {
                val sx = minOf(tw - 1, gx * GRID + GRID / 2)
                inside[gy * gw + gx] = (px[sy * tw + sx] ushr 24) >= 128
            }
        }
        fillEnclosed(inside, gw, gh)
        val dist = distanceInward(inside, gw, gh)

        // Red is the art's alpha as drawn; green the inward distance, read off the grid between cell centres.
        for (y in 0 until th) {
            val fy = ((y + 0.5f) / GRID - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = minOf(gh - 1, y0 + 1)
            val wy = fy - y0
            for (x in 0 until tw) {
                val fx = ((x + 0.5f) / GRID - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = minOf(gw - 1, x0 + 1)
                val wx = fx - x0
                val d = (dist[y0 * gw + x0] * (1 - wx) + dist[y0 * gw + x1] * wx) * (1 - wy) +
                    (dist[y1 * gw + x0] * (1 - wx) + dist[y1 * gw + x1] * wx) * wy
                val g = ((d * GRID / RANGE).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val i = y * tw + x
                val a = px[i] ushr 24
                px[i] = (0xFF shl 24) or (a shl 16) or (g shl 8)
            }
        }
        bmp.setPixels(px, 0, tw, 0, 0, tw, th)
        return MaskField(bmp, RANGE)
    }

    /** Outside is what a flood from the border reaches through clear cells; everything else is inside. */
    private fun fillEnclosed(inside: BooleanArray, w: Int, h: Int) {
        val n = w * h
        val reached = BooleanArray(n)
        val stack = IntArray(n)
        var sp = 0
        fun push(i: Int) {
            if (!reached[i] && !inside[i]) {
                reached[i] = true
                stack[sp++] = i
            }
        }
        for (x in 0 until w) {
            push(x)
            push((h - 1) * w + x)
        }
        for (y in 0 until h) {
            push(y * w)
            push(y * w + w - 1)
        }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        for (i in 0 until n) if (!reached[i]) inside[i] = true
    }

    /** Euclidean distance from each inside cell to the outline, in cells; outside cells read zero. */
    private fun distanceInward(inside: BooleanArray, w: Int, h: Int): FloatArray {
        val n = w * h
        val sq = FloatArray(n) { if (inside[it]) FAR else 0f }
        val len = maxOf(w, h)
        val f = FloatArray(len)
        val d = FloatArray(len)
        val v = IntArray(len)
        val z = FloatArray(len + 1)
        // Columns first, then rows: the separable squared-distance transform.
        for (x in 0 until w) {
            for (y in 0 until h) f[y] = sq[y * w + x]
            transform1d(f, h, d, v, z)
            for (y in 0 until h) sq[y * w + x] = d[y]
        }
        for (y in 0 until h) {
            for (x in 0 until w) f[x] = sq[y * w + x]
            transform1d(f, w, d, v, z)
            for (x in 0 until w) sq[y * w + x] = d[x]
        }
        // The outline lies half a cell past the last inside centre; where the art touches the bounds, the bounds are the edge.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!inside[i]) {
                    sq[i] = 0f
                    continue
                }
                val border = minOf(minOf(x, w - 1 - x), minOf(y, h - 1 - y)) + 0.5f
                sq[i] = minOf(sqrt(sq[i]) - 0.5f, border).coerceAtLeast(0f)
            }
        }
        return sq
    }

    /** One line of the lower-envelope distance transform; `f` holds squared seeds, `d` receives squared distances. */
    private fun transform1d(f: FloatArray, n: Int, d: FloatArray, v: IntArray, z: FloatArray) {
        var k = 0
        v[0] = 0
        z[0] = -FAR
        z[1] = FAR
        for (q in 1 until n) {
            var s = intersect(f, q, v[k])
            while (s <= z[k]) {
                k--
                s = intersect(f, q, v[k])
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = FAR
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val dq = (q - v[k]).toFloat()
            d[q] = dq * dq + f[v[k]]
        }
    }

    private fun intersect(f: FloatArray, q: Int, p: Int): Float =
        ((f[q] + q.toFloat() * q) - (f[p] + p.toFloat() * p)) / (2f * q - 2f * p)
}

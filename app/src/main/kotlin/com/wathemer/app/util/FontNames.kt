// Reads the family name straight from a font file's sfnt name table; Android exposes no API for it.
package com.wathemer.app.util

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

object FontNames {

    private const val SFNT_TRUETYPE = 0x00010000
    private const val SFNT_OTTO = 0x4F54544F
    private const val SFNT_TRUE = 0x74727565
    private const val SFNT_TTCF = 0x74746366
    private const val TAG_NAME = 0x6E616D65

    // Caps against a hostile or corrupt file; a real name table is a few KB.
    private const val MAX_TABLES = 512
    private const val MAX_NAME_RECORDS = 4096
    private const val MAX_NAME_BYTES = 512

    /** Family name of the first face, or null when the file has none we can decode. */
    fun familyName(file: File): String? = runCatching {
        RandomAccessFile(file, "r").use { raf -> parse(raf) }
    }.getOrNull()

    private fun parse(raf: RandomAccessFile): String? {
        val len = raf.length()
        if (len < 12) return null
        var base = 0L
        var tag = raf.u32At(0)
        if (tag == SFNT_TTCF.toLong()) {
            // Collection: Typeface.Builder loads the first face, so the name comes from it too.
            base = raf.u32At(12)
            if (base + 12 > len) return null
            tag = raf.u32At(base)
        }
        if (tag != SFNT_TRUETYPE.toLong() && tag != SFNT_OTTO.toLong() && tag != SFNT_TRUE.toLong()) return null
        val numTables = raf.u16At(base + 4)
        if (numTables > MAX_TABLES) return null
        var nameOff = -1L
        for (i in 0 until numTables) {
            val rec = base + 12 + 16L * i
            if (rec + 16 > len) return null
            if (raf.u32At(rec) == TAG_NAME.toLong()) {
                nameOff = raf.u32At(rec + 8)
                break
            }
        }
        if (nameOff < 0 || nameOff + 6 > len) return null
        val count = raf.u16At(nameOff + 2).coerceAtMost(MAX_NAME_RECORDS)
        val storage = nameOff + raf.u16At(nameOff + 4)
        var best: String? = null
        var bestScore = -1
        for (i in 0 until count) {
            val r = nameOff + 6 + 12L * i
            if (r + 12 > len) break
            val plat = raf.u16At(r)
            val lang = raf.u16At(r + 4)
            val nameId = raf.u16At(r + 6)
            if (nameId != 1 && nameId != 16) continue
            // Typographic family (16) beats legacy family (1); Windows English beats the rest.
            val platScore = when {
                plat == 3 && lang == 0x409 -> 3
                plat == 3 -> 2
                plat == 0 -> 1
                else -> 0
            }
            val score = (if (nameId == 16) 10 else 0) + platScore
            if (score <= bestScore) continue
            val byteLen = raf.u16At(r + 8).coerceAtMost(MAX_NAME_BYTES)
            val off = storage + raf.u16At(r + 10)
            if (off + byteLen > len) continue
            val raw = ByteArray(byteLen)
            raf.seek(off)
            raf.readFully(raw)
            // Platforms 0/3 store UTF-16BE; Mac Roman agrees with Latin-1 on the ASCII range.
            val charset = if (plat == 0 || plat == 3) Charsets.UTF_16BE else Charset.forName("ISO-8859-1")
            val s = runCatching { String(raw, charset) }.getOrNull()
                ?.split(Regex("\\s+"))?.joinToString(" ")?.trim()
                ?.filter { !it.isISOControl() }
                ?: continue
            if (s.isNotEmpty()) {
                best = s.take(64)
                bestScore = score
            }
        }
        return best
    }

    private fun RandomAccessFile.u16At(pos: Long): Int {
        seek(pos)
        return readUnsignedShort()
    }

    private fun RandomAccessFile.u32At(pos: Long): Long {
        seek(pos)
        return readInt().toLong() and 0xFFFFFFFFL
    }
}

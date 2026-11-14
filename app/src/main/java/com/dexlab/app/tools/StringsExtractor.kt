package com.dexlab.app.tools

import java.io.File

data class StringHit(val offset: Long, val value: String, val encoding: String)

object StringsExtractor {
    private const val CHUNK = 1 shl 20
    private const val OVERLAP = 4096
    private const val MAX_SCAN = 256L * 1024 * 1024

    fun extract(
        file: File,
        minLength: Int,
        includeUtf16: Boolean,
        maxHits: Int = 30_000
    ): List<StringHit> {
        val out = ArrayList<StringHit>()
        val seen = HashSet<String>()
        val min = minLength.coerceIn(2, 64)
        var position = 0L
        var carry = ByteArray(0)
        file.inputStream().buffered(CHUNK).use { input ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                val data = if (carry.isEmpty()) buf.copyOf(read) else carry + buf.copyOf(read)
                val origin = position - carry.size
                scanAscii(data, origin, min, out, seen, maxHits)
                if (includeUtf16) scanUtf16(data, origin, min, out, seen, maxHits)
                if (out.size >= maxHits) break
                carry = data.copyOfRange(maxOf(0, data.size - OVERLAP), data.size)
                position += read
                if (position >= MAX_SCAN) break
            }
        }
        return out.sortedBy { it.offset }
    }

    private fun scanAscii(
        data: ByteArray,
        origin: Long,
        min: Int,
        out: MutableList<StringHit>,
        seen: MutableSet<String>,
        maxHits: Int
    ) {
        var start = -1
        for (i in data.indices) {
            val v = data[i].toInt() and 0xFF
            val printable = v in 32..126
            if (printable) {
                if (start < 0) start = i
            } else {
                if (start >= 0) {
                    emit(data, start, i, origin, min, "ASCII", out, seen, maxHits)
                    start = -1
                }
            }
            if (out.size >= maxHits) break
        }
        if (start >= 0) emit(data, start, data.size, origin, min, "ASCII", out, seen, maxHits)
    }

    private fun scanUtf16(
        data: ByteArray,
        origin: Long,
        min: Int,
        out: MutableList<StringHit>,
        seen: MutableSet<String>,
        maxHits: Int
    ) {
        var start = -1
        var i = 0
        while (i + 1 < data.size) {
            val lo = data[i].toInt() and 0xFF
            val hi = data[i + 1].toInt() and 0xFF
            val printable = hi == 0 && lo in 32..126
            if (printable) {
                if (start < 0) start = i
            } else {
                if (start >= 0) {
                    emitUtf16(data, start, i, origin, min, out, seen, maxHits)
                    start = -1
                }
            }
            i += 2
            if (out.size >= maxHits) break
        }
        if (start >= 0) emitUtf16(data, start, data.size - (data.size % 2), origin, min, out, seen, maxHits)
    }

    private fun emit(
        data: ByteArray,
        start: Int,
        end: Int,
        origin: Long,
        min: Int,
        encoding: String,
        out: MutableList<StringHit>,
        seen: MutableSet<String>,
        maxHits: Int
    ) {
        if (end - start < min) return
        val value = String(data, start, end - start, Charsets.US_ASCII)
        val offset = origin + start
        val key = "$offset|$encoding|${value.take(200)}"
        if (seen.add(key)) out.add(StringHit(offset, value, encoding))
        if (out.size >= maxHits) return
    }

    private fun emitUtf16(
        data: ByteArray,
        start: Int,
        end: Int,
        origin: Long,
        min: Int,
        out: MutableList<StringHit>,
        seen: MutableSet<String>,
        maxHits: Int
    ) {
        val chars = (end - start) / 2
        if (chars < min) return
        val sb = StringBuilder(chars)
        var i = start
        while (i + 1 < end) {
            sb.append((data[i].toInt() and 0xFF).toChar())
            i += 2
        }
        val value = sb.toString()
        val offset = origin + start
        val key = "$offset|UTF-16LE|${value.take(200)}"
        if (seen.add(key)) out.add(StringHit(offset, value, "UTF-16LE"))
        if (out.size >= maxHits) return
    }
}

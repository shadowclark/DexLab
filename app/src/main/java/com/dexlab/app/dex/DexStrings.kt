package com.dexlab.app.dex

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object DexStrings {
    fun read(file: File, limit: Int = 200_000): List<String> {
        if (!file.isFile || file.length() > 96L * 1024 * 1024) return emptyList()
        return runCatching { readBytes(file.readBytes(), limit) }.getOrDefault(emptyList())
    }

    fun readBytes(data: ByteArray, limit: Int = 200_000): List<String> {
        if (data.size < 0x70) return emptyList()
        val magic = String(data, 0, 4, Charsets.US_ASCII)
        if (magic != "dex\n") return emptyList()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val size = buf.getInt(0x38)
        val offset = buf.getInt(0x3C)
        if (size <= 0 || offset <= 0) return emptyList()
        if (offset.toLong() + size.toLong() * 4 > data.size.toLong()) return emptyList()
        val count = minOf(size, limit)
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            val stringOffset = buf.getInt(offset + i * 4)
            if (stringOffset <= 0 || stringOffset >= data.size) continue
            var p = stringOffset
            var length = 0
            var shift = 0
            while (p < data.size) {
                val b = data[p].toInt() and 0xFF
                p++
                length = length or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
                if (shift > 28) break
            }
            if (length <= 0 || p + length > data.size) continue
            val raw = String(data, p, length, Charsets.UTF_8)
            out.add(raw.replace('\u0000', ' '))
        }
        return out
    }
}

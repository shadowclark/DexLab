package com.dexlab.app.tools

import java.io.File

data class EntropyBlock(val offset: Long, val entropy: Double)

object Entropy {
    fun analyze(bytes: ByteArray, blockSize: Int = 1024): List<EntropyBlock> {
        if (bytes.isEmpty()) return emptyList()
        val size = blockSize.coerceIn(64, 1 shl 20)
        val out = ArrayList<EntropyBlock>()
        var off = 0
        while (off < bytes.size) {
            val end = minOf(bytes.size, off + size)
            out.add(EntropyBlock(off.toLong(), block(bytes, off, end)))
            off = end
        }
        return out
    }

    fun analyzeFile(file: File, blockSize: Int = 4096, maxBytes: Int = 8 * 1024 * 1024): List<EntropyBlock> {
        val length = minOf(file.length(), maxBytes.toLong()).toInt()
        if (length <= 0) return emptyList()
        val bytes = ByteArray(length)
        file.inputStream().use { input ->
            var read = 0
            while (read < length) {
                val n = input.read(bytes, read, length - read)
                if (n <= 0) break
                read += n
            }
        }
        return analyze(bytes, blockSize)
    }

    private fun block(bytes: ByteArray, start: Int, end: Int): Double {
        val counts = IntArray(256)
        for (i in start until end) counts[bytes[i].toInt() and 0xFF]++
        val n = (end - start).toDouble()
        var h = 0.0
        for (c in counts) {
            if (c > 0) {
                val p = c / n
                h -= p * (Math.log(p) / Math.log(2.0))
            }
        }
        return h
    }

    fun interpretation(average: Double): String = when {
        average < 3.2 -> "Mostly text or structured data. Expect readable strings and fixed headers."
        average < 5.2 -> "Mixed content: code, resources and some compressed data."
        average < 6.8 -> "Likely compressed or compiled code. High entropy alone is not proof of encryption."
        else -> "Very high entropy: encrypted, compressed or packed. Compare with the headers and string output."
    }
}

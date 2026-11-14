package com.dexlab.app.core

import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    fun bytes(n: Long): String = when {
        n < 0 -> "?"
        n < 1024 -> "$n B"
        n < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", n / 1024.0)
        n < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", n / (1024.0 * 1024.0 * 1024.0))
    }

    fun hex(data: ByteArray, max: Int = Int.MAX_VALUE): String {
        val n = minOf(data.size, max)
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) {
            val v = data[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        if (n < data.size) sb.append('…')
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"

    fun address(v: Long): String = "0x" + java.lang.Long.toHexString(v)

    fun date(ts: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ts))

    fun sha256(bytes: ByteArray): String = digest("SHA-256", bytes)

    fun sha1(bytes: ByteArray): String = digest("SHA-1", bytes)

    fun md5(bytes: ByteArray): String = digest("MD5", bytes)

    fun digest(algorithm: String, bytes: ByteArray): String {
        val d = MessageDigest.getInstance(algorithm).digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    fun digestFile(algorithm: String, file: File): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        val d = md.digest()
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    fun ellipsize(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1) + "…"

    fun plural(n: Int, one: String, many: String = one + "s"): String = if (n == 1) "$n $one" else "$n $many"
}

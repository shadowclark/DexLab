package com.dexlab.app.tools

import com.dexlab.app.core.Fmt
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

class HexFile(val file: File) {
    val size: Long get() = file.length()

    fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length <= 0 || offset >= file.length()) return ByteArray(0)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val n = minOf(length.toLong(), raf.length() - offset).toInt()
            val out = ByteArray(n)
            raf.readFully(out)
            return out
        }
    }

    fun hash(algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        return Fmt.hex(md.digest())
    }

    fun magic(): String {
        val head = read(0, 16)
        if (head.isEmpty()) return "empty file"
        fun startsWith(vararg bytes: Int): Boolean {
            if (head.size < bytes.size) return false
            return bytes.indices.all { (head[it].toInt() and 0xFF) == bytes[it] }
        }
        return when {
            startsWith(0x7F, 0x45, 0x4C, 0x46) -> "ELF executable / shared library"
            startsWith(0x64, 0x65, 0x78, 0x0A) -> "Dalvik DEX"
            startsWith(0x50, 0x4B, 0x03, 0x04) -> "ZIP archive / APK / JAR"
            startsWith(0x89, 0x50, 0x4E, 0x47) -> "PNG image"
            startsWith(0xFF, 0xD8, 0xFF) -> "JPEG image"
            startsWith(0x25, 0x50, 0x44, 0x46) -> "PDF document"
            startsWith(0x03, 0x00, 0x08, 0x00) -> "Android binary XML (AXML)"
            startsWith(0x02, 0x00, 0x0C, 0x00) -> "Android resources.arsc"
            startsWith(0x6F, 0x61, 0x74, 0x0A) -> "OAT (compiled Android code)"
            startsWith(0x64, 0x65, 0x79, 0x0A) -> "CompactDex"
            head.size >= 16 && String(head, 0, 15, Charsets.US_ASCII) == "SQLite format 3" -> "SQLite database"
            head.all {
                val v = it.toInt() and 0xFF
                v == 9 || v == 10 || v == 13 || v in 32..126
            } -> "text"
            else -> "unknown (0x${Fmt.hex(head, 8)})"
        }
    }
}

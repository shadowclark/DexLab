package com.dexlab.app.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class DataFormat(val label: String) {
    TEXT("Text"),
    HEX("Hex"),
    BASE64("Base64")
}

data class XorCandidate(val key: Int, val score: Double, val preview: String)

object CryptoKit {
    private const val HEX = "0123456789abcdef"

    fun encode(data: ByteArray, format: DataFormat): String = when (format) {
        DataFormat.TEXT -> String(data, Charsets.UTF_8)
        DataFormat.HEX -> hex(data)
        DataFormat.BASE64 -> Base64.getEncoder().encodeToString(data)
    }

    fun decode(text: String, format: DataFormat): ByteArray = when (format) {
        DataFormat.TEXT -> text.toByteArray(Charsets.UTF_8)
        DataFormat.HEX -> hexToBytes(text)
        DataFormat.BASE64 -> Base64.getMimeDecoder().decode(text.trim())
    }

    fun hex(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    fun hexToBytes(text: String): ByteArray {
        val cleaned = text.filter { !it.isWhitespace() && it != ':' && it != '-' && it != '_' }
        require(cleaned.length % 2 == 0) { "Hex input has an odd number of digits (${cleaned.length})" }
        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(cleaned[i * 2], 16)
            val lo = Character.digit(cleaned[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex digit near position ${i * 2}" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun hash(algorithm: String, data: ByteArray): String {
        val name = when (algorithm.uppercase(Locale.US)) {
            "MD5" -> "MD5"
            "SHA1", "SHA-1" -> "SHA-1"
            "SHA256", "SHA-256" -> "SHA-256"
            "SHA384", "SHA-384" -> "SHA-384"
            "SHA512", "SHA-512" -> "SHA-512"
            else -> algorithm
        }
        return hex(MessageDigest.getInstance(name).digest(data))
    }

    fun crc32(data: ByteArray): String {
        val crc = CRC32()
        crc.update(data)
        return "%08x".format(crc.value)
    }

    fun xor(data: ByteArray, key: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "Key must not be empty" }
        val out = ByteArray(data.size)
        for (i in data.indices) {
            out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return out
    }

    fun bruteXor(data: ByteArray, limit: Int = 12): List<XorCandidate> {
        val out = ArrayList<XorCandidate>(256)
        for (k in 0..255) {
            val decoded = ByteArray(data.size) { (data[it].toInt() xor k).toByte() }
            val score = score(decoded)
            if (score > 0.48) out.add(XorCandidate(k, score, printablePreview(decoded, 96)))
        }
        return out.sortedByDescending { it.score }.take(limit)
    }

    private fun score(data: ByteArray): Double {
        if (data.isEmpty()) return 0.0
        var printable = 0
        var letters = 0
        var spaces = 0
        for (b in data) {
            val v = b.toInt() and 0xFF
            if (v in 32..126 || v == 9 || v == 10 || v == 13) printable++
            if (v in 65..90 || v in 97..122) letters++
            if (v == 32) spaces++
        }
        val p = printable.toDouble() / data.size
        val l = letters.toDouble() / data.size
        val s = spaces.toDouble() / data.size
        val text = String(data, Charsets.ISO_8859_1)
        val lower = text.lowercase()
        var bonus = 0.0
        if (listOf("flag{", "ctf{", "dexlab{", "key{", "secret{").any { lower.contains(it) }) bonus += 0.30
        if (text.contains('{') && text.contains('}')) bonus += 0.15
        val words = listOf(
            "the", "and", "not", "is", "are", "secret", "key", "xor", "flag",
            "password", "admin", "hello", "this", "that", "with", "you"
        )
        val hits = words.count { lower.contains(it) }
        bonus += minOf(hits * 0.06, 0.24)
        if (spaces > 0) bonus += 0.03
        return (p * 0.5 + l * 0.35 + minOf(s * 2.0, 0.15) * 0.15 + bonus).coerceAtMost(1.0)
    }

    fun printablePreview(data: ByteArray, max: Int): String = buildString {
        for (b in data.take(max)) {
            val v = b.toInt() and 0xFF
            append(if (v in 32..126) v.toChar() else '.')
        }
    }

    fun aes(
        mode: String,
        padding: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray,
        encrypt: Boolean
    ): ByteArray {
        require(key.size == 16 || key.size == 24 || key.size == 32) {
            "AES needs a 16, 24 or 32 byte key (got ${key.size})"
        }
        val transformation = "AES/$mode/$padding"
        val cipher = Cipher.getInstance(transformation)
        val keySpec = SecretKeySpec(key, "AES")
        val op = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        when {
            mode == "ECB" -> cipher.init(op, keySpec)
            mode == "GCM" -> {
                require(iv != null && iv.size >= 12) { "GCM needs a 12 byte IV" }
                cipher.init(op, keySpec, GCMParameterSpec(128, iv))
            }
            else -> {
                require(iv != null && iv.size == 16) { "AES-$mode needs a 16 byte IV" }
                cipher.init(op, keySpec, IvParameterSpec(iv))
            }
        }
        return cipher.doFinal(data)
    }

    fun des(
        triple: Boolean,
        mode: String,
        padding: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray,
        encrypt: Boolean
    ): ByteArray {
        val algorithm = if (triple) "DESede" else "DES"
        val expected = if (triple) 24 else 8
        require(key.size == expected) { "$algorithm needs a $expected byte key (got ${key.size})" }
        val transformation = "$algorithm/$mode/$padding"
        val cipher = Cipher.getInstance(transformation)
        val keySpec = SecretKeySpec(key, algorithm)
        val op = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        if (mode == "ECB") cipher.init(op, keySpec) else {
            require(iv != null && iv.size == 8) { "$algorithm-$mode needs an 8 byte IV" }
            cipher.init(op, keySpec, IvParameterSpec(iv))
        }
        return cipher.doFinal(data)
    }

    fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "RC4 key must not be empty" }
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]
            s[i] = s[j]
            s[j] = t
        }
        var i = 0
        j = 0
        val out = ByteArray(data.size)
        for (k in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val t = s[i]
            s[i] = s[j]
            s[j] = t
            out[k] = (data[k].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
        return out
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    fun identifyHash(text: String): String? {
        val t = text.trim()
        if (t.startsWith("$2a$") || t.startsWith("$2b$") || t.startsWith("$2y$")) return "bcrypt hash"
        if (t.startsWith("\$argon2")) return "Argon2 hash"
        if (t.startsWith("\$pbkdf2")) return "PBKDF2 hash"
        if (t.matches(Regex("[0-9a-fA-F]{32}"))) return "MD5 / NTLM (128-bit)"
        if (t.matches(Regex("[0-9a-fA-F]{40}"))) return "SHA-1 (160-bit)"
        if (t.matches(Regex("[0-9a-fA-F]{64}"))) return "SHA-256 (256-bit)"
        if (t.matches(Regex("[0-9a-fA-F]{96}"))) return "SHA-384 (384-bit)"
        if (t.matches(Regex("[0-9a-fA-F]{128}"))) return "SHA-512 (512-bit)"
        return null
    }

    fun entropy(data: ByteArray): Double {
        if (data.isEmpty()) return 0.0
        val counts = IntArray(256)
        for (b in data) counts[b.toInt() and 0xFF]++
        val n = data.size.toDouble()
        var h = 0.0
        for (c in counts) {
            if (c > 0) {
                val p = c / n
                h -= p * (Math.log(p) / Math.log(2.0))
            }
        }
        return h
    }
}

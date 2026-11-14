package com.dexlab.app.crypto

data class CryptoFinding(
    val category: String,
    val severity: String,
    val evidence: String,
    val location: String,
    val explanation: String
)

object CryptoScanner {
    private data class Rule(
        val category: String,
        val severity: String,
        val pattern: Regex,
        val explanation: String
    )

    private val RULES = listOf(
        Rule("AES usage", "info", Regex("""(?i)\bAES\b"""), "AES itself is fine; the mode and IV handling decide whether it is safe."),
        Rule("ECB mode", "high", Regex("""(?i)\bECB\b"""), "ECB encrypts identical blocks identically, leaking structure. Use AES-GCM or AES-CBC with a random IV plus a MAC."),
        Rule("CBC mode", "medium", Regex("""(?i)\bCBC\b"""), "CBC needs a random, unpredictable IV per message and a separate integrity check (encrypt-then-MAC)."),
        Rule("GCM mode", "info", Regex("""(?i)\bGCM\b"""), "GCM provides confidentiality and integrity, but never reuse a nonce with the same key."),
        Rule("Weak hash", "high", Regex("""(?i)\b(MD5|SHA-?1)\b"""), "MD5 and SHA-1 are broken for collisions. Use SHA-256 or better for integrity."),
        Rule("DES / 3DES", "high", Regex("""(?i)\b(DES|DESede|TripleDES)\b"""), "Single DES is brute-forceable. 3DES is deprecated and slow. Use AES."),
        Rule("RC4", "high", Regex("""(?i)\b(RC4|ARCFOUR)\b"""), "RC4 has known biases and is prohibited in modern protocols."),
        Rule("Hardcoded key material", "medium", Regex("""(?i)(secretkey|secret_key|aes_key|encryption_key|private_key|api_key|hardcoded)"""), "Names suggesting embedded key material are worth following into the bytecode."),
        Rule("Key / IV / salt", "low", Regex("""(?i)\b(key|iv|salt|nonce)\b"""), "Look for where this value comes from. A constant IV or salt is a finding."),
        Rule("Base64", "info", Regex("""(?i)base64"""), "Base64 is encoding, not encryption. It only obscures data."),
        Rule("XOR", "medium", Regex("""(?i)\bxor\b"""), "Single-byte XOR is trivially recoverable. Repeating-key XOR is also weak without a proper KDF/MAC."),
        Rule("Random source", "info", Regex("""(?i)(java/util/Random|Math/random|SecureRandom)"""), "java.util.Random and Math.random are predictable. Secrets need SecureRandom."),
        Rule("Certificate pinning", "info", Regex("""(?i)(certificatepinner|pinning|sha256/)"""), "Pinning code is a useful map of the app's trust anchors."),
        Rule("Native crypto", "info", Regex("""(?i)(openssl|boringssl|mbedtls|libcrypto)"""), "Crypto may be implemented in native code; check the ELF imports and symbols too.")
    )

    private val KEYWORDS = listOf(
        "aes", "des", "cipher", "crypto", "hash", "md5", "sha", "key", "iv", "salt",
        "secret", "hmac", "base64", "xor", "rc4", "random", "pinning", "encrypt", "decrypt"
    )

    fun scan(
        strings: List<String>,
        classNames: List<String>,
        methodRefs: List<String>
    ): Pair<List<CryptoFinding>, List<String>> {
        val findings = ArrayList<CryptoFinding>()
        val seen = HashSet<String>()

        val sources = listOf(
            "string" to strings,
            "class" to classNames,
            "method" to methodRefs
        )
        for ((kind, list) in sources) {
            val candidates = when (kind) {
                "string" -> list.asSequence().filter { value ->
                    val lower = value.lowercase()
                    KEYWORDS.any { lower.contains(it) }
                }
                else -> list.asSequence()
            }
            var scanned = 0
            for (value in candidates) {
                scanned++
                if (scanned > 60_000) break
                if (value.length > 4096) continue
                val printable = printable(value) ?: continue
                for (rule in RULES) {
                    if (rule.pattern.containsMatchIn(printable)) {
                        val key = rule.category + "|" + kind + "|" + printable.take(200)
                        if (seen.add(key)) {
                            findings.add(
                                CryptoFinding(
                                    category = rule.category,
                                    severity = rule.severity,
                                    evidence = printable.take(180),
                                    location = kind,
                                    explanation = rule.explanation
                                )
                            )
                        }
                    }
                }
            }
        }

        val keys = keyCandidates(strings)
        val rank = mapOf("high" to 3, "medium" to 2, "low" to 1, "info" to 0)
        val sorted = findings.sortedWith(
            compareByDescending<CryptoFinding> { rank[it.severity] ?: 0 }
                .thenBy { it.category }
                .thenBy { it.evidence }
        ).take(240)
        return sorted to keys
    }

    fun keyCandidates(strings: List<String>): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (raw in strings) {
            val s = raw.trim()
            if (s.length !in 8..128) continue
            if (s.startsWith("L") && s.endsWith(";")) continue
            if (s.startsWith("http://") || s.startsWith("https://")) continue
            if (s.startsWith("android.permission")) continue
            if (printable(s) == null) continue
            if (s.startsWith("/")) continue
            if (s.contains('%')) continue
            if (s.lowercase().startsWith("android.")) continue
            val candidate = when {
                s.matches(Regex("[0-9a-fA-F]{32}")) -> "32 hex chars (16 bytes) — possible AES-128 key or MD5"
                s.matches(Regex("[0-9a-fA-F]{48}")) -> "48 hex chars (24 bytes) — possible AES-192 key"
                s.matches(Regex("[0-9a-fA-F]{64}")) -> "64 hex chars (32 bytes) — possible AES-256 key or SHA-256"
                s.length == 16 && s.all { it.isLetterOrDigit() } -> "16 printable chars — possible raw AES-128 key"
                s.length == 24 && s.all { it.isLetterOrDigit() } -> "24 printable chars — possible raw AES-192 key or 3DES key"
                s.length == 32 && s.all { it.isLetterOrDigit() } -> "32 printable chars — possible raw AES-256 key"
                s.length == 24 && s.matches(Regex("[A-Za-z0-9+/]{22}==")) -> "Base64 16-byte value — possible key or IV"
                s.length == 44 && s.matches(Regex("[A-Za-z0-9+/]{43}=")) -> "Base64 32-byte value — possible key"
                else -> null
            } ?: continue
            if (s.all { it == s[0] }) continue
            if (seen.add(s)) out.add("${s.take(96)}  —  $candidate")
            if (out.size >= 40) break
        }
        return out
    }

    /** Reject binary string-table garbage, and replace stray controls in text. */
    private fun printable(value: String): String? {
        if (value.isEmpty() || value.contains('\u0000')) return null
        val controls = value.count { it.code < 32 && it != '\n' && it != '\t' }
        if (controls > value.length / 8 && value.length > 16) return null
        return value.map { ch ->
            if (ch == '\n' || ch == '\t' || ch.code in 32..126) ch else '·'
        }.joinToString("")
    }
}

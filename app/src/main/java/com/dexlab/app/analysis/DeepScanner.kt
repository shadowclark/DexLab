package com.dexlab.app.analysis

data class DeepFinding(
    val kind: String,
    val value: String
)

object DeepScanner {
    private val URL = Regex("""https?://[^\s"'<>\\]{4,240}""", RegexOption.IGNORE_CASE)
    private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val AWS_KEY = Regex("""\bAKIA[0-9A-Z]{16}\b""")
    private val GOOGLE_KEY = Regex("""\bAIza[0-9A-Za-z_-]{35}\b""")
    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b""")
    private val PRIVATE_KEY = Regex("""-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----""")
    private val ASSIGNMENT = Regex(
        """(?i)\b(api[_-]?key|secret|token|password|passwd|client[_-]?secret)\b\s*[:=]\s*["']?([A-Za-z0-9_./+=-]{8,})"""
    )

    fun scan(strings: List<String>, limit: Int = 120): List<DeepFinding> {
        val out = ArrayList<DeepFinding>()
        val seen = HashSet<String>()

        fun add(kind: String, value: String) {
            if (value.any { it.code == 0 || (it.code < 32 && it != '\n' && it != '\t') }) return
            val clean = value.trim().trim('"', '\'', ',', ';')
            if (clean.length < 4) return
            if (clean.startsWith("http://schemas.android.com")) return
            if (clean.startsWith("http://www.w3.org")) return
            if (clean.startsWith("https://schemas.android.com")) return
            if (clean.startsWith("android.permission")) return
            val key = "$kind|$clean"
            if (seen.add(key)) out.add(DeepFinding(kind, clean.take(240)))
        }

        for (raw in strings) {
            if (out.size >= limit) break
            if (raw.length > 4096) continue

            URL.findAll(raw).take(3).forEach { add("URL", it.value) }
            EMAIL.findAll(raw).take(2).forEach { add("Email", it.value) }
            AWS_KEY.findAll(raw).take(2).forEach { add("AWS access key", it.value) }
            GOOGLE_KEY.findAll(raw).take(2).forEach { add("Google API key", it.value) }
            JWT.findAll(raw).take(2).forEach { add("JWT", it.value) }
            PRIVATE_KEY.findAll(raw).take(1).forEach { add("Private key header", it.value) }

            IPV4.findAll(raw).take(3).forEach {
                val value = it.value
                val octets = value.split('.')
                if (octets.size == 4 && octets.all { part -> part.toIntOrNull() in 0..255 }) {
                    if (value != "0.0.0.0" && value != "127.0.0.1") add("IPv4", value)
                }
            }

            ASSIGNMENT.findAll(raw).take(2).forEach {
                val value = it.groupValues[2]
                if (!looksLikePlaceholder(value)) add("Secret-shaped assignment", "${it.groupValues[1]}=$value")
            }
        }

        return out
            .sortedWith(compareBy<DeepFinding> { it.kind }.thenBy { it.value })
            .take(limit)
    }

    private fun looksLikePlaceholder(value: String): Boolean {
        val v = value.lowercase()
        return v in setOf("password", "secret", "token", "apikey", "api_key", "example", "changeme", "null")
    }
}

package com.dexlab.app.diff

import com.dexlab.app.native.ElfParser
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.TreeMap
import java.util.zip.ZipFile

enum class BinaryKind(val label: String) {
    APK("APK"),
    DEX("DEX"),
    ELF("ELF / native"),
    ZIP("Zip archive"),
    OTHER("Binary")
}

data class DiffFile(
    val name: String,
    val path: String,
    val size: Long,
    val sha256: String,
    val kind: BinaryKind
)

enum class DiffChange(val label: String) {
    ADDED("added"),
    REMOVED("removed"),
    CHANGED("changed")
}

data class DiffRow(
    val name: String,
    val change: DiffChange,
    val before: String = "",
    val after: String = ""
)

data class ByteDifference(
    val differingBytes: Long,
    val firstDifference: Long?,
    val lastDifference: Long?,
    val comparedBytes: Long,
    val sameHash: Boolean,
    val similarity: Double,
    val note: String = ""
)

data class BinaryDiffReport(
    val a: DiffFile,
    val b: DiffFile,
    val bytes: ByteDifference,
    val entries: List<DiffRow>,
    val classes: List<DiffRow>,
    val functions: List<DiffRow>,
    val sections: List<DiffRow>,
    val symbols: List<DiffRow>,
    val notes: List<String>
) {
    val changedCount: Int
        get() = listOf(entries, classes, functions, sections, symbols).sumOf { rows ->
            rows.count { it.change != DiffChange.ADDED && it.change != DiffChange.REMOVED }
        }

    val addedCount: Int
        get() = listOf(entries, classes, functions, sections, symbols).sumOf { rows ->
            rows.count { it.change == DiffChange.ADDED }
        }

    val removedCount: Int
        get() = listOf(entries, classes, functions, sections, symbols).sumOf { rows ->
            rows.count { it.change == DiffChange.REMOVED }
        }
}

object BinaryDiffEngine {
    private const val MAX_BYTE_COMPARE = 64L * 1024L * 1024L
    private const val MAX_ROWS = 2_000

    fun compare(a: File, b: File): BinaryDiffReport {
        require(a.isFile) { "${a.name} is not a file" }
        require(b.isFile) { "${b.name} is not a file" }
        val fa = fingerprint(a)
        val fb = fingerprint(b)
        val notes = ArrayList<String>()

        var entries = emptyList<DiffRow>()
        var classes = emptyList<DiffRow>()
        var functions = emptyList<DiffRow>()
        var sections = emptyList<DiffRow>()
        var symbols = emptyList<DiffRow>()

        val pairZip = fa.kind == BinaryKind.APK || fa.kind == BinaryKind.ZIP
        val pairZipB = fb.kind == BinaryKind.APK || fb.kind == BinaryKind.ZIP
        when {
            pairZip && pairZipB -> {
                val za = runCatching { zipRows(a) }.onFailure { notes.add("Could not read ${a.name}: ${it.message}") }.getOrDefault(emptyMap())
                val zb = runCatching { zipRows(b) }.onFailure { notes.add("Could not read ${b.name}: ${it.message}") }.getOrDefault(emptyMap())
                entries = compareMaps(za, zb)
                if (fa.kind == BinaryKind.APK || fb.kind == BinaryKind.APK) {
                    notes.add("APK comparison uses archive entries. Split APKs are compared one file at a time.")
                }
            }
            fa.kind == BinaryKind.DEX && fb.kind == BinaryKind.DEX -> {
                val da = runCatching { dexRows(a) }.onFailure { notes.add("Could not parse ${a.name}: ${it.message}") }.getOrDefault(emptyMap())
                val db = runCatching { dexRows(b) }.onFailure { notes.add("Could not parse ${b.name}: ${it.message}") }.getOrDefault(emptyMap())
                classes = compareMaps(da, db)
                notes.add("DEX class changes are compared by method signature sets. Method bodies are not compiled or executed.")
            }
            fa.kind == BinaryKind.ELF && fb.kind == BinaryKind.ELF -> {
                val ea = runCatching { ElfParser().parse(a) }.onFailure { notes.add("Could not parse ${a.name}: ${it.message}") }.getOrNull()
                val eb = runCatching { ElfParser().parse(b) }.onFailure { notes.add("Could not parse ${b.name}: ${it.message}") }.getOrNull()
                if (ea != null && eb != null) {
                    functions = compareFunctions(ea, eb)
                    sections = compareSections(ea, eb)
                    symbols = compareImports(ea, eb)
                    if (ea.buildId != null || eb.buildId != null) {
                        notes.add("Build ID A: ${ea.buildId ?: "absent"}")
                        notes.add("Build ID B: ${eb.buildId ?: "absent"}")
                    }
                    notes.add("Function matching uses symbol names and addresses. Stripped functions can be ambiguous.")
                }
            }
            else -> {
                notes.add("No structured format was recognized, so only byte and hash differences are shown.")
            }
        }

        return BinaryDiffReport(
            a = fa,
            b = fb,
            bytes = compareBytes(a, b),
            entries = entries.take(MAX_ROWS),
            classes = classes.take(MAX_ROWS),
            functions = functions.take(MAX_ROWS),
            sections = sections.take(MAX_ROWS),
            symbols = symbols.take(MAX_ROWS),
            notes = notes
        )
    }

    fun render(report: BinaryDiffReport): String = buildString {
        appendLine("DexLab binary diff")
        appendLine("==================")
        appendLine()
        appendLine("A: ${report.a.name}")
        appendLine("   kind: ${report.a.kind.label}")
        appendLine("   size: ${report.a.size} bytes")
        appendLine("   sha256: ${report.a.sha256}")
        appendLine()
        appendLine("B: ${report.b.name}")
        appendLine("   kind: ${report.b.kind.label}")
        appendLine("   size: ${report.b.size} bytes")
        appendLine("   sha256: ${report.b.sha256}")
        appendLine()
        appendLine("Byte comparison")
        appendLine("---------------")
        appendLine("Identical SHA-256: ${if (report.bytes.sameHash) "yes" else "no"}")
        appendLine("Compared bytes: ${report.bytes.comparedBytes}")
        appendLine("Differing bytes: ${report.bytes.differingBytes}")
        appendLine("Similarity: ${"%.2f".format(report.bytes.similarity * 100)}%")
        appendLine("First difference: ${report.bytes.firstDifference?.let { "0x${it.toString(16)}" } ?: "none"}")
        appendLine("Last difference: ${report.bytes.lastDifference?.let { "0x${it.toString(16)}" } ?: "none"}")
        if (report.bytes.note.isNotBlank()) appendLine("Note: ${report.bytes.note}")
        appendLine()
        if (report.entries.isNotEmpty()) {
            appendLine("Archive entries")
            appendLine("---------------")
            report.entries.forEach { appendRow(it) }
            appendLine()
        }
        if (report.classes.isNotEmpty()) {
            appendLine("DEX classes")
            appendLine("-----------")
            report.classes.forEach { appendRow(it) }
            appendLine()
        }
        if (report.functions.isNotEmpty()) {
            appendLine("Native functions")
            appendLine("----------------")
            report.functions.forEach { appendRow(it) }
            appendLine()
        }
        if (report.sections.isNotEmpty()) {
            appendLine("ELF sections")
            appendLine("------------")
            report.sections.forEach { appendRow(it) }
            appendLine()
        }
        if (report.symbols.isNotEmpty()) {
            appendLine("Imported symbols")
            appendLine("----------------")
            report.symbols.forEach { appendRow(it) }
            appendLine()
        }
        if (report.notes.isNotEmpty()) {
            appendLine("Limits and notes")
            appendLine("----------------")
            report.notes.forEach { appendLine("- $it") }
        }
    }

    private fun StringBuilder.appendRow(row: DiffRow) {
        append(row.change.label.padEnd(8))
        append(row.name)
        val detail = listOf(row.before, row.after).filter { it.isNotBlank() }.joinToString(" -> ")
        if (detail.isNotBlank()) append("  ").append(detail)
        appendLine()
    }

    private fun fingerprint(file: File): DiffFile = DiffFile(
        name = file.name,
        path = file.absolutePath,
        size = file.length(),
        sha256 = sha256(file),
        kind = detectKind(file)
    )

    private fun detectKind(file: File): BinaryKind {
        val lower = file.name.lowercase()
        if (lower.endsWith(".apk")) return BinaryKind.APK
        if (lower.endsWith(".dex")) return BinaryKind.DEX
        if (lower.endsWith(".so") || lower.endsWith(".elf")) return BinaryKind.ELF
        if (lower.endsWith(".zip") || lower.endsWith(".apks") || lower.endsWith(".xapk") || lower.endsWith(".jar")) {
            return BinaryKind.ZIP
        }
        val head = ByteArray(4)
        val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
        return when {
            n >= 4 && head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() &&
                head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte() -> BinaryKind.ELF
            n >= 4 && head[0] == 'd'.code.toByte() && head[1] == 'e'.code.toByte() &&
                head[2] == 'x'.code.toByte() && head[3] == '\n'.code.toByte() -> BinaryKind.DEX
            n >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> BinaryKind.ZIP
            else -> BinaryKind.OTHER
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun compareBytes(a: File, b: File): ByteDifference {
        val lengthA = a.length()
        val lengthB = b.length()
        val compared = minOf(lengthA, lengthB, MAX_BYTE_COMPARE)
        var differing = 0L
        var first: Long? = null
        var last: Long? = null
        var offset = 0L
        val bufferA = ByteArray(64 * 1024)
        val bufferB = ByteArray(64 * 1024)
        RandomAccessFile(a, "r").use { ra ->
            RandomAccessFile(b, "r").use { rb ->
                while (offset < compared) {
                    val want = minOf(bufferA.size.toLong(), compared - offset).toInt()
                    val readA = ra.read(bufferA, 0, want)
                    val readB = rb.read(bufferB, 0, want)
                    if (readA <= 0 || readB <= 0) break
                    val n = minOf(readA, readB)
                    for (i in 0 until n) {
                        if (bufferA[i] != bufferB[i]) {
                            differing++
                            if (first == null) first = offset + i
                            last = offset + i
                        }
                    }
                    offset += n
                }
            }
        }
        val tail = (maxOf(lengthA, lengthB) - compared).coerceAtLeast(0)
        if (tail > 0) {
            differing += tail
            if (first == null) first = compared
            last = maxOf(lengthA, lengthB) - 1
        }
        val denom = maxOf(lengthA, lengthB).coerceAtLeast(1)
        val note = if (maxOf(lengthA, lengthB) > MAX_BYTE_COMPARE) {
            "Byte scan capped at ${MAX_BYTE_COMPARE} bytes; structural lists are complete."
        } else {
            ""
        }
        return ByteDifference(
            differingBytes = differing,
            firstDifference = first,
            lastDifference = last,
            comparedBytes = compared,
            sameHash = sha256(a) == sha256(b),
            similarity = (1.0 - differing.toDouble() / denom).coerceIn(0.0, 1.0),
            note = note
        )
    }

    private fun zipRows(file: File): Map<String, String> {
        ZipFile(file).use { zip ->
            val out = TreeMap<String, String>()
            val e = zip.entries()
            while (e.hasMoreElements()) {
                val entry = e.nextElement()
                if (entry.isDirectory) continue
                out[entry.name] = "crc=${java.lang.Long.toHexString(entry.crc)} size=${entry.size} compressed=${entry.compressedSize} method=${entry.method}"
            }
            return out
        }
    }

    private fun dexRows(file: File): Map<String, String> {
        val dex = DexFileFactory.loadDexFile(file, Opcodes.getDefault())
        val out = TreeMap<String, String>()
        dex.classes.forEach { c ->
            val methods = c.methods.map { method ->
                method.name + method.parameterTypes.joinToString("") + method.returnType
            }.sorted()
            out[c.type] = "${methods.size} methods · ${sha256(methods.joinToString("\n").toByteArray()).take(16)}"
        }
        return out
    }

    private fun compareMaps(
        a: Map<String, String>,
        b: Map<String, String>
    ): List<DiffRow> {
        val out = ArrayList<DiffRow>()
        val keys = (a.keys + b.keys).sorted()
        for (key in keys) {
            val left = a[key]
            val right = b[key]
            when {
                left == null -> out.add(DiffRow(key, DiffChange.ADDED, after = right.orEmpty()))
                right == null -> out.add(DiffRow(key, DiffChange.REMOVED, before = left))
                left != right -> out.add(DiffRow(key, DiffChange.CHANGED, before = left, after = right))
            }
        }
        return out
    }

    private fun compareFunctions(a: com.dexlab.app.native.ElfInfo, b: com.dexlab.app.native.ElfInfo): List<DiffRow> {
        fun map(info: com.dexlab.app.native.ElfInfo): Map<String, String> {
            val out = TreeMap<String, String>()
            info.functions.forEachIndexed { index, fn ->
                val key = if (out.containsKey(fn.name)) "${fn.name}#$index" else fn.name
                val digest = runCatching { sha256(info.functionBytes(fn)) }.getOrNull()
                out[key] = "0x${fn.address.toString(16)} · ${fn.size} bytes · ${digest?.take(16) ?: "unmapped"}"
            }
            return out
        }
        return compareMaps(map(a), map(b))
    }

    private fun compareSections(a: com.dexlab.app.native.ElfInfo, b: com.dexlab.app.native.ElfInfo): List<DiffRow> {
        fun map(info: com.dexlab.app.native.ElfInfo): Map<String, String> =
            info.sections.associate { section ->
                section.name to "0x${section.addr.toString(16)} · ${section.size} bytes"
            }
        return compareMaps(map(a), map(b))
    }

    private fun compareImports(a: com.dexlab.app.native.ElfInfo, b: com.dexlab.app.native.ElfInfo): List<DiffRow> {
        fun map(info: com.dexlab.app.native.ElfInfo): Map<String, String> =
            info.imports.map { it.name }.distinct().sorted().associateWith { "" }
        return compareMaps(map(a), map(b))
    }
}

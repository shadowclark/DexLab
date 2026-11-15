package com.dexlab.app.apk

import com.dexlab.app.native.ElfBuildId
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkArchive(val files: List<File>) : Closeable {
    data class Entry(
        val name: String,
        val size: Long,
        val compressedSize: Long,
        val method: Int,
        val container: File
    ) {
        val stored: Boolean get() = method == ZipEntry.STORED
    }

    /** Files under one lib/<abi>/ directory. Empty ABIs are ignored. */
    data class AbiGroup(val abi: String, val files: List<Entry>) {
        val soCount: Int get() = files.count { it.name.endsWith(".so") }
        val bytes: Long get() = files.sumOf { it.size.coerceAtLeast(0) }
    }

    private val zips: List<ZipFile> = files.map { ZipFile(it) }

    val entries: List<Entry> by lazy {
        val out = ArrayList<Entry>()
        for (zip in zips) {
            val e = zip.entries()
            while (e.hasMoreElements()) {
                val entry = e.nextElement()
                if (entry.isDirectory) continue
                out.add(Entry(entry.name, entry.size, entry.compressedSize, entry.method, File(zip.name)))
            }
        }
        out
    }

    fun read(name: String): ByteArray? {
        for (zip in zips) {
            val entry = zip.getEntry(name) ?: continue
            return zip.getInputStream(entry).use { it.readBytes() }
        }
        return null
    }

    fun extract(name: String, dest: File): File? {
        val bytes = read(name) ?: return null
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        return dest
    }

    fun contains(name: String): Boolean = zips.any { it.getEntry(name) != null }

    /**
     * Copies one APK entry directly from its source ZIP. This avoids loading
     * a large Roblox-sized .so into memory during an ABI export.
     */
    fun copyEntry(entry: Entry, out: OutputStream): Boolean {
        val input = openEntry(entry) ?: return false
        input.use { it.copyTo(out) }
        return true
    }

    /**
     * Writes the files under lib/<abi>/ to a ZIP, preserving the APK's directory layout.
     * Returns the number of files written.
     */
    fun writeAbiZip(abi: String, out: OutputStream, projectName: String? = null): Int {
        val group = abiGroups.firstOrNull { it.abi == abi }
            ?: error("This APK has no lib/$abi files")
        var written = 0
        val records = ArrayList<ManifestRecord>()
        ZipOutputStream(out).use { zip ->
            for (entry in group.files) {
                val input = openEntry(entry) ?: continue
                val buildId = if (entry.name.endsWith(".so", ignoreCase = true)) {
                    openEntry(entry)?.use { ElfBuildId.read(it) }
                } else {
                    null
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                zip.putNextEntry(ZipEntry(entry.name))
                input.use { src ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = src.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        zip.write(buffer, 0, read)
                        size += read
                    }
                }
                zip.closeEntry()
                records.add(ManifestRecord(entry.name, size, digest.digest().toHex(), buildId))
                written++
            }
            require(written > 0) { "No readable files in lib/$abi" }
            val manifest = buildManifest(abi, projectName, records)
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return written
    }

    private fun openEntry(entry: Entry): InputStream? {
        val zip = zips.firstOrNull { it.name == entry.container.path } ?: return null
        val zipEntry = zip.getEntry(entry.name) ?: return null
        return zip.getInputStream(zipEntry)
    }

    private data class ManifestRecord(
        val name: String,
        val size: Long,
        val sha256: String,
        val buildId: String?
    )

    private fun buildManifest(abi: String, projectName: String?, records: List<ManifestRecord>): String = buildString {
        appendLine("DexLab ABI manifest")
        appendLine("=================")
        appendLine("Project: ${projectName?.ifBlank { "APK" } ?: "APK"}")
        appendLine("ABI: $abi")
        appendLine("Architecture: ${abiArchitecture(abi)}")
        appendLine("Files: ${records.size}")
        val soRecords = records.filter { it.name.endsWith(".so", ignoreCase = true) }
        appendLine("Build IDs: ${soRecords.count { it.buildId != null }} / ${soRecords.size} present (ELF PT_NOTE)")
        appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("SHA-256 covers the exact bytes written to this ZIP.")
        appendLine()
        for (record in records) {
            appendLine(record.name)
            appendLine("  architecture: ${abiArchitecture(abi)}")
            appendLine("  size: ${record.size} bytes")
            appendLine("  sha256: ${record.sha256}")
            appendLine(
                "  build-id: ${record.buildId ?: if (record.name.endsWith(".so", ignoreCase = true)) "not present" else "n/a"}"
            )
            appendLine()
        }
    }

    val dexNames: List<String>
        get() = entries.map { it.name }
            .filter { DEX_NAME.matches(it) }
            .distinct()
            .sortedWith(compareBy({ it.length }, { it }))

    val soNames: List<String>
        get() = entries.map { it.name }
            .filter { it.startsWith("lib/") && it.endsWith(".so") }
            .distinct()
            .sorted()

    val abiGroups: List<AbiGroup>
        get() {
            val grouped = LinkedHashMap<String, MutableList<Entry>>()
            for (entry in entries) {
                val parts = entry.name.split('/')
                if (parts.size < 3 || parts[0] != "lib" || parts[1].isBlank()) continue
                grouped.getOrPut(parts[1]) { ArrayList() }.add(entry)
            }
            return grouped.map { (abi, files) ->
                AbiGroup(abi, files.distinctBy { it.name }.sortedBy { it.name })
            }.sortedWith(compareBy({ abiRank(it.abi) }, { it.abi }))
        }

    val assetCount: Int get() = entries.count { it.name.startsWith("assets/") }
    val totalSize: Long get() = entries.sumOf { it.size.coerceAtLeast(0) }

    override fun close() {
        zips.forEach { runCatching { it.close() } }
    }

    companion object {
        private val DEX_NAME = Regex("""classes\d*\.dex""")

        private const val MANIFEST_NAME = "ABI-MANIFEST.txt"

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun abiArchitecture(abi: String): String = when (abi) {
            "arm64-v8a" -> "AArch64 (64-bit ARM)"
            "armeabi-v7a" -> "ARMv7 (32-bit ARM)"
            "armeabi" -> "ARM (legacy 32-bit)"
            "x86_64" -> "x86-64"
            "x86" -> "x86 (32-bit)"
            "riscv64" -> "RISC-V 64-bit"
            else -> "unknown ABI"
        }

        private fun abiRank(abi: String): Int = when (abi) {
            "arm64-v8a" -> 0
            "armeabi-v7a" -> 1
            "x86_64" -> 2
            "x86" -> 3
            "armeabi" -> 4
            "riscv64" -> 5
            else -> 6
        }
    }
}

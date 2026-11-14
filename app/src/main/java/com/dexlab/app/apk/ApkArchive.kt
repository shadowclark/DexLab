package com.dexlab.app.apk

import java.io.Closeable
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

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

    val assetCount: Int get() = entries.count { it.name.startsWith("assets/") }
    val totalSize: Long get() = entries.sumOf { it.size.coerceAtLeast(0) }

    override fun close() {
        zips.forEach { runCatching { it.close() } }
    }

    companion object {
        private val DEX_NAME = Regex("""classes\d*\.dex""")
    }
}

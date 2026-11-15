package com.dexlab.app.core

import java.io.File
import java.util.zip.ZipFile

/**
 * Expands .apkm/.apks/.xapk split bundles into their nested APK files so
 * ApkArchive can expose the base DEX plus each ABI's native libraries.
 */
object BundleSplitter {
    private val extensions = setOf("apkm", "apks", "xapk")

    fun isBundle(file: File): Boolean = file.extension.lowercase() in extensions

    /** Returns base APK first, or null when the file is not a usable bundle. */
    fun extract(bundle: File): List<File>? {
        if (!isBundle(bundle)) return null
        val outDir = File(bundle.parentFile, "splits").apply { mkdirs() }
        val extracted = ArrayList<File>()
        try {
            ZipFile(bundle).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.lowercase().endsWith(".apk")) continue
                    val safe = entry.name.substringAfterLast('/')
                        .replace(Regex("""[\\/:*?"<>|]"""), "_")
                        .ifBlank { "split.apk" }
                    var dest = File(outDir, safe)
                    var n = 1
                    while (dest.exists()) dest = File(outDir, "${n++}_$safe")
                    zip.getInputStream(entry).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    extracted.add(dest)
                }
            }
        } catch (t: Throwable) {
            outDir.deleteRecursively()
            return null
        }
        if (extracted.isEmpty()) {
            outDir.deleteRecursively()
            return null
        }
        bundle.delete()
        return extracted.sortedWith(
            compareBy<File> { if (it.name.equals("base.apk", ignoreCase = true)) 0 else 1 }
                .thenBy { it.name }
        )
    }
}
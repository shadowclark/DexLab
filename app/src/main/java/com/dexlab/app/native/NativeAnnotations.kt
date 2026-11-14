package com.dexlab.app.native

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

data class NativeAnnotation(
    val label: String = "",
    val comment: String = "",
    val bookmarked: Boolean = false
) {
    val isEmpty: Boolean get() = label.isBlank() && comment.isBlank() && !bookmarked

    fun displayName(original: String): String = label.ifBlank { original }
}

/**
 * Local analyst notes. They never touch the analyzed file and are keyed by a
 * stable binary identity plus function address, so notes survive reopening the
 * same library from a different project.
 */
object NativeAnnotations {
    private const val PREFS = "dexlab_native_annotations"

    fun get(context: Context, elf: ElfInfo, function: ElfFunction): NativeAnnotation {
        val raw = prefs(context).getString(key(elf, function.address), null) ?: return NativeAnnotation()
        return decode(raw)
    }

    fun all(context: Context, elf: ElfInfo, functions: List<ElfFunction>): Map<Long, NativeAnnotation> {
        val p = prefs(context)
        val out = LinkedHashMap<Long, NativeAnnotation>()
        functions.forEach { function ->
            p.getString(key(elf, function.address), null)?.let { raw ->
                val annotation = decode(raw)
                if (!annotation.isEmpty) out[function.address] = annotation
            }
        }
        return out
    }

    fun save(context: Context, elf: ElfInfo, function: ElfFunction, annotation: NativeAnnotation) {
        val editor = prefs(context).edit()
        val key = key(elf, function.address)
        if (annotation.isEmpty) {
            editor.remove(key).apply()
            return
        }
        val json = JSONObject().apply {
            put("label", annotation.label.trim())
            put("comment", annotation.comment.trim())
            put("bookmarked", annotation.bookmarked)
        }
        editor.putString(key, json.toString()).apply()
    }

    private fun decode(raw: String): NativeAnnotation = runCatching {
        val json = JSONObject(raw)
        NativeAnnotation(
            label = json.optString("label").orEmpty(),
            comment = json.optString("comment").orEmpty(),
            bookmarked = json.optBoolean("bookmarked")
        )
    }.getOrDefault(NativeAnnotation())

    private fun key(elf: ElfInfo, address: Long): String {
        val binary = elf.buildId
            ?: listOfNotNull(elf.soname, elf.arch.name, elf.bytes.size.toString()).joinToString("-")
        val safe = binary.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(96)
        return "$safe:${address.toString(16)}"
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

package com.dexlab.app.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object ToolFiles {
    suspend fun import(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "file"
        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80).ifBlank { "file" }
        val dir = File(context.cacheDir, "tools").apply { mkdirs() }
        val dest = File(dir, "${UUID.randomUUID().toString().take(8)}_$safe")
        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open $name")
        input.use { src -> dest.outputStream().use { src.copyTo(it) } }
        dest
    }
}

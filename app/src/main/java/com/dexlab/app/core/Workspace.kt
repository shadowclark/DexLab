package com.dexlab.app.core

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.dexlab.app.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class Workspace(private val context: Context) {
    private val root: File = File(context.filesDir, "workspaces").apply { mkdirs() }

    suspend fun list(): List<Project> = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty()
            .mapNotNull { readMeta(it) }
            .sortedByDescending { it.createdAt }
    }

    suspend fun get(id: String): Project? = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().firstOrNull { it.name == id }?.let { readMeta(it) }
    }

    fun projectDir(id: String): File = File(root, id)

    fun cacheDir(id: String): File = File(projectDir(id), "cache").apply { mkdirs() }

    fun size(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    suspend fun importUris(uris: List<Uri>): Project = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "No file selected" }
        val resolver = context.contentResolver
        val dir = newDir()
        val input = File(dir, "input").apply { mkdirs() }
        val copied = uris.mapIndexed { index, uri -> copyUri(resolver, uri, input, index) }
        val sourceName = copied.firstOrNull()?.name ?: "Imported file"
        val files = if (copied.size == 1) {
            BundleSplitter.extract(copied.first()) ?: copied
        } else {
            copied
        }
        val kind = detectKind(files)
        val project = Project(
            id = dir.name,
            name = if (files !== copied) sourceName else files.firstOrNull()?.name ?: sourceName,
            kind = kind,
            files = files.map { it.absolutePath },
            sizeBytes = files.sumOf { it.length() },
            createdAt = System.currentTimeMillis()
        )
        writeMeta(dir, project)
        project
    }

    suspend fun importInstalled(packageName: String): Project = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_META_DATA or
            (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
        val pi = pm.getPackageInfo(packageName, flags)
        val ai = pi.applicationInfo ?: error("No application info for $packageName")
        val paths = buildList {
            add(ai.sourceDir)
            ai.splitSourceDirs?.let { addAll(it) }
        }.filter { it.isNotBlank() }

        val dir = newDir()
        val input = File(dir, "input").apply { mkdirs() }
        val files = try {
            paths.mapIndexed { index, path ->
                val src = File(path)
                val out = File(input, if (index == 0) src.name else "${index}_${src.name}")
                src.copyTo(out, overwrite = true)
                out
            }
        } catch (t: Throwable) {
            // Normal package-manager access is blocked on some OEM builds.
            // Shizuku's shell identity can open the APK files directly.
            if (ShizukuManager.isReady()) {
                runCatching { ShizukuManager.copyInstalledApks(packageName, input) }
                    .getOrElse {
                        dir.deleteRecursively()
                        throw t
                    }
            } else {
                dir.deleteRecursively()
                throw t
            }
        }
        val label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(packageName)
        val project = Project(
            id = dir.name,
            name = label,
            kind = ProjectKind.APK,
            files = files.map { it.absolutePath },
            sizeBytes = files.sumOf { it.length() },
            createdAt = System.currentTimeMillis(),
            packageName = packageName,
            label = label,
            versionName = pi.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong(),
            minSdk = ai.minSdkVersion,
            targetSdk = ai.targetSdkVersion
        )
        writeMeta(dir, project)
        project
    }

    suspend fun importSelf(): Project = withContext(Dispatchers.IO) {
        val src = File(context.applicationInfo.sourceDir)
        val dir = newDir()
        val input = File(dir, "input").apply { mkdirs() }
        val out = File(input, "dexlab.apk")
        src.copyTo(out, overwrite = true)
        val project = Project(
            id = dir.name,
            name = "DexLab (self)",
            kind = ProjectKind.APK,
            files = listOf(out.absolutePath),
            sizeBytes = out.length(),
            createdAt = System.currentTimeMillis(),
            packageName = context.packageName,
            label = "DexLab"
        )
        writeMeta(dir, project)
        project
    }

    suspend fun importAsset(assetPath: String, outName: String): Project = withContext(Dispatchers.IO) {
        val dir = newDir()
        val input = File(dir, "input").apply { mkdirs() }
        val out = File(input, outName)
        context.assets.open(assetPath).use { inputStream ->
            out.outputStream().use { inputStream.copyTo(it) }
        }
        val project = Project(
            id = dir.name,
            name = outName,
            kind = detectKind(listOf(out)),
            files = listOf(out.absolutePath),
            sizeBytes = out.length(),
            createdAt = System.currentTimeMillis()
        )
        writeMeta(dir, project)
        project
    }

    suspend fun importNativeLib(name: String): Project = withContext(Dispatchers.IO) {
        val src = File(context.applicationInfo.nativeLibraryDir, name)
        require(src.exists()) { "$name is not installed for this ABI" }
        val dir = newDir()
        val input = File(dir, "input").apply { mkdirs() }
        val out = File(input, name)
        src.copyTo(out, overwrite = true)
        val project = Project(
            id = dir.name,
            name = name,
            kind = ProjectKind.ELF,
            files = listOf(out.absolutePath),
            sizeBytes = out.length(),
            createdAt = System.currentTimeMillis()
        )
        writeMeta(dir, project)
        project
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        Unit
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().forEach { dir -> File(dir, "cache").deleteRecursively() }
        Unit
    }

    private fun newDir(): File = File(root, UUID.randomUUID().toString()).apply { mkdirs() }

    private fun copyUri(resolver: ContentResolver, uri: Uri, dir: File, index: Int): File {
        val name = queryName(resolver, uri) ?: "file_$index"
        val safe = sanitize(name)
        val out = File(dir, if (index == 0) safe else "${index}_$safe")
        val input = resolver.openInputStream(uri) ?: error("Cannot open $uri")
        input.use { src -> out.outputStream().use { src.copyTo(it) } }
        return out
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        return cleaned.ifBlank { "file" }.take(96)
    }

    private fun detectKind(files: List<File>): ProjectKind {
        val f = files.firstOrNull() ?: return ProjectKind.FILE
        val lower = f.name.lowercase()
        if (lower.endsWith(".apk")) return ProjectKind.APK
        if (lower.endsWith(".dex")) return ProjectKind.DEX
        if (lower.endsWith(".so")) return ProjectKind.ELF
        if (lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".apks") || lower.endsWith(".xapk")) {
            return ProjectKind.ARCHIVE
        }
        val head = runCatching {
            f.inputStream().use { input ->
                val b = ByteArray(8)
                val n = input.read(b)
                b.copyOf(n)
            }
        }.getOrDefault(ByteArray(0))
        return when {
            head.size >= 4 && head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() &&
                head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte() -> ProjectKind.ELF
            head.size >= 4 && head[0] == 'd'.code.toByte() && head[1] == 'e'.code.toByte() &&
                head[2] == 'x'.code.toByte() && head[3] == '\n'.code.toByte() -> ProjectKind.DEX
            head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> ProjectKind.ARCHIVE
            else -> ProjectKind.FILE
        }
    }

    private fun writeMeta(dir: File, project: Project) {
        val json = JSONObject().apply {
            put("id", project.id)
            put("name", project.name)
            put("kind", project.kind.name)
            put("files", JSONArray(project.files))
            put("size", project.sizeBytes)
            put("created", project.createdAt)
            project.packageName?.let { put("packageName", it) }
            project.label?.let { put("label", it) }
            project.versionName?.let { put("versionName", it) }
            project.versionCode?.let { put("versionCode", it) }
            project.minSdk?.let { put("minSdk", it) }
            project.targetSdk?.let { put("targetSdk", it) }
        }
        File(dir, "meta.json").writeText(json.toString(2))
    }

    private fun readMeta(dir: File): Project? {
        val file = File(dir, "meta.json")
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val files = json.getJSONArray("files")
            val fileList = ArrayList<String>(files.length())
            for (i in 0 until files.length()) fileList.add(files.getString(i))
            Project(
                id = json.getString("id"),
                name = json.getString("name"),
                kind = ProjectKind.valueOf(json.getString("kind")),
                files = fileList,
                sizeBytes = json.optLong("size"),
                createdAt = json.optLong("created"),
                packageName = json.optStringOrNull("packageName"),
                label = json.optStringOrNull("label"),
                versionName = json.optStringOrNull("versionName"),
                versionCode = if (json.has("versionCode")) json.optLong("versionCode") else null,
                minSdk = if (json.has("minSdk")) json.optInt("minSdk") else null,
                targetSdk = if (json.has("targetSdk")) json.optInt("targetSdk") else null
            )
        }.getOrNull()
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)

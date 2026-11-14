package com.dexlab.app.core

import android.content.Context
import com.dexlab.app.apk.AndroidAttrNames
import com.dexlab.app.apk.ApkArchive
import com.dexlab.app.apk.ApkSummary
import com.dexlab.app.apk.ApkSummaryReader
import com.dexlab.app.apk.AxmlParser
import com.dexlab.app.apk.ManifestReader
import com.dexlab.app.apk.ManifestSummary
import com.dexlab.app.apk.prettyXml
import com.dexlab.app.dex.ApkDexSource
import com.dexlab.app.dex.DexRepository
import com.dexlab.app.dex.FileDexSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.LinkedHashMap

class ProjectSession(
    val project: Project,
    val archive: ApkArchive?,
    val summary: ApkSummary?,
    val manifest: ManifestSummary?,
    val manifestXml: String?,
    val dex: DexRepository?,
    val elfFile: File?,
    val errors: List<String>
)

object SessionCache {
    private val lock = Any()
    private val map = object : LinkedHashMap<String, ProjectSession>(3, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ProjectSession>?): Boolean = size > 3
    }

    suspend fun get(context: Context, id: String): ProjectSession? = withContext(Dispatchers.IO) {
        synchronized(lock) { map[id] }?.let { return@withContext it }
        val project = Workspace(context).get(id) ?: return@withContext null
        val session = build(context, project)
        synchronized(lock) { map[id] = session }
        session
    }

    fun invalidate(id: String) {
        synchronized(lock) {
            map.remove(id)?.archive?.let { runCatching { it.close() } }
        }
    }

    fun clear() {
        synchronized(lock) {
            map.values.forEach { runCatching { it.archive?.close() } }
            map.clear()
        }
    }

    private fun build(context: Context, project: Project): ProjectSession {
        val errors = ArrayList<String>()
        var archive: ApkArchive? = null
        var summary: ApkSummary? = null
        var manifest: ManifestSummary? = null
        var manifestXml: String? = null
        var dex: DexRepository? = null
        var elfFile: File? = null
        val cache = Workspace(context).cacheDir(project.id)
        val files = project.files.map(::File).filter { it.exists() }
        if (files.isEmpty()) errors.add("Project files are missing from the workspace")

        try {
            when (project.kind) {
                ProjectKind.APK, ProjectKind.ARCHIVE -> {
                    if (files.isNotEmpty()) {
                        archive = ApkArchive(files)
                        if (project.kind == ProjectKind.APK) {
                            summary = runCatching { ApkSummaryReader.read(context, files.first()) }.getOrNull()
                            val raw = archive.read("AndroidManifest.xml")
                            if (raw != null) {
                                val root = runCatching {
                                    AxmlParser(AndroidAttrNames.resolver).parse(raw)
                                }.getOrElse {
                                    errors.add("Manifest parse failed: ${it.message}")
                                    null
                                }
                                if (root != null) {
                                    manifestXml = root.prettyXml()
                                    manifest = ManifestReader.summarize(root)
                                }
                            } else {
                                errors.add("No AndroidManifest.xml found in this APK")
                            }
                        }
                        if (archive.dexNames.isNotEmpty()) {
                            dex = DexRepository(ApkDexSource(archive), cache)
                        }
                    }
                }
                ProjectKind.DEX -> {
                    if (files.isNotEmpty()) {
                        dex = DexRepository(FileDexSource(files.first()), cache)
                    }
                }
                ProjectKind.ELF -> {
                    if (files.isNotEmpty()) elfFile = files.first()
                }
                ProjectKind.FILE -> {}
            }
        } catch (t: Throwable) {
            errors.add("${t.javaClass.simpleName}: ${t.message}")
        }

        return ProjectSession(project, archive, summary, manifest, manifestXml, dex, elfFile, errors)
    }
}

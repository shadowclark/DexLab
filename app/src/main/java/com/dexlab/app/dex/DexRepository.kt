package com.dexlab.app.dex

import com.dexlab.app.apk.ApkArchive
import org.jf.baksmali.Baksmali
import org.jf.baksmali.BaksmaliOptions
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import java.io.File
import java.util.LinkedHashMap
import java.util.LinkedHashSet

interface DexSource {
    fun names(): List<String>
    fun read(name: String): ByteArray?
}

class ApkDexSource(private val archive: ApkArchive) : DexSource {
    override fun names(): List<String> = archive.dexNames
    override fun read(name: String): ByteArray? = archive.read(name)
}

class FileDexSource(private val file: File) : DexSource {
    override fun names(): List<String> = listOf("classes.dex")
    override fun read(name: String): ByteArray? = runCatching { file.readBytes() }.getOrNull()
}

data class DexClassInfo(
    val descriptor: String,
    val displayName: String,
    val packageName: String,
    val methodCount: Int,
    val fieldCount: Int,
    val methods: List<String> = emptyList()
)

class DexRepository(private val source: DexSource, private val cacheDir: File) {
    private val loaded = HashMap<String, DexFile>()
    private val classCache = HashMap<String, List<DexClassInfo>>()
    private val smaliCache = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 12
    }
    private val javaCache = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 12
    }

    val dexNames: List<String> get() = source.names()
    val hasDex: Boolean get() = dexNames.isNotEmpty()

    @Synchronized
    fun dex(name: String): DexFile {
        loaded[name]?.let { return it }
        val bytes = source.read(name) ?: error("Could not read $name")
        val dir = File(cacheDir, "dex").apply { mkdirs() }
        val file = File(dir, name.replace('/', '_').replace('\\', '_'))
        file.writeBytes(bytes)
        val parsed = DexFileFactory.loadDexFile(file, Opcodes.getDefault())
        loaded[name] = parsed
        return parsed
    }

    fun classes(name: String): List<DexClassInfo> {
        classCache[name]?.let { return it }
        val dex = dex(name)
        val out = ArrayList<DexClassInfo>(dex.classes.size)
        for (c in dex.classes) {
            val display = displayName(c.type)
            val methods = c.methods.map { it.name }.distinct()
            out.add(
                DexClassInfo(
                    descriptor = c.type,
                    displayName = display,
                    packageName = display.substringBeforeLast('.', ""),
                    methodCount = c.methods.count(),
                    fieldCount = c.fields.count(),
                    methods = methods
                )
            )
        }
        out.sortBy { it.displayName }
        classCache[name] = out
        return out
    }

    fun smali(name: String, descriptor: String): String {
        val key = "$name:$descriptor"
        synchronized(smaliCache) { smaliCache[key]?.let { return it } }
        val dir = File(cacheDir, "smali/${name.hashCode()}/${descriptor.hashCode()}")
        dir.deleteRecursively()
        dir.mkdirs()
        val ok = Baksmali.disassembleDexFile(
            dex(name),
            dir,
            1,
            BaksmaliOptions(),
            listOf(descriptor)
        )
        if (!ok) error("baksmali could not disassemble $descriptor")
        val file = dir.walkTopDown().firstOrNull { it.isFile && it.extension == "smali" }
            ?: error("baksmali produced no output for $descriptor")
        val text = file.readText()
        synchronized(smaliCache) { smaliCache[key] = text }
        return text
    }

    fun java(name: String, descriptor: String): String {
        val key = "$name:$descriptor"
        synchronized(javaCache) { javaCache[key]?.let { return it } }
        val text = DexJavaDecompiler.generate(dex(name), descriptor)
        synchronized(javaCache) { javaCache[key] = text }
        return text
    }

    fun strings(name: String, limit: Int = 200_000): List<String> {
        val bytes = source.read(name) ?: return emptyList()
        return DexStrings.readBytes(bytes, limit)
    }

    fun methodRefs(name: String, limit: Int = 50_000): List<String> {
        val out = LinkedHashSet<String>()
        outer@ for (c in dex(name).classes) {
            for (m in c.methods) {
                val impl = m.implementation ?: continue
                for (insn in impl.instructions) {
                    if (insn is ReferenceInstruction) {
                        val ref = insn.reference
                        if (ref is MethodReference) {
                            out.add(
                                "${ref.definingClass}->${ref.name}(" +
                                    ref.parameterTypes.joinToString("") + ")" + ref.returnType
                            )
                            if (out.size >= limit) break@outer
                        }
                    }
                }
            }
        }
        return out.toList()
    }

    fun callers(
        name: String,
        targetClass: String,
        targetName: String,
        limit: Int = 500,
        onProgress: (Int) -> Unit = {}
    ): List<String> {
        val dex = dex(name)
        val out = LinkedHashSet<String>()
        var scanned = 0
        outer@ for (c in dex.classes) {
            scanned++
            if (scanned % 500 == 0) onProgress(scanned)
            for (m in c.methods) {
                val impl = m.implementation ?: continue
                for (insn in impl.instructions) {
                    if (insn is ReferenceInstruction) {
                        val ref = insn.reference
                        if (ref is MethodReference && ref.definingClass == targetClass && ref.name == targetName) {
                            out.add("${displayName(c.type)} -> ${m.name}")
                            if (out.size >= limit) break@outer
                        }
                    }
                }
            }
            if (out.size >= limit) break
        }
        onProgress(scanned)
        return out.toList()
    }

    companion object {
        fun displayName(descriptor: String): String =
            descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
    }
}

package com.dexlab.app.native

import com.dexlab.app.core.Fmt
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeSet

enum class ElfArch(val label: String) {
    ARM("ARM"),
    ARM64("AArch64"),
    X86("x86"),
    X86_64("x86-64"),
    RISCV("RISC-V"),
    MIPS("MIPS"),
    UNKNOWN("Unknown")
}

data class ElfSection(
    val name: String,
    val type: Long,
    val flags: Long,
    val addr: Long,
    val offset: Long,
    val size: Long,
    val link: Int,
    val info: Int,
    val addralign: Long,
    val entsize: Long
) {
    val executable: Boolean get() = (flags and 0x4L) != 0L
    val writable: Boolean get() = (flags and 0x1L) != 0L
}

data class ElfSegment(
    val type: Long,
    val flags: Int,
    val offset: Long,
    val vaddr: Long,
    val filesz: Long,
    val memsz: Long
) {
    val executable: Boolean get() = (flags and 0x1) != 0
    val writable: Boolean get() = (flags and 0x2) != 0
    val readable: Boolean get() = (flags and 0x4) != 0
}

data class ElfSymbol(
    val name: String,
    val value: Long,
    val size: Long,
    val info: Int,
    val other: Int,
    val shndx: Int,
    val thumb: Boolean
) {
    val type: Int get() = info and 0xF
    val bind: Int get() = info shr 4
    val isFunction: Boolean get() = type == 2
    val isUndefined: Boolean get() = shndx == 0
    val isGlobal: Boolean get() = bind == 1 || bind == 2
}

data class ElfFunction(
    val name: String,
    val address: Long,
    val size: Long,
    val source: String,
    val thumb: Boolean
)

data class ElfInfo(
    val arch: ElfArch,
    val is64: Boolean,
    val littleEndian: Boolean,
    val type: Int,
    val machine: Int,
    val entry: Long,
    val sections: List<ElfSection>,
    val segments: List<ElfSegment>,
    val symbols: List<ElfSymbol>,
    val needed: List<String>,
    val soname: String?,
    val rpath: String?,
    val runpath: String?,
    val buildId: String?,
    val relro: Boolean,
    val nx: Boolean,
    val pie: Boolean,
    val stripped: Boolean,
    val dynamicFlags: Long,
    val functions: List<ElfFunction>,
    val bytes: ByteArray
) {
    val typeName: String
        get() = when (type) {
            1 -> "REL"
            2 -> "EXEC"
            3 -> "DYN (shared object / PIE)"
            4 -> "CORE"
            else -> "0x%x".format(type)
        }

    val machineName: String get() = arch.label

    val imports: List<ElfSymbol> get() = symbols.filter { it.isUndefined && it.name.isNotBlank() }

    val exports: List<ElfSymbol>
        get() = symbols.filter { !it.isUndefined && it.name.isNotBlank() && it.isFunction && it.isGlobal }

    val suspiciousImports: List<String>
        get() = imports.map { it.name }.filter { it in SUSPICIOUS_IMPORTS }.distinct().sorted()

    fun sectionAt(address: Long): ElfSection? = sections.firstOrNull {
        it.addr != 0L && address >= it.addr && address < it.addr + it.size
    }

    fun segmentAt(address: Long): ElfSegment? = segments.firstOrNull {
        it.vaddr != 0L && address >= it.vaddr && address < it.vaddr + it.memsz
    }

    fun readVirtual(address: Long, size: Int): ByteArray? {
        val section = sectionAt(address)
        if (section != null) {
            val delta = address - section.addr
            val off = (section.offset + delta).toInt()
            val n = minOf(size.toLong(), section.size - delta).toInt()
            if (off >= 0 && n > 0 && off + n <= bytes.size) return bytes.copyOfRange(off, off + n)
        }
        val segment = segmentAt(address) ?: return null
        val delta = address - segment.vaddr
        if (delta >= segment.filesz) return null
        val off = (segment.offset + delta).toInt()
        val n = minOf(size.toLong(), segment.filesz - delta).toInt()
        if (off < 0 || n <= 0 || off + n > bytes.size) return null
        return bytes.copyOfRange(off, off + n)
    }

    fun functionBytes(function: ElfFunction): ByteArray {
        val size = when {
            function.size in 4..65536 -> function.size.toInt()
            else -> 512
        }
        return readVirtual(function.address, size) ?: ByteArray(0)
    }

    companion object {
        val SUSPICIOUS_IMPORTS = setOf(
            "ptrace", "dlopen", "dlsym", "dlclose", "system", "popen", "execve", "execvp",
            "fork", "vfork", "kill", "getprop", "__system_property_get", "__system_property_set",
            "setprop", "su", "busybox", "frida", "frida-agent", "magisk", "substrate",
            "pthread_create", "socket", "connect", "send", "recv", "mmap", "mprotect",
            "prctl", "syscall", "open", "openat", "read", "write", "unlink", "rename"
        )
    }
}

class ElfParser {
    fun parse(file: File): ElfInfo {
        val bytes = file.readBytes()
        require(bytes.size >= 52) { "File is too small to be ELF" }
        require(
            bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()
        ) { "Not an ELF file" }
        val is64 = bytes[4].toInt() == 2
        val little = bytes[5].toInt() == 1
        val buf = ByteBuffer.wrap(bytes).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)

        val type = buf.getShort(0x10).toInt() and 0xFFFF
        val machine = buf.getShort(0x12).toInt() and 0xFFFF
        val entry: Long
        val phoff: Long
        val shoff: Long
        val phentsize: Int
        val phnum: Int
        val shentsize: Int
        val shnum: Int
        val shstrndx: Int

        if (is64) {
            entry = buf.getLong(0x18)
            phoff = buf.getLong(0x20)
            shoff = buf.getLong(0x28)
            phentsize = buf.getShort(0x36).toInt() and 0xFFFF
            phnum = buf.getShort(0x38).toInt() and 0xFFFF
            shentsize = buf.getShort(0x3A).toInt() and 0xFFFF
            shnum = buf.getShort(0x3C).toInt() and 0xFFFF
            shstrndx = buf.getShort(0x3E).toInt() and 0xFFFF
        } else {
            entry = unsigned(buf.getInt(0x18))
            phoff = unsigned(buf.getInt(0x1C))
            shoff = unsigned(buf.getInt(0x20))
            phentsize = buf.getShort(0x2A).toInt() and 0xFFFF
            phnum = buf.getShort(0x2C).toInt() and 0xFFFF
            shentsize = buf.getShort(0x2E).toInt() and 0xFFFF
            shnum = buf.getShort(0x30).toInt() and 0xFFFF
            shstrndx = buf.getShort(0x32).toInt() and 0xFFFF
        }

        val segments = readSegments(buf, bytes.size, is64, phoff, phnum, phentsize)
        val rawSections = readSections(buf, bytes.size, is64, shoff, shnum, shentsize)
        val shstr = rawSections.getOrNull(shstrndx)?.let { raw ->
            if (raw.offset in 0 until bytes.size) {
                val start = raw.offset.toInt()
                val end = minOf(bytes.size, start + raw.size.toInt().coerceAtLeast(0))
                bytes.copyOfRange(start, end)
            } else null
        }
        val sections = rawSections.map {
            ElfSection(
                name = readCString(shstr, it.nameIndex) ?: "",
                type = it.type,
                flags = it.flags,
                addr = it.addr,
                offset = it.offset,
                size = it.size,
                link = it.link,
                info = it.info,
                addralign = it.addralign,
                entsize = it.entsize
            )
        }

        val dynstr = sections.firstOrNull { it.name == ".dynstr" }
        val dynstrBytes = dynstr?.let { s ->
            if (s.offset in 0 until bytes.size) {
                val start = s.offset.toInt()
                val end = minOf(bytes.size, start + s.size.toInt().coerceAtLeast(0))
                bytes.copyOfRange(start, end)
            } else null
        }

        val needed = ArrayList<String>()
        var soname: String? = null
        var rpath: String? = null
        var runpath: String? = null
        var dynamicFlags = 0L
        val dynamic = sections.firstOrNull { it.type == 6L }
        if (dynamic != null) {
            val start = dynamic.offset.toInt()
            val entrySize = if (is64) 16 else 8
            var p = start
            val end = minOf(bytes.size, start + dynamic.size.toInt().coerceAtLeast(0))
            while (p + entrySize <= end) {
                val tag = if (is64) buf.getLong(p) else unsigned(buf.getInt(p)).toLong()
                val value = if (is64) buf.getLong(p + 8) else unsigned(buf.getInt(p + 4)).toLong()
                when (tag) {
                    1L -> readCString(dynstrBytes, value.toInt())?.takeIf { it.isNotBlank() }?.let { needed.add(it) }
                    14L -> soname = readCString(dynstrBytes, value.toInt())
                    15L -> rpath = readCString(dynstrBytes, value.toInt())
                    29L -> runpath = readCString(dynstrBytes, value.toInt())
                    24L -> dynamicFlags = dynamicFlags or 0x8
                    30L -> dynamicFlags = dynamicFlags or value
                    0x6FFFFFFBL -> dynamicFlags = dynamicFlags or value
                    else -> {}
                }
                p += entrySize
            }
        }

        val symbols = readSymbols(buf, bytes.size, is64, sections, shstr, machine)
        val arch = when (machine) {
            40 -> ElfArch.ARM
            183 -> ElfArch.ARM64
            3 -> ElfArch.X86
            62 -> ElfArch.X86_64
            243 -> ElfArch.RISCV
            8 -> ElfArch.MIPS
            else -> ElfArch.UNKNOWN
        }

        val buildId = sections.firstOrNull { it.name == ".note.gnu.build-id" }?.let {
            readBuildId(bytes, buf, it.offset.toInt(), it.size.toInt(), little)
        }
        val relro = segments.any { it.type == 0x6474E552L }
        val stack = segments.firstOrNull { it.type == 0x6474E551L }
        val nx = stack == null || (stack.flags and 0x1) == 0
        val pie = type == 3 && (entry != 0L || (dynamicFlags and 0x08000000L) != 0L)
        val stripped = sections.none { it.type == 2L }

        val functions = buildFunctions(bytes, arch, sections, segments, symbols, machine)

        return ElfInfo(
            arch = arch,
            is64 = is64,
            littleEndian = little,
            type = type,
            machine = machine,
            entry = entry,
            sections = sections,
            segments = segments,
            symbols = symbols,
            needed = needed.distinct(),
            soname = soname,
            rpath = rpath,
            runpath = runpath,
            buildId = buildId,
            relro = relro,
            nx = nx,
            pie = pie,
            stripped = stripped,
            dynamicFlags = dynamicFlags,
            functions = functions,
            bytes = bytes
        )
    }

    private data class RawSection(
        val nameIndex: Int,
        val type: Long,
        val flags: Long,
        val addr: Long,
        val offset: Long,
        val size: Long,
        val link: Int,
        val info: Int,
        val addralign: Long,
        val entsize: Long,
        val name: String = ""
    )

    private fun readSegments(
        buf: ByteBuffer, fileSize: Int, is64: Boolean, off: Long, num: Int, entrySize: Int
    ): List<ElfSegment> {
        if (off <= 0 || num <= 0 || num > 65536) return emptyList()
        val out = ArrayList<ElfSegment>(minOf(num, 128))
        val base = off.toInt()
        for (i in 0 until minOf(num, 4096)) {
            val p = base + i * entrySize
            if (p < 0 || p + (if (is64) 56 else 32) > fileSize) break
            val type: Long
            val flags: Int
            val offset: Long
            val vaddr: Long
            val filesz: Long
            val memsz: Long
            if (is64) {
                type = buf.getInt(p).toLong() and 0xFFFFFFFFL
                flags = buf.getInt(p + 4)
                offset = buf.getLong(p + 8)
                vaddr = buf.getLong(p + 16)
                filesz = buf.getLong(p + 32)
                memsz = buf.getLong(p + 40)
            } else {
                type = unsigned(buf.getInt(p)).toLong()
                offset = unsigned(buf.getInt(p + 4))
                vaddr = unsigned(buf.getInt(p + 8))
                filesz = unsigned(buf.getInt(p + 16))
                memsz = unsigned(buf.getInt(p + 20))
                flags = buf.getInt(p + 24)
            }
            if (offset < 0 || filesz < 0 || memsz < 0) continue
            out.add(ElfSegment(type, flags, offset, vaddr, filesz, memsz))
        }
        return out
    }

    private fun readSections(
        buf: ByteBuffer, fileSize: Int, is64: Boolean, off: Long, num: Int, entrySize: Int
    ): List<RawSection> {
        if (off <= 0 || num <= 0 || num > 65536) return emptyList()
        val out = ArrayList<RawSection>(minOf(num, 256))
        val base = off.toInt()
        for (i in 0 until minOf(num, 16384)) {
            val p = base + i * entrySize
            if (p < 0 || p + (if (is64) 64 else 40) > fileSize) break
            val nameIndex: Int
            val type: Long
            val flags: Long
            val addr: Long
            val secOff: Long
            val size: Long
            val link: Int
            val info: Int
            val align: Long
            val entsize: Long
            if (is64) {
                nameIndex = buf.getInt(p)
                type = buf.getInt(p + 4).toLong() and 0xFFFFFFFFL
                flags = buf.getLong(p + 8)
                addr = buf.getLong(p + 16)
                secOff = buf.getLong(p + 24)
                size = buf.getLong(p + 32)
                link = buf.getInt(p + 40)
                info = buf.getInt(p + 44)
                align = buf.getLong(p + 48)
                entsize = buf.getLong(p + 56)
            } else {
                nameIndex = buf.getInt(p)
                type = unsigned(buf.getInt(p + 4)).toLong()
                flags = unsigned(buf.getInt(p + 8)).toLong()
                addr = unsigned(buf.getInt(p + 12))
                secOff = unsigned(buf.getInt(p + 16))
                size = unsigned(buf.getInt(p + 20))
                link = buf.getInt(p + 24)
                info = buf.getInt(p + 28)
                align = unsigned(buf.getInt(p + 32)).toLong()
                entsize = unsigned(buf.getInt(p + 36)).toLong()
            }
            if (secOff < 0 || size < 0) continue
            out.add(RawSection(nameIndex, type, flags, addr, secOff, size, link, info, align, entsize))
        }
        return out
    }

    private fun readSymbols(
        buf: ByteBuffer,
        fileSize: Int,
        is64: Boolean,
        sections: List<ElfSection>,
        shstr: ByteArray?,
        machine: Int
    ): List<ElfSymbol> {
        val table = sections.firstOrNull { it.type == 2L }
            ?: sections.firstOrNull { it.type == 11L }
        if (table == null) return emptyList()
        val strings = sections.getOrNull(table.link)?.let { str ->
            if (str.offset in 0 until fileSize) {
                val start = str.offset.toInt()
                val end = minOf(fileSize, start + str.size.toInt().coerceAtLeast(0))
                buf.array().copyOfRange(start, end)
            } else null
        }
        val entrySize = if (is64) 24 else 16
        val count = (table.size / entrySize).toInt()
        if (count <= 0 || count > 500_000) return emptyList()
        val base = table.offset.toInt()
        val out = ArrayList<ElfSymbol>(minOf(count, 8192))
        for (i in 1 until count) {
            val p = base + i * entrySize
            if (p < 0 || p + entrySize > fileSize) break
            val nameIndex: Int
            val info: Int
            val other: Int
            val shndx: Int
            val value: Long
            val size: Long
            if (is64) {
                nameIndex = buf.getInt(p)
                info = buf.get(p + 4).toInt() and 0xFF
                other = buf.get(p + 5).toInt() and 0xFF
                shndx = buf.getShort(p + 6).toInt() and 0xFFFF
                value = buf.getLong(p + 8)
                size = buf.getLong(p + 16)
            } else {
                nameIndex = buf.getInt(p)
                value = unsigned(buf.getInt(p + 4))
                size = unsigned(buf.getInt(p + 8))
                info = buf.get(p + 12).toInt() and 0xFF
                other = buf.get(p + 13).toInt() and 0xFF
                shndx = buf.getShort(p + 14).toInt() and 0xFFFF
            }
            val name = readCString(strings, nameIndex) ?: ""
            val thumb = machine == 40 && (value and 1L) == 1L
            val address = if (thumb) value and 1L.inv() else value
            out.add(ElfSymbol(name, address, size, info, other, shndx, thumb))
        }
        return out
    }

    private fun buildFunctions(
        bytes: ByteArray,
        arch: ElfArch,
        sections: List<ElfSection>,
        segments: List<ElfSegment>,
        symbols: List<ElfSymbol>,
        machine: Int
    ): List<ElfFunction> {
        val defined = symbols
            .filter { it.isFunction && !it.isUndefined && it.value != 0L && it.name.isNotBlank() }
            .sortedBy { it.value }
        val fromSymbols = defined.mapIndexed { index, symbol ->
            val next = defined.getOrNull(index + 1)?.value ?: 0L
            val size = if (symbol.size > 0) symbol.size else if (next > symbol.value) (next - symbol.value).coerceAtMost(65536) else 0L
            ElfFunction(symbol.name, symbol.value, size, "symbol", symbol.thumb)
        }

        val exec = executableRanges(sections, segments)
        val heuristics = heuristicFunctions(bytes, arch, exec)
        val result = ArrayList<ElfFunction>(fromSymbols.size + heuristics.size)
        result.addAll(fromSymbols)
        val known = TreeSet<Long>()
        fromSymbols.forEach { known.add(it.address) }
        for (h in heuristics) {
            val floor = known.floor(h.address)
            val ceiling = known.ceiling(h.address)
            if (floor != null && h.address - floor < 4) continue
            if (ceiling != null && ceiling - h.address < 4) continue
            if (known.add(h.address)) result.add(h)
        }
        return result.sortedBy { it.address }
    }

    private data class ExecRange(val vaddr: Long, val offset: Long, val size: Long)

    private fun executableRanges(sections: List<ElfSection>, segments: List<ElfSegment>): List<ExecRange> {
        val fromSections = sections.filter { it.executable && it.type == 1L && it.size > 0 }
            .map { ExecRange(it.addr, it.offset, it.size) }
        if (fromSections.isNotEmpty()) return fromSections
        return segments.filter { it.executable && it.type == 1L && it.filesz > 0 }
            .map { ExecRange(it.vaddr, it.offset, it.filesz) }
    }

    private fun heuristicFunctions(bytes: ByteArray, arch: ElfArch, ranges: List<ExecRange>): List<ElfFunction> {
        val out = ArrayList<ElfFunction>()
        for (range in ranges) {
            if (range.size <= 0) continue
            val start = range.offset.toInt()
            val end = minOf(bytes.size, (range.offset + range.size).toInt())
            if (start < 0 || start >= end) continue
            val cap = 3000 - out.size
            if (cap <= 0) break
            when (arch) {
                ElfArch.ARM64 -> {
                    var p = (start + 3) and -4
                    while (p + 4 <= end && out.size < 3000) {
                        val w = readIntLe(bytes, p)
                        val prologue = (w and 0xFFC07FFF.toInt()) == 0xA9807BFD.toInt() ||
                            (w and 0xFFC003FF.toInt()) == 0xD10003FF.toInt() ||
                            w == 0x910003FD.toInt() ||
                            (w and 0xFFC07FFF.toInt()) == 0xA9007BFD.toInt()
                        if (prologue) {
                            val address = range.vaddr + (p - start)
                            out.add(ElfFunction("sub_%X".format(address), address, 0, "heuristic", false))
                        }
                        p += 4
                    }
                }
                ElfArch.ARM -> {
                    var p = start
                    while (p + 4 <= end && out.size < 3000) {
                        val b0 = bytes[p].toInt() and 0xFF
                        val b1 = bytes[p + 1].toInt() and 0xFF
                        val thumbPush = b0 == 0xB5 || (b0 == 0x2D && b1 == 0xE9)
                        if (thumbPush) {
                            val address = range.vaddr + (p - start)
                            out.add(ElfFunction("sub_%X".format(address), address, 0, "heuristic", true))
                        }
                        p += 2
                    }
                }
                ElfArch.X86, ElfArch.X86_64 -> {
                    val patterns = if (arch == ElfArch.X86_64) listOf(
                        byteArrayOf(0x55, 0x48, 0x89.toByte(), 0xE5.toByte()),
                        byteArrayOf(0x55, 0x48, 0x8B.toByte(), 0xEC.toByte()),
                        byteArrayOf(0xF3.toByte(), 0x0F, 0x1E, 0xFA.toByte(), 0x55)
                    ) else listOf(
                        byteArrayOf(0x55, 0x89.toByte(), 0xE5.toByte()),
                        byteArrayOf(0x55, 0x8B.toByte(), 0xEC.toByte())
                    )
                    var p = start
                    while (p + 5 <= end && out.size < 3000) {
                        if (patterns.any { pattern -> matches(bytes, p, pattern) }) {
                            val address = range.vaddr + (p - start)
                            out.add(ElfFunction("sub_%X".format(address), address, 0, "heuristic", false))
                        }
                        p++
                    }
                }
                else -> {}
            }
        }
        return out
    }

    private fun matches(bytes: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        if (offset + pattern.size > bytes.size) return false
        for (i in pattern.indices) if (bytes[offset + i] != pattern[i]) return false
        return true
    }

    private fun readBuildId(bytes: ByteArray, buf: ByteBuffer, offset: Int, size: Int, little: Boolean): String? {
        if (offset < 0 || size < 12 || offset + size > bytes.size) return null
        var p = offset
        val end = offset + size
        while (p + 12 <= end) {
            val namesz = buf.getInt(p)
            val descsz = buf.getInt(p + 4)
            val type = buf.getInt(p + 8)
            if (namesz < 0 || descsz < 0 || type < 0) return null
            val nameStart = p + 12
            val descStart = nameStart + align4(namesz)
            val next = descStart + align4(descsz)
            if (descStart + descsz > end || next <= p) return null
            val name = String(bytes, nameStart, minOf(namesz, end - nameStart).coerceAtLeast(0), Charsets.US_ASCII)
            if (name.startsWith("GNU")) {
                return Fmt.hex(bytes.copyOfRange(descStart, descStart + descsz))
            }
            p = next
        }
        return null
    }

    private fun align4(n: Int): Int = (n + 3) and -4

    private fun unsigned(v: Int): Long = v.toLong() and 0xFFFFFFFFL

    private fun readCString(data: ByteArray?, offset: Int): String? {
        if (data == null || offset < 0 || offset >= data.size) return null
        var end = offset
        while (end < data.size && data[end] != 0.toByte()) end++
        return String(data, offset, end - offset, Charsets.UTF_8)
    }

    private fun readIntLe(bytes: ByteArray, p: Int): Int =
        (bytes[p].toInt() and 0xFF) or
            ((bytes[p + 1].toInt() and 0xFF) shl 8) or
            ((bytes[p + 2].toInt() and 0xFF) shl 16) or
            ((bytes[p + 3].toInt() and 0xFF) shl 24)
}

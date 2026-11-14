package com.dexlab.app.native

object CapstoneBridge {
    const val ARCH_ARM = 0
    const val ARCH_ARM64 = 1
    const val ARCH_X86 = 3

    const val MODE_ARM = 0
    const val MODE_16 = 1 shl 1
    const val MODE_32 = 1 shl 2
    const val MODE_64 = 1 shl 3
    const val MODE_THUMB = 1 shl 4

    private var loadError: String? = null

    init {
        try {
            System.loadLibrary("dexlab_capstone")
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
        }
    }

    val available: Boolean get() = loadError == null
    val error: String? get() = loadError

    private external fun nativeVersion(): Int
    private external fun nativeOpen(arch: Int, mode: Int, detail: Boolean): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeDisasm(handle: Long, arch: Int, code: ByteArray, address: Long, max: Int): String?

    data class Insn(
        val address: Long,
        val size: Int,
        val bytes: String,
        val mnemonic: String,
        val operands: String,
        val target: Long
    )

    fun version(): String {
        if (!available) return "unavailable (${loadError ?: "unknown"})"
        return runCatching {
            val v = nativeVersion()
            "${v / 10000}.${(v / 100) % 100}.${v % 100}"
        }.getOrElse { "error: ${it.message}" }
    }

    fun disassemble(arch: Int, mode: Int, code: ByteArray, address: Long, max: Int = 5000): List<Insn> {
        if (!available) error("Capstone native library not loaded: ${loadError ?: "unknown"}")
        if (code.isEmpty()) return emptyList()
        val handle = nativeOpen(arch, mode, true)
        if (handle == 0L) error("Capstone could not open arch=$arch mode=$mode")
        try {
            val text = nativeDisasm(handle, arch, code, address, max) ?: return emptyList()
            if (text.isEmpty()) return emptyList()
            return text.lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 6) return@mapNotNull null
                    Insn(
                        address = parts[0].toLongOrNull(16) ?: return@mapNotNull null,
                        size = parts[1].toIntOrNull() ?: 0,
                        bytes = parts[2],
                        mnemonic = parts[3],
                        operands = parts[4],
                        target = parts[5].toLongOrNull(16) ?: -1L
                    )
                }
                .toList()
        } finally {
            nativeClose(handle)
        }
    }
}

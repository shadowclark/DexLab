package com.dexlab.app.native

import java.util.TreeSet

data class CfgBlock(
    val startAddress: Long,
    val endAddress: Long,
    val instructions: List<CapstoneBridge.Insn>,
    val successors: List<Long>,
    val terminator: String
) {
    val label: String get() = "L_${startAddress.toString(16)}"
    val instructionCount: Int get() = instructions.size
}

data class NativeCfg(
    val function: ElfFunction,
    val blocks: List<CfgBlock>,
    val note: String
)

/**
 * Splits a function at branch targets and after control-flow instructions.
 * This is deliberately a CFG, not a decompiler: one basic block per contiguous
 * instruction range and only edges we can prove from the disassembly.
 */
object NativeCfgBuilder {
    private const val MAX_INSTRUCTIONS = 2400

    fun build(elf: ElfInfo, function: ElfFunction, thumb: Boolean = false): NativeCfg {
        val mode = modeFor(elf.arch, thumb)
            ?: return NativeCfg(function, emptyList(), "Unsupported architecture: ${elf.arch.label}")
        val bytes = runCatching { elf.functionBytes(function) }.getOrDefault(ByteArray(0))
        if (bytes.isEmpty()) return NativeCfg(function, emptyList(), "No mapped code bytes for this function.")
        val instructions = runCatching {
            CapstoneBridge.disassemble(mode.first, mode.second, bytes, function.address, MAX_INSTRUCTIONS)
        }.getOrDefault(emptyList())
        if (instructions.isEmpty()) return NativeCfg(function, emptyList(), "Capstone decoded no instructions.")

        val start = function.address
        val lastEnd = instructions.maxOf { it.address + it.size }
        val end = start + maxOf(
            function.size,
            (lastEnd - start).coerceAtLeast(1)
        )
        val leaders = TreeSet<Long>()
        leaders.add(start)
        instructions.forEach { insn ->
            val mnemonic = insn.mnemonic.lowercase()
            if (isControl(elf.arch, mnemonic)) {
                val next = insn.address + insn.size
                if (next < end) leaders.add(next)
                normalizeTarget(elf, insn.target)?.takeIf { it in start until end }?.let { leaders.add(it) }
            }
        }

        val starts = leaders.filter { it in start until end }.toList()
        val blocks = ArrayList<CfgBlock>()
        starts.forEachIndexed { index, blockStart ->
            val blockEnd = starts.getOrNull(index + 1) ?: end
            val blockInsns = instructions.filter { it.address >= blockStart && it.address < blockEnd }
            if (blockInsns.isEmpty()) return@forEachIndexed
            val last = blockInsns.last()
            val mnemonic = last.mnemonic.lowercase()
            val target = normalizeTarget(elf, last.target)?.takeIf { it in start until end }
            val nextStart = starts.getOrNull(index + 1)
            val successors = ArrayList<Long>()
            when {
                isReturn(elf.arch, mnemonic, last.operands) -> Unit
                isConditional(elf.arch, mnemonic) -> {
                    target?.let { successors.add(it) }
                    nextStart?.let { successors.add(it) }
                }
                isUnconditional(elf.arch, mnemonic) -> target?.let { successors.add(it) }
                isIndirect(elf.arch, mnemonic) -> Unit
                else -> nextStart?.let { successors.add(it) }
            }
            blocks.add(
                CfgBlock(
                    startAddress = blockStart,
                    endAddress = blockEnd,
                    instructions = blockInsns,
                    successors = successors.distinct(),
                    terminator = "${last.mnemonic} ${last.operands}".trim()
                )
            )
        }

        return NativeCfg(
            function = function,
            blocks = blocks,
            note = if (instructions.size >= MAX_INSTRUCTIONS) {
                "Basic blocks split at branch targets and fall-through points. Output capped at $MAX_INSTRUCTIONS instructions."
            } else {
                "Basic blocks split at branch targets and fall-through points. Indirect jumps end a block without a guessed edge."
            }
        )
    }

    private fun modeFor(arch: ElfArch, thumb: Boolean): Pair<Int, Int>? = when (arch) {
        ElfArch.ARM64 -> CapstoneBridge.ARCH_ARM64 to CapstoneBridge.MODE_ARM
        ElfArch.ARM -> CapstoneBridge.ARCH_ARM to if (thumb) CapstoneBridge.MODE_THUMB else CapstoneBridge.MODE_ARM
        ElfArch.X86_64 -> CapstoneBridge.ARCH_X86 to CapstoneBridge.MODE_64
        ElfArch.X86 -> CapstoneBridge.ARCH_X86 to CapstoneBridge.MODE_32
        else -> null
    }

    private fun normalizeTarget(elf: ElfInfo, target: Long): Long? {
        if (target <= 0) return null
        return if (elf.arch == ElfArch.ARM && target and 1L == 1L) target - 1 else target
    }

    private fun isConditional(arch: ElfArch, mnemonic: String): Boolean = when (arch) {
        ElfArch.ARM, ElfArch.ARM64 -> mnemonic.startsWith("b.") ||
            mnemonic in setOf("cbz", "cbnz", "tbz", "tbnz")
        ElfArch.X86, ElfArch.X86_64 -> mnemonic.startsWith("j") && mnemonic != "jmp"
        else -> false
    }

    private fun isUnconditional(arch: ElfArch, mnemonic: String): Boolean = when (arch) {
        ElfArch.ARM, ElfArch.ARM64 -> mnemonic == "b"
        ElfArch.X86, ElfArch.X86_64 -> mnemonic == "jmp"
        else -> false
    }

    private fun isReturn(arch: ElfArch, mnemonic: String, operands: String): Boolean =
        mnemonic == "ret" || (arch == ElfArch.ARM && mnemonic == "bx" && operands.trim() == "lr")

    private fun isIndirect(arch: ElfArch, mnemonic: String): Boolean = when (arch) {
        ElfArch.ARM, ElfArch.ARM64 -> mnemonic in setOf("br", "blr")
        ElfArch.X86, ElfArch.X86_64 -> mnemonic == "call" || mnemonic == "jmp"
        else -> false
    }

    private fun isControl(arch: ElfArch, mnemonic: String): Boolean =
        isConditional(arch, mnemonic) || isUnconditional(arch, mnemonic) ||
            isReturn(arch, mnemonic, "") || mnemonic in setOf("br", "blr")
}

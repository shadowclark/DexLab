package com.dexlab.app.native

enum class XrefKind {
    CALL,
    JUMP,
    STRING
}

data class NativeXref(
    val fromAddress: Long,
    val fromFunction: String,
    val toAddress: Long,
    val toFunction: String,
    val kind: XrefKind,
    val instruction: String,
    val text: String? = null
)

data class FunctionXrefs(
    val function: ElfFunction,
    val callers: List<NativeXref>,
    val callees: List<NativeXref>,
    val strings: List<NativeXref>,
    val scannedFunctions: Int,
    val totalFunctions: Int,
    val truncated: Boolean,
    val note: String
) {
    val total: Int get() = callers.size + callees.size + strings.size
}

/**
 * Ghidra/IDA-style cross references without a full database:
 * - incoming direct calls/jumps to a function (callers)
 * - outgoing direct calls/jumps from a function (callees)
 * - obvious string loads in the function (adr, adrp+add/ldr, RIP-relative lea)
 *
 * Indirect calls and non-obvious data loads are deliberately not guessed.
 */
object NativeXrefs {
    private const val MAX_CALLER_FUNCTIONS = 1200
    private const val MAX_CALLER_INSTRUCTIONS = 180
    private const val MAX_SELECTED_INSTRUCTIONS = 1600

    fun scan(elf: ElfInfo, function: ElfFunction, thumb: Boolean = false): FunctionXrefs {
        val mode = modeFor(elf.arch, thumb)
            ?: return empty(function, "Unsupported architecture: ${elf.arch.label}")

        val selected = disassemble(elf, function, mode, MAX_SELECTED_INSTRUCTIONS)
        if (selected.isEmpty()) {
            return empty(function, "No mapped instructions for this function.")
        }

        val end = function.address + maxOf(
            function.size,
            selected.maxOfOrNull { it.address + it.size }?.minus(function.address) ?: 0L
        )

        val callees = ArrayList<NativeXref>()
        val strings = ArrayList<NativeXref>()
        var adrp: Pair<String, Long>? = null

        for (insn in selected) {
            val mnemonic = insn.mnemonic.lowercase()
            val operands = insn.operands

            if (elf.arch == ElfArch.ARM64 && mnemonic == "adrp") {
                adrp = parseAdrp(operands)
            } else if (adrp != null) {
                val page = adrp
                if (mnemonic == "add") {
                    val parts = splitOperands(operands)
                    val imm = parts.getOrNull(2)?.let(::parseImmediate)
                    if (parts.size >= 3 && parts[1] == page.first && imm != null) {
                        addString(strings, elf, function, insn, page.second + imm)
                    }
                } else if (mnemonic.startsWith("ldr")) {
                    val parts = splitOperands(operands)
                    val mem = parts.getOrNull(1)?.let(::parseMemory)
                    if (mem != null && mem.first == page.first) {
                        addString(strings, elf, function, insn, page.second + mem.second)
                    }
                }
                adrp = null
            }

            if (elf.arch == ElfArch.ARM64 && mnemonic == "adr") {
                val parts = splitOperands(operands)
                val imm = parts.getOrNull(1)?.let(::parseImmediate)
                if (imm != null) {
                    val relative = insn.address + imm
                    val absolute = imm
                    addString(
                        strings, elf, function, insn,
                        if (stringAt(elf, relative) != null) relative else absolute
                    )
                }
            }

            if (elf.arch == ElfArch.X86 || elf.arch == ElfArch.X86_64) {
                if (mnemonic == "lea") {
                    ripTarget(insn)?.let { addString(strings, elf, function, insn, it) }
                }
            }

            val callKind = callKind(elf.arch, mnemonic) ?: continue
            val target = insn.target
            if (target <= 0) continue
            if (target in function.address until end) continue
            val targetName = nameAt(elf, target)
            callees.add(
                NativeXref(
                    fromAddress = insn.address,
                    fromFunction = function.name,
                    toAddress = target,
                    toFunction = targetName,
                    kind = callKind,
                    instruction = "${insn.mnemonic} ${insn.operands}".trim()
                )
            )
        }

        val callers = ArrayList<NativeXref>()
        var scanned = 0
        var truncated = false
        for (candidate in elf.functions) {
            if (candidate.address == function.address) continue
            if (scanned >= MAX_CALLER_FUNCTIONS) {
                truncated = true
                break
            }
            val candidateMode = modeFor(elf.arch, candidate.thumb) ?: continue
            scanned++
            val instructions = disassemble(elf, candidate, candidateMode, MAX_CALLER_INSTRUCTIONS)
            if (instructions.isEmpty()) continue
            for (insn in instructions) {
                val kind = callKind(elf.arch, insn.mnemonic.lowercase()) ?: continue
                val target = insn.target
                val matches = target == function.address ||
                    (elf.arch == ElfArch.ARM && target == function.address + 1)
                if (!matches) continue
                callers.add(
                    NativeXref(
                        fromAddress = insn.address,
                        fromFunction = candidate.name,
                        toAddress = function.address,
                        toFunction = function.name,
                        kind = kind,
                        instruction = "${insn.mnemonic} ${insn.operands}".trim()
                    )
                )
            }
        }

        return FunctionXrefs(
            function = function,
            callers = callers.distinctBy { it.fromAddress },
            callees = callees.distinctBy { it.toAddress },
            strings = strings.distinctBy { it.toAddress },
            scannedFunctions = scanned,
            totalFunctions = elf.functions.size,
            truncated = truncated,
            note = buildString {
                append("Direct calls and jumps only; indirect calls are not resolved. ")
                if (truncated) {
                    append("Callers were scanned in $scanned of ${elf.functions.size} functions. ")
                }
                append("String xrefs cover adr/adrp+add/ldr and RIP-relative lea; other data loads may be missed.")
            }
        )
    }

    private fun empty(function: ElfFunction, note: String) = FunctionXrefs(
        function = function,
        callers = emptyList(),
        callees = emptyList(),
        strings = emptyList(),
        scannedFunctions = 0,
        totalFunctions = 0,
        truncated = false,
        note = note
    )

    private fun disassemble(
        elf: ElfInfo,
        function: ElfFunction,
        mode: Pair<Int, Int>,
        max: Int
    ): List<CapstoneBridge.Insn> {
        val bytes = elf.functionBytes(function)
        if (bytes.isEmpty()) return emptyList()
        return runCatching {
            CapstoneBridge.disassemble(mode.first, mode.second, bytes, function.address, max)
        }.getOrDefault(emptyList())
    }

    private fun modeFor(arch: ElfArch, thumb: Boolean): Pair<Int, Int>? = when (arch) {
        ElfArch.ARM64 -> CapstoneBridge.ARCH_ARM64 to CapstoneBridge.MODE_ARM
        ElfArch.ARM -> CapstoneBridge.ARCH_ARM to if (thumb) CapstoneBridge.MODE_THUMB else CapstoneBridge.MODE_ARM
        ElfArch.X86_64 -> CapstoneBridge.ARCH_X86 to CapstoneBridge.MODE_64
        ElfArch.X86 -> CapstoneBridge.ARCH_X86 to CapstoneBridge.MODE_32
        else -> null
    }

    private fun callKind(arch: ElfArch, mnemonic: String): XrefKind? = when (arch) {
        ElfArch.ARM, ElfArch.ARM64 -> when (mnemonic) {
            "bl", "blx" -> XrefKind.CALL
            "b" -> XrefKind.JUMP
            else -> null
        }
        ElfArch.X86, ElfArch.X86_64 -> when (mnemonic) {
            "call" -> XrefKind.CALL
            "jmp" -> XrefKind.JUMP
            else -> null
        }
        else -> null
    }

    private fun nameAt(elf: ElfInfo, address: Long): String =
        elf.functions.firstOrNull { it.address == address }?.name ?: "sub_${address.toString(16)}"

    private fun addString(
        out: MutableList<NativeXref>,
        elf: ElfInfo,
        function: ElfFunction,
        insn: CapstoneBridge.Insn,
        address: Long
    ) {
        val value = stringAt(elf, address) ?: return
        out.add(
            NativeXref(
                fromAddress = insn.address,
                fromFunction = function.name,
                toAddress = address,
                toFunction = value,
                kind = XrefKind.STRING,
                instruction = "${insn.mnemonic} ${insn.operands}".trim(),
                text = value
            )
        )
    }

    private fun stringAt(elf: ElfInfo, address: Long): String? {
        val bytes = elf.readVirtual(address, 200) ?: return null
        val end = bytes.indexOf(0)
        if (end < 2) return null
        val text = String(bytes, 0, end, Charsets.UTF_8)
        if (text.any { it.code !in 9..13 && it.code !in 32..126 }) return null
        return text
    }

    private fun splitOperands(operands: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        val current = StringBuilder()
        for (ch in operands) {
            when (ch) {
                '[', '{', '(' -> {
                    depth++
                    current.append(ch)
                }
                ']', '}', ')' -> {
                    depth--
                    current.append(ch)
                }
                ',' -> if (depth == 0) {
                    out.add(current.toString().trim())
                    current.clear()
                } else {
                    current.append(ch)
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) out.add(current.toString().trim())
        return out
    }

    private fun parseAdrp(operands: String): Pair<String, Long>? {
        val parts = splitOperands(operands)
        if (parts.size < 2) return null
        val imm = parseImmediate(parts[1]) ?: return null
        return parts[0] to imm
    }

    private fun parseMemory(operand: String): Pair<String, Long>? {
        val open = operand.indexOf('[')
        val close = operand.lastIndexOf(']')
        if (open < 0 || close <= open) return null
        val parts = operand.substring(open + 1, close).split(',').map { it.trim() }
        val base = parts.firstOrNull() ?: return null
        var offset = 0L
        for (part in parts.drop(1)) {
            val imm = parseImmediate(part) ?: continue
            offset = if (part.startsWith("-")) -imm else imm
        }
        return base to offset
    }

    private fun parseImmediate(text: String): Long? {
        var value = text.trim()
            .removePrefix("#")
            .removePrefix("=")
            .removePrefix("0x")
            .removePrefix("0X")
            .trim()
        if (value.isEmpty()) return null
        val negative = value.startsWith("-")
        if (negative) value = value.substring(1).removePrefix("0x").removePrefix("0X")
        val parsed = value.toLongOrNull(16) ?: return null
        return if (negative) -parsed else parsed
    }

    private fun ripTarget(insn: CapstoneBridge.Insn): Long? {
        if (!insn.operands.contains("rip")) return null
        val match = Regex("""(?:rip\s*([+-])\s*)?(0x[0-9a-fA-F]+|\d+)""")
            .find(insn.operands.substring(insn.operands.indexOf("rip"))) ?: return null
        val value = parseImmediate(match.groupValues[2]) ?: return null
        val signed = if (match.groupValues[1] == "-") -value else value
        return insn.address + insn.size + signed
    }
}

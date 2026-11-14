package com.dexlab.app.native

/**
 * A deliberately small, honest pseudo-decompiler. It does not recover types or
 * exact control flow; it turns common Capstone instructions into readable
 * statements and resolves obvious string references.
 */
data class PseudoResult(
    val function: ElfFunction,
    val code: String,
    val instructionCount: Int,
    val note: String
)

object PseudoDecompiler {
    fun generate(
        elf: ElfInfo,
        function: ElfFunction,
        thumb: Boolean = false,
        maxInstructions: Int = 1500
    ): PseudoResult {
        val arch = when (elf.arch) {
            ElfArch.ARM -> CapstoneBridge.ARCH_ARM
            ElfArch.ARM64 -> CapstoneBridge.ARCH_ARM64
            ElfArch.X86, ElfArch.X86_64 -> CapstoneBridge.ARCH_X86
            else -> return unsupported(function, "Unsupported architecture: ${elf.arch.label}")
        }
        val mode = when (elf.arch) {
            ElfArch.ARM64 -> CapstoneBridge.MODE_ARM
            ElfArch.ARM -> if (thumb) CapstoneBridge.MODE_THUMB else CapstoneBridge.MODE_ARM
            ElfArch.X86_64 -> CapstoneBridge.MODE_64
            ElfArch.X86 -> CapstoneBridge.MODE_32
            else -> CapstoneBridge.MODE_ARM
        }

        val code = runCatching { elf.functionBytes(function) }.getOrElse {
            return unsupported(function, "Could not map function bytes: ${it.message}")
        }
        if (code.isEmpty()) return unsupported(function, "This function has no mapped code bytes.")

        val instructions = runCatching {
            CapstoneBridge.disassemble(arch, mode, code, function.address, maxInstructions)
        }.getOrElse {
            return unsupported(function, "Capstone failed: ${it.message}")
        }
        if (instructions.isEmpty()) return unsupported(function, "Capstone decoded no instructions.")

        val labels = instructions.mapNotNull {
            val target = it.target
            if (target in (function.address + 1) until (function.address + code.size)) target else null
        }.toSet()

        val body = StringBuilder()
        var lastCmp: Pair<String, String>? = null
        var lastTest: Pair<String, String>? = null
        var adrp: Pair<String, Long>? = null
        var index = 0

        while (index < instructions.size) {
            val insn = instructions[index]
            val mnemonic = insn.mnemonic.lowercase()
            val operands = insn.operands.trim()

            if (insn.address in labels) {
                body.append("label_").append(hex(insn.address)).append(":\n")
            }

            // ARM64 adr is PC-relative; adrp + add/ldr often points at a
            // string in .rodata.
            if (elf.arch == ElfArch.ARM64 && mnemonic == "adr") {
                val parts = splitOperands(operands)
                val imm = parts.getOrNull(1)?.let(::parseImmediate)
                if (parts.isNotEmpty() && imm != null) {
                    val relative = insn.address + imm
                    val absolute = imm
                    val target = when {
                        stringAt(elf, relative) != null -> relative
                        stringAt(elf, absolute) != null -> absolute
                        else -> relative
                    }
                    val string = stringAt(elf, target)
                    if (string != null) {
                        body.append("    ").append(reg(parts[0])).append(" = \"").append(escape(string)).append("\";\n")
                    } else {
                        body.append("    ").append(reg(parts[0])).append(" = 0x").append(target.toString(16)).append(";\n")
                    }
                    index++
                    continue
                }
            }
            if (elf.arch == ElfArch.ARM64 && mnemonic == "adrp") {
                adrp = parseAdrp(operands)
                body.append("    // ").append(mnemonic).append(' ').append(operands).append('\n')
                index++
                continue
            }
            if (elf.arch == ElfArch.ARM64 && adrp != null) {
                val page = adrp
                if (mnemonic == "add") {
                    val parts = splitOperands(operands)
                    val imm = parts.getOrNull(2)?.let(::parseImmediate)
                    if (parts.size >= 3 && parts[1] == page.first && imm != null) {
                        val target = page.second + imm
                        val string = stringAt(elf, target)
                        if (string != null) {
                            body.append("    ").append(reg(parts[0])).append(" = \"").append(escape(string)).append("\";\n")
                        } else {
                            body.append("    ").append(reg(parts[0])).append(" = 0x").append(target.toString(16)).append(";\n")
                        }
                        adrp = null
                        index++
                        continue
                    }
                } else if (mnemonic.startsWith("ldr")) {
                    val parts = splitOperands(operands)
                    val mem = parts.getOrNull(1)?.let(::parseMemory)
                    if (mem != null && mem.first == page.first) {
                        val target = page.second + mem.second
                        val string = stringAt(elf, target)
                        if (string != null) {
                            body.append("    ").append(reg(parts[0])).append(" = \"").append(escape(string)).append("\";\n")
                            adrp = null
                            index++
                            continue
                        }
                    }
                }
                adrp = null
            }

            when {
                mnemonic == "nop" -> Unit

                mnemonic == "ret" || (elf.arch == ElfArch.ARM && mnemonic == "bx" && operands == "lr") -> {
                    body.append("    return ").append(returnRegister(elf.arch)).append(";\n")
                }

                mnemonic == "bx" || mnemonic == "br" -> {
                    body.append("    // indirect jump: ").append(mnemonic).append(' ').append(operands).append('\n')
                }

                mnemonic == "blr" -> {
                    body.append("    // indirect call via ").append(operands).append('\n')
                }

                mnemonic == "svc" || mnemonic == "syscall" -> {
                    body.append("    // syscall ").append(operands).append('\n')
                }

                mnemonic == "cmp" -> {
                    val parts = splitOperands(operands)
                    if (parts.size >= 2) {
                        lastCmp = cValue(parts[0], elf) to cValue(parts[1], elf)
                        lastTest = null
                        body.append("    // flags: ").append(lastCmp!!.first).append(" - ").append(lastCmp!!.second).append('\n')
                    }
                }

                mnemonic == "tst" || mnemonic == "test" -> {
                    val parts = splitOperands(operands)
                    if (parts.size >= 2) {
                        lastTest = cValue(parts[0], elf) to cValue(parts[1], elf)
                        lastCmp = null
                        body.append("    // flags: ").append(lastTest!!.first).append(" & ").append(lastTest!!.second).append('\n')
                    }
                }

                mnemonic == "cbz" || mnemonic == "cbnz" -> {
                    val parts = splitOperands(operands)
                    val condition = if (mnemonic == "cbz") "== 0" else "!= 0"
                    val target = branchTarget(insn, function, parts)
                    if (parts.isNotEmpty() && target != null) {
                        body.append("    if (").append(reg(parts[0])).append(' ').append(condition)
                            .append(") goto label_").append(hex(target)).append(";\n")
                    } else {
                        body.append("    // ").append(mnemonic).append(' ').append(operands).append('\n')
                    }
                }

                mnemonic == "tbz" || mnemonic == "tbnz" -> {
                    val parts = splitOperands(operands)
                    val bit = parts.getOrNull(1)?.let(::parseImmediate)
                    val target = branchTarget(insn, function, parts)
                    if (parts.isNotEmpty() && bit != null && target != null) {
                        body.append("    if ((").append(reg(parts[0])).append(" & (1 << ").append(bit).append(")) ")
                            .append(if (mnemonic == "tbz") "== 0" else "!= 0")
                            .append(") goto label_").append(hex(target)).append(";\n")
                    } else {
                        body.append("    // ").append(mnemonic).append(' ').append(operands).append('\n')
                    }
                }

                mnemonic == "b" || mnemonic == "jmp" -> {
                    val target = insn.target
                    if (target > 0) {
                        if (target in labels) body.append("    goto label_").append(hex(target)).append(";\n")
                        else body.append("    goto ").append(sanitize(resolveName(elf, target))).append("; // tail call\n")
                    } else {
                        body.append("    // jump ").append(operands).append('\n')
                    }
                }

                mnemonic.startsWith("b.") -> {
                    val condition = conditionFor(mnemonic, lastCmp, null)
                    val target = insn.target
                    if (condition != null && target in labels) {
                        body.append("    if (").append(condition).append(") goto label_").append(hex(target)).append(";\n")
                    } else {
                        body.append("    // ").append(mnemonic).append(' ').append(operands).append('\n')
                    }
                }

                mnemonic.startsWith("j") && mnemonic != "jmp" -> {
                    val condition = conditionFor(mnemonic, lastCmp, lastTest)
                    val target = insn.target
                    if (condition != null && target in labels) {
                        body.append("    if (").append(condition).append(") goto label_").append(hex(target)).append(";\n")
                    } else {
                        body.append("    // ").append(mnemonic).append(' ').append(operands).append('\n')
                    }
                }

                mnemonic == "bl" || mnemonic == "call" || mnemonic == "blx" -> {
                    val target = insn.target
                    if (target > 0) {
                        val name = sanitize(resolveName(elf, target))
                        body.append("    // call ").append(name).append(" @ 0x").append(target.toString(16)).append('\n')
                        body.append("    ").append(returnRegister(elf.arch)).append(" = ")
                            .append(name).append('(').append(callArguments(elf.arch)).append(");\n")
                    } else {
                        body.append("    // indirect call: ").append(operands).append('\n')
                    }
                }

                mnemonic == "mov" || mnemonic == "movz" || mnemonic == "movabs" -> {
                    val parts = splitOperands(operands)
                    if (parts.size >= 2) {
                        val dst = parts[0].trim()
                        val src = parts[1].trim()
                        if ((dst == "x29" && src == "sp") || (dst == "rbp" && src == "rsp")) {
                            body.append("    ").append(reg(dst)).append(" = ").append(reg(src)).append(";\n")
                        } else {
                            body.append("    ").append(cAssign(dst, src, elf)).append('\n')
                        }
                    }
                }

                mnemonic == "movk" -> {
                    val parts = splitOperands(operands)
                    if (parts.size >= 2) {
                        body.append("    ").append(reg(parts[0])).append(" |= ")
                            .append(cValue(parts[1], elf)).append("; // movk halfword\n")
                    }
                }

                mnemonic == "lea" -> {
                    val parts = splitOperands(operands)
                    val dst = parts.getOrNull(0)?.let(::reg) ?: "rax"
                    val target = ripTarget(insn)
                    val string = target?.let { stringAt(elf, it) }
                    if (string != null) {
                        body.append("    ").append(dst).append(" = \"").append(escape(string)).append("\";\n")
                    } else if (target != null) {
                        body.append("    ").append(dst).append(" = (void *)0x").append(target.toString(16)).append(";\n")
                    } else {
                        body.append("    ").append(dst).append(" = ").append(cValue(parts.getOrNull(1) ?: operands, elf)).append(";\n")
                    }
                }

                mnemonic.startsWith("ldr") || mnemonic.startsWith("ldrb") ||
                    mnemonic.startsWith("ldrh") || mnemonic.startsWith("ldrsb") ||
                    mnemonic.startsWith("ldrsh") || mnemonic.startsWith("ldrsw") -> {
                    val parts = splitOperands(operands)
                    val dst = parts.getOrNull(0)?.let(::reg)
                    val mem = parts.getOrNull(1)?.let(::parseMemory)
                    if (dst != null && mem != null) {
                        if (mem.first == "rip") {
                            val target = insn.address + insn.size + mem.second
                            val string = stringAt(elf, target)
                            if (string != null) {
                                body.append("    ").append(dst).append(" = \"").append(escape(string)).append("\";\n")
                            } else {
                                body.append("    ").append(dst).append(" = ").append(memoryLValue(mem, loadType(mnemonic, dst))).append(";\n")
                            }
                        } else {
                            body.append("    ").append(dst).append(" = ").append(memoryLValue(mem, loadType(mnemonic, dst))).append(";\n")
                        }
                    } else {
                        body.append("    // ").append(mnemonic).append(' ').append(operands).append('\n')
                    }
                }

                mnemonic.startsWith("str") || mnemonic.startsWith("strb") || mnemonic.startsWith("strh") -> {
                    val parts = splitOperands(operands)
                    val src = parts.getOrNull(0)?.let(::reg)
                    val mem = parts.getOrNull(1)?.let(::parseMemory)
                    if (src != null && mem != null) {
                        body.append("    ").append(memoryLValue(mem, loadType(mnemonic, src))).append(" = ").append(src).append(";\n")
                    } else {
                        body.append("    // ").append(mnemonic).append(' ').append(operands).append('\n')
                    }
                }

                mnemonic == "ldp" || mnemonic == "stp" -> {
                    val parts = splitOperands(operands)
                    val memory = parts.getOrNull(2)?.let(::parseMemory)
                    if (parts.size >= 3 && memory != null) {
                        val type = operandType(parts[0], parts[0])
                        val width = if (type == "uint32_t") 4L else 8L
                        if (mnemonic == "ldp") {
                            body.append("    ").append(reg(parts[0])).append(" = ")
                                .append(memoryLValue(memory, type)).append(";\n")
                            body.append("    ").append(reg(parts[1])).append(" = ")
                                .append(memoryLValue(memory.copy(second = memory.second + width), type)).append(";\n")
                        } else {
                            body.append("    ").append(memoryLValue(memory, type)).append(" = ")
                                .append(reg(parts[0])).append(";\n")
                            body.append("    ").append(memoryLValue(memory.copy(second = memory.second + width), type)).append(" = ")
                                .append(reg(parts[1])).append(";\n")
                        }
                    } else {
                        body.append("    // ").append(mnemonic).append(' ').append(operands).append(" (pair access)\n")
                    }
                }

                mnemonic in setOf("add", "sub", "and", "orr", "eor", "mul", "lsl", "lsr", "asr", "shl", "shr") -> {
                    val parts = splitOperands(operands)
                    val symbol = when (mnemonic) {
                            "add" -> "+"
                            "sub" -> "-"
                            "and" -> "&"
                            "orr" -> "|"
                            "eor" -> "^"
                            "mul" -> "*"
                            "lsl", "shl" -> "<<"
                            "lsr", "shr", "asr" -> ">>"
                            else -> "?"
                        }
                    if (parts.size >= 3) {
                        body.append("    ").append(reg(parts[0])).append(" = ").append(cValue(parts[1], elf))
                            .append(' ').append(symbol).append(' ').append(cValue(parts[2], elf)).append(";\n")
                    } else if (parts.size == 2) {
                        body.append("    ").append(reg(parts[0])).append(" = ").append(cValue(parts[0], elf))
                            .append(' ').append(symbol).append(' ').append(cValue(parts[1], elf)).append(";\n")
                    } else {
                        body.append("    // ").append(mnemonic).append(' ').append(operands).append('\n')
                    }
                }

                mnemonic == "xor" -> {
                    val parts = splitOperands(operands)
                    if (parts.size >= 2 && parts[0] == parts[1]) {
                        body.append("    ").append(reg(parts[0])).append(" = 0;\n")
                    } else if (parts.size >= 2) {
                        body.append("    ").append(reg(parts[0])).append(" = ").append(cValue(parts[0], elf))
                            .append(" ^ ").append(cValue(parts[1], elf)).append(";\n")
                    } else {
                        body.append("    // xor ").append(operands).append('\n')
                    }
                }

                mnemonic == "push" || mnemonic == "pop" || mnemonic == "leave" -> {
                    body.append("    // ").append(mnemonic).append(' ').append(operands).append('\n')
                }

                mnemonic == "neg" || mnemonic == "not" || mnemonic == "inc" || mnemonic == "dec" -> {
                    val parts = splitOperands(operands)
                    if (parts.isNotEmpty()) {
                        val target = reg(parts[0])
                        val expression = when (mnemonic) {
                            "neg" -> "-" + cValue(parts.getOrElse(0) { target }, elf)
                            "not" -> "~" + cValue(parts.getOrElse(0) { target }, elf)
                            "inc" -> cValue(parts.getOrElse(0) { target }, elf) + " + 1"
                            else -> cValue(parts.getOrElse(0) { target }, elf) + " - 1"
                        }
                        body.append("    ").append(target).append(" = ").append(expression).append(";\n")
                    }
                }

                else -> {
                    body.append("    // ").append(mnemonic)
                    if (operands.isNotBlank()) body.append(' ').append(operands)
                    body.append('\n')
                }
            }
            index++
        }

        val name = sanitize(function.name)
        val locals = collectRegisters(instructions, elf.arch)
        val header = buildString {
            append("// Heuristic pseudo-code from Capstone. Types and names are inferred.\n")
            append("// C-like reconstruction; not a replacement for the disassembly.\n")
            append(returnType(elf.arch)).append(' ').append(name).append('(')
            append(functionArguments(elf.arch)).append(") {\n")
            locals.forEach { append("    ").append(localType(it)).append(' ').append(it).append(";\n") }
            if (locals.isNotEmpty()) append('\n')
        }
        val footer = "\n}"
        return PseudoResult(
            function = function,
            code = header + body + footer,
            instructionCount = instructions.size,
            note = if (instructions.size >= maxInstructions) {
                "Output capped at $maxInstructions instructions."
            } else {
                "Strings are resolved from mapped .rodata when an adrp/add or RIP-relative load is obvious."
            }
        )
    }

    private fun unsupported(function: ElfFunction, message: String) = PseudoResult(
        function = function,
        code = "// Pseudo-code unavailable.\n// $message\n",
        instructionCount = 0,
        note = message
    )

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
        return reg(parts[0]) to imm
    }

    private fun parseMemory(operand: String): Pair<String, Long>? {
        val open = operand.indexOf('[')
        val close = operand.lastIndexOf(']')
        if (open < 0 || close <= open) return null
        val inner = operand.substring(open + 1, close)
        val parts = inner.split(',').map { it.trim() }
        val base = parts.firstOrNull()?.let(::reg) ?: return null
        var offset = 0L
        for (part in parts.drop(1)) {
            val imm = parseImmediate(part) ?: continue
            offset = if (part.trim().startsWith("-")) -imm else imm
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

    private fun reg(name: String): String = name.trim().trimEnd(',')

    private fun stripSize(text: String): String = text.trim().replace(
        Regex("""^(byte|word|dword|qword|xmmword|ymmword|zmmword)\s+ptr\s+""", RegexOption.IGNORE_CASE),
        ""
    )

    private fun operandType(operand: String, source: String = ""): String {
        val lower = operand.lowercase()
        return when {
            lower.contains("qword") -> "uint64_t"
            lower.contains("dword") -> "uint32_t"
            lower.contains("word") -> "uint16_t"
            lower.contains("byte") -> "uint8_t"
            source.endsWith("l") || source.endsWith("al") || source.endsWith("bl") ||
                source.endsWith("cl") || source.endsWith("dl") -> "uint8_t"
            source.startsWith("w") || source.startsWith("e") -> "uint32_t"
            else -> "uint64_t"
        }
    }

    /** Converts a memory operand to a typed C lvalue. */
    private fun memoryLValue(memory: Pair<String, Long>, type: String): String {
        val pointer = "${reg(memory.first)}${addressOffset(memory.second)}"
        return "*($type *)(" + pointer + ")"
    }

    private fun cAssign(destination: String, source: String, elf: ElfInfo): String {
        val cleanDestination = stripSize(destination)
        val dst = if (cleanDestination.startsWith("[")) {
            val memory = parseMemory(cleanDestination) ?: return "// unsupported store: $destination"
            memoryLValue(memory, operandType(destination, source))
        } else {
            reg(cleanDestination)
        }
        return "$dst = ${cValue(source, elf)};"
    }

    private fun cValue(text: String, elf: ElfInfo): String {
        val original = text.trim()
        val t = stripSize(original)
        if (t.isEmpty()) return t
        if (t.startsWith("[")) {
            val memory = parseMemory(t) ?: return t
            return memoryLValue(memory, operandType(original))
        }
        if (t.startsWith("#") || t.startsWith("0x") || t.startsWith("0X") || t.matches(Regex("-?\\d+"))) {
            val value = parseImmediate(t)
            return when {
                value == null -> t
                value < 0 -> "-0x${(-value).toString(16)}"
                else -> "0x${value.toString(16)}"
            }
        }
        if (t.startsWith("\"") || t.startsWith("'")) return t
        return reg(t)
    }

    private fun collectRegisters(instructions: List<CapstoneBridge.Insn>, arch: ElfArch): List<String> {
        val regex = Regex("""\b(?:[xw]\d{1,2}|[re]?[abcd]x|[re]?(?:si|di|bp|sp)|r(?:8|9|1[0-5])d?|rip)\b""")
        val found = LinkedHashSet<String>()
        instructions.forEach { instruction ->
            regex.findAll(instruction.operands).forEach { found.add(it.value) }
        }
        found.removeAll(argumentRegisters(arch))
        found.removeAll(setOf("xzr", "wzr"))
        return found.toList()
    }

    private fun localType(name: String): String = when {
        name.startsWith("w") || name.startsWith("e") || name.endsWith("d") -> "uint32_t"
        name.endsWith("l") || name.endsWith("b") -> "uint8_t"
        name in setOf("ax", "bx", "cx", "dx", "si", "di") -> "uint16_t"
        else -> "uint64_t"
    }

    private fun loadType(mnemonic: String, register: String): String = when {
        mnemonic.contains("byte") || mnemonic.startsWith("ldrb") || mnemonic.startsWith("strb") -> "uint8_t"
        mnemonic.contains("short") || mnemonic.startsWith("ldrh") || mnemonic.startsWith("strh") -> "uint16_t"
        register.startsWith("w") || register.startsWith("e") -> "uint32_t"
        else -> "uint64_t"
    }

    private fun addressOffset(offset: Long): String = when {
        offset == 0L -> ""
        offset > 0 -> " + 0x${offset.toString(16)}"
        else -> " - 0x${(-offset).toString(16)}"
    }

    private fun conditionFor(
        mnemonic: String,
        compare: Pair<String, String>?,
        test: Pair<String, String>?
    ): String? {
        val op = when {
            mnemonic.contains("eq") || mnemonic.contains("je") -> "=="
            mnemonic.contains("ne") || mnemonic.contains("jne") -> "!="
            mnemonic.endsWith("jz") -> "=="
            mnemonic.endsWith("jnz") -> "!="
            mnemonic.endsWith("js") -> "<"
            mnemonic.endsWith("jns") -> ">="
            mnemonic.contains("lt") || mnemonic.contains("jl") -> "<"
            mnemonic.contains("le") || mnemonic.contains("jle") -> "<="
            mnemonic.contains("gt") || mnemonic.contains("jg") -> ">"
            mnemonic.contains("ge") || mnemonic.contains("jge") -> ">="
            mnemonic.contains("lo") || mnemonic.contains("jb") -> "<"
            mnemonic.contains("hi") || mnemonic.contains("ja") -> ">"
            mnemonic.contains("ls") || mnemonic.contains("jbe") -> "<="
            mnemonic.contains("hs") || mnemonic.contains("jae") -> ">="
            else -> null
        } ?: return null
        if (test != null) {
            val left = test.first
            val right = test.second
            return if (left == right) "$left $op 0" else "($left & $right) $op 0"
        }
        val left = compare?.first ?: return null
        return "$left $op ${compare.second}"
    }

    private fun branchTarget(insn: CapstoneBridge.Insn, function: ElfFunction, parts: List<String>): Long? {
        if (insn.target > function.address) return insn.target
        val immediate = parts.lastOrNull()?.let(::parseImmediate) ?: return null
        return if (immediate > function.address) immediate else null
    }

    private fun ripTarget(insn: CapstoneBridge.Insn): Long? {
        val operands = insn.operands
        val marker = if (operands.contains("rip")) "rip" else return null
        val index = operands.indexOf(marker)
        if (index < 0) return null
        val tail = operands.substring(index)
        val match = Regex("""(?:rip\s*([+-])\s*)?(0x[0-9a-fA-F]+|\d+)""").find(tail) ?: return null
        val value = parseImmediate(match.groupValues[2]) ?: return null
        val signed = if (match.groupValues[1] == "-") -value else value
        return insn.address + insn.size + signed
    }

    private fun stringAt(elf: ElfInfo, address: Long): String? {
        val bytes = elf.readVirtual(address, 192) ?: return null
        val end = bytes.indexOf(0)
        if (end < 2) return null
        val text = String(bytes, 0, end, Charsets.UTF_8)
        if (text.any { it.code !in 9..13 && it.code !in 32..126 }) return null
        return text
    }

    private fun resolveName(elf: ElfInfo, target: Long): String {
        elf.functions.firstOrNull { it.address == target }?.name?.let { return it }
        elf.symbols.firstOrNull { it.value == target && it.name.isNotBlank() }?.name?.let { return it }
        return "sub_${target.toString(16)}"
    }

    private fun sanitize(name: String): String {
        val clean = name.replace(Regex("[^A-Za-z0-9_]"), "_").trim('_')
        return if (clean.isEmpty() || clean.first().isDigit()) "sub_$clean" else clean
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun hex(value: Long): String = value.toString(16)

    private fun returnRegister(arch: ElfArch): String = when (arch) {
        ElfArch.ARM -> "r0"
        ElfArch.ARM64 -> "x0"
        ElfArch.X86 -> "eax"
        ElfArch.X86_64 -> "rax"
        else -> "x0"
    }

    private fun returnType(arch: ElfArch): String =
        if (arch == ElfArch.ARM || arch == ElfArch.X86) "uint32_t" else "uint64_t"

    private fun functionArguments(arch: ElfArch): String = when (arch) {
        ElfArch.ARM -> "uint32_t r0, uint32_t r1, uint32_t r2, uint32_t r3"
        ElfArch.X86_64 -> "uint64_t rdi, uint64_t rsi, uint64_t rdx, uint64_t rcx, uint64_t r8, uint64_t r9"
        ElfArch.X86 -> "uint32_t eax, uint32_t ebx, uint32_t ecx, uint32_t edx"
        else -> "uint64_t x0, uint64_t x1, uint64_t x2, uint64_t x3, uint64_t x4, uint64_t x5, uint64_t x6, uint64_t x7"
    }

    private fun callArguments(arch: ElfArch): String = when (arch) {
        ElfArch.ARM -> "r0, r1, r2, r3"
        ElfArch.X86_64 -> "rdi, rsi, rdx, rcx, r8, r9"
        ElfArch.X86 -> "eax, ebx, ecx, edx"
        else -> "x0, x1, x2, x3"
    }

    private fun argumentRegisters(arch: ElfArch): Set<String> = when (arch) {
        ElfArch.ARM -> setOf("r0", "r1", "r2", "r3")
        ElfArch.X86_64 -> setOf("rdi", "rsi", "rdx", "rcx", "r8", "r9")
        ElfArch.X86 -> setOf("eax", "ebx", "ecx", "edx")
        else -> (0..7).map { "x$it" }.toSet()
    }
}

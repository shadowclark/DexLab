package com.dexlab.app.native

object InstructionExplainer {
    private val ARM64 = mapOf(
        "mov" to "Copies a register or immediate value. No flags are changed.",
        "movz" to "Moves a 16-bit immediate into a register and zeroes the rest.",
        "movk" to "Keeps the other bits and inserts a 16-bit immediate at a lane; used to build 64-bit constants.",
        "add" to "Adds two registers or a register and immediate, writing the result to a register.",
        "sub" to "Subtracts; often used for stack allocation (sub sp, sp, #N).",
        "cmp" to "Alias for subs with the result discarded; sets condition flags.",
        "b" to "Unconditional branch. The link register is unchanged.",
        "bl" to "Branch with link: calls a function and writes the return address to x30.",
        "br" to "Branch to the address held in a register.",
        "blr" to "Branch with link to a register; indirect call.",
        "ret" to "Returns, usually by branching to x30.",
        "cbz" to "Compare and branch if the register is zero.",
        "cbnz" to "Compare and branch if the register is non-zero.",
        "tbz" to "Test a single bit and branch if it is zero.",
        "tbnz" to "Test a single bit and branch if it is non-zero.",
        "ldr" to "Load a register from memory, often a literal pool or a struct field.",
        "str" to "Store a register to memory.",
        "ldp" to "Load a pair of registers; common in function prologues and epilogues.",
        "stp" to "Store a pair of registers; stp x29, x30, [sp, #-N]! is the standard frame setup.",
        "adrp" to "Form the 4 KB page address of a symbol or literal into a register.",
        "adr" to "Form the address of a nearby label into a register.",
        "and" to "Bitwise AND; also used for masking and alignment.",
        "orr" to "Bitwise OR.",
        "eor" to "Bitwise exclusive OR; a common XOR obfuscation primitive.",
        "lsl" to "Logical shift left.",
        "lsr" to "Logical shift right.",
        "asr" to "Arithmetic shift right, preserving the sign.",
        "mul" to "Multiplies two registers.",
        "madd" to "Multiply-add: Rd = Ra + Rn * Rm.",
        "sdiv" to "Signed division.",
        "udiv" to "Unsigned division.",
        "nop" to "No operation; often used for alignment or patching.",
        "svc" to "Supervisor call: enters the kernel. Syscall number is in x8.",
        "pac" to "Pointer authentication: signs a pointer with a key.",
        "aut" to "Pointer authentication: authenticates a signed pointer.",
        "brk" to "Breakpoint trap; used by debuggers and anti-debugging checks."
    )

    private val ARM = mapOf(
        "push" to "Stores registers on the stack and decrements sp; push {lr} preserves the return address.",
        "pop" to "Loads registers from the stack and increments sp; pop {pc} returns.",
        "ldr" to "Load a register from memory or a literal pool.",
        "str" to "Store a register to memory.",
        "bl" to "Branch with link: calls a function and writes the return address to lr.",
        "bx" to "Branch and exchange; bx lr returns from a function.",
        "blx" to "Branch with link and exchange (ARM/Thumb interworking).",
        "cmp" to "Compare and set condition flags.",
        "mov" to "Move a register or immediate.",
        "add" to "Add.",
        "sub" to "Subtract; often adjusts the stack pointer.",
        "b" to "Branch.",
        "ret" to "Return (pseudo-instruction for bx lr in newer assemblers).",
        "svc" to "Supervisor call into the kernel.",
        "mrs" to "Move from a special register, such as the thread pointer.",
        "msr" to "Move to a special register."
    )

    private val X86 = mapOf(
        "push" to "Pushes a value onto the stack; push rbp is the classic frame setup.",
        "pop" to "Pops a value from the stack.",
        "mov" to "Copies data between registers or memory.",
        "movzx" to "Moves and zero-extends a smaller value.",
        "movsx" to "Moves and sign-extends a smaller value.",
        "lea" to "Loads an effective address; often used for arithmetic without touching memory.",
        "call" to "Calls a function, pushing the return address.",
        "jmp" to "Unconditional jump.",
        "je" to "Jump if equal (ZF=1).",
        "jne" to "Jump if not equal (ZF=0).",
        "jz" to "Jump if zero.",
        "jnz" to "Jump if not zero.",
        "cmp" to "Compares two operands and sets flags.",
        "test" to "Bitwise AND that only sets flags; common before a conditional jump.",
        "add" to "Add.",
        "sub" to "Subtract.",
        "xor" to "Exclusive OR; xor eax, eax is the idiomatic zeroing instruction.",
        "and" to "Bitwise AND.",
        "or" to "Bitwise OR.",
        "shl" to "Shift left.",
        "shr" to "Logical shift right.",
        "sar" to "Arithmetic shift right.",
        "imul" to "Signed multiply.",
        "idiv" to "Signed divide.",
        "ret" to "Return to the caller.",
        "nop" to "No operation.",
        "int3" to "Breakpoint trap (0xCC); used by debuggers and anti-debugging checks.",
        "syscall" to "Enter the kernel on x86-64; syscall number is in rax.",
        "leave" to "Restore the stack frame: mov rsp, rbp; pop rbp."
    )

    fun explain(arch: ElfArch, mnemonic: String): String? {
        val table = when (arch) {
            ElfArch.ARM64 -> ARM64
            ElfArch.ARM -> ARM
            ElfArch.X86, ElfArch.X86_64 -> X86
            else -> emptyMap()
        }
        val key = mnemonic.lowercase().substringBefore('.')
        return table[key] ?: table[mnemonic.lowercase()]
    }

    fun register(arch: ElfArch, name: String): String? {
        val r = name.lowercase().removePrefix("%")
        return when (arch) {
            ElfArch.ARM64 -> when {
                r == "sp" -> "Stack pointer (x31 in most contexts)."
                r == "lr" || r == "x30" -> "Link register: holds the return address after a bl."
                r == "fp" || r == "x29" -> "Frame pointer, by convention."
                r == "pc" -> "Program counter."
                r == "xzr" || r == "wzr" -> "Zero register: reads as 0, writes are discarded."
                r == "x8" -> "Syscall number on Linux, or an indirect result pointer."
                Regex("""[wx]([0-7])""").matches(r) -> "Argument/return register ${r.substring(1)} in the AAPCS64 calling convention."
                Regex("""[wx]([0-9]|[12][0-9]|30)""").matches(r) -> "General-purpose register ${r.substring(1)}. Callee-saved from x19 upward."
                else -> null
            }
            ElfArch.ARM -> when {
                r == "sp" || r == "r13" -> "Stack pointer."
                r == "lr" || r == "r14" -> "Link register: holds the return address after a bl."
                r == "pc" || r == "r15" -> "Program counter."
                Regex("""r([0-3])""").matches(r) -> "Argument/return register in the AAPCS calling convention."
                Regex("""r([4-9]|1[0-2])""").matches(r) -> "General-purpose register; r4-r11 are callee-saved."
                else -> null
            }
            ElfArch.X86, ElfArch.X86_64 -> when (r) {
                "rsp", "esp", "sp" -> "Stack pointer."
                "rbp", "ebp", "bp" -> "Frame pointer, by convention."
                "rip", "eip", "ip" -> "Instruction pointer."
                "rax", "eax", "ax", "al" -> "Accumulator; return value on x86-64, syscall number on Linux."
                "rdi", "edi", "di" -> "First integer argument on x86-64 (SysV)."
                "rsi", "esi", "si" -> "Second integer argument on x86-64."
                "rdx", "edx", "dx" -> "Third integer argument; also the high half of some divides."
                "rcx", "ecx", "cx" -> "Fourth integer argument; loop counter."
                "r8", "r9" -> "Fifth and sixth integer arguments on x86-64."
                "rbx", "ebx", "bx" -> "Callee-saved general register."
                else -> null
            }
            else -> null
        }
    }
}

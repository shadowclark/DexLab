package com.dexlab.app.native

import java.util.ArrayDeque

enum class CallDirection {
    CALLEES,
    CALLERS
}

data class CallEdge(
    val fromAddress: Long,
    val fromFunctionAddress: Long,
    val fromName: String,
    val toAddress: Long,
    val toName: String,
    val kind: XrefKind,
    val instruction: String
)

data class CallGraphNode(
    val address: Long,
    val name: String,
    val callers: List<CallEdge>,
    val callees: List<CallEdge>
)

data class CallTreeNode(
    val address: Long,
    val name: String,
    val children: List<CallTreeNode>,
    val cycle: Boolean,
    val more: Boolean
)

/**
 * One bounded pass over the function table. Xrefs and call trees then read from
 * this map instead of rescanning every function for each node.
 */
data class NativeCallGraph(
    val focusAddress: Long,
    val nodes: Map<Long, CallGraphNode>,
    val edges: List<CallEdge>,
    val scannedFunctions: Int,
    val totalFunctions: Int,
    val truncated: Boolean,
    val note: String
) {
    fun node(address: Long): CallGraphNode =
        nodes[address] ?: CallGraphNode(
            address = address,
            name = "sub_${address.toString(16)}",
            callers = emptyList(),
            callees = emptyList()
        )

    fun callers(address: Long): List<CallEdge> = node(address).callers

    fun callees(address: Long): List<CallEdge> = node(address).callees

    fun tree(
        root: Long,
        direction: CallDirection,
        maxDepth: Int = 3,
        maxNodes: Int = 80
    ): CallTreeNode {
        var visited = 0
        fun walk(address: Long, name: String, depth: Int, path: Set<Long>): CallTreeNode {
            if (address in path) {
                return CallTreeNode(address, name, emptyList(), cycle = true, more = false)
            }
            visited++
            val edges = when (direction) {
                CallDirection.CALLEES -> callersOrCallees(address, true)
                CallDirection.CALLERS -> callersOrCallees(address, false)
            }
            if (depth >= maxDepth) {
                return CallTreeNode(address, name, emptyList(), cycle = false, more = edges.isNotEmpty())
            }
            val children = ArrayList<CallTreeNode>()
            var cut = false
            for (edge in edges) {
                if (visited >= maxNodes) {
                    cut = true
                    break
                }
                val childAddress = when (direction) {
                    CallDirection.CALLEES -> edge.toAddress
                    CallDirection.CALLERS -> edge.fromAddress
                }
                val childName = when (direction) {
                    CallDirection.CALLEES -> edge.toName
                    CallDirection.CALLERS -> edge.fromName
                }
                children.add(walk(childAddress, childName, depth + 1, path + address))
            }
            return CallTreeNode(address, name, children, cycle = false, more = cut)
        }
        return walk(root, node(root).name, 0, emptySet())
    }

    private fun callersOrCallees(address: Long, outgoing: Boolean): List<CallEdge> {
        val node = nodes[address] ?: return emptyList()
        return if (outgoing) node.callees else node.callers
    }
}

object NativeCallGraphBuilder {
    private const val MAX_FUNCTIONS = 900
    private const val MAX_INSTRUCTIONS = 220
    private const val MAX_EDGES = 8_000

    fun build(elf: ElfInfo): NativeCallGraph {
        val functions = elf.functions
            .asSequence()
            .filter { it.address > 0 }
            .take(MAX_FUNCTIONS)
            .toList()
        val byAddress = functions.associateBy { it.address }
        val edges = LinkedHashMap<String, CallEdge>()
        var scanned = 0
        var truncated = functions.size < elf.functions.size

        for (function in functions) {
            if (edges.size >= MAX_EDGES) {
                truncated = true
                break
            }
            val mode = modeFor(elf.arch, function.thumb) ?: break
            val span = if (function.size > 0) function.size else 512L
            val functionEnd = function.address + span
            val bytes = runCatching { elf.functionBytes(function) }.getOrDefault(ByteArray(0))
            if (bytes.isEmpty()) continue
            val instructions = runCatching {
                CapstoneBridge.disassemble(mode.first, mode.second, bytes, function.address, MAX_INSTRUCTIONS)
            }.getOrDefault(emptyList())
            if (instructions.isEmpty()) continue
            scanned++
            for (insn in instructions) {
                val kind = directKind(elf.arch, insn.mnemonic.lowercase()) ?: continue
                val target = normalizeTarget(elf, insn.target)
                if (target <= 0 || target == function.address) continue
                // A branch inside this function is control flow, not a call edge.
                if (target in function.address until functionEnd) continue
                if (!isExecutable(elf, target)) continue
                val targetName = byAddress[target]?.name ?: nameAt(elf, target)
                val edge = CallEdge(
                    fromAddress = insn.address,
                    fromFunctionAddress = function.address,
                    fromName = function.name,
                    toAddress = target,
                    toName = targetName,
                    kind = kind,
                    instruction = "${insn.mnemonic} ${insn.operands}".trim()
                )
                edges["${insn.address}:${target}"] = edge
                if (edges.size >= MAX_EDGES) {
                    truncated = true
                    break
                }
            }
        }

        val allEdges = edges.values.toList()
        val callers = allEdges.groupBy { it.toAddress }
        val callees = allEdges.groupBy { it.fromFunctionAddress }
        val addresses = LinkedHashSet<Long>()
        functions.forEach { addresses.add(it.address) }
        allEdges.forEach {
            addresses.add(it.fromFunctionAddress)
            addresses.add(it.toAddress)
        }
        val nodes = addresses.associateWith { address ->
            CallGraphNode(
                address = address,
                name = byAddress[address]?.name ?: nameAt(elf, address),
                callers = callers[address].orEmpty(),
                callees = callees[address].orEmpty()
            )
        }

        val note = buildString {
            append("Direct calls and tail jumps only. ")
            if (truncated) append("Scanned $scanned of ${elf.functions.size} functions. ")
            append("Indirect calls stay unresolved.")
        }
        return NativeCallGraph(
            focusAddress = 0,
            nodes = nodes,
            edges = allEdges,
            scannedFunctions = scanned,
            totalFunctions = elf.functions.size,
            truncated = truncated,
            note = note
        )
    }

    private fun modeFor(arch: ElfArch, thumb: Boolean): Pair<Int, Int>? = when (arch) {
        ElfArch.ARM64 -> CapstoneBridge.ARCH_ARM64 to CapstoneBridge.MODE_ARM
        ElfArch.ARM -> CapstoneBridge.ARCH_ARM to if (thumb) CapstoneBridge.MODE_THUMB else CapstoneBridge.MODE_ARM
        ElfArch.X86_64 -> CapstoneBridge.ARCH_X86 to CapstoneBridge.MODE_64
        ElfArch.X86 -> CapstoneBridge.ARCH_X86 to CapstoneBridge.MODE_32
        else -> null
    }

    private fun directKind(arch: ElfArch, mnemonic: String): XrefKind? = when (arch) {
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

    private fun normalizeTarget(elf: ElfInfo, target: Long): Long =
        if (elf.arch == ElfArch.ARM && target and 1L == 1L) target - 1 else target

    private fun isExecutable(elf: ElfInfo, address: Long): Boolean =
        elf.sectionAt(address)?.executable == true || elf.segmentAt(address)?.executable == true

    private fun nameAt(elf: ElfInfo, address: Long): String {
        elf.functions.firstOrNull { it.address == address }?.name?.let { return it }
        elf.symbols.firstOrNull { it.value == address && it.name.isNotBlank() }?.name?.let { return it }
        return "sub_${address.toString(16)}"
    }
}

/**
 * Small helper used by the UI to describe a tree in plain text for exports.
 */
object CallTreeText {
    fun render(root: CallTreeNode, direction: CallDirection): String {
        val out = StringBuilder()
        fun visit(node: CallTreeNode, depth: Int) {
            out.append("  ".repeat(depth))
            out.append(if (depth == 0) "" else if (direction == CallDirection.CALLEES) "-> " else "<- ")
            out.append(node.name).append(" @ ").append("0x").append(node.address.toString(16))
            if (node.cycle) out.append(" [cycle]")
            if (node.more) out.append(" [more]")
            out.append('\n')
            node.children.forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
        return out.toString()
    }
}

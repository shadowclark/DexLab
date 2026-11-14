package com.dexlab.app.export

import android.content.Context
import android.net.Uri
import com.dexlab.app.core.Fmt
import com.dexlab.app.native.CallDirection
import com.dexlab.app.native.CallTreeText
import com.dexlab.app.native.CapstoneBridge
import com.dexlab.app.native.ElfFunction
import com.dexlab.app.native.ElfInfo
import com.dexlab.app.native.NativeAnnotation
import com.dexlab.app.native.NativeFunctionAnalysis
import com.dexlab.app.native.PseudoResult
import com.dexlab.app.native.XrefKind
import java.io.File

enum class GraphExportFormat(val extension: String, val label: String) {
    DOT("dot", "DOT"),
    JSON("json", "JSON")
}

/**
 * Plain-text exports stay useful outside DexLab and state their limits up front.
 * File exports copy the analyst's original or extracted binary without edits.
 */
object NativeExport {
    fun writeText(context: Context, uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.writer(Charsets.UTF_8).use { it.write(text) }
        } ?: error("Cannot open the selected destination")
    }

    fun copyFile(context: Context, uri: Uri, file: File) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        } ?: error("Cannot open the selected destination")
    }

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        "dex" -> "application/octet-stream"
        "so", "elf" -> "application/octet-stream"
        "zip", "apks", "xapk", "jar" -> "application/zip"
        "c", "h" -> "text/x-c"
        "asm", "s" -> "text/plain"
        "txt", "md" -> "text/plain"
        else -> "application/octet-stream"
    }

    fun suggestedName(name: String, extension: String): String {
        val clean = name.substringAfterLast('/').substringBeforeLast('.').ifBlank { "dexlab-export" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .take(72)
        return "$clean.$extension"
    }

    fun pseudo(result: PseudoResult, annotation: NativeAnnotation? = null): String = buildString {
        appendLine("// DexLab static export")
        appendLine("// Function: ${result.function.name}")
        appendLine("// Address: ${Fmt.address(result.function.address)}")
        appendLine("// Instructions decoded: ${result.instructionCount}")
        if (!annotation?.label.isNullOrBlank()) appendLine("// Local label: ${annotation?.label}")
        if (!annotation?.comment.isNullOrBlank()) appendLine("// Analyst note: ${annotation?.comment}")
        appendLine("// ${result.note}")
        appendLine("// Heuristic reconstruction, not a true decompiler. Verify against disassembly.")
        appendLine()
        append(result.code)
    }

    fun disassembly(
        elf: ElfInfo,
        function: ElfFunction,
        instructions: List<CapstoneBridge.Insn>,
        annotation: NativeAnnotation? = null
    ): String = buildString {
        appendLine("; DexLab native disassembly export")
        appendLine("; Binary: ${binaryLabel(elf)}")
        appendLine("; Function: ${function.name}")
        appendLine("; Address: ${Fmt.address(function.address)}")
        appendLine("; Architecture: ${elf.machineName}")
        if (!annotation?.label.isNullOrBlank()) appendLine("; Local label: ${annotation?.label}")
        if (!annotation?.comment.isNullOrBlank()) appendLine("; Analyst note: ${annotation?.comment}")
        appendLine("; Capstone output. Direct calls only in the xref report.")
        appendLine()
        instructions.forEach { insn ->
            append(insn.address.toString(16).padStart(8, '0'))
            append("  ")
            append(insn.bytes.padEnd(18))
            append(insn.mnemonic.padEnd(9))
            append(insn.operands)
            appendLine()
        }
    }

    fun report(analysis: NativeFunctionAnalysis): String = buildString {
        appendLine("DexLab native function report")
        appendLine("==============================")
        appendLine("Binary:        ${analysis.binaryLabel}")
        appendLine("Function:      ${analysis.function.name}")
        appendLine("Display name:  ${analysis.displayName}")
        appendLine("Address:       ${Fmt.address(analysis.function.address)}")
        appendLine("Size:          ${Fmt.bytes(analysis.function.size)}")
        appendLine("Bookmarked:    ${if (analysis.annotation.bookmarked) "yes" else "no"}")
        appendLine()
        appendLine("Local annotation")
        appendLine("----------------")
        appendLine(analysis.annotation.comment.ifBlank { "(none)" })
        appendLine()
        appendLine("Cross-references")
        appendLine("----------------")
        appendLine("Callers: ${analysis.xrefs.callers.size}")
        analysis.xrefs.callers.forEach { x ->
            appendLine("  ${Fmt.address(x.fromAddress)}  ${x.fromFunction}  ${x.instruction}")
        }
        appendLine("Callees: ${analysis.xrefs.callees.size}")
        analysis.xrefs.callees.forEach { x ->
            val kind = if (x.kind == XrefKind.CALL) "call" else "jump"
            appendLine("  ${Fmt.address(x.fromAddress)}  $kind ${x.toFunction} @ ${Fmt.address(x.toAddress)}  ${x.instruction}")
        }
        appendLine("String references: ${analysis.xrefs.strings.size}")
        analysis.xrefs.strings.forEach { x ->
            appendLine("  ${Fmt.address(x.fromAddress)}  \"${x.text.orEmpty()}\"")
        }
        appendLine()
        appendLine("Call tree — callees (depth 3)")
        appendLine("-----------------------------")
        append(CallTreeText.render(analysis.graph.tree(analysis.function.address, CallDirection.CALLEES), CallDirection.CALLEES))
        appendLine()
        appendLine("Call tree — callers (depth 3)")
        appendLine("-----------------------------")
        append(CallTreeText.render(analysis.graph.tree(analysis.function.address, CallDirection.CALLERS), CallDirection.CALLERS))
        appendLine()
        appendLine("Basic blocks")
        appendLine("------------")
        analysis.cfg.blocks.forEach { block ->
            val successors = block.successors.joinToString(", ") { "L_${it.toString(16)}" }.ifBlank { "none" }
            appendLine(
                "${block.label}  ${Fmt.address(block.startAddress)}..${Fmt.address(block.endAddress)}  " +
                    "${block.instructionCount} insn  term=${block.terminator}  -> $successors"
            )
        }
        appendLine()
        appendLine("Limits")
        appendLine("------")
        appendLine(analysis.xrefs.note)
        appendLine(analysis.graph.note)
        appendLine(analysis.cfg.note)
    }

    fun graph(analysis: NativeFunctionAnalysis, format: GraphExportFormat): String =
        when (format) {
            GraphExportFormat.DOT -> graphDot(analysis)
            GraphExportFormat.JSON -> graphJson(analysis)
        }

    /**
     * One portable graph file: direct call edges around the selected function
     * plus the basic-block CFG as a separate cluster.
     */
    fun graphDot(analysis: NativeFunctionAnalysis): String = buildString {
        val fn = analysis.function
        appendLine("digraph DexLabNative {")
        appendLine("  graph [rankdir=LR, bgcolor=\"transparent\", fontname=\"monospace\"];")
        appendLine("  node [shape=box, style=\"filled\", fillcolor=\"#1d1914\", color=\"#453c2f\", fontcolor=\"#ede6d8\", fontname=\"monospace\", fontsize=10];")
        appendLine("  edge [color=\"#9eae99\", fontcolor=\"#aa9e8b\", fontname=\"monospace\", fontsize=9];")
        appendLine("  ${dotId("focus", fn.address)} [label=\"${dotEscape(fn.name)}\\n0x${fn.address.toString(16)}\", color=\"#d39a5a\", penwidth=2];")
        val callers = analysis.graph.callers(fn.address).distinctBy { it.fromAddress }
        val callees = analysis.graph.callees(fn.address).distinctBy { it.toAddress }
        callers.forEach { edge ->
            appendLine("  ${dotId("caller", edge.fromAddress)} [label=\"${dotEscape(edge.fromName)}\\n0x${edge.fromAddress.toString(16)}\"];")
            appendLine("  ${dotId("caller", edge.fromAddress)} -> ${dotId("focus", fn.address)} [label=\"${dotEscape(edge.instruction)}\"];")
        }
        callees.forEach { edge ->
            appendLine("  ${dotId("callee", edge.toAddress)} [label=\"${dotEscape(edge.toName)}\\n0x${edge.toAddress.toString(16)}\"];")
            appendLine("  ${dotId("focus", fn.address)} -> ${dotId("callee", edge.toAddress)} [label=\"${dotEscape(edge.instruction)}\"];")
        }
        appendLine("  subgraph cluster_cfg {")
        appendLine("    label=\"basic blocks · ${analysis.cfg.blocks.size}\";")
        appendLine("    color=\"#453c2f\";")
        appendLine("    fontcolor=\"#aa9e8b\";")
        analysis.cfg.blocks.forEach { block ->
            appendLine("    ${dotId("block", block.startAddress)} [label=\"${dotEscape(block.label)}\\n${block.instructionCount} insn\", color=\"#8d9eac\"];")
        }
        analysis.cfg.blocks.forEach { block ->
            block.successors.forEach { target ->
                appendLine("    ${dotId("block", block.startAddress)} -> ${dotId("block", target)};")
            }
        }
        appendLine("  }")
        appendLine("}")
    }

    fun graphJson(analysis: NativeFunctionAnalysis): String = buildString {
        val fn = analysis.function
        appendLine("{")
        appendLine("  \"binary\": ${jsonString(analysis.binaryLabel)},")
        appendLine("  \"function\": {\"name\": ${jsonString(fn.name)}, \"address\": \"0x${fn.address.toString(16)}\", \"size\": ${fn.size}},")
        appendLine("  \"callers\": [")
        val callers = analysis.graph.callers(fn.address).distinctBy { it.fromAddress }
        callers.forEachIndexed { index, edge ->
            append("    {\"address\": \"0x${edge.fromAddress.toString(16)}\", \"name\": ${jsonString(edge.fromName)}, \"instruction\": ${jsonString(edge.instruction)}, \"kind\": \"${edge.kind.name.lowercase()}\"}")
            if (index != callers.lastIndex) append(',')
            appendLine()
        }
        appendLine("  ],")
        appendLine("  \"callees\": [")
        val callees = analysis.graph.callees(fn.address).distinctBy { it.toAddress }
        callees.forEachIndexed { index, edge ->
            append("    {\"address\": \"0x${edge.toAddress.toString(16)}\", \"name\": ${jsonString(edge.toName)}, \"instruction\": ${jsonString(edge.instruction)}, \"kind\": \"${edge.kind.name.lowercase()}\"}")
            if (index != callees.lastIndex) append(',')
            appendLine()
        }
        appendLine("  ],")
        appendLine("  \"cfg\": {")
        appendLine("    \"note\": ${jsonString(analysis.cfg.note)},")
        appendLine("    \"blocks\": [")
        analysis.cfg.blocks.forEachIndexed { index, block ->
            append("      {\"label\": ${jsonString(block.label)}, \"start\": \"0x${block.startAddress.toString(16)}\", \"end\": \"0x${block.endAddress.toString(16)}\", \"instructions\": ${block.instructionCount}, \"terminator\": ${jsonString(block.terminator)}, \"successors\": [")
            append(block.successors.joinToString(", ") { "\"L_${it.toString(16)}\"" })
            append("]}")
            if (index != analysis.cfg.blocks.lastIndex) append(',')
            appendLine()
        }
        appendLine("    ]")
        appendLine("  }")
        appendLine("}")
    }

    private fun dotId(prefix: String, address: Long): String = "${prefix}_${address.toString(16)}"

    private fun dotEscape(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun jsonString(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\""

    private fun binaryLabel(elf: ElfInfo): String =
        elf.soname ?: elf.buildId?.let { "build $it" } ?: "${elf.arch.label} ELF (${elf.bytes.size} bytes)"
}

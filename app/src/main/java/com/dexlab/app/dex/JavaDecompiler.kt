package com.dexlab.app.dex

import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.Field
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.jf.dexlib2.iface.instruction.OffsetInstruction
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.reference.TypeReference
import org.jf.dexlib2.iface.value.BooleanEncodedValue
import org.jf.dexlib2.iface.value.CharEncodedValue
import org.jf.dexlib2.iface.value.DoubleEncodedValue
import org.jf.dexlib2.iface.value.EncodedValue
import org.jf.dexlib2.iface.value.FloatEncodedValue
import org.jf.dexlib2.iface.value.IntEncodedValue
import org.jf.dexlib2.iface.value.LongEncodedValue
import org.jf.dexlib2.iface.value.StringEncodedValue

/**
 * A deliberately conservative Java-shaped view of a DEX class. It is not a
 * compiler and it does not recover types or structured control flow. The smali
 * pane remains the ground truth.
 */
object DexJavaDecompiler {
    fun generate(dex: DexFile, descriptor: String): String {
        val type = dex.classes.firstOrNull { it.type == descriptor }
            ?: return "// Class $descriptor was not found in this DEX.\n"
        return buildString {
            appendLine("// DexLab heuristic Java view")
            appendLine("// Class: ${type.type}")
            if (!type.sourceFile.isNullOrBlank()) appendLine("// Source: ${type.sourceFile}")
            appendLine("// Not compilable. Dalvik registers are shown as v0, p0, etc.;")
            appendLine("// use the smali pane for ground truth.")
            appendLine()
            val packageName = DexRepository.displayName(type.type).substringBeforeLast('.', "")
            if (packageName.isNotBlank()) appendLine("package $packageName;")
            appendLine()

            val className = DexRepository.displayName(type.type).substringAfterLast('.')
            val classFlags = modifier(type.accessFlags, classModifiers).trim()
            val keyword = when {
                AccessFlags.ANNOTATION.isSet(type.accessFlags) -> "@interface"
                AccessFlags.INTERFACE.isSet(type.accessFlags) -> "interface"
                AccessFlags.ENUM.isSet(type.accessFlags) -> "enum"
                else -> "class"
            }
            if (classFlags.isNotBlank()) append(classFlags).append(' ')
            append(keyword).append(' ').append(className)
            val superClass = type.superclass
            if (!superClass.isNullOrBlank() && superClass != "Ljava/lang/Object;") {
                append(" extends ").append(javaType(superClass))
            }
            if (type.interfaces.isNotEmpty()) {
                append(" implements ").append(type.interfaces.joinToString(", ") { javaType(it) })
            }
            appendLine(" {")

            val fields = type.fields.toList().sortedBy { it.name }
            if (fields.isNotEmpty()) {
                appendLine("    // Fields")
                fields.forEach { field ->
                    append("    ").append(modifier(field.accessFlags, fieldModifiers))
                        .append(javaType(field.type)).append(' ').append(sanitize(field.name))
                    field.initialValue?.let { value ->
                        valueToJava(value)?.let { append(" = ").append(it) }
                    }
                    appendLine(";")
                }
                appendLine()
            }

            type.methods.sortedWith(compareBy<Method> { if (it.name == "<init>") 0 else 1 }.thenBy { it.name }).forEach { method ->
                if (method.name == "<clinit>") return@forEach
                appendLine("    " + signature(method))
                val impl = method.implementation
                if (impl == null || AccessFlags.ABSTRACT.isSet(method.accessFlags) ||
                    AccessFlags.NATIVE.isSet(method.accessFlags)
                ) {
                    appendLine()
                    return@forEach
                }
                val body = body(method)
                if (body.isBlank()) {
                    appendLine("    { }")
                } else {
                    appendLine("    {")
                    appendLine("        // ${impl.registerCount} Dalvik registers")
                    append(body.prependIndent("        "))
                    appendLine("    }")
                }
                appendLine()
            }
            appendLine("}")
        }
    }

    private fun signature(method: Method): String {
        val name = if (method.name == "<init>") DexRepository.displayName(method.definingClass).substringAfterLast('.')
        else sanitize(method.name)
        val params = method.parameterTypes.mapIndexed { index, type ->
            "${javaType(type.toString())} p$index"
        }.joinToString(", ")
        if (method.name == "<init>") {
            return "${modifier(method.accessFlags, methodModifiers)}$name($params)"
        }
        return "${modifier(method.accessFlags, methodModifiers)}${javaType(method.returnType)} $name($params)"
    }

    private fun body(method: Method): String {
        val instructions = method.implementation?.instructions?.toList().orEmpty()
        if (instructions.isEmpty()) return ""
        val labels = LinkedHashSet<Int>()
        var pc = 0
        instructions.forEach { insn ->
            if (insn is OffsetInstruction) labels.add(pc + insn.codeOffset * 2)
            pc += insn.codeUnits * 2
        }
        val out = StringBuilder()
        var address = 0
        var emitted = 0
        for (insn in instructions) {
            if (address in labels) out.append("label_").append(address.toString(16)).appendLine(":")
            if (emitted >= 500) {
                out.appendLine("// Body capped at 500 decoded instructions.")
                break
            }
            val op = insn.opcode.name.lowercase().replace('_', '-')
            val line = statement(insn, op, address)
            if (line != null) {
                out.append(line).appendLine()
                emitted++
            }
            address += insn.codeUnits * 2
        }
        return out.toString()
    }

    private fun statement(
        insn: org.jf.dexlib2.iface.instruction.Instruction,
        op: String,
        address: Int
    ): String? {
        val one = insn as? OneRegisterInstruction
        val two = insn as? TwoRegisterInstruction
        val three = insn as? ThreeRegisterInstruction
        if (op == "nop") return null
        if (op.startsWith("return")) {
            return when (op) {
                "return-void" -> "return;"
                else -> one?.let { "return ${reg(it.registerA)};" } ?: "return;"
            }
        }
        if (op.startsWith("move-result")) {
            return one?.let { "${reg(it.registerA)} = result;" }
        }
        if (op.startsWith("move-exception")) {
            return one?.let { "${reg(it.registerA)} = exception;" }
        }
        if (op.startsWith("move")) {
            return if (two != null) "${reg(two.registerA)} = ${reg(two.registerB)};" else null
        }
        if (op == "const-string" || op == "const-string/jumbo" || op == "const-string-jumbo") {
            val reference = (insn as? ReferenceInstruction)?.reference as? StringReference
            return if (one != null && reference != null) {
                "${reg(one.registerA)} = \"${escape(reference.string)}\";"
            } else null
        }
        if (op == "const-class") {
            val reference = (insn as? ReferenceInstruction)?.reference as? TypeReference
            return if (one != null && reference != null) {
                "${reg(one.registerA)} = ${javaType(reference.type)}.class;"
            } else null
        }
        if (op.startsWith("const")) {
            val literal = insn as? NarrowLiteralInstruction
            val wide = insn as? WideLiteralInstruction
            val value = literal?.narrowLiteral ?: wide?.wideLiteral
            if (one != null && value != null) return "${reg(one.registerA)} = $value;"
        }
        if (op == "new-instance") {
            val reference = (insn as? ReferenceInstruction)?.reference as? TypeReference
            return if (one != null && reference != null) {
                "${reg(one.registerA)} = new ${javaType(reference.type)}();"
            } else null
        }
        if (op.startsWith("new-array")) {
            val reference = (insn as? ReferenceInstruction)?.reference as? TypeReference
            return if (two != null && reference != null) {
                "${reg(two.registerA)} = new ${javaType(reference.type)}[${reg(two.registerB)}];"
            } else null
        }
        if (op.startsWith("iget")) {
            val reference = (insn as? ReferenceInstruction)?.reference as? FieldReference
            return if (two != null && reference != null) {
                "${reg(two.registerA)} = ${reg(two.registerB)}.${sanitize(reference.name)};"
            } else null
        }
        if (op.startsWith("iput")) {
            val reference = (insn as? ReferenceInstruction)?.reference as? FieldReference
            return if (two != null && reference != null) {
                "${reg(two.registerB)}.${sanitize(reference.name)} = ${reg(two.registerA)};"
            } else null
        }
        if (op.startsWith("sget") || op.startsWith("sput")) {
            val reference = (insn as? ReferenceInstruction)?.reference as? FieldReference
            if (one != null && reference != null) {
                val field = "${javaType(reference.definingClass)}.${sanitize(reference.name)}"
                return if (op.startsWith("sget")) "${reg(one.registerA)} = $field;" else "$field = ${reg(one.registerA)};"
            }
        }
        if (op.startsWith("invoke-")) {
            val reference = (insn as? ReferenceInstruction)?.reference as? MethodReference
                ?: return null
            val methodName = if (reference.name == "<init>") javaType(reference.definingClass).substringAfterLast('.')
            else sanitize(reference.name)
            val registers = registers(insn)
            if (op.contains("static")) {
                return "${javaType(reference.definingClass)}.$methodName(${registers.joinToString(", ") { reg(it) }});"
            }
            if (registers.isEmpty()) return null
            val receiver = registers.first()
            val args = registers.drop(1).joinToString(", ") { reg(it) }
            if (reference.name == "<init>") {
                return "${reg(receiver)} = new ${javaType(reference.definingClass)}($args);"
            }
            return "${reg(receiver)}.$methodName($args);"
        }
        if (op.startsWith("if-")) {
            val offset = insn as? OffsetInstruction ?: return null
            val target = "label_" + (address + offset.codeOffset * 2).toString(16)
            val condition = when (op) {
                "if-eqz", "if-nez", "if-ltz", "if-gez", "if-gtz", "if-lez" -> {
                    val r = one?.registerA ?: return null
                    val cmp = when (op) {
                        "if-eqz" -> "== 0"
                        "if-nez" -> "!= 0"
                        "if-ltz" -> "< 0"
                        "if-gez" -> ">= 0"
                        "if-gtz" -> "> 0"
                        else -> "<= 0"
                    }
                    "${reg(r)} $cmp"
                }
                else -> {
                    val a = two?.registerA ?: return null
                    val b = two.registerB
                    val cmp = when (op) {
                        "if-eq" -> "=="
                        "if-ne" -> "!="
                        "if-lt" -> "<"
                        "if-ge" -> ">="
                        "if-gt" -> ">"
                        else -> "<="
                    }
                    "${reg(a)} $cmp ${reg(b)}"
                }
            }
            return "if ($condition) goto $target;"
        }
        if (op.startsWith("goto")) {
            val offset = insn as? OffsetInstruction ?: return null
            return "goto label_" + (address + offset.codeOffset * 2).toString(16) + ";"
        }
        if (op == "check-cast") {
            val reference = (insn as? ReferenceInstruction)?.reference as? TypeReference
            return if (one != null && reference != null) {
                "${reg(one.registerA)} = (${javaType(reference.type)}) ${reg(one.registerA)};"
            } else null
        }
        if (op == "instance-of") {
            val reference = (insn as? ReferenceInstruction)?.reference as? TypeReference
            return if (two != null && reference != null) {
                "${reg(two.registerA)} = ${reg(two.registerB)} instanceof ${javaType(reference.type)};"
            } else null
        }
        if (op == "array-length") {
            return if (two != null) "${reg(two.registerA)} = ${reg(two.registerB)}.length;" else null
        }
        if (op.startsWith("aget") && one != null && two != null && three != null) {
            return "${reg(one.registerA)} = ${reg(two.registerB)}[${reg(three.registerC)}];"
        }
        if (op.startsWith("aput") && one != null && two != null && three != null) {
            return "${reg(two.registerB)}[${reg(three.registerC)}] = ${reg(one.registerA)};"
        }
        if (op.startsWith("cmp") && one != null && two != null && three != null) {
            return "${reg(one.registerA)} = (${reg(two.registerB)} > ${reg(three.registerC)}) - (${reg(two.registerB)} < ${reg(three.registerC)});"
        }
        if (op in setOf("neg-int", "neg-long", "neg-float", "neg-double", "not-int", "not-long") && two != null) {
            val symbol = if (op.startsWith("not")) "~" else "-"
            return "${reg(two.registerA)} = $symbol${reg(two.registerB)};"
        }
        if (op.contains("-to-") && two != null) {
            val target = op.substringAfter("-to-").substringBefore('/')
            val cast = when (target) {
                "byte" -> "byte"
                "char" -> "char"
                "short" -> "short"
                "int" -> "int"
                "long" -> "long"
                "float" -> "float"
                "double" -> "double"
                else -> null
            }
            if (cast != null) return "${reg(two.registerA)} = ($cast) ${reg(two.registerB)};"
        }
        val baseOp = op.substringBefore('/')
        val binary = when (baseOp) {
            "add-int", "add-long", "add-float", "add-double" -> "+"
            "sub-int", "sub-long", "sub-float", "sub-double" -> "-"
            "mul-int", "mul-long", "mul-float", "mul-double" -> "*"
            "div-int", "div-long", "div-float", "div-double" -> "/"
            "rem-int", "rem-long" -> "%"
            "and-int", "and-long" -> "&"
            "or-int", "or-long" -> "|"
            "xor-int", "xor-long" -> "^"
            "shl-int", "shl-long" -> "<<"
            "shr-int", "shr-long" -> ">>"
            "ushr-int", "ushr-long" -> ">>>"
            else -> null
        }
        val literal = (insn as? NarrowLiteralInstruction)?.narrowLiteral
            ?: (insn as? WideLiteralInstruction)?.wideLiteral
        if (binary != null && two != null && literal != null) {
            return "${reg(two.registerA)} = ${reg(two.registerB)} $binary $literal;"
        }
        if (binary != null && three != null && one != null && two != null) {
            return "${reg(one.registerA)} = ${reg(two.registerB)} $binary ${reg(three.registerC)};"
        }
        if (binary != null && two != null && op.endsWith("/2addr")) {
            return "${reg(two.registerA)} = ${reg(two.registerA)} $binary ${reg(two.registerB)};"
        }
        if (op == "throw") return one?.let { "throw ${reg(it.registerA)};" }
        if (op == "monitor-enter" || op == "monitor-exit") {
            return one?.let { "// $op ${reg(it.registerA)}" }
        }
        return "// $op"
    }

    private fun registers(insn: org.jf.dexlib2.iface.instruction.Instruction): List<Int> {
        return when (insn) {
            is FiveRegisterInstruction -> {
                val all = listOf(
                    insn.registerC, insn.registerD, insn.registerE,
                    insn.registerF, insn.registerG
                ).filterNotNull()
                all.take(insn.registerCount.coerceIn(0, 5))
            }
            is RegisterRangeInstruction -> (0 until insn.registerCount).map { insn.startRegister + it }
            is ThreeRegisterInstruction -> listOf(
                (insn as OneRegisterInstruction).registerA,
                (insn as TwoRegisterInstruction).registerB,
                insn.registerC
            )
            is TwoRegisterInstruction -> listOf(insn.registerA, insn.registerB)
            is OneRegisterInstruction -> listOf(insn.registerA)
            else -> emptyList()
        }
    }

    private fun reg(register: Int): String = "v$register"

    private fun modifier(flags: Int, allowed: List<Pair<AccessFlags, String>>): String =
        allowed.filter { (flag, _) -> flag.isSet(flags) }.joinToString(" ") { it.second }
            .let { if (it.isBlank()) "" else "$it " }

    private val classModifiers = listOf(
        AccessFlags.PUBLIC to "public",
        AccessFlags.PRIVATE to "private",
        AccessFlags.PROTECTED to "protected",
        AccessFlags.FINAL to "final",
        AccessFlags.ABSTRACT to "abstract"
    )
    private val methodModifiers = listOf(
        AccessFlags.PUBLIC to "public",
        AccessFlags.PRIVATE to "private",
        AccessFlags.PROTECTED to "protected",
        AccessFlags.STATIC to "static",
        AccessFlags.FINAL to "final",
        AccessFlags.ABSTRACT to "abstract",
        AccessFlags.NATIVE to "native",
        AccessFlags.SYNCHRONIZED to "synchronized"
    )
    private val fieldModifiers = listOf(
        AccessFlags.PUBLIC to "public",
        AccessFlags.PRIVATE to "private",
        AccessFlags.PROTECTED to "protected",
        AccessFlags.STATIC to "static",
        AccessFlags.FINAL to "final",
        AccessFlags.VOLATILE to "volatile",
        AccessFlags.TRANSIENT to "transient"
    )

    private fun javaType(descriptor: String): String {
        if (descriptor.isEmpty()) return "void"
        return when (descriptor[0]) {
            'V' -> "void"
            'Z' -> "boolean"
            'B' -> "byte"
            'S' -> "short"
            'C' -> "char"
            'I' -> "int"
            'J' -> "long"
            'F' -> "float"
            'D' -> "double"
            'L' -> DexRepository.displayName(descriptor)
            '[' -> javaType(descriptor.substring(1)) + "[]"
            else -> descriptor
        }
    }

    private fun valueToJava(value: EncodedValue): String? = when (value) {
        is StringEncodedValue -> "\"${escape(value.value)}\""
        is IntEncodedValue -> value.value.toString()
        is LongEncodedValue -> "${value.value}L"
        is BooleanEncodedValue -> value.value.toString()
        is CharEncodedValue -> "'${value.value}'"
        is FloatEncodedValue -> "${value.value}f"
        is DoubleEncodedValue -> value.value.toString()
        else -> null
    }

    private fun sanitize(name: String): String {
        val clean = name.replace(Regex("[^A-Za-z0-9_$]"), "_")
        return if (clean.isEmpty() || clean.first().isDigit()) "field_$clean" else clean
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

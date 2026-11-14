package com.dexlab.app.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

data class AxmlAttribute(
    val namespace: String?,
    val name: String,
    val resourceId: Int,
    val rawValue: String?,
    val value: String
)

data class AxmlNode(
    val name: String,
    val namespace: String?,
    val attributes: List<AxmlAttribute>,
    val children: MutableList<AxmlNode> = mutableListOf()
) {
    fun attr(name: String): String? = attributes.firstOrNull { it.name == name }?.value

    fun androidAttr(name: String): String? = attributes.firstOrNull {
        it.name == name && (it.namespace == ANDROID_NS || it.namespace == null)
    }?.value
}

object AndroidAttrNames {
    val resolver: (Int) -> String? by lazy {
        val map = HashMap<Int, String>(2048)
        runCatching {
            val cls = Class.forName("android.R\$attr")
            for (field in cls.fields) {
                val id = runCatching { field.getInt(null) }.getOrNull() ?: continue
                map[id] = field.name
            }
        }
        val lookup: (Int) -> String? = { id -> map[id] }
        lookup
    }
}

/**
 * Parser for Android's compiled binary XML (AXML) format.
 * Implements just enough of the resource chunk format to reconstruct a readable manifest.
 */
class AxmlParser(private val attrNameResolver: (Int) -> String? = { null }) {
    private var bytes: ByteArray = ByteArray(0)

    fun parse(data: ByteArray): AxmlNode {
        bytes = data
        require(data.size >= 8) { "File is too small to be binary XML" }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val type = buf.getShort(0).toInt() and 0xFFFF
        require(type == RES_XML_TYPE) { "Not a binary Android XML file (chunk 0x%04x)".format(type) }
        val headerSize = buf.getShort(2).toInt() and 0xFFFF
        val fileSize = minOf(data.size, buf.getInt(4))
        require(fileSize >= 8) { "Invalid binary XML size" }

        var strings: List<String> = emptyList()
        var resIds = IntArray(0)
        val stack = ArrayDeque<AxmlNode>()
        var root: AxmlNode? = null

        var pos = if (headerSize in 8..fileSize) headerSize else 8
        while (pos + 8 <= fileSize) {
            val chunkType = buf.getShort(pos).toInt() and 0xFFFF
            val chunkHeaderSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(pos + 4)
            if (chunkSize < 8 || pos + chunkSize > fileSize) break

            when (chunkType) {
                RES_STRING_POOL_TYPE -> strings = parseStringPool(buf, pos, chunkHeaderSize, chunkSize)
                RES_XML_RESOURCE_MAP_TYPE -> resIds = parseIntArray(buf, pos, chunkHeaderSize, chunkSize)
                RES_XML_START_ELEMENT_TYPE -> {
                    val node = parseStartElement(buf, pos, chunkHeaderSize, chunkSize, strings, resIds)
                    if (stack.isEmpty()) {
                        if (root == null) root = node
                    } else {
                        stack.last().children.add(node)
                    }
                    stack.addLast(node)
                }
                RES_XML_END_ELEMENT_TYPE -> {
                    if (stack.isNotEmpty()) stack.removeLast()
                }
            }
            pos += chunkSize
        }
        return root ?: error("No root element in binary XML")
    }

    private fun parseStringPool(buf: ByteBuffer, start: Int, headerSize: Int, size: Int): List<String> {
        val count = buf.getInt(start + 8)
        val flags = buf.getInt(start + 16)
        val stringsStart = buf.getInt(start + 20)
        if (count <= 0) return emptyList()
        if (count > 1_000_000) error("Unreasonable string pool size")
        val utf8 = (flags and (1 shl 8)) != 0
        val offsetsBase = start + headerSize
        val dataBase = start + stringsStart
        val end = start + size
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            val offPos = offsetsBase + i * 4
            if (offPos + 4 > end) break
            val stringPos = dataBase + buf.getInt(offPos)
            if (stringPos < dataBase || stringPos >= end) {
                out.add("")
                continue
            }
            out.add(if (utf8) readUtf8(buf, stringPos, end) else readUtf16(buf, stringPos, end))
        }
        return out
    }

    private fun readUtf8(buf: ByteBuffer, pos: Int, end: Int): String {
        var p = pos
        if (p >= end) return ""
        var len = buf.get(p).toInt() and 0xFF
        p++
        if ((len and 0x80) != 0) {
            if (p >= end) return ""
            len = ((len and 0x7F) shl 8) or (buf.get(p).toInt() and 0xFF)
            p++
        }
        if (len < 0 || len > 8 * 1024 * 1024) return ""
        val stop = minOf(p + len, end)
        return String(bytes, p, (stop - p).coerceAtLeast(0), Charsets.UTF_8)
    }

    private fun readUtf16(buf: ByteBuffer, pos: Int, end: Int): String {
        var p = pos
        if (p + 2 > end) return ""
        var len = buf.getShort(p).toInt() and 0xFFFF
        p += 2
        if ((len and 0x8000) != 0) {
            if (p + 2 > end) return ""
            len = ((len and 0x7FFF) shl 16) or (buf.getShort(p).toInt() and 0xFFFF)
            p += 2
        }
        if (len < 0 || len > 4 * 1024 * 1024) return ""
        val chars = CharArray(len)
        for (i in 0 until len) {
            val q = p + i * 2
            if (q + 2 > end) return String(chars, 0, i)
            chars[i] = buf.getChar(q)
        }
        return String(chars)
    }

    private fun parseIntArray(buf: ByteBuffer, start: Int, headerSize: Int, size: Int): IntArray {
        val count = ((size - headerSize) / 4).coerceAtLeast(0)
        val out = IntArray(count)
        for (i in 0 until count) {
            val p = start + headerSize + i * 4
            if (p + 4 > start + size) break
            out[i] = buf.getInt(p)
        }
        return out
    }

    private fun parseStartElement(
        buf: ByteBuffer,
        start: Int,
        headerSize: Int,
        size: Int,
        strings: List<String>,
        resIds: IntArray
    ): AxmlNode {
        val end = start + size
        val ext = start + headerSize
        val nsIndex = buf.getInt(ext)
        val nameIndex = buf.getInt(ext + 4)
        val attrStart = buf.getShort(ext + 8).toInt() and 0xFFFF
        val attrSize = buf.getShort(ext + 10).toInt() and 0xFFFF
        val attrCount = buf.getShort(ext + 12).toInt() and 0xFFFF
        val stride = if (attrSize >= 20) attrSize else 20
        val attrBase = ext + attrStart
        val attributes = ArrayList<AxmlAttribute>(attrCount)

        for (i in 0 until attrCount) {
            val p = attrBase + i * stride
            if (p + 20 > end) break
            val attrNs = strings.getOrNull(buf.getInt(p))
            val nameIdx = buf.getInt(p + 4)
            val rawIdx = buf.getInt(p + 8)
            val dataType = buf.get(p + 15).toInt() and 0xFF
            val data = buf.getInt(p + 16)
            val raw = if (rawIdx in strings.indices) strings[rawIdx] else null
            val poolName = if (nameIdx in strings.indices) strings[nameIdx] else ""
            val resId = if (nameIdx in resIds.indices) resIds[nameIdx] else 0
            val resolved = if (resId != 0) attrNameResolver(resId) else null
            val name = resolved ?: poolName.ifEmpty {
                if (resId != 0) "attr_0x%08x".format(resId) else "attr_$i"
            }
            val value = if (!raw.isNullOrEmpty()) raw else formatTyped(dataType, data, strings)
            attributes.add(AxmlAttribute(attrNs, name, resId, raw, value))
        }

        return AxmlNode(
            name = strings.getOrNull(nameIndex).orEmpty().ifEmpty { "unknown" },
            namespace = strings.getOrNull(nsIndex),
            attributes = attributes
        )
    }

    private fun formatTyped(type: Int, data: Int, strings: List<String>): String = when (type) {
        0x00 -> "null"
        0x01 -> "@0x%08x".format(data)
        0x02 -> "?0x%08x".format(data)
        0x03 -> strings.getOrNull(data) ?: ""
        0x04 -> java.lang.Float.intBitsToFloat(data).toString()
        0x05 -> formatComplex(data)
        0x06 -> formatPercent(data)
        0x10 -> data.toString()
        0x11 -> "0x%08x".format(data)
        0x12 -> if (data != 0) "true" else "false"
        in 0x1c..0x1f -> "#%08x".format(data)
        else -> "0x%08x".format(data)
    }

    private fun formatComplex(data: Int): String {
        val radix = (data shr 4) and 0x3
        val unit = data and 0xF
        val mantissa = (data and 0xFFFFFF00.toInt()) shr 8
        val value = when (radix) {
            0 -> mantissa.toFloat()
            1 -> mantissa.toFloat() / 256f
            2 -> mantissa.toFloat() / 65536f
            else -> mantissa.toFloat() / 16777216f
        }
        val suffix = when (unit) {
            0 -> "px"
            1 -> "dp"
            2 -> "sp"
            3 -> "pt"
            4 -> "in"
            5 -> "mm"
            else -> ""
        }
        return String.format(Locale.US, "%.2f%s", value, suffix)
    }

    private fun formatPercent(data: Int): String {
        val radix = (data shr 4) and 0x3
        val mantissa = (data and 0xFFFFFF00.toInt()) shr 8
        val value = when (radix) {
            0 -> mantissa.toFloat()
            1 -> mantissa.toFloat() / 256f
            2 -> mantissa.toFloat() / 65536f
            else -> mantissa.toFloat() / 16777216f
        }
        return String.format(Locale.US, "%.1f%%", value * 100f)
    }

    companion object {
        private const val RES_XML_TYPE = 0x0003
        private const val RES_STRING_POOL_TYPE = 0x0001
        private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
        private const val RES_XML_START_ELEMENT_TYPE = 0x0102
        private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    }
}

fun AxmlNode.prettyXml(): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
    writeNode(this@prettyXml, 0, this)
}

private fun writeNode(node: AxmlNode, depth: Int, sb: StringBuilder) {
    val indent = "  ".repeat(depth)
    sb.append(indent).append('<').append(node.name)
    if (depth == 0) sb.append(" xmlns:android=\"").append(ANDROID_NS).append('"')
    for (a in node.attributes) {
        val prefix = if (a.namespace == ANDROID_NS) "android:" else ""
        sb.append(' ').append(prefix).append(a.name).append("=\"")
        sb.append(escapeXml(a.value)).append('"')
    }
    if (node.children.isEmpty()) {
        sb.append(" />\n")
    } else {
        sb.append(">\n")
        for (child in node.children) writeNode(child, depth + 1, sb)
        sb.append(indent).append("</").append(node.name).append(">\n")
    }
}

private fun escapeXml(s: String): String = buildString(s.length) {
    for (c in s) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}

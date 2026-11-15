package com.dexlab.app.native

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the GNU build ID from an ELF PT_NOTE segment without loading the
 * whole native library. Roblox libraries are large, so the parser skips
 * directly to the note segment instead of buffering the .so in memory.
 */
object ElfBuildId {
    private const val PT_NOTE = 4L
    private const val NT_GNU_BUILD_ID = 3L
    private const val MAX_PROGRAM_HEADERS = 65_535
    private const val MAX_NOTE_BYTES = 1_048_576L
    private const val MAX_PROGRAM_HEADER_BYTES = 8_000_000

    fun read(input: InputStream): String? = runCatching { readOrNull(input) }.getOrNull()

    private fun readOrNull(input: InputStream): String? {
        val ident = ByteArray(16)
        if (!readFully(input, ident, 0, 16)) return null
        if (ident[0] != 0x7f.toByte() || ident[1] != 'E'.code.toByte() ||
            ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()
        ) return null

        val is64 = ident[4].toInt() == 2
        val little = ident[5].toInt() == 1
        val headerSize = if (is64) 64 else 52
        val header = ByteArray(headerSize)
        ident.copyInto(header)
        if (!readFully(input, header, 16, headerSize - 16)) return null
        val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val headerBuf = ByteBuffer.wrap(header).order(order)

        val phoff = if (is64) headerBuf.getLong(32) else headerBuf.getInt(28).toLong() and 0xffffffffL
        val phentsize = (if (is64) headerBuf.getShort(54) else headerBuf.getShort(42)).toInt() and 0xffff
        val phnum = (if (is64) headerBuf.getShort(56) else headerBuf.getShort(44)).toInt() and 0xffff
        val minPhentsize = if (is64) 56 else 32
        if (phoff < headerSize || phnum <= 0 || phnum > MAX_PROGRAM_HEADERS || phentsize < minPhentsize) return null

        val tableBytes = phnum.toLong() * phentsize
        if (tableBytes > MAX_PROGRAM_HEADER_BYTES) return null
        if (!skipFully(input, phoff - headerSize)) return null

        val table = ByteArray(tableBytes.toInt())
        if (!readFully(input, table, 0, table.size)) return null
        val tableBuf = ByteBuffer.wrap(table).order(order)
        val notes = ArrayList<Note>()
        for (i in 0 until phnum) {
            val base = i * phentsize
            val type = if (is64) tableBuf.getInt(base).toLong() and 0xffffffffL
            else tableBuf.getInt(base).toLong() and 0xffffffffL
            if (type != PT_NOTE) continue
            val offset = if (is64) tableBuf.getLong(base + 8) else tableBuf.getInt(base + 4).toLong() and 0xffffffffL
            val size = if (is64) tableBuf.getLong(base + 32) else tableBuf.getInt(base + 16).toLong() and 0xffffffffL
            if (offset >= 0 && size in 1..MAX_NOTE_BYTES) notes.add(Note(offset, size))
        }

        var cursor = phoff + tableBytes
        for (note in notes.sortedBy { it.offset }) {
            if (note.offset < cursor) continue
            if (!skipFully(input, note.offset - cursor)) return null
            val data = ByteArray(note.size.toInt())
            if (!readFully(input, data, 0, data.size)) return null
            cursor = note.offset + note.size
            parseNote(data, little)?.let { return it }
        }
        return null
    }

    private fun parseNote(data: ByteArray, little: Boolean): String? {
        val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val buf = ByteBuffer.wrap(data).order(order)
        var p = 0
        while (p + 12 <= data.size) {
            val namesz = buf.getInt(p).toLong() and 0xffffffffL
            val descsz = buf.getInt(p + 4).toLong() and 0xffffffffL
            val type = buf.getInt(p + 8).toLong() and 0xffffffffL
            val nameStart = p + 12L
            val descStart = nameStart + align4(namesz)
            val next = descStart + align4(descsz)
            if (namesz > data.size || descStart + descsz > data.size || next <= p) return null
            if (type == NT_GNU_BUILD_ID) {
                val name = String(data, nameStart.toInt(), namesz.toInt(), Charsets.US_ASCII)
                if (name.startsWith("GNU")) {
                    return data.copyOfRange(descStart.toInt(), (descStart + descsz).toInt()).toHex()
                }
            }
            p = next.toInt()
        }
        return null
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    private fun align4(value: Long): Long = (value + 3) and -4L

    private fun readFully(input: InputStream, dest: ByteArray, offset: Int, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val n = input.read(dest, offset + read, length - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long): Boolean {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() < 0) return false
                remaining--
            }
        }
        return true
    }

    private data class Note(val offset: Long, val size: Long)

    private val HEX = "0123456789abcdef".toCharArray()
}
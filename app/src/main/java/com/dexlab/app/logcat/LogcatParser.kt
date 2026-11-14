package com.dexlab.app.logcat

data class LogEntry(
    val time: String,
    val pid: Int,
    val tid: Int,
    val level: Char,
    val tag: String,
    val message: String
)

object LogcatParser {
    private val THREADTIME = Regex(
        """^(\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2}\.\d{3,6})\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.*?)\s*:\s?(.*)$"""
    )
    private val BRIEF = Regex("""^([VDIWEFA])/(.*?)\(\s*(\d+)\):\s?(.*)$""")

    fun parse(line: String): LogEntry? {
        THREADTIME.find(line)?.let { m ->
            return LogEntry(
                time = m.groupValues[2],
                pid = m.groupValues[3].toIntOrNull() ?: -1,
                tid = m.groupValues[4].toIntOrNull() ?: -1,
                level = m.groupValues[5].first(),
                tag = m.groupValues[6].trim(),
                message = m.groupValues[7]
            )
        }
        BRIEF.find(line)?.let { m ->
            return LogEntry(
                time = "",
                pid = m.groupValues[3].toIntOrNull() ?: -1,
                tid = -1,
                level = m.groupValues[1].first(),
                tag = m.groupValues[2].trim(),
                message = m.groupValues[4]
            )
        }
        return null
    }
}

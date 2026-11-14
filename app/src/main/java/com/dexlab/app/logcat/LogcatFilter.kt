package com.dexlab.app.logcat

import java.util.Locale

data class LogFilterState(
    val minLevel: Char = 'V',
    val preset: String = "All",
    val query: String = "",
    val tag: String = "",
    val pid: String = ""
) {
    val activeCount: Int
        get() = listOf(
            minLevel != 'V',
            preset != "All",
            query.isNotBlank(),
            tag.isNotBlank(),
            pid.isNotBlank()
        ).count { it }
}

object LogcatFilter {
    val LEVELS = listOf('V', 'D', 'I', 'W', 'E', 'F', 'A')
    val PRESETS = listOf("All", "Crashes", "JNI", "TLS", "Activity", "Network")

    fun rank(level: Char): Int = when (level) {
        'V' -> 0
        'D' -> 1
        'I' -> 2
        'W' -> 3
        'E' -> 4
        'F' -> 5
        'A' -> 6
        else -> 0
    }

    fun matches(entry: LogEntry, filter: LogFilterState): Boolean {
        if (rank(entry.level) < rank(filter.minLevel)) return false

        val q = filter.query.trim()
        if (q.isNotEmpty()) {
            val haystack = "${entry.tag} ${entry.message} ${entry.pid} ${entry.tid}"
            if (!haystack.contains(q, ignoreCase = true)) return false
        }

        if (filter.tag.isNotBlank() && !entry.tag.contains(filter.tag.trim(), ignoreCase = true)) {
            return false
        }

        val pid = filter.pid.trim()
        if (pid.isNotEmpty()) {
            val matchesPid = if (pid.all { it.isDigit() }) {
                entry.pid.toString() == pid || entry.tid.toString() == pid
            } else {
                ("${entry.pid} ${entry.tid}").contains(pid, ignoreCase = true)
            }
            if (!matchesPid) return false
        }

        val text = "${entry.tag} ${entry.message}".lowercase(Locale.US)
        return when (filter.preset) {
            "Crashes" -> entry.level in "EFA" ||
                text.contains("fatal") ||
                text.contains("exception") ||
                text.contains("crash") ||
                text.contains("anr")
            "JNI" -> text.contains("jni") || text.contains("native") ||
                text.contains("jni_onload") || text.contains("unsatisfiedlink")
            "TLS" -> text.contains("ssl") || text.contains("tls") ||
                text.contains("cert") || text.contains("trustmanager") ||
                text.contains("okhttp")
            "Activity" -> text.contains("activity") || text.contains("windowmanager") ||
                text.contains("activitytaskmanager") || text.contains("anr")
            "Network" -> text.contains("network") || text.contains("okhttp") ||
                text.contains("http") || text.contains("socket") || text.contains("connect")
            else -> true
        }
    }

    fun apply(entries: List<LogEntry>, filter: LogFilterState, limit: Int = 2000): List<LogEntry> {
        val out = ArrayList<LogEntry>(minOf(entries.size, limit))
        for (entry in entries) {
            if (matches(entry, filter)) {
                out.add(entry)
                if (out.size > limit) out.removeAt(0)
            }
        }
        return out
    }
}

package com.dexlab.app.logcat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class LogcatEngine {
    private var process: Process? = null
    private var job: Job? = null
    private var maxEntries = 20_000

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun start(scope: CoroutineScope, root: Boolean, pid: Int?, max: Int = 20_000) {
        stop()
        maxEntries = max
        _error.value = null
        val command = if (root) {
            val inner = buildString {
                append("logcat -v threadtime -T 200")
                if (pid != null) append(" --pid=").append(pid)
            }
            listOf("su", "-c", inner)
        } else {
            buildList {
                add("logcat")
                add("-v")
                add("threadtime")
                add("-T")
                add("200")
                if (pid != null) add("--pid=$pid")
            }
        }

        _running.value = true
        job = scope.launch(Dispatchers.IO) {
            var reader: BufferedReader? = null
            try {
                val p = ProcessBuilder(command).redirectErrorStream(true).start()
                process = p
                reader = BufferedReader(InputStreamReader(p.inputStream))
                val buffer = ArrayList<LogEntry>(64)
                var lastFlush = 0L
                while (isActive) {
                    val line = reader.readLine() ?: break
                    val parsed = LogcatParser.parse(line)
                    if (parsed != null) {
                        buffer.add(parsed)
                    } else if (line.isNotBlank() && buffer.isNotEmpty()) {
                        val last = buffer[buffer.size - 1]
                        buffer[buffer.size - 1] = last.copy(message = last.message + "\n" + line)
                    }
                    val now = System.currentTimeMillis()
                    if (buffer.size >= 32 || (buffer.isNotEmpty() && now - lastFlush > 150)) {
                        flush(buffer)
                        lastFlush = now
                    }
                }
                flush(buffer)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _error.value = t.message ?: t.javaClass.simpleName
            } finally {
                runCatching { reader?.close() }
                runCatching { process?.destroy() }
                process = null
                _running.value = false
            }
        }
    }

    /** Start a stream whose lines arrive from another process, such as Shizuku. */
    fun beginExternal(max: Int = 20_000) {
        stop()
        maxEntries = max
        _error.value = null
        _running.value = true
    }

    /** Append a batch of raw `logcat -v threadtime` lines from an external source. */
    fun ingest(lines: List<String>) {
        if (lines.isEmpty()) return
        val parsed = ArrayList<LogEntry>(lines.size)
        for (line in lines) {
            val entry = LogcatParser.parse(line)
            if (entry != null) {
                parsed.add(entry)
            } else if (line.isNotBlank() && parsed.isNotEmpty()) {
                val last = parsed[parsed.size - 1]
                parsed[parsed.size - 1] = last.copy(message = last.message + "\n" + line)
            }
        }
        if (parsed.isEmpty()) return
        _entries.value = (_entries.value + parsed).takeLast(maxEntries)
    }

    fun fail(message: String) {
        _error.value = message
        _running.value = false
    }

    fun finishExternal() {
        _running.value = false
    }

    private fun flush(buffer: MutableList<LogEntry>) {
        if (buffer.isEmpty()) return
        val incoming = ArrayList(buffer)
        buffer.clear()
        _entries.value = (_entries.value + incoming).takeLast(maxEntries)
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { process?.destroy() }
        process = null
        _running.value = false
    }

    fun clear() {
        _entries.value = emptyList()
    }

    fun save(file: File, lines: List<LogEntry> = _entries.value): Int {
        val text = lines.map { "${it.time} ${it.level}/${it.tag}: ${it.message}" }
        file.writeText(text.joinToString("\n"))
        return lines.size
    }
}

object Privilege {
    fun hasRoot(): Boolean {
        return runCatching {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val finished = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                p.destroy()
                return false
            }
            val out = p.inputStream.bufferedReader().readText()
            out.contains("uid=0")
        }.getOrDefault(false)
    }
}

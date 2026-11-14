package com.dexlab.app.shizuku

import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Runs in a separate process started by Shizuku with the shell (UID 2000) or
 * root identity. Do not declare this class as an Android <service>: Shizuku
 * loads it through app_process using the ComponentName in UserServiceArgs.
 */
class LogcatUserService : ILogcatService.Stub() {
    private val lock = Any()

    @Volatile
    private var running = false

    @Volatile
    private var process: Process? = null

    @Volatile
    private var thread: Thread? = null

    override fun start(callback: ILogcatCallback?, args: Array<out String>?) {
        stopInternal(notify = false)
        if (callback == null) return

        synchronized(lock) {
            running = true
            thread = Thread({ readLogcat(callback, args.orEmpty()) }, "dexlab-shizuku-logcat").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun readLogcat(callback: ILogcatCallback, extra: Array<out String>) {
        val command = ArrayList<String>(8 + extra.size)
        command.add("/system/bin/logcat")
        command.add("-v")
        command.add("threadtime")
        command.addAll(extra)

        var p: Process? = null
        try {
            p = ProcessBuilder(command).redirectErrorStream(true).start()
            synchronized(lock) {
                if (!running) {
                    p.destroy()
                    return
                }
                process = p
            }

            p.inputStream.bufferedReader().use { reader ->
                val batch = ArrayList<String>(64)
                var lastFlush = System.currentTimeMillis()
                while (running) {
                    val line = reader.readLine() ?: break
                    batch.add(line)
                    val now = System.currentTimeMillis()
                    if (batch.size >= 64 || now - lastFlush >= 120) {
                        flush(callback, batch)
                        lastFlush = now
                    }
                }
                flush(callback, batch)
            }
        } catch (t: Throwable) {
            val current = synchronized(lock) { running && process === p }
            if (current) {
                runCatching { callback.onError(t.message ?: t.javaClass.simpleName) }
            }
        } finally {
            runCatching { p?.destroy() }
            // Only the thread that still owns the active process may clear the
            // running state. An older thread must not stop a newer stream.
            val wasRunning = synchronized(lock) {
                if (process === p) {
                    process = null
                    running = false
                    true
                } else {
                    false
                }
            }
            if (wasRunning) runCatching { callback.onStopped() }
        }
    }

    private fun flush(callback: ILogcatCallback, batch: MutableList<String>) {
        if (batch.isEmpty()) return
        val lines = batch.toTypedArray()
        batch.clear()
        runCatching { callback.onLines(lines) }
    }

    override fun stop() {
        stopInternal(notify = true)
    }

    private fun stopInternal(notify: Boolean) {
        val wasRunning = running
        running = false
        val p = process
        process = null
        runCatching { p?.destroy() }
        thread?.interrupt()
        thread = null
        if (notify && wasRunning) {
            // The service callback stops the manager-side stream. Calling
            // onStopped from here can race a newly started stream.
            runCatching { process?.destroy() }
        }
    }

    override fun isRunning(): Boolean = running

    override fun packagePaths(packageName: String?): Array<String> {
        if (packageName.isNullOrBlank()) return emptyArray()
        return runCatching {
            val p = ProcessBuilder("/system/bin/pm", "path", packageName)
                .redirectErrorStream(true)
                .start()
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor()
            output.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotBlank() }
                .toList()
                .toTypedArray()
        }.getOrDefault(emptyArray())
    }

    override fun openRead(path: String?): ParcelFileDescriptor? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull()
    }

    override fun destroy() {
        stopInternal(notify = false)
        System.exit(0)
    }
}

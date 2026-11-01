package com.clahbot.input

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class RootInputSource : InputEventSource {

    override val label: String = "Root"

    @Volatile
    override var lastFailure: String? = null

    @Volatile
    private var generation = 0

    @Volatile
    private var process: Process? = null

    @Volatile
    private var readerThread: Thread? = null

    fun probe(): Boolean {
        val result = runCommand("id", 2) ?: return false
        return result.second == 0 && result.first.contains("uid=0")
    }

    override fun discover(): String? {
        val result = runCommand("/system/bin/getevent -lp", 5)
        if (result == null) {
            lastFailure = "Root getevent did not answer. Passive recording cannot start."
            return null
        }
        if (result.first.isBlank()) {
            lastFailure = "Root getevent returned no touchscreen list."
            return null
        }
        lastFailure = null
        return result.first
    }

    override fun start(
        devicePath: String,
        onLines: (String) -> Unit,
        onFatal: (String) -> Unit
    ): Boolean {
        if (!DEVICE_PATH.matches(devicePath)) {
            lastFailure = "Root touchscreen path is invalid."
            onFatal(lastFailure!!)
            return false
        }
        stop()
        val session = synchronized(this) {
            generation++
            generation
        }
        val script = "/system/bin/getevent -lt $devicePath & child=\$!; " +
            "trap 'kill \$child 2>/dev/null' TERM INT HUP; wait \$child"
        val started = try {
            ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
        } catch (t: Throwable) {
            lastFailure = "Could not start root getevent: ${t.message ?: "su unavailable"}"
            onFatal(lastFailure!!)
            return false
        }
        synchronized(this) {
            process = started
            readerThread = Thread(
                { readEvents(started, session, onLines, onFatal) },
                "AutoClash-root-getevent"
            ).apply { start() }
        }
        return true
    }

    override fun stop() {
        val running: Process?
        val thread: Thread?
        synchronized(this) {
            generation++
            running = process
            process = null
            thread = readerThread
            readerThread = null
        }
        try {
            running?.destroy()
            if (running != null && !running.waitFor(500, TimeUnit.MILLISECONDS)) {
                running.destroyForcibly()
            }
        } catch (_: Throwable) {
            // Already gone.
        }
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun close() {
        stop()
    }

    private fun readEvents(
        started: Process,
        session: Int,
        onLines: (String) -> Unit,
        onFatal: (String) -> Unit
    ) {
        try {
            val reader = BufferedReader(InputStreamReader(started.inputStream))
            var line = reader.readLine()
            while (line != null) {
                if (session != generation) break
                onLines(line)
                line = reader.readLine()
            }
            val exit = try {
                started.waitFor()
            } catch (_: Throwable) {
                -1
            }
            if (session == generation) {
                lastFailure = "Root getevent exited (code $exit)"
                onFatal(lastFailure!!)
            }
        } catch (t: Throwable) {
            if (session == generation) {
                lastFailure = "Root input failed: ${t.message ?: "unknown error"}"
                onFatal(lastFailure!!)
            }
        } finally {
            try {
                started.destroyForcibly()
            } catch (_: Throwable) {
                // Already gone.
            }
            synchronized(this) {
                if (process === started) process = null
                if (readerThread === Thread.currentThread()) readerThread = null
            }
        }
    }

    private fun runCommand(script: String, timeoutSeconds: Long): Pair<String, Int>? {
        var process: Process? = null
        val output = StringBuilder()
        var collector: Thread? = null
        try {
            val started = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            process = started
            collector = Thread({
                try {
                    started.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(output) { output.append(line).append('\n') }
                    }
                } catch (_: Throwable) {
                    // Partial output is still useful.
                }
            }, "AutoClash-root-command").apply {
                isDaemon = true
                start()
            }
            if (!started.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                started.destroyForcibly()
                return null
            }
            collector.join(500)
            return synchronized(output) { output.toString() } to started.exitValue()
        } catch (_: Throwable) {
            return null
        } finally {
            try {
                process?.destroyForcibly()
            } catch (_: Throwable) {
                // Already gone.
            }
        }
    }

    companion object {
        private val DEVICE_PATH = Regex("""^/dev/input/event\d+$""")
    }
}

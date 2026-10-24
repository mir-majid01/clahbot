package com.clahbot.input

import android.content.Context
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Runs inside the Shizuku user-service process with shell UID. The app binds
 * this through AIDL; the process itself owns getevent, so stopping is a direct
 * process kill even when the app goes away.
 */
@Keep
class ShizukuInputService : IInputEventService.Stub {

    @Keep
    constructor() : super()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    @Volatile
    private var generation = 0

    @Volatile
    private var process: Process? = null

    @Volatile
    private var readerThread: Thread? = null

    override fun getTouchscreenInfo(): String = runDiscovery()

    @Synchronized
    override fun start(devicePath: String, callback: IInputEventCallback) {
        if (!DEVICE_PATH.matches(devicePath)) {
            safeError(callback, "Invalid touchscreen path")
            return
        }
        stopLocked()
        generation++
        val session = generation
        val thread = Thread(
            { readEvents(devicePath, callback, session) },
            "AutoClash-getevent"
        )
        readerThread = thread
        thread.start()
    }

    override fun stop() {
        stopLocked()
    }

    override fun destroy() {
        stopLocked()
        System.exit(0)
    }

    private fun readEvents(
        devicePath: String,
        callback: IInputEventCallback,
        session: Int
    ) {
        var local: Process? = null
        var errorThread: Thread? = null
        val errors = StringBuilder()
        try {
            val started = ProcessBuilder(GETEVENT, "-lt", devicePath).start()
            local = started
            synchronized(this) {
                if (session != generation) {
                    started.destroyForcibly()
                    return
                }
                process = started
            }

            errorThread = Thread({
                try {
                    started.errorStream.bufferedReader().useLines { lines ->
                        lines.take(4).forEach { line ->
                            synchronized(errors) { errors.append(line).append('\n') }
                        }
                    }
                } catch (_: Throwable) {
                    // A closed stderr stream is expected on stop.
                }
            }, "AutoClash-getevent-err").apply { isDaemon = true }
            errorThread.start()

            val reader = BufferedReader(InputStreamReader(started.inputStream))
            val batch = StringBuilder()
            var line = reader.readLine()
            while (line != null) {
                if (session != generation) break
                batch.append(line).append('\n')
                if (line.contains("SYN_REPORT") ||
                    line.contains("SYN_DROPPED") ||
                    batch.length >= MAX_BATCH_CHARS
                ) {
                    sendLines(session, callback, batch.toString())
                    batch.setLength(0)
                }
                line = reader.readLine()
            }
            if (batch.isNotEmpty() && session == generation) {
                sendLines(session, callback, batch.toString())
            }
            try {
                errorThread.join(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val exit = try {
                started.waitFor()
            } catch (_: Throwable) {
                -1
            }
            if (session == generation) {
                val reason = synchronized(errors) { errors.toString().trim() }
                safeError(
                    callback,
                    if (reason.isNotEmpty()) {
                        "getevent: $reason"
                    } else {
                        "getevent exited (code $exit)"
                    }
                )
            }
        } catch (t: Throwable) {
            if (session == generation) {
                safeError(callback, "getevent failed: ${t.message ?: "unknown error"}")
            }
        } finally {
            try {
                local?.destroyForcibly()
            } catch (_: Throwable) {
                // Already gone.
            }
            synchronized(this) {
                if (process === local) process = null
                if (readerThread === Thread.currentThread()) readerThread = null
            }
        }
    }

    private fun sendLines(session: Int, callback: IInputEventCallback, text: String) {
        if (session != generation) return
        try {
            callback.onLines(text)
        } catch (_: Throwable) {
            stopLocked()
        }
    }

    private fun safeError(callback: IInputEventCallback, message: String) {
        try {
            callback.onError(message)
        } catch (_: Throwable) {
            // The app process is gone.
        }
    }

    @Synchronized
    private fun stopLocked() {
        generation++
        val running = process
        process = null
        try {
            running?.destroyForcibly()
        } catch (_: Throwable) {
            // Already gone.
        }
        val thread = readerThread
        readerThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun runDiscovery(): String {
        val output = StringBuilder()
        var discovery: Process? = null
        var collector: Thread? = null
        try {
            val started = ProcessBuilder(GETEVENT, "-lp")
                .redirectErrorStream(true)
                .start()
            discovery = started
            collector = Thread({
                try {
                    started.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(output) { output.append(line).append('\n') }
                    }
                } catch (_: Throwable) {
                    // Partial output is still useful.
                }
            }, "AutoClash-getevent-discovery").apply { isDaemon = true }
            collector.start()
            if (!started.waitFor(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                started.destroyForcibly()
            }
            collector.join(500)
        } catch (_: Throwable) {
            // The app receives an empty string and reports setup failure.
        } finally {
            try {
                discovery?.destroyForcibly()
            } catch (_: Throwable) {
                // Already gone.
            }
        }
        return synchronized(output) { output.toString() }
    }

    companion object {
        private const val GETEVENT = "/system/bin/getevent"
        private const val MAX_BATCH_CHARS = 32_000
        private const val DISCOVERY_TIMEOUT_SECONDS = 5L
        private val DEVICE_PATH = Regex("""^/dev/input/event\d+$""")
    }
}

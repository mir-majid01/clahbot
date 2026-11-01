package com.clahbot.input

/**
 * Owns one discovery + getevent session. [close] is the only method that
 * releases the source; [stop] guarantees the process is killed first.
 */
class PassiveRecorder(
    private val source: InputEventSource,
    private val geometry: () -> ScreenGeometry?,
    private val controllerBounds: () -> ScreenRect?,
    private val onGesture: (BuiltGesture) -> Unit,
    private val onError: (String) -> Unit,
    private val onTouch: (Int, ScreenPoint, Boolean) -> Unit = { _, _, _ -> }
) {
    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    private var sourceStarted = false
    private var errorReported = false

    @Volatile
    private var starting = false

    @Volatile
    private var startFailure: String? = null
    private var parser: GetEventParser? = null
    private var builder: PassiveGestureBuilder? = null

    val isRunning: Boolean get() = running

    /**
     * Runs discovery and starts the getevent stream. Returns null on success or
     * a human-readable reason on failure. Errors raised while starting are
     * returned here and never reported through [onError], so the caller can
     * restore its UI instead of showing an empty recording.
     */
    fun start(preferredDevice: TouchscreenDevice? = null): String? {
        if (running) return null

        synchronized(this) {
            errorReported = false
            starting = true
            startFailure = null
        }
        val device = preferredDevice ?: discoverDevice()
        if (device == null) {
            val reason = source.lastFailure
                ?: "No touchscreen found. Passive recording is unavailable on this device."
            synchronized(this) { starting = false }
            return reason
        }

        val newBuilder = PassiveGestureBuilder(
            device = device,
            geometry = geometry,
            controllerBounds = controllerBounds,
            onGesture = { gesture ->
                if (running && !paused) onGesture(gesture)
            },
            onTouch = onTouch
        )
        newBuilder.setEnabled(!paused)
        val newParser = GetEventParser(device) { frame -> newBuilder.onFrame(frame) }
        synchronized(this) {
            parser = newParser
            builder = newBuilder
            running = true
            sourceStarted = true
        }

        val started = try {
            source.start(
                devicePath = device.path,
                onLines = { payload ->
                    if (running) {
                        try {
                            // Shizuku batches a whole SYN_REPORT frame into one
                            // payload; root delivers one line at a time.
                            payload.lineSequence().forEach { line ->
                                if (line.isNotBlank()) newParser.feed(line)
                            }
                        } catch (t: Throwable) {
                            fail("Touch capture stopped: ${t.message ?: "parser error"}")
                        }
                    }
                },
                onFatal = { message ->
                    val report = synchronized(this) {
                        if (starting) {
                            // Startup failures must go back to start()'s caller.
                            startFailure = message
                            running = false
                            false
                        } else {
                            failLocked(message)
                        }
                    }
                    if (report) onError(message)
                }
            )
        } catch (t: Throwable) {
            synchronized(this) {
                startFailure = "Could not start touch capture: ${t.message ?: "source error"}"
                running = false
            }
            false
        }

        synchronized(this) {
            starting = false
        }
        val failure = synchronized(this) { startFailure }
        if (!started || !running || failure != null) {
            val reason = failure
                ?: source.lastFailure
                ?: "Could not start touch capture."
            source.stop()
            synchronized(this) {
                errorReported = true
                running = false
                sourceStarted = false
                clearState()
            }
            return reason
        }
        return null
    }

    private fun discoverDevice(): TouchscreenDevice? {
        val discovery = try {
            source.discover()
        } catch (t: Throwable) {
            null
        }
        if (discovery.isNullOrBlank()) return null
        return GetEventDiscovery.pickTouchscreen(discovery)
    }

    @Synchronized
    fun setPaused(paused: Boolean) {
        this.paused = paused
        builder?.setEnabled(!paused)
    }

    @Synchronized
    fun stop() {
        val hadSource = sourceStarted
        running = false
        starting = false
        errorReported = true
        parser?.reset()
        builder?.cancel()
        clearState()
        sourceStarted = false
        if (hadSource) source.stop()
    }

    fun close() {
        stop()
        source.close()
    }

    private fun fail(message: String) {
        val report = synchronized(this) {
            if (errorReported) return
            errorReported = true
            running = false
            parser?.reset()
            builder?.cancel()
            clearState()
            true
        }
        if (report) onError(message)
    }

    private fun failLocked(message: String): Boolean {
        if (errorReported) return false
        errorReported = true
        running = false
        parser?.reset()
        builder?.cancel()
        clearState()
        return true
    }

    private fun clearState() {
        parser = null
        builder = null
    }
}

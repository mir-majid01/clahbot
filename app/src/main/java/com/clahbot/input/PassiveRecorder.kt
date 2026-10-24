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
    private val onError: (String) -> Unit
) {
    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    private var sourceStarted = false
    private var errorReported = false
    private var parser: GetEventParser? = null
    private var builder: PassiveGestureBuilder? = null

    val isRunning: Boolean get() = running

    @Synchronized
    fun start(): Boolean {
        if (running) return true

        errorReported = false
        val discovery = try {
            source.discover()
        } catch (t: Throwable) {
            null
        }
        if (discovery.isNullOrBlank()) {
            fail("Could not read the touchscreen device list.")
            return false
        }
        val device = GetEventDiscovery.pickTouchscreen(discovery)
        if (device == null) {
            fail("No touchscreen found. Passive recording is unavailable on this device.")
            return false
        }

        val newBuilder = PassiveGestureBuilder(
            device = device,
            geometry = geometry,
            controllerBounds = controllerBounds,
            onGesture = { gesture ->
                if (running && !paused) onGesture(gesture)
            }
        )
        newBuilder.setEnabled(!paused)
        val newParser = GetEventParser(device) { frame -> newBuilder.onFrame(frame) }
        parser = newParser
        builder = newBuilder
        running = true
        sourceStarted = true

        val started = try {
            source.start(
                devicePath = device.path,
                onLines = { line ->
                    if (!running) return@start
                    try {
                        newParser.feed(line)
                    } catch (t: Throwable) {
                        fail("Touch capture stopped: ${t.message ?: "parser error"}")
                    }
                },
                onFatal = { message -> fail(message) }
            )
        } catch (t: Throwable) {
            fail("Could not start touch capture: ${t.message ?: "source error"}")
            false
        }

        if (!started || !running) {
            if (running) fail("Could not start touch capture.")
            source.stop()
            sourceStarted = false
            clearState()
            return false
        }
        return true
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
        synchronized(this) {
            if (errorReported) return
            errorReported = true
            running = false
            parser?.reset()
            builder?.cancel()
            clearState()
        }
        onError(message)
    }

    private fun clearState() {
        parser = null
        builder = null
    }
}

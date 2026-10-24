package com.clahbot.input

/**
 * A privileged `getevent` stream. Implementations must stop the process in
 * [stop] and release their binder in [close].
 */
interface InputEventSource {
    val label: String

    /** Runs `getevent -lp` and returns its raw text, or null on failure. */
    fun discover(): String?

    /**
     * [onLines] receives complete getevent output and may contain several
     * newline-separated lines per callback.
     */
    fun start(
        devicePath: String,
        onLines: (String) -> Unit,
        onFatal: (String) -> Unit
    ): Boolean

    fun stop()

    fun close()
}

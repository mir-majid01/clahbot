package com.clahbot.results

import kotlinx.coroutines.delay

/**
 * Coordinates a results-screen capture with macro playback.
 *
 * The watcher publishes one sample per detection poll. Immediately before a
 * late tap, the player waits for a fresh sample and, if a result screen was
 * seen, waits until the watcher has captured and read it. The tap is therefore
 * not allowed to dismiss the screen while it is being saved.
 */
class ResultCaptureGate(
    private val freshEnoughMs: Long = 500L,
    private val freshSampleWaitMs: Long = 2_000L,
    private val holdTimeoutMs: Long = 8_000L,
    private val stepMs: Long = 40L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    @Volatile
    private var hold = false

    @Volatile
    private var lastPollStartedAt = 0L

    @Volatile
    private var lastPollNotedAt = 0L

    @Volatile
    private var lastPollResults = false

    @Volatile
    private var lastPollReason: String? = null

    fun beginSession() {
        hold = false
        lastPollStartedAt = 0L
        lastPollNotedAt = 0L
        lastPollResults = false
        lastPollReason = null
    }

    fun notePoll(startedAt: Long, results: Boolean, reason: String?) {
        lastPollStartedAt = startedAt
        lastPollNotedAt = clock()
        lastPollResults = results
        lastPollReason = reason
    }

    fun requestHold() {
        hold = true
    }

    fun releaseHold() {
        hold = false
        lastPollResults = false
    }

    fun isHeld(): Boolean = hold

    fun snapshot(): String =
        "hold=$hold results=$lastPollResults reason=${lastPollReason ?: "-"}"

    /**
     * Returns true when a result screen was involved and the wait actually
     * happened. A fresh negative sample returns false immediately.
     */
    suspend fun awaitClearance(): Boolean {
        val startedAt = clock()
        if (startedAt - lastPollStartedAt > freshEnoughMs) {
            val waitUntil = startedAt + freshSampleWaitMs
            while (clock() < waitUntil && lastPollNotedAt < startedAt) {
                delay(stepMs)
            }
        }
        if (!hold && !lastPollResults) return false
        val holdUntil = clock() + holdTimeoutMs
        while (clock() < holdUntil && (hold || lastPollResults)) {
            delay(stepMs)
        }
        return true
    }
}

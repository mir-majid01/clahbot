package com.clahbot.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassiveRecorderTest {

    private val discovery = """
        add device 1: /dev/input/event2
          name:     "test-touch"
          events:
            ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_X     : value 0, min 0, max 1079, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_Y     : value 0, min 0, max 2399, fuzz 0, flat 0, resolution 0
          input props:
            INPUT_PROP_DIRECT
    """.trimIndent()

    private val source = FakeSource(discovery)
    private val gestures = mutableListOf<BuiltGesture>()
    private val errors = mutableListOf<String>()
    private val recorder = PassiveRecorder(
        source = source,
        geometry = { ScreenGeometry(1080, 2400, 0) },
        controllerBounds = { null },
        onGesture = { gestures += it },
        onError = { errors += it }
    )

    @Test
    fun startsTheParsedDeviceAndStopsWithoutLeakingInput() {
        assertTrue(recorder.start())
        assertEquals("/dev/input/event2", source.startedPath)
        assertTrue(recorder.isRunning)

        source.emit(down(1000, 1, 300, 2300))
        source.emit(up(1200))

        val gesture = gestures.single()
        assertEquals(1000L, gesture.startUptimeMs)
        assertEquals(200L, gesture.durationMs)

        recorder.stop()
        assertFalse(recorder.isRunning)
        assertEquals(1, source.stopCalls)

        source.emit(down(2000, 2, 400, 1500))
        source.emit(up(2200))
        assertEquals(1, gestures.size)

        recorder.close()
        assertEquals(1, source.closeCalls)
    }

    @Test
    fun failsClearlyWhenDiscoveryHasNoTouchscreen() {
        val noDeviceSource = FakeSource(
            """
            add device 1: /dev/input/event0
              name: "gpio-keys"
            """.trimIndent()
        )
        val local = PassiveRecorder(
            source = noDeviceSource,
            geometry = { ScreenGeometry(1080, 2400, 0) },
            controllerBounds = { null },
            onGesture = { gestures += it },
            onError = { errors += it }
        )

        assertFalse(local.start())
        assertFalse(local.isRunning)
        assertTrue(errors.single().contains("touchscreen", ignoreCase = true))
    }

    @Test
    fun sourceStartFailureIsReported() {
        source.startResult = false

        assertFalse(recorder.start())
        assertFalse(recorder.isRunning)
        assertTrue(errors.single().contains("capture", ignoreCase = true))
        assertEquals(1, source.stopCalls)
    }

    @Test
    fun fatalSourceErrorStopsTheRecording() {
        assertTrue(recorder.start())
        source.fatal("getevent exited")

        assertFalse(recorder.isRunning)
        assertEquals("getevent exited", errors.single())

        source.emit(down(1000, 1, 300, 2300))
        source.emit(up(1200))
        assertTrue(gestures.isEmpty())
    }

    @Test
    fun multiLineBatchesFromShizukuAreParsedLineByLine() {
        assertTrue(recorder.start())

        source.emitRaw(down(1000, 1, 300, 2300) + "\n")
        source.emitRaw(up(1200) + "\n")

        val gesture = gestures.single()
        assertEquals(1000L, gesture.startUptimeMs)
        assertEquals(200L, gesture.durationMs)
        assertPoint(gesture.primary.points.first(), 300f, 2300f, 0L)
        assertPoint(gesture.primary.points.last(), 300f, 2300f, 200L)
    }

    @Test
    fun pauseDropsInputAndResumeStartsFresh() {
        assertTrue(recorder.start())
        recorder.setPaused(true)
        source.emit(down(1000, 1, 300, 2300))
        source.emit(up(1200))
        assertTrue(gestures.isEmpty())

        recorder.setPaused(false)
        source.emit(down(2000, 2, 400, 1500))
        source.emit(up(2200))
        assertEquals(1, gestures.size)
        assertEquals(2000L, gestures.single().startUptimeMs)
    }

    @Test
    fun closeWithoutStartStillClosesTheSource() {
        recorder.close()

        assertEquals(0, source.stopCalls)
        assertEquals(1, source.closeCalls)
    }

    private fun down(ts: Long, id: Int, x: Int, y: Int): String = listOf(
        abs("ABS_MT_TRACKING_ID", id.toLong()),
        abs("ABS_MT_POSITION_X", x.toLong()),
        abs("ABS_MT_POSITION_Y", y.toLong()),
        syn()
    ).joinToString("\n") { line(ts, it) }

    private fun up(ts: Long): String = listOf(
        abs("ABS_MT_TRACKING_ID", 0xffffffffL),
        syn()
    ).joinToString("\n") { line(ts, it) }

    private fun abs(code: String, value: Long): String =
        "EV_ABS       $code    ${value.toString(16).padStart(8, '0')}"

    private fun syn(): String = "EV_SYN       SYN_REPORT           00000000"

    private fun line(timestampMs: Long, body: String): String =
        "[  ${timestampMs / 1000}.${(timestampMs % 1000).toString().padStart(3, '0')}000] $body"

    private class FakeSource(private val discovery: String?) : InputEventSource {
        override val label: String = "fake"
        var startResult = true
        var startedPath: String? = null
        var stopCalls = 0
        var closeCalls = 0
        private var lines: ((String) -> Unit)? = null
        private var fatal: ((String) -> Unit)? = null

        override fun discover(): String? = discovery

        override fun start(
            devicePath: String,
            onLines: (String) -> Unit,
            onFatal: (String) -> Unit
        ): Boolean {
            startedPath = devicePath
            lines = onLines
            fatal = onFatal
            return startResult
        }

        override fun stop() {
            stopCalls++
            lines = null
            fatal = null
        }

        override fun close() {
            closeCalls++
            lines = null
            fatal = null
        }

        fun emit(lines: String) {
            lines.split('\n').forEach { this.lines?.invoke(it) }
        }

        fun emitRaw(batch: String) {
            this.lines?.invoke(batch)
        }

        fun fatal(message: String) {
            this.fatal?.invoke(message)
        }
    }

    private fun assertPoint(
        point: com.clahbot.data.models.GesturePoint,
        x: Float,
        y: Float,
        t: Long
    ) {
        assertEquals(x, point.x, 0.001f)
        assertEquals(y, point.y, 0.001f)
        assertEquals(t, point.t)
    }
}

package com.clahbot.input

/**
 * Parses Linux multitouch protocol B from `getevent -lt` text.
 *
 * Only state that changed survives until SYN_REPORT; a frame is emitted for
 * every report that carries a real contact change, including the empty frame
 * that ends a gesture.
 */
class GetEventParser(
    private val device: TouchscreenDevice,
    private val onFrame: (RawTouchFrame) -> Unit
) {

    private class SlotState(
        var trackingId: Int = -1,
        var rawX: Int? = null,
        var rawY: Int? = null
    )

    private data class Event(
        val timestampMs: Long,
        val type: Int,
        val code: Int,
        val value: Long
    )

    private val slots = sortedMapOf<Int, SlotState>()
    private var currentSlot = 0
    private var acceptSlot = true
    private var dirty = false
    private var hadContacts = false

    @Synchronized
    fun feed(line: String) {
        val event = parseLine(line) ?: return
        when (event.type) {
            TYPE_SYN -> handleSyn(event)
            TYPE_ABS -> if (acceptSlot) handleAbs(event)
        }
    }

    @Synchronized
    fun reset() {
        slots.clear()
        currentSlot = 0
        acceptSlot = true
        dirty = false
        hadContacts = false
    }

    private fun handleAbs(event: Event) {
        if (event.code == ABS_MT_SLOT) {
            currentSlot = event.value.toInt()
            acceptSlot = isSlotValid(currentSlot)
            if (acceptSlot) slots.getOrPut(currentSlot) { SlotState() }
            return
        }

        val state = slots.getOrPut(currentSlot) { SlotState() }
        when (event.code) {
            ABS_MT_TRACKING_ID -> {
                state.trackingId = if (event.value == UINT32_MAX) -1 else event.value.toInt()
                dirty = true
            }

            ABS_MT_POSITION_X -> {
                state.rawX = event.value.toInt()
                dirty = true
            }

            ABS_MT_POSITION_Y -> {
                state.rawY = event.value.toInt()
                dirty = true
            }
        }
    }

    private fun handleSyn(event: Event) {
        when (event.code) {
            SYN_DROPPED -> {
                val hadActiveContacts = hadContacts
                slots.clear()
                currentSlot = 0
                acceptSlot = true
                dirty = false
                hadContacts = false
                if (hadActiveContacts) {
                    onFrame(RawTouchFrame(event.timestampMs, emptyList()))
                }
            }

            SYN_REPORT -> {
                if (!dirty) return
                dirty = false
                val contacts = slots.mapNotNull { (slot, state) ->
                    val id = state.trackingId
                    val x = state.rawX
                    val y = state.rawY
                    if (id >= 0 && x != null && y != null) {
                        RawTouchContact(slot, id, x, y)
                    } else {
                        null
                    }
                }
                if (contacts.isNotEmpty() || hadContacts) {
                    onFrame(RawTouchFrame(event.timestampMs, contacts))
                }
                hadContacts = contacts.isNotEmpty()
                slots.entries.removeAll { it.value.trackingId < 0 }
            }
        }
    }

    private fun isSlotValid(slot: Int): Boolean {
        if (slot < 0) return false
        val max = if (device.maxSlots > 0) device.maxSlots else MAX_UNKNOWN_SLOTS
        return slot <= max
    }

    private fun parseLine(line: String): Event? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("[")) return null
        val close = trimmed.indexOf(']')
        if (close <= 1) return null
        val timestampMs = parseTimestamp(trimmed.substring(1, close)) ?: return null

        val tokens = trimmed.substring(close + 1).trim().split(WHITESPACE)
        var index = 0
        if (tokens.isNotEmpty() && tokens[0].startsWith("/dev/")) index++
        if (tokens.size < index + 3) return null

        val type = when (tokens[index]) {
            "EV_SYN" -> TYPE_SYN
            "EV_KEY" -> TYPE_KEY
            "EV_ABS" -> TYPE_ABS
            else -> tokens[index].toIntOrNull(16) ?: return null
        }
        val code = when (type) {
            TYPE_ABS -> when (tokens[index + 1]) {
                "ABS_MT_SLOT" -> ABS_MT_SLOT
                "ABS_MT_POSITION_X" -> ABS_MT_POSITION_X
                "ABS_MT_POSITION_Y" -> ABS_MT_POSITION_Y
                "ABS_MT_TRACKING_ID" -> ABS_MT_TRACKING_ID
                else -> tokens[index + 1].toIntOrNull(16) ?: return null
            }

            TYPE_SYN -> when (tokens[index + 1]) {
                "SYN_REPORT" -> SYN_REPORT
                "SYN_DROPPED" -> SYN_DROPPED
                else -> tokens[index + 1].toIntOrNull(16) ?: return null
            }

            else -> tokens[index + 1].toIntOrNull(16) ?: return null
        }
        val value = tokens[index + 2].toLongOrNull(16) ?: return null
        return Event(timestampMs, type, code, value)
    }

    private fun parseTimestamp(text: String): Long? {
        val dot = text.indexOf('.')
        val secondsPart = if (dot >= 0) text.substring(0, dot) else text
        val seconds = secondsPart.trim().toLongOrNull() ?: return null
        if (dot < 0) return seconds * 1000L
        val fraction = text.substring(dot + 1).trim()
        if (fraction.isEmpty()) return seconds * 1000L
        val millis = fraction.padEnd(6, '0').take(3).toLongOrNull() ?: return null
        return seconds * 1000L + millis
    }

    companion object {
        private const val TYPE_SYN = 0
        private const val TYPE_KEY = 1
        private const val TYPE_ABS = 3

        private const val SYN_REPORT = 0
        private const val SYN_DROPPED = 3

        private const val ABS_MT_SLOT = 0x2f
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39

        private const val UINT32_MAX = 0xffffffffL
        private const val MAX_UNKNOWN_SLOTS = 31

        private val WHITESPACE = Regex("""\s+""")
    }
}

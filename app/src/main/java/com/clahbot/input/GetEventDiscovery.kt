package com.clahbot.input

object GetEventDiscovery {

    private val deviceHeader = Regex("""^\s*add device \d+:\s*(/\S+)\s*$""")
    private val deviceName = Regex("""^\s*name:\s*(?:"([^"]*)"|(\S.*?))\s*$""")
    private const val ABS_PREFIX = """^\s*(?:ABS \(0003\):\s*)?"""
    private val positionX = Regex(
        ABS_PREFIX + """(?:ABS_MT_POSITION_X|0035)\s*:.*\bmax\s+(\d+)"""
    )
    private val positionY = Regex(
        ABS_PREFIX + """(?:ABS_MT_POSITION_Y|0036)\s*:.*\bmax\s+(\d+)"""
    )
    private val slotCount = Regex(
        ABS_PREFIX + """(?:ABS_MT_SLOT|002f)\s*:.*\bmax\s+(\d+)"""
    )
    private val directProp = Regex("""^\s*INPUT_PROP_DIRECT\s*$""")

    fun parse(output: String): List<TouchscreenDevice> {
        val devices = mutableListOf<TouchscreenDevice>()
        var path: String? = null
        var name: String? = null
        var maxX: Int? = null
        var maxY: Int? = null
        var maxSlots = 0
        var direct = false

        fun flush() {
            val devicePath = path
            val x = maxX
            val y = maxY
            if (devicePath != null && x != null && y != null && x > 0 && y > 0) {
                devices += TouchscreenDevice(
                    path = devicePath,
                    name = name.orEmpty(),
                    maxX = x,
                    maxY = y,
                    maxSlots = maxSlots,
                    direct = direct
                )
            }
            path = null
            name = null
            maxX = null
            maxY = null
            maxSlots = 0
            direct = false
        }

        for (rawLine in output.lineSequence()) {
            val header = deviceHeader.find(rawLine)
            if (header != null) {
                flush()
                path = header.groupValues[1]
                continue
            }
            if (path == null) continue

            val nameMatch = deviceName.find(rawLine)
            if (nameMatch != null) {
                name = nameMatch.groupValues[1].ifBlank {
                    nameMatch.groupValues[2].trim()
                }
                continue
            }
            val xMatch = positionX.find(rawLine)
            if (xMatch != null) {
                maxX = xMatch.groupValues[1].toIntOrNull()
                continue
            }
            val yMatch = positionY.find(rawLine)
            if (yMatch != null) {
                maxY = yMatch.groupValues[1].toIntOrNull()
                continue
            }
            val slotMatch = slotCount.find(rawLine)
            if (slotMatch != null) {
                maxSlots = slotMatch.groupValues[1].toIntOrNull() ?: 0
                continue
            }
            if (directProp.matches(rawLine)) direct = true
        }
        flush()
        return devices
    }

    fun pickTouchscreen(output: String): TouchscreenDevice? {
        return parse(output)
            .sortedWith(
                compareByDescending<TouchscreenDevice> { it.direct }
                    .thenByDescending { it.maxSlots > 0 }
                    .thenByDescending { it.maxX.toLong() * it.maxY.toLong() }
            )
            .firstOrNull()
    }
}

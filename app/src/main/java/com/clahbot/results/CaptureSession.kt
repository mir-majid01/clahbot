package com.clahbot.results

data class CaptureSession(
    val id: Long,
    val startedAt: Long,
    val macroId: Long,
    val macroName: String,
    val speed: Float,
    val repeat: String
)

/**
 * A results screen is evidence of an attack. A playback ending is not, and a
 * results screen that was already open before the playback started is stale
 * evidence and must not create a record.
 */
object ResultsCapturePolicy {
    fun mayAutoRecord(
        detectedResults: Boolean,
        startedOnResults: Boolean,
        manual: Boolean = false
    ): Boolean = detectedResults && (!startedOnResults || manual)

    fun mayRecordOpenResult(manual: Boolean, alreadyRecorded: Boolean): Boolean =
        manual && !alreadyRecorded
}

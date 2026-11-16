package com.clahbot.walls

import com.clahbot.game.GameState

/**
 * Phase 5C — builder evidence tied to ONE genuine captured frame.
 *
 * [BuilderEvidence.Validated] can ONLY be constructed by the live capture
 * pipeline (AutoWallsDevice.observe() → AutoWallsRuntime). Unit tests exercise
 * [BuilderDetection] directly but cannot manufacture [Validated] evidence for
 * production dispatch paths.
 *
 * There is no public constructor on [Validated]; production code receives it
 * from the capture seam. Synthetic tests cannot impersonate it.
 */
sealed class BuilderEvidence private constructor() {
    abstract val reading: BuilderDetection.Reading

    /** Always false on the base type; only [Validated] can return true. */
    open fun authorizesUpgrade(): Boolean = false

    /** No frame — cannot authorize. Always fail-closed. */
    object Unvalidated : BuilderEvidence() {
        override val reading: BuilderDetection.Reading
            get() = BuilderDetection.Reading(
                status = BuilderDetection.Status.UNCERTAIN,
                confidence = 0f
            )
    }

    /**
     * Genuine captured frame + provenance. Constructed exclusively by the
     * capture pipeline. The private constructor prevents arbitrary code from
     * fabricating validated evidence via .copy().
     */
    data class Validated private constructor(
        override val reading: BuilderDetection.Reading,
        val provenance: BuilderProvenance
    ) : BuilderEvidence() {

        /**
         * Fail-closed: authorization requires ALL of:
         *  - detection says builders are available (reading.isAvailable)
         *  - the real-frame validation gate is enabled
         *  - the screen is HOME (not a popup, unknown, or other app)
         *  - the evidence is fresh (not stale)
         */
        override fun authorizesUpgrade(): Boolean {
            if (!BuilderValidationGate.realFrameValidationEnabled) return false
            if (!reading.isAvailable) return false
            if (provenance.screenState != GameState.HOME) return false
            return isFresh(provenance.captureTimestampMillis)
        }

        private fun isFresh(captureAt: Long): Boolean {
            val now = System.currentTimeMillis()
            return now - captureAt <= BuilderValidationGate.BUILDER_EVIDENCE_MAX_AGE_MS
        }

        companion object {
            /**
             * Called only by AutoWallsRuntime from a live WallFrame (produced by the capture pipeline).
             * Not accessible to test code or arbitrary callers outside this module.
             */
            internal fun fromFrame(
                reading: BuilderDetection.Reading,
                wallFrame: com.clahbot.walls.WallFrame,
                screenState: GameState,
                source: CaptureSource = CaptureSource.WINDOW_SCREENSHOT
            ): Validated {
                return Validated(
                    reading = reading,
                    provenance = BuilderProvenance(
                        frameHash = wallFrame.observation.hash,
                        captureTimestampMillis = wallFrame.capturedAtMillis,
                        screenState = screenState,
                        ocrLineCount = wallFrame.lines.size,
                        source = source,
                        deviceSerial = null
                    )
                )
            }
        }
    }

    companion object {
        /** Build unvalidated evidence (fail-closed). */
        fun unvalidated(): BuilderEvidence = Unvalidated
    }
}

/** Provenance of a single captured frame. */
data class BuilderProvenance(
    /** ScreenHash of the bitmap — empty if not yet computed by the pipeline. */
    val frameHash: String,
    /** SystemClock.elapsedRealtime() when the frame was captured. */
    val captureTimestampMillis: Long,
    /** GameScreenEngine classification result. */
    val screenState: GameState,
    /** Number of OCR lines on the frame. */
    val ocrLineCount: Int,
    /** How the frame was captured. */
    val source: CaptureSource,
    /** ADB device serial, for audit. Null when not available. */
    val deviceSerial: String?
)

enum class CaptureSource { WINDOW_SCREENSHOT, DISPLAY_MASKED }

/**
 * Gate controlling whether real-world builder authorization is allowed.
 * Defaults to OFF. Flipped ONLY after genuine Clash of Clans screenshots have
 * been manually labelled and validated (zero false authorizations required).
 * Synthetic test results never justify flipping this.
 */
object BuilderValidationGate {
    @Volatile
    var realFrameValidationEnabled: Boolean = false

    const val BUILDER_EVIDENCE_MAX_AGE_MS = 2_000L
}

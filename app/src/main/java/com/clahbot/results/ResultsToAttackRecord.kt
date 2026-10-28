package com.clahbot.results

import com.clahbot.data.models.AttackRecord
import com.clahbot.ocr.ResultsRead
import java.util.Locale

/** Maps a detected result-screen read to the one confirmed attack record. */
object ResultsToAttackRecord {

    fun build(
        session: CaptureSession,
        read: ResultsRead,
        playbackEndedAt: Long,
        interrupted: Boolean,
        screenshotPath: String?,
        sidecarPath: String?,
        recordId: Long = session.id,
        recordStartedAt: Long = session.startedAt,
        captureSource: String = AttackRecord.SOURCE_AUTO,
        screenHash: String? = null
    ): AttackRecord {
        val anyLoot = listOf(
            read.gold,
            read.elixir,
            read.darkElixir,
            read.bonusGold,
            read.bonusElixir,
            read.bonusDarkElixir
        ).any { it != null }
        val ended = playbackEndedAt.coerceAtLeast(recordStartedAt)
        val note = buildString {
            if (anyLoot) {
                append("Read automatically from the result screen.")
            } else {
                append("Results screen captured; loot not captured.")
            }
            if (interrupted) append(" Playback was stopped before the macro ended.")
            if (anyLoot && !read.lootComplete) append(" Some loot fields were not captured.")
        }
        return AttackRecord(
            id = recordId,
            startedAt = recordStartedAt,
            endedAt = ended,
            durationMs = ended - recordStartedAt,
            macroId = session.macroId,
            macroName = session.macroName,
            speedMultiplier = session.speed,
            repeatMode = session.repeat,
            gold = read.gold,
            elixir = read.elixir,
            darkElixir = read.darkElixir,
            bonusGold = read.bonusGold,
            bonusElixir = read.bonusElixir,
            bonusDarkElixir = read.bonusDarkElixir,
            lootSource = if (anyLoot) AttackRecord.LOOT_OCR else AttackRecord.LOOT_NONE,
            captureSource = captureSource,
            screenHash = screenHash,
            result = AttackRecord.RESULT_COMPLETED,
            outcome = read.outcome.name.lowercase(Locale.US),
            resultsScreenshot = screenshotPath,
            resultsSidecar = sidecarPath,
            verified = anyLoot,
            complete = read.lootComplete,
            confirmed = true,
            note = note
        )
    }

    fun buildUnreadable(
        session: CaptureSession,
        playbackEndedAt: Long,
        interrupted: Boolean,
        screenshotPath: String?,
        sidecarPath: String?,
        recordId: Long = session.id,
        recordStartedAt: Long = session.startedAt,
        screenHash: String? = null
    ): AttackRecord {
        val ended = playbackEndedAt.coerceAtLeast(recordStartedAt)
        val note = buildString {
            append("Results screen captured; loot not captured.")
            if (interrupted) append(" Playback was stopped before the macro ended.")
        }
        return AttackRecord(
            id = recordId,
            startedAt = recordStartedAt,
            endedAt = ended,
            durationMs = ended - recordStartedAt,
            macroId = session.macroId,
            macroName = session.macroName,
            speedMultiplier = session.speed,
            repeatMode = session.repeat,
            lootSource = AttackRecord.LOOT_NONE,
            captureSource = AttackRecord.SOURCE_AUTO,
            screenHash = screenHash,
            result = AttackRecord.RESULT_COMPLETED,
            outcome = null,
            resultsScreenshot = screenshotPath,
            resultsSidecar = sidecarPath,
            verified = false,
            complete = false,
            confirmed = true,
            note = note
        )
    }
}

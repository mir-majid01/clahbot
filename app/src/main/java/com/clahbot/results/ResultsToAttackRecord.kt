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
        screenHash: String? = null,
        reviewReasons: List<String> = emptyList()
    ): AttackRecord {
        val sanity = LootSanity.sanitize(read)
        val clean = sanity.read
        val anyLoot = listOf(
            clean.gold,
            clean.elixir,
            clean.darkElixir,
            clean.bonusGold,
            clean.bonusElixir,
            clean.bonusDarkElixir
        ).any { it != null }
        val bonusAny = clean.bonusGold != null ||
            clean.bonusElixir != null ||
            clean.bonusDarkElixir != null
        val bonusState = when {
            clean.bonusPanel && bonusAny -> AttackRecord.BONUS_CAPTURED
            clean.bonusPanel -> AttackRecord.BONUS_NOT_CAPTURED
            else -> AttackRecord.BONUS_NONE
        }
        val reasons = (reviewReasons + sanity.reasons).toMutableList()
        if (bonusState == AttackRecord.BONUS_NOT_CAPTURED) {
            reasons += "Bonus panel present but not captured"
        }
        if (!clean.lootComplete) {
            reasons += "Some main loot fields were not captured"
        }
        val lootIssues = read.issues.filterNot {
            it.contains("balance row", ignoreCase = true) ||
                it.startsWith("balance", ignoreCase = true)
        }
        if (read.needsReview && lootIssues.isNotEmpty()) {
            reasons += lootIssues.distinct().take(3)
        }
        val reviewReason = reasons
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("; ")
            .takeIf { it.isNotBlank() }
        val ended = playbackEndedAt.coerceAtLeast(recordStartedAt)
        val note = buildString {
            if (anyLoot) {
                append("Read automatically from the result screen.")
            } else {
                append("Results screen captured; loot not captured.")
            }
            if (interrupted) append(" Playback was stopped before the macro ended.")
            if (reviewReason != null) append(" Needs review: $reviewReason.")
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
            gold = clean.gold,
            elixir = clean.elixir,
            darkElixir = clean.darkElixir,
            bonusGold = clean.bonusGold,
            bonusElixir = clean.bonusElixir,
            bonusDarkElixir = clean.bonusDarkElixir,
            lootSource = if (anyLoot) AttackRecord.LOOT_OCR else AttackRecord.LOOT_NONE,
            captureSource = captureSource,
            screenHash = screenHash,
            result = AttackRecord.RESULT_COMPLETED,
            outcome = clean.outcome.name.lowercase(Locale.US),
            resultsScreenshot = screenshotPath,
            resultsSidecar = sidecarPath,
            verified = anyLoot && reviewReason == null,
            complete = clean.lootComplete,
            reviewReason = reviewReason,
            bonusState = bonusState,
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
            reviewReason = "Results screen captured; loot not captured",
            bonusState = null,
            confirmed = true,
            note = note
        )
    }
}

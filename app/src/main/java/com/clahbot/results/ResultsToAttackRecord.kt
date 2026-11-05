package com.clahbot.results

import com.clahbot.data.models.AttackRecord
import com.clahbot.ocr.ResultsRead
import java.util.Locale

/** Maps detected result-screen evidence to the one confirmed attack record. */
object ResultsToAttackRecord {

    /**
     * One record from one finished episode. Review is added only for a real
     * problem: missing/unreadable loot, a bonus panel that never settled, or a
     * screen that closed before it could be confirmed.
     */
    fun fromEpisode(
        session: CaptureSession,
        episode: EpisodeResult,
        playbackEndedAt: Long,
        interrupted: Boolean,
        screenshotPath: String?,
        sidecarPath: String?,
        recordId: Long = session.id,
        recordStartedAt: Long = session.startedAt,
        captureSource: String = AttackRecord.SOURCE_AUTO,
        screenHash: String? = null
    ): AttackRecord {
        val reviewReason = episode.reasons
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("; ")
            .takeIf { it.isNotBlank() }
        val ended = playbackEndedAt.coerceAtLeast(recordStartedAt)
        val anyLoot = episode.gold != null ||
            episode.elixir != null ||
            episode.darkElixir != null
        val note = buildString {
            if (anyLoot) {
                append("Read automatically from the result screen.")
            } else {
                append("Results screen captured; loot not captured.")
            }
            if (episode.closedEarly) append(" The screen closed early; confirmed values were kept.")
            if (episode.partialReasons.isNotEmpty()) {
                append(" ${episode.partialReasons.joinToString("; ")} — gold and elixir still count.")
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
            gold = episode.gold,
            elixir = episode.elixir,
            darkElixir = episode.darkElixir,
            bonusGold = episode.bonusGold,
            bonusElixir = episode.bonusElixir,
            bonusDarkElixir = episode.bonusDarkElixir,
            lootSource = if (anyLoot) AttackRecord.LOOT_OCR else AttackRecord.LOOT_NONE,
            captureSource = captureSource,
            screenHash = screenHash,
            result = AttackRecord.RESULT_COMPLETED,
            outcome = episode.outcome?.name?.lowercase(Locale.US),
            resultsScreenshot = screenshotPath,
            resultsSidecar = sidecarPath,
            verified = episode.hasMain && reviewReason == null,
            complete = episode.hasMain,
            reviewReason = reviewReason,
            bonusState = episode.bonusState,
            confirmed = true,
            note = note
        )
    }

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
        val missing = listOf(
            "Gold" to clean.gold,
            "Elixir" to clean.elixir,
            "Dark elixir" to clean.darkElixir
        ).filter { it.second == null }.map { "${it.first} unreadable" }
        val reasons = (reviewReasons + sanity.reasons + missing).toMutableList()
        val bonusAny = clean.bonusGold != null ||
            clean.bonusElixir != null ||
            clean.bonusDarkElixir != null
        val bonusState = when {
            clean.bonusPanel && bonusAny -> AttackRecord.BONUS_CAPTURED
            clean.bonusPanel -> {
                reasons += "Bonus missing"
                AttackRecord.BONUS_NOT_CAPTURED
            }

            else -> AttackRecord.BONUS_NONE
        }
        val episode = EpisodeResult(
            read = clean,
            outcome = clean.outcome,
            gold = clean.gold,
            elixir = clean.elixir,
            darkElixir = clean.darkElixir,
            bonusGold = clean.bonusGold,
            bonusElixir = clean.bonusElixir,
            bonusDarkElixir = clean.bonusDarkElixir,
            bonusState = bonusState,
            mainStable = clean.lootComplete,
            bonusResolved = bonusState != AttackRecord.BONUS_NOT_CAPTURED,
            closedEarly = false,
            reasons = reasons,
            elapsedMs = 0L
        )
        return fromEpisode(
            session = session,
            episode = episode,
            playbackEndedAt = playbackEndedAt,
            interrupted = interrupted,
            screenshotPath = screenshotPath,
            sidecarPath = sidecarPath,
            recordId = recordId,
            recordStartedAt = recordStartedAt,
            captureSource = captureSource,
            screenHash = screenHash
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
        screenHash: String? = null,
        reason: String = "Loot unreadable; Closed early"
    ): AttackRecord {
        val ended = playbackEndedAt.coerceAtLeast(recordStartedAt)
        val note = buildString {
            append("Results screen captured; loot not captured.")
            if (interrupted) append(" Playback was stopped before the macro ended.")
            append(" Needs review: $reason.")
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
            reviewReason = reason,
            bonusState = null,
            confirmed = true,
            note = note
        )
    }
}

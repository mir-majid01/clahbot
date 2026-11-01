package com.clahbot.results

import com.clahbot.data.models.AttackRecord
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.ResultsOutcome
import com.clahbot.ocr.ResultsRead
import kotlin.math.abs

/**
 * One analysed frame of a single result-screen episode. [read] is null when the
 * frame had no anchors or the row readers returned nothing. [bonusRowBoxes]
 * lets the policy wait for the bonus panel to stop moving before its values
 * are trusted.
 */
data class EpisodeFrame(
    val atMs: Long,
    val resultsScreen: Boolean,
    val read: ResultsRead?,
    val bonusRowBoxes: List<OcrBox> = emptyList(),
    val bonusRowCount: Int = bonusRowBoxes.size
)

/** Final, already-reconciled evidence for one result screen. */
data class EpisodeResult(
    val read: ResultsRead?,
    val outcome: ResultsOutcome?,
    val gold: Long?,
    val elixir: Long?,
    val darkElixir: Long?,
    val bonusGold: Long?,
    val bonusElixir: Long?,
    val bonusDarkElixir: Long?,
    val bonusState: String?,
    val mainStable: Boolean,
    val bonusResolved: Boolean,
    val closedEarly: Boolean,
    val reasons: List<String>,
    val elapsedMs: Long,
    val mainStableAtMs: Long? = null,
    val bonusSettledAtMs: Long? = null
) {
    val hasMain: Boolean get() = gold != null && elixir != null && darkElixir != null
    val hasBonus: Boolean
        get() = bonusGold != null || bonusElixir != null || bonusDarkElixir != null
    val needsReview: Boolean get() = reasons.isNotEmpty()
}

sealed interface EpisodeDecision {
    data object Continue : EpisodeDecision
    data class Finish(val result: EpisodeResult) : EpisodeDecision
}

/**
 * Decides when a detected results screen has been read well enough to write its
 * one record.
 *
 * Main loot is accepted after two consecutive frames agree on outcome and all
 * three values. The bonus panel animates, so a bonus value (all three together)
 * is accepted only after three consecutive equal reads while the panel's row
 * boxes have stopped moving. A panel that never appears within the wait window
 * is recorded as absent. A screen that disappears ends the episode; anything
 * that was confirmed is kept and the record is marked "closed early".
 */
class EpisodePolicy(
    private val startMs: Long,
    private val bonusWaitMs: Long = 4_000L,
    private val hardLimitMs: Long = 12_000L,
    private val closeSilenceMs: Long = 1_500L,
    private val panelMoveTolerancePx: Int = 4
) {

    private var bestRead: ResultsRead? = null
    private var candidateMain: ResultsRead? = null
    private var stableMain: ResultsRead? = null
    private var stableAtMs: Long? = null

    private var panelSeen = false
    private var bonusCandidate: LongArray? = null
    private var bonusEqualFrames = 0
    private var bonusCaptured: LongArray? = null
    private var bonusCapturedAtMs: Long? = null
    private var bonusUnreadable = false
    private var previousBonusBoxes: List<OcrBox> = emptyList()
    private var emptyPanelFrames = 0
    private var firstEmptyPanelAt: Long? = null
    private var emptyPanelResolved = false

    private var lastResultsAtMs = startMs
    private var lastFrameAtMs = startMs

    fun onFrame(frame: EpisodeFrame): EpisodeDecision {
        lastFrameAtMs = frame.atMs
        if (!frame.resultsScreen) return missing(frame.atMs)
        lastResultsAtMs = frame.atMs
        if (frame.read == null) {
            if (frame.atMs - startMs >= hardLimitMs) {
                return EpisodeDecision.Finish(
                    build(closedEarly = false, atMs = frame.atMs)
                )
            }
            return EpisodeDecision.Continue
        }

        val sanitized = LootSanity.sanitize(frame.read)
        val read = sanitized.read
        if (score(read) >= score(bestRead)) bestRead = read

        acceptMain(read, frame.atMs)
        acceptBonus(read, frame, rawBonusAny = frame.read.bonusGold != null ||
            frame.read.bonusElixir != null ||
            frame.read.bonusDarkElixir != null)

        resolveStop(frame.atMs)?.let { return EpisodeDecision.Finish(it) }
        if (frame.atMs - startMs >= hardLimitMs) {
            return EpisodeDecision.Finish(build(closedEarly = false, atMs = frame.atMs))
        }
        return EpisodeDecision.Continue
    }

    /** A poll that did not see the results screen. */
    fun onMissing(atMs: Long): EpisodeDecision {
        lastFrameAtMs = atMs
        if (atMs - lastResultsAtMs >= closeSilenceMs) {
            return EpisodeDecision.Finish(build(closedEarly = true, atMs = atMs))
        }
        return EpisodeDecision.Continue
    }

    fun finishNow(atMs: Long): EpisodeResult =
        build(closedEarly = false, atMs = atMs)

    fun lastSeenAt(): Long = lastResultsAtMs

    private fun acceptMain(read: ResultsRead, atMs: Long) {
        if (!read.lootComplete) return
        val candidate = candidateMain
        if (candidate != null && sameMain(candidate, read)) {
            if (stableMain == null) {
                stableMain = read
                stableAtMs = atMs
            }
        } else {
            candidateMain = read
            stableMain = null
            stableAtMs = null
        }
    }

    private fun acceptBonus(read: ResultsRead, frame: EpisodeFrame, rawBonusAny: Boolean) {
        if (!read.bonusPanel) {
            previousBonusBoxes = emptyList()
            if (bonusCaptured == null) {
                bonusCandidate = null
                bonusEqualFrames = 0
            }
            return
        }
        panelSeen = true
        val gold = read.bonusGold
        val elixir = read.bonusElixir
        val dark = read.bonusDarkElixir
        val triple = if (gold != null && elixir != null && dark != null) {
            longArrayOf(gold, elixir, dark)
        } else {
            null
        }
        if (triple == null) {
            if (bonusCaptured == null) {
                bonusCandidate = null
                bonusEqualFrames = 0
            }
            if (rawBonusAny) bonusUnreadable = true
            val empty = frame.bonusRowCount == 0
            if (empty) {
                if (emptyPanelFrames == 0) firstEmptyPanelAt = frame.atMs
                emptyPanelFrames++
                if (emptyPanelFrames >= 5 &&
                    frame.atMs - (firstEmptyPanelAt ?: frame.atMs) >= EMPTY_PANEL_MS
                ) {
                    emptyPanelResolved = true
                }
            } else {
                emptyPanelFrames = 0
                firstEmptyPanelAt = null
            }
            previousBonusBoxes = frame.bonusRowBoxes
            return
        }

        val previous = bonusCandidate
        if (previous != null && previous.contentEquals(triple)) {
            bonusEqualFrames++
        } else {
            bonusCandidate = triple
            bonusEqualFrames = 1
        }
        val moved = boxesMoved(previousBonusBoxes, frame.bonusRowBoxes)
        if (bonusEqualFrames >= BONUS_STABLE_FRAMES && !moved) {
            if (bonusCaptured == null) bonusCapturedAtMs = frame.atMs
            bonusCaptured = triple
        }
        previousBonusBoxes = frame.bonusRowBoxes
        emptyPanelFrames = 0
        firstEmptyPanelAt = null
    }

    private fun resolveStop(atMs: Long): EpisodeResult? {
        stableMain ?: return null
        if (bonusCaptured != null || emptyPanelResolved) {
            return build(closedEarly = false, atMs = atMs)
        }
        if (!panelSeen) {
            if (stableMain?.outcome == ResultsOutcome.DEFEAT) {
                return build(closedEarly = false, atMs = atMs)
            }
            val stableAt = stableAtMs ?: return null
            if (atMs - stableAt >= bonusWaitMs) {
                return build(closedEarly = false, atMs = atMs)
            }
            return null
        }
        // The panel was seen but never produced a settled value.
        return null
    }

    private fun missing(atMs: Long): EpisodeDecision = onMissing(atMs)

    private fun build(closedEarly: Boolean, atMs: Long): EpisodeResult {
        val main = stableMain ?: candidateMain ?: bestRead
        val reasons = mutableListOf<String>()
        if (stableMain == null && main?.lootComplete == true) {
            reasons += "Loot not settled"
        }

        val gold = main?.gold
        val elixir = main?.elixir
        val dark = main?.darkElixir
        val missingMain = listOf(
            "Gold" to gold,
            "Elixir" to elixir,
            "Dark elixir" to dark
        ).filter { it.second == null }.map { "${it.first} unreadable" }
        if (missingMain.size == 3) {
            reasons += "Loot unreadable"
        } else {
            reasons += missingMain
        }

        val captured = bonusCaptured
        val bonusState: String?
        val bonusGold: Long?
        val bonusElixir: Long?
        val bonusDark: Long?
        var noPanelWaitElapsed = false
        when {
            captured != null -> {
                bonusState = AttackRecord.BONUS_CAPTURED
                bonusGold = captured[0]
                bonusElixir = captured[1]
                bonusDark = captured[2]
            }

            emptyPanelResolved -> {
                bonusState = AttackRecord.BONUS_NONE
                bonusGold = null
                bonusElixir = null
                bonusDark = null
            }

            !panelSeen -> {
                bonusState = AttackRecord.BONUS_NONE
                bonusGold = null
                bonusElixir = null
                bonusDark = null
                val stableAt = stableAtMs
                noPanelWaitElapsed = main?.outcome == ResultsOutcome.DEFEAT ||
                    (stableAt != null && atMs - stableAt >= bonusWaitMs)
            }

            else -> {
                bonusState = AttackRecord.BONUS_NOT_CAPTURED
                bonusGold = null
                bonusElixir = null
                bonusDark = null
                reasons += if (bonusUnreadable) "Bonus unreadable" else "Bonus missing"
            }
        }

        val complete = gold != null && elixir != null && dark != null
        val bonusUnresolved = bonusState == AttackRecord.BONUS_NOT_CAPTURED ||
            (!panelSeen && !noPanelWaitElapsed)
        if (closedEarly && (!complete || bonusUnresolved)) {
            reasons += "Closed early"
        }

        // Only the final chosen values are sanity-checked. A bad frame that was
        // never accepted must not leave a review reason behind.
        val chosen = ResultsRead(
            outcome = main?.outcome ?: ResultsOutcome.VICTORY,
            stars = null,
            gold = gold,
            elixir = elixir,
            darkElixir = dark,
            bonusGold = bonusGold,
            bonusElixir = bonusElixir,
            bonusDarkElixir = bonusDark,
            balanceGold = null,
            balanceElixir = null,
            balanceDarkElixir = null,
            confidence = main?.confidence ?: 0.0,
            needsReview = false,
            issues = emptyList(),
            bonusPanel = panelSeen || captured != null
        )
        val sanity = LootSanity.sanitize(chosen)
        reasons += sanity.reasons

        val distinct = reasons.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val resolved = complete &&
            bonusState != AttackRecord.BONUS_NOT_CAPTURED &&
            !bonusUnresolved &&
            sanity.reasons.isEmpty()
        return EpisodeResult(
            read = if (main != null || captured != null) chosen else null,
            outcome = main?.outcome,
            gold = gold,
            elixir = elixir,
            darkElixir = dark,
            bonusGold = bonusGold,
            bonusElixir = bonusElixir,
            bonusDarkElixir = bonusDark,
            bonusState = bonusState,
            mainStable = stableMain != null,
            bonusResolved = resolved,
            closedEarly = closedEarly,
            reasons = if (resolved && closedEarly) distinct - "Closed early" else distinct,
            elapsedMs = (atMs - startMs).coerceAtLeast(0L),
            mainStableAtMs = stableAtMs,
            bonusSettledAtMs = bonusCapturedAtMs
        )
    }

    private fun sameMain(a: ResultsRead, b: ResultsRead): Boolean =
        a.outcome == b.outcome &&
            a.gold == b.gold &&
            a.elixir == b.elixir &&
            a.darkElixir == b.darkElixir

    private fun boxesMoved(before: List<OcrBox>, after: List<OcrBox>): Boolean {
        if (before.isEmpty() || before.size != after.size) return true
        return before.indices.any { index ->
            abs(before[index].centerY - after[index].centerY) > panelMoveTolerancePx ||
                abs(before[index].centerX - after[index].centerX) > panelMoveTolerancePx
        }
    }

    private fun score(read: ResultsRead?): Int {
        if (read == null) return 0
        var value = 1
        if (read.lootComplete) value += 4
        if (read.bonusGold != null && read.bonusElixir != null && read.bonusDarkElixir != null) {
            value += 2
        }
        return value
    }

    private companion object {
        const val BONUS_STABLE_FRAMES = 3
        const val EMPTY_PANEL_MS = 3_000L
    }
}

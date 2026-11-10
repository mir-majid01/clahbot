package com.clahbot.recovery

import com.clahbot.game.GameState
import com.clahbot.game.PopupKind
import com.clahbot.game.ScreenBox
import com.clahbot.game.ScreenObservation

enum class RecoveryActionType { TAP_TEXT, TAP_CLOSE_X, TAP_POINT, PRESS_BACK, WAIT, GO_HOME }

/** Which automation runs a rule is allowed to act during. */
enum class RuleScope { ATTACK, WALL, BOTH }

/**
 * One data-driven recovery rule. Built-in rules are code constants; rules the
 * user taught (Unknown screens -> Teach) live in recovery_rules.json and are
 * matched by screenshot hash.
 */
data class RecoveryRule(
    val id: String,
    val name: String,
    val action: RecoveryActionType,
    /** Normalized text any-of match inside the dialog region. */
    val matchText: List<String> = emptyList(),
    /** Matches the engine's popup kind when set. */
    val matchPopupKind: PopupKind? = null,
    /** TAP_TEXT: the caption to press. */
    val targetText: String? = null,
    /** Taught rules match a stored screen hash within this distance. */
    val matchHash: String? = null,
    /** Taught tap point in frame fractions. */
    val tapX: Float? = null,
    val tapY: Float? = null,
    /**
     * The screen state this rule is expected on. `null` keeps the legacy
     * popup-only behaviour; a non-null value lets a rule match ordinary
     * screens such as SEARCHING, not just popups.
     */
    val expectedState: GameState? = null,
    /**
     * Attack macros, wall macros, or both. `null` means BOTH, so a rule written
     * before this field existed keeps applying everywhere.
     */
    val scope: RuleScope? = null,
    /**
     * `null` means enabled. Gson does not apply Kotlin defaults to a field a
     * stored file omits, so an older rule must not read as disabled just
     * because the field is absent.
     */
    val enabled: Boolean? = null,
    /** WAIT: keep waiting until the screen changes rather than acting once. */
    val waitForTransition: Boolean = false,
    /** Plain-language description of what the screen represents. */
    val purpose: String? = null,
    /** Last time this rule matched, epoch ms; null until it first matches. */
    val lastMatchedAt: Long? = null,
    /** How many times this rule has matched. */
    val matchCount: Int = 0
) {
    /** Scope with the legacy default applied. */
    val appliesTo: RuleScope get() = scope ?: RuleScope.BOTH

    /** Enabled with the legacy default applied. */
    val isEnabled: Boolean get() = enabled ?: true
    fun matches(
        observation: ScreenObservation,
        runScope: RuleScope = RuleScope.BOTH,
        hashDistance: (String) -> Int?
    ): Boolean {
        if (!isEnabled) return false
        val ruleScope = appliesTo
        if (ruleScope != RuleScope.BOTH && runScope != RuleScope.BOTH && ruleScope != runScope) {
            return false
        }
        val expected = expectedState
        if (expected != null) {
            if (observation.state != expected) return false
        } else if (observation.state != GameState.POPUP) {
            return false
        }
        if (matchHash != null) {
            val distance = hashDistance(matchHash) ?: return false
            return distance <= TAUGHT_HASH_MAX_DISTANCE
        }
        if (matchPopupKind != null && observation.popupKind != matchPopupKind) return false
        if (matchText.isNotEmpty()) {
            val dialog = observation.lines.filter {
                it.left in 0.12f..0.88f && it.top in 0.12f..0.82f
            }
            val hit = dialog.any { line -> matchText.any { line.text.contains(it) } }
            if (!hit) return false
        }
        return true
    }

    companion object {
        const val TAUGHT_HASH_MAX_DISTANCE = 3
    }
}

/**
 * The single place recovery actions are born. Every candidate target is
 * checked against the observation's forbidden boxes (gem icons, gem prices,
 * Buy, Surrender/End Battle, Quit/Exit), and gem-spend dialogs may only ever
 * be closed through the red X. This guard is code, not a rule-file promise.
 */
object SafeActionFactory {

    data class RecoveryAction(
        val type: RecoveryActionType,
        val target: ScreenBox?,
        val label: String
    )

    data class Attempt(val action: RecoveryAction?, val refusal: String? = null)

    fun resolve(observation: ScreenObservation, rule: RecoveryRule): Attempt {
        val isPopup = observation.state == GameState.POPUP
        if (!isPopup && rule.expectedState == null) {
            return Attempt(null, "not a popup")
        }
        if (isPopup && observation.popupKind == PopupKind.GEM_SPEND &&
            rule.matchHash == null &&
            rule.action != RecoveryActionType.TAP_CLOSE_X
        ) {
            return Attempt(null, "gem dialog may only be closed with X")
        }
        val proposed = when (rule.action) {
            RecoveryActionType.TAP_CLOSE_X -> tapClose(observation)
            RecoveryActionType.TAP_TEXT -> tapText(observation, rule.targetText)
            RecoveryActionType.TAP_POINT -> tapPoint(observation, rule)
            RecoveryActionType.PRESS_BACK ->
                RecoveryAction(RecoveryActionType.PRESS_BACK, null, "Back")
            RecoveryActionType.WAIT -> RecoveryAction(RecoveryActionType.WAIT, null, "Wait")
            RecoveryActionType.GO_HOME ->
                RecoveryAction(RecoveryActionType.GO_HOME, null, "Go home")
        } ?: return Attempt(null, "no ${rule.action} target found")
        val target = proposed.target ?: return Attempt(proposed)
        val forbidden = observation.forbidden.firstOrNull { it.intersects(target) }
        if (forbidden != null) {
            return Attempt(null, "refused: ${proposed.label} overlaps a never-press box")
        }
        return Attempt(proposed)
    }

    /**
     * Fallback chain for unknown popups: red X -> Cancel -> Later -> Close ->
     * Okay -> wait. On a gem-spend dialog only the red X is ever considered.
     */
    fun resolveGeneric(observation: ScreenObservation): List<Attempt> {
        val attempts = mutableListOf<Attempt>()
        tapClose(observation)?.let { attempts += Attempt(it) }
        if (observation.popupKind != PopupKind.GEM_SPEND) {
            for (caption in GENERIC_CAPTIONS) {
                val action = tapText(observation, caption)
                attempts += if (action != null) Attempt(action) else {
                    Attempt(null, "no $caption button")
                }
            }
        }
        attempts += Attempt(RecoveryAction(RecoveryActionType.WAIT, null, "Wait"))
        return attempts
    }

    fun tapClose(observation: ScreenObservation): RecoveryAction? {
        val box = observation.closeButton ?: return null
        if (observation.forbidden.any { it.intersects(box) }) return null
        return RecoveryAction(RecoveryActionType.TAP_CLOSE_X, box, "Close X")
    }

    fun tapText(observation: ScreenObservation, caption: String?): RecoveryAction? {
        if (caption.isNullOrBlank()) return null
        val wanted = com.clahbot.game.GameScreenEngine.normalize(caption)
        val line = observation.lines.firstOrNull { it.text.contains(wanted) } ?: return null
        val frameW = observation.frameWidth
        val frameH = observation.frameHeight
        if (frameW <= 0 || frameH <= 0) return null
        val box = ScreenBox(
            left = (line.left * frameW).toInt(),
            top = (line.top * frameH).toInt(),
            right = (line.right * frameW).toInt(),
            bottom = (line.bottom * frameH).toInt()
        ).expanded(frameW / 64, frameH / 40, frameW, frameH)
        if (observation.forbidden.any { it.intersects(box) }) return null
        return RecoveryAction(RecoveryActionType.TAP_TEXT, box, caption)
    }

    private fun tapPoint(observation: ScreenObservation, rule: RecoveryRule): RecoveryAction? {
        val x = rule.tapX ?: return null
        val y = rule.tapY ?: return null
        val frameW = observation.frameWidth
        val frameH = observation.frameHeight
        if (frameW <= 0 || frameH <= 0) return null
        val box = ScreenBox(
            left = (x * frameW).toInt() - frameW / 90,
            top = (y * frameH).toInt() - frameH / 50,
            right = (x * frameW).toInt() + frameW / 90,
            bottom = (y * frameH).toInt() + frameH / 50
        )
        if (box.left < 0 || box.top < 0 || box.right > frameW || box.bottom > frameH) return null
        if (observation.forbidden.any { it.intersects(box) }) return null
        return RecoveryAction(RecoveryActionType.TAP_POINT, box, rule.name)
    }

    private val GENERIC_CAPTIONS = listOf("cancel", "later", "close", "okay", "confirm")
}

/** Built-in rules, ordered by specificity. Taught rules are tried first. */
object BuiltInRecoveryRules {

    val all: List<RecoveryRule> = listOf(
        RecoveryRule(
            id = "searching_wait",
            name = "Searching for opponents",
            action = RecoveryActionType.WAIT,
            expectedState = GameState.SEARCHING,
            scope = RuleScope.ATTACK,
            waitForTransition = true,
            purpose = "Wait for an enemy village instead of acting"
        ),
        RecoveryRule(
            id = "connection_lost",
            name = "Connection lost",
            action = RecoveryActionType.TAP_TEXT,
            matchText = listOf("reload", "connectionlost", "disconnected"),
            targetText = "reload"
        ),
        RecoveryRule(
            id = "surrender_confirm",
            name = "Surrender confirm",
            action = RecoveryActionType.TAP_TEXT,
            matchPopupKind = PopupKind.SURRENDER_CONFIRM,
            targetText = "cancel"
        ),
        RecoveryRule(
            id = "exit_confirm",
            name = "Exit game confirm",
            action = RecoveryActionType.TAP_TEXT,
            matchPopupKind = PopupKind.EXIT_CONFIRM,
            targetText = "cancel"
        ),
        RecoveryRule(
            id = "gem_dialog",
            name = "Gem dialog",
            action = RecoveryActionType.TAP_CLOSE_X,
            matchPopupKind = PopupKind.GEM_SPEND
        ),
        RecoveryRule(
            id = "shop_offer",
            name = "Shop or offer dialog",
            action = RecoveryActionType.TAP_CLOSE_X,
            matchPopupKind = PopupKind.SHOP_OR_OFFER
        ),
        RecoveryRule(
            id = "rate_season",
            name = "Rate or season dialog",
            action = RecoveryActionType.TAP_CLOSE_X,
            matchPopupKind = PopupKind.RATE_OR_SEASON
        ),
        RecoveryRule(
            id = "generic_x",
            name = "Unknown popup: close X",
            action = RecoveryActionType.TAP_CLOSE_X
        ),
        RecoveryRule(
            id = "generic_cancel",
            name = "Unknown popup: Cancel",
            action = RecoveryActionType.TAP_TEXT,
            targetText = "cancel"
        ),
        RecoveryRule(
            id = "generic_later",
            name = "Unknown popup: Later",
            action = RecoveryActionType.TAP_TEXT,
            targetText = "later"
        ),
        RecoveryRule(
            id = "generic_okay",
            name = "Unknown popup: Okay",
            action = RecoveryActionType.TAP_TEXT,
            targetText = "okay"
        )
    )
}

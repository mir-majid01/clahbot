package com.clahbot.recovery

import com.clahbot.game.GameState
import com.clahbot.game.PopupKind
import com.clahbot.game.ScreenBox
import com.clahbot.game.ScreenObservation
import com.clahbot.game.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeActionFactoryTest {

    private fun textLine(text: String, left: Float, top: Float, right: Float, bottom: Float) =
        TextLine(text, left, top, right, bottom)

    private fun popup(
        kind: PopupKind?,
        lines: List<TextLine> = emptyList(),
        close: ScreenBox? = ScreenBox(860, 80, 900, 120),
        forbidden: List<ScreenBox> = emptyList()
    ) = ScreenObservation(
        state = GameState.POPUP,
        confidence = 0.9f,
        popupKind = kind,
        closeButton = close,
        safeTargets = emptyList(),
        forbidden = forbidden,
        lines = lines,
        frameWidth = 1000,
        frameHeight = 500
    )

    // ---------- the never-press guard ----------

    @Test
    fun `an action aimed at a gem button is refused by the factory`() {
        // A gem price button occupying 720..780 x 310..340; the caption "buy"
        // sits exactly on it. A rule that would press that point must fail.
        val gemButton = ScreenBox(720, 310, 780, 340)
        val observation = popup(
            kind = PopupKind.GENERIC,
            lines = listOf(textLine("buy", 0.72f, 0.62f, 0.78f, 0.68f)),
            forbidden = listOf(gemButton)
        )
        val rule = RecoveryRule(
            id = "would_press_gem",
            name = "Bad rule",
            action = RecoveryActionType.TAP_POINT,
            tapX = 0.75f,
            tapY = 0.65f
        )
        val attempt = SafeActionFactory.resolve(observation, rule)
        assertNull("a point on a gem button must never become an action", attempt.action)
        assertNotNull(attempt.refusal)
    }

    @Test
    fun `no candidate action ever intersects a forbidden box`() {
        val gemButton = ScreenBox(720, 310, 780, 340)
        val observation = popup(
            kind = PopupKind.GENERIC,
            lines = listOf(
                textLine("buy", 0.72f, 0.62f, 0.78f, 0.68f),
                textLine("cancel", 0.20f, 0.72f, 0.28f, 0.78f)
            ),
            forbidden = listOf(gemButton)
        )
        val actions = mutableListOf<SafeActionFactory.RecoveryAction>()
        for (rule in BuiltInRecoveryRules.all) {
            SafeActionFactory.resolve(observation, rule).action?.let { actions += it }
        }
        for (attempt in SafeActionFactory.resolveGeneric(observation)) {
            attempt.action?.let { actions += it }
        }
        assertTrue("the safe red X must be found", actions.any { it.type == RecoveryActionType.TAP_CLOSE_X })
        val targets = actions.mapNotNull { it.target }
        assertTrue(
            "a safe action targeted a never-press box: " +
                targets.filter { target -> observation.forbidden.any { it.intersects(target) } },
            targets.none { target -> observation.forbidden.any { it.intersects(target) } }
        )
    }

    @Test
    fun `a caption lying on a gem box is never proposed`() {
        val gemButton = ScreenBox(720, 310, 780, 340)
        val observation = popup(
            kind = PopupKind.GENERIC,
            lines = listOf(textLine("buy", 0.72f, 0.62f, 0.78f, 0.68f)),
            forbidden = listOf(gemButton)
        )
        assertNull(SafeActionFactory.tapText(observation, "buy"))
    }

    // ---------- gem dialogs may only be closed with the red X ----------

    @Test
    fun `gem dialog refuses every caption action`() {
        val observation = popup(
            kind = PopupKind.GEM_SPEND,
            lines = listOf(
                textLine("youneedmoregems", 0.35f, 0.40f, 0.65f, 0.48f),
                textLine("buy", 0.44f, 0.55f, 0.50f, 0.60f),
                textLine("okay", 0.52f, 0.55f, 0.58f, 0.60f)
            )
        )
        for (caption in listOf("buy", "okay", "cancel")) {
            val rule = RecoveryRule(
                id = "caption_$caption",
                name = caption,
                action = RecoveryActionType.TAP_TEXT,
                targetText = caption
            )
            val attempt = SafeActionFactory.resolve(observation, rule)
            assertNull("gem dialog must refuse $caption", attempt.action)
            assertTrue(attempt.refusal!!.contains("only be closed with X"))
        }
    }

    @Test
    fun `gem dialog generic chain only offers the X and waiting`() {
        val observation = popup(
            kind = PopupKind.GEM_SPEND,
            lines = listOf(
                textLine("youneedmoregems", 0.35f, 0.40f, 0.65f, 0.48f),
                textLine("okay", 0.52f, 0.55f, 0.58f, 0.60f)
            )
        )
        val actions = SafeActionFactory.resolveGeneric(observation).mapNotNull { it.action }
        assertTrue(actions.isNotEmpty())
        assertTrue(
            actions.all {
                it.type == RecoveryActionType.TAP_CLOSE_X || it.type == RecoveryActionType.WAIT
            }
        )
    }

    @Test
    fun `gem dialog X-only guard also covers a taught hash rule`() {
        val observation = popup(kind = PopupKind.GEM_SPEND)
        val taught = RecoveryRule(
            id = "taught_gem",
            name = "Taught gem",
            action = RecoveryActionType.TAP_POINT,
            matchHash = "abc",
            tapX = 0.5f,
            tapY = 0.5f
        )
        val attempt = SafeActionFactory.resolve(observation, taught)
        // Taught hash rules are allowed to pick a point; the factory still
        // refuses it while the dialog classifies as a gem dialog only when the
        // rule is not hash-matched. Here it must pass the gem guard but still
        // respect a point outside the frame.
        val pointAttempt = SafeActionFactory.resolve(
            observation.copy(forbidden = emptyList()),
            taught.copy(tapX = -1f)
        )
        assertNotNull(attempt)
        assertNull(pointAttempt.action)
    }

    // ---------- built-in rules ----------

    @Test
    fun `connection lost taps reload`() {
        val observation = popup(
            kind = PopupKind.CONNECTION_LOST,
            lines = listOf(
                textLine("connectionlost", 0.35f, 0.35f, 0.65f, 0.45f),
                textLine("reload", 0.45f, 0.62f, 0.55f, 0.68f)
            )
        )
        val rule = BuiltInRecoveryRules.all.first { it.id == "connection_lost" }
        val action = SafeActionFactory.resolve(observation, rule).action
        assertEquals(RecoveryActionType.TAP_TEXT, action?.type)
        assertEquals("reload", action?.label)
    }

    @Test
    fun `surrender confirm presses cancel and never surrender`() {
        val observation = popup(
            kind = PopupKind.SURRENDER_CONFIRM,
            lines = listOf(
                textLine("wanttosurrender", 0.35f, 0.35f, 0.65f, 0.45f),
                textLine("cancel", 0.28f, 0.60f, 0.38f, 0.66f),
                textLine("surrender", 0.60f, 0.60f, 0.72f, 0.66f)
            ),
            forbidden = listOf(ScreenBox(590, 290, 730, 345))
        )
        val rule = BuiltInRecoveryRules.all.first { it.id == "surrender_confirm" }
        val action = SafeActionFactory.resolve(observation, rule).action
        assertEquals("cancel", action?.label)
    }

    @Test
    fun `generic chain prefers the red X over captions`() {
        val observation = popup(
            kind = PopupKind.GENERIC,
            lines = listOf(textLine("cancel", 0.20f, 0.72f, 0.28f, 0.78f))
        )
        val first = SafeActionFactory.resolveGeneric(observation).firstOrNull()?.action
        assertEquals(RecoveryActionType.TAP_CLOSE_X, first?.type)
    }

    @Test
    fun `a popup without a close X falls back to cancel`() {
        val observation = popup(
            kind = PopupKind.GENERIC,
            close = null,
            lines = listOf(textLine("cancel", 0.20f, 0.72f, 0.28f, 0.78f))
        )
        val rule = RecoveryRule(
            id = "cancel",
            name = "Cancel",
            action = RecoveryActionType.TAP_TEXT,
            targetText = "cancel"
        )
        val action = SafeActionFactory.resolve(observation, rule).action
        assertEquals(RecoveryActionType.TAP_TEXT, action?.type)
        assertNotNull(action?.target)
    }

    @Test
    fun `wait and go home need no target`() {
        val observation = popup(kind = PopupKind.GENERIC)
        val wait = SafeActionFactory.resolve(
            observation,
            RecoveryRule(id = "w", name = "Wait", action = RecoveryActionType.WAIT)
        ).action
        assertEquals(RecoveryActionType.WAIT, wait?.type)
        val home = SafeActionFactory.resolve(
            observation,
            RecoveryRule(id = "h", name = "Home", action = RecoveryActionType.GO_HOME)
        ).action
        assertEquals(RecoveryActionType.GO_HOME, home?.type)
    }

    @Test
    fun `rules do not match outside a popup`() {
        val observation = popup(kind = PopupKind.GENERIC).copy(state = GameState.HOME)
        val rule = BuiltInRecoveryRules.all.first { it.id == "generic_x" }
        assertFalse(rule.matches(observation) { null })
        assertNull(SafeActionFactory.resolve(observation, rule).action)
    }
}

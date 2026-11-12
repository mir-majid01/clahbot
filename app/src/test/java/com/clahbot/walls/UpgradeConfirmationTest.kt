package com.clahbot.walls

import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Confirmation-dialog validation + 2.0 safety (Auto Walls PRD §3.4/§10). These
 * tests model the real dialog: title "Upgrade Wall to Level N?", a CONFIRM
 * caption, the discounted button price directly under it, and a struck-through
 * original above it. They also cover the "You Need More Gold … Buy … Gems"
 * popup, a Wall Ring row, and an unrelated popup.
 */
class UpgradeConfirmationTest {

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        OcrLine(text, OcrBox(left, top, right, bottom), emptyList())

    private fun candidate(level: Int, price: Long, currency: String) =
        WallCandidate(
            id = "row:0",
            level = level,
            price = price,
            currency = currency,
            listIndex = 0,
            confidence = 0.9f
        )

    private fun currencyAtGold(box: OcrBox): String? = if (box.top in 588..630) "gold" else null

    @Test
    fun `a legitimate gold confirmation validates with the discounted price`() {
        val lines = listOf(
            line("Upgrade Wall to Level 17?", 300, 20, 900, 60),
            line("CONFIRM", 760, 560, 880, 590),
            line("2 500 000", 760, 592, 880, 622),
            line("5 500 000", 760, 520, 880, 548)
        )
        val result = UpgradeConfirmation.validate(lines, candidate(17, 2_500_000, "gold")) { currencyAtGold(it) }
        assertTrue(result is UpgradeConfirmation.Result.Confirm)
        val confirm = result as UpgradeConfirmation.Result.Confirm
        assertEquals(17, confirm.level)
        assertEquals(2_500_000L, confirm.price)
        assertEquals("gold", confirm.currency)
    }

    @Test
    fun `a legitimate elixir confirmation validates`() {
        val lines = listOf(
            line("Upgrade Wall to Level 13?", 300, 20, 900, 60),
            line("CONFIRM", 760, 560, 880, 590),
            line("500 000", 760, 592, 880, 622)
        )
        val currencyAt = { box: OcrBox -> if (box.top in 588..630) "elixir" else null }
        val result = UpgradeConfirmation.validate(lines, candidate(13, 500_000, "elixir"), currencyAt)
        assertTrue(result is UpgradeConfirmation.Result.Confirm)
        assertEquals(500_000L, (result as UpgradeConfirmation.Result.Confirm).price)
    }

    @Test
    fun `the discounted confirm price is used, never the struck original`() {
        val lines = listOf(
            line("Upgrade Wall to Level 17?", 300, 20, 900, 60),
            line("CONFIRM", 760, 560, 880, 590),
            line("1 000 000", 760, 592, 880, 622),
            line("2 500 000", 760, 520, 880, 548)
        )
        // Candidate carries the discounted price the wall list showed.
        val result = UpgradeConfirmation.validate(lines, candidate(17, 1_000_000, "gold")) { currencyAtGold(it) }
        assertTrue(result is UpgradeConfirmation.Result.Confirm)
        assertEquals(1_000_000L, (result as UpgradeConfirmation.Result.Confirm).price)
    }

    @Test
    fun `a mismatched cost is refused`() {
        val lines = listOf(
            line("Upgrade Wall to Level 17?", 300, 20, 900, 60),
            line("CONFIRM", 760, 560, 880, 590),
            line("2 500 000", 760, 592, 880, 622)
        )
        val result = UpgradeConfirmation.validate(lines, candidate(17, 3_000_000, "gold")) { currencyAtGold(it) }
        assertTrue(result is UpgradeConfirmation.Result.Refuse)
        assertTrue((result as UpgradeConfirmation.Result.Refuse).reason.contains("cost mismatch"))
    }

    @Test
    fun `a mismatched level is refused`() {
        val lines = listOf(
            line("Upgrade Wall to Level 16?", 300, 20, 900, 60),
            line("CONFIRM", 760, 560, 880, 590),
            line("2 500 000", 760, 592, 880, 622)
        )
        val result = UpgradeConfirmation.validate(lines, candidate(17, 2_500_000, "gold")) { currencyAtGold(it) }
        assertTrue(result is UpgradeConfirmation.Result.Refuse)
        assertTrue((result as UpgradeConfirmation.Result.Refuse).reason.contains("level mismatch"))
    }

    @Test
    fun `a mismatched currency is refused`() {
        val lines = listOf(
            line("Upgrade Wall to Level 17?", 300, 20, 900, 60),
            line("CONFIRM", 760, 560, 880, 590),
            line("2 500 000", 760, 592, 880, 622)
        )
        val currencyAt = { box: OcrBox -> if (box.top in 588..630) "elixir" else null }
        val result = UpgradeConfirmation.validate(lines, candidate(17, 2_500_000, "gold"), currencyAt)
        assertTrue(result is UpgradeConfirmation.Result.Refuse)
        assertTrue((result as UpgradeConfirmation.Result.Refuse).reason.contains("currency mismatch"))
    }

    @Test
    fun `a gem purchase dialog is refused and never purchases`() {
        val lines = listOf(
            line("You Need More Gold", 300, 100, 700, 140),
            line("Buy the missing 805739 Gold?", 300, 200, 800, 240),
            line("218", 400, 350, 470, 390)
        )
        val result = UpgradeConfirmation.validate(lines, candidate(17, 2_500_000, "gold")) { null }
        assertTrue(result is UpgradeConfirmation.Result.Refuse)
        assertTrue((result as UpgradeConfirmation.Result.Refuse).reason.contains("insufficient"))
    }

    @Test
    fun `a wall ring control is refused`() {
        val lines = listOf(
            line("Upgrade Wall to Level 17?", 300, 20, 900, 60),
            line("Wall Ring", 760, 500, 880, 530)
        )
        val result = UpgradeConfirmation.validate(lines, candidate(17, 2_500_000, "gold")) { currencyAtGold(it) }
        assertTrue(result is UpgradeConfirmation.Result.Refuse)
        assertTrue((result as UpgradeConfirmation.Result.Refuse).reason.contains("premium"))
    }

    @Test
    fun `an unrelated popup is not a wall upgrade confirmation`() {
        val lines = listOf(
            line("Are you sure you want to quit?", 300, 100, 700, 140),
            line("Cancel", 300, 300, 450, 340),
            line("Quit", 550, 300, 700, 340)
        )
        val result = UpgradeConfirmation.validate(lines, candidate(17, 2_500_000, "gold")) { null }
        assertTrue(result is UpgradeConfirmation.Result.Refuse)
        assertTrue((result as UpgradeConfirmation.Result.Refuse).reason.contains("not a wall upgrade"))
    }
}

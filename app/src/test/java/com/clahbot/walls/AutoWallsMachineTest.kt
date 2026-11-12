package com.clahbot.walls

import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto Walls 2.0 execution state machine (PRD §8/§9). Every test checks one
 * transition or failure path and asserts both the returned [AutoWallsMachine.Action]
 * and the recorded [WallEvent]s, so an attempted tap can never masquerade as a
 * verified upgrade.
 */
class AutoWallsMachineTest {

    // ---- helpers -----------------------------------------------------------

    private fun candidate(
        id: String,
        level: Int,
        price: Long,
        listIndex: Int = 0,
        currency: String = "gold",
        confidence: Float = 0.9f,
        count: Int? = null
    ) = WallCandidate(
        id = id,
        level = level,
        price = price,
        currency = currency,
        listIndex = listIndex,
        confidence = confidence,
        count = count
    )

    /** One list scan: the level group plus, optionally, the next level group. */
    private fun scan(level: Int, count: Int?, nextCount: Int? = null): List<WallCandidate> = buildList {
        add(candidate("row:0", level = level, price = 2_500_000, count = count))
        if (nextCount != null) {
            add(candidate("row:1", level = level + 1, price = 2_500_000, listIndex = 1, count = nextCount))
        }
    }

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        OcrLine(text, OcrBox(left, top, right, bottom), emptyList())

    private fun currencyAt(box: OcrBox): String? = when (box.top) {
        in 588..630 -> confirmCurrency
        else -> null
    }

    private var confirmCurrency: String = "gold"

    /** The dialog as it appears on the real capture: discounted price under CONFIRM. */
    private fun confirmationLines(
        level: Int,
        discounted: String,
        original: String? = null
    ): List<OcrLine> = buildList {
        add(line("Upgrade Wall to Level $level?", 300, 20, 900, 60))
        add(line("CONFIRM", 760, 560, 880, 590))
        add(line(discounted, 760, 592, 880, 622))
        if (original != null) add(line(original, 760, 520, 880, 548))
    }

    private fun AutoWallsMachine.statuses(): List<WallEventStatus> = events.map { it.status }

    /** A minimal passing evaluation; the machine reacts to pass + waitingReason. */
    private fun passEvaluation(): TriggerEvaluation =
        TriggerEvaluation(listOf(TriggerCondition("attacks", true, "10 attacks since the last verified upgrade")))

    private fun waitEvaluation(reason: String = "attacks since last verified upgrade: 0 of 10"): TriggerEvaluation =
        TriggerEvaluation(listOf(TriggerCondition("attacks", false, reason)))

    private fun readyMachine(
        selection: WallSelectionConfig = WallSelectionConfig(),
        allowTapping: Boolean = true,
        maxScrolls: Int = 4
    ): AutoWallsMachine {
        val machine = AutoWallsMachine(
            AutoWallsConfig(enabled = true, selection = selection, allowTapping = allowTapping, maxScrolls = maxScrolls)
        )
        val action = machine.onTrigger(passEvaluation())
        assertTrue(action is AutoWallsMachine.Action.ScanRows)
        return machine
    }

    // ---- lifecycle ---------------------------------------------------------

    @Test
    fun `a disabled config starts disabled and enable moves to waiting`() {
        val machine = AutoWallsMachine(AutoWallsConfig(enabled = false))
        assertEquals(AutoWallsState.DISABLED, machine.state)
        assertEquals(AutoWallsMachine.Action.WaitForTrigger, machine.enable())
        assertEquals(AutoWallsState.WAITING_FOR_TRIGGER, machine.state)
    }

    @Test
    fun `disable clears a stopped run back to disabled`() {
        val machine = AutoWallsMachine(AutoWallsConfig(enabled = true, allowTapping = false, maxScrolls = 0))
        machine.onTrigger(passEvaluation())
        machine.onRows(emptyList())
        assertTrue(machine.state.isTerminal)
        machine.disable()
        assertEquals(AutoWallsState.DISABLED, machine.state)
        assertEquals(AutoWallsMachine.Action.WaitForTrigger, machine.enable())
        assertEquals(AutoWallsState.WAITING_FOR_TRIGGER, machine.state)
    }

    @Test
    fun `a failed combined trigger keeps waiting and records nothing`() {
        val machine = AutoWallsMachine(AutoWallsConfig(enabled = true))
        val action = machine.onTrigger(waitEvaluation())
        assertEquals(AutoWallsMachine.Action.WaitForTrigger, action)
        assertEquals(AutoWallsState.WAITING_FOR_TRIGGER, machine.state)
        assertTrue(machine.events.isEmpty())
    }

    @Test
    fun `a failed trigger exposes the exact waiting reason`() {
        val machine = AutoWallsMachine(AutoWallsConfig(enabled = true))
        val reason = "gold 4800000 below threshold 5000000"
        machine.onTrigger(waitEvaluation(reason))
        assertEquals(AutoWallsState.WAITING_FOR_TRIGGER, machine.state)
        assertEquals(reason, machine.waitingReason)
        assertTrue(machine.events.isEmpty())
    }

    @Test
    fun `a passing trigger clears the waiting reason`() {
        val machine = AutoWallsMachine(AutoWallsConfig(enabled = true))
        machine.onTrigger(waitEvaluation())
        assertEquals("attacks since last verified upgrade: 0 of 10", machine.waitingReason)
        machine.onTrigger(passEvaluation())
        assertNull(machine.waitingReason)
        assertEquals(AutoWallsState.SCANNING_WALLS, machine.state)
    }

    @Test
    fun `a passing trigger starts a fresh scan`() {
        val machine = AutoWallsMachine(AutoWallsConfig(enabled = true))
        val action = machine.onTrigger(passEvaluation())
        assertEquals(AutoWallsMachine.Action.ScanRows, action)
        assertEquals(AutoWallsState.SCANNING_WALLS, machine.state)
        assertTrue(machine.events.isEmpty())
    }

    @Test
    fun `the real combined evaluator drives the machine and tapping stays gated`() {
        val machine = AutoWallsMachine(AutoWallsConfig(enabled = true, allowTapping = false))
        val config = WallTriggerConfig()
        val selection = WallSelectionConfig(currency = WallCurrency.GOLD)
        val evidence = TriggerEvidence(
            enabled = true, resources = WallCounters.Reading(gold = 5_000_000), now = 1_000_000
        )
        // Attacks not reached -> the machine waits with the evaluator's reason.
        val waiting = AutoWallsTrigger.evaluate(
            config, selection, WallTriggerCounters(attacksSinceVerified = 3), evidence
        )
        assertEquals(AutoWallsMachine.Action.WaitForTrigger, machine.onTrigger(waiting))
        assertEquals(AutoWallsState.WAITING_FOR_TRIGGER, machine.state)
        assertTrue(machine.waitingReason!!.contains("3 of 10"))
        // Attacks reached + gold meets threshold and reserve -> scan, but no tap.
        val passing = AutoWallsTrigger.evaluate(
            config, selection, WallTriggerCounters(attacksSinceVerified = 10), evidence
        )
        assertEquals(AutoWallsMachine.Action.ScanRows, machine.onTrigger(passing))
        val action = machine.onRows(listOf(candidate("row:1", level = 13, price = 500_000)))
        assertTrue(action is AutoWallsMachine.Action.Stop)
        assertEquals(AutoWallsState.SAFETY_BLOCKED, (action as AutoWallsMachine.Action.Stop).state)
        assertFalse(machine.statuses().contains(WallEventStatus.ATTEMPTED))
    }

    @Test
    fun `an unreadable balance keeps the machine waiting through the real evaluator`() {
        val machine = AutoWallsMachine(AutoWallsConfig(enabled = true))
        val evaluation = AutoWallsTrigger.evaluate(
            WallTriggerConfig(),
            WallSelectionConfig(currency = WallCurrency.GOLD),
            WallTriggerCounters(attacksSinceVerified = 10),
            TriggerEvidence(enabled = true, resources = WallCounters.Reading(gold = null), now = 1_000_000)
        )
        assertEquals(AutoWallsMachine.Action.WaitForTrigger, machine.onTrigger(evaluation))
        assertTrue(machine.waitingReason!!.contains("not readable"))
    }

    // ---- discovery / selection --------------------------------------------

    @Test
    fun `an eligible wall is detected, selected and only then tapped`() {
        val machine = readyMachine()
        val action = machine.onRows(listOf(candidate("row:3", level = 14, price = 500_000, listIndex = 3)))
        assertTrue(action is AutoWallsMachine.Action.TapRow)
        assertEquals("row:3", (action as AutoWallsMachine.Action.TapRow).candidate.id)
        assertEquals(AutoWallsState.VALIDATING_CONFIRMATION, machine.state)
        assertEquals(listOf(WallEventStatus.DETECTED, WallEventStatus.SELECTED), machine.statuses())
    }

    @Test
    fun `with tapping disabled it selects then stops before any tap`() {
        val machine = readyMachine(allowTapping = false)
        val action = machine.onRows(listOf(candidate("row:1", level = 13, price = 500_000)))
        assertTrue(action is AutoWallsMachine.Action.Stop)
        val stop = action as AutoWallsMachine.Action.Stop
        assertEquals(AutoWallsState.SAFETY_BLOCKED, stop.state)
        assertTrue(stop.reason.contains("tapping is disabled"))
        assertEquals(AutoWallsState.SAFETY_BLOCKED, machine.state)
        assertEquals(listOf(WallEventStatus.DETECTED, WallEventStatus.SELECTED), machine.statuses())
        assertFalse(machine.events.any { it.status == WallEventStatus.ATTEMPTED })
    }

    @Test
    fun `an empty scan scrolls up to the limit and then stops safely`() {
        val machine = readyMachine(maxScrolls = 2)
        assertEquals(AutoWallsMachine.Action.ScrollAndScan, machine.onRows(emptyList()))
        assertEquals(AutoWallsState.SCANNING_WALLS, machine.state)
        assertEquals(AutoWallsMachine.Action.ScrollAndScan, machine.onRows(emptyList()))
        val action = machine.onRows(emptyList())
        assertTrue(action is AutoWallsMachine.Action.Stop)
        assertEquals(AutoWallsState.SAFETY_BLOCKED, (action as AutoWallsMachine.Action.Stop).state)
        assertTrue(machine.events.isEmpty())
    }

    @Test
    fun `a scan after scrolling uses the fresh rows, never stale ones`() {
        val machine = readyMachine(maxScrolls = 2)
        assertEquals(AutoWallsMachine.Action.ScrollAndScan, machine.onRows(emptyList()))
        val action = machine.onRows(listOf(candidate("row:5", level = 15, price = 700_000, listIndex = 5)))
        assertTrue(action is AutoWallsMachine.Action.TapRow)
        assertEquals("row:5", (action as AutoWallsMachine.Action.TapRow).candidate.id)
        assertEquals(listOf(WallEventStatus.DETECTED, WallEventStatus.SELECTED), machine.statuses())
    }

    @Test
    fun `low-confidence rows are skipped in favour of a confident one`() {
        val machine = readyMachine()
        val action = machine.onRows(
            listOf(
                candidate("blurred", level = 9, price = 1_000, listIndex = 0, confidence = 0.3f),
                candidate("clear", level = 14, price = 500_000, listIndex = 1)
            )
        )
        assertEquals("clear", (action as AutoWallsMachine.Action.TapRow).candidate.id)
    }

    @Test
    fun `the configured currency filter never selects the other currency`() {
        val machine = readyMachine(
            selection = WallSelectionConfig(currency = WallCurrency.GOLD)
        )
        val action = machine.onRows(
            listOf(
                candidate("goldy", level = 14, price = 500_000, listIndex = 0, currency = "gold"),
                candidate("elixiry", level = 15, price = 50_000, listIndex = 1, currency = "elixir")
            )
        )
        assertEquals("goldy", (action as AutoWallsMachine.Action.TapRow).candidate.id)
    }

    @Test
    fun `rows in only the excluded currency lead to a safe stop`() {
        val machine = readyMachine(
            selection = WallSelectionConfig(currency = WallCurrency.ELIXIR),
            maxScrolls = 0
        )
        val action = machine.onRows(listOf(candidate("goldy", level = 14, price = 500_000, currency = "gold")))
        assertTrue(action is AutoWallsMachine.Action.Stop)
        assertEquals(AutoWallsState.SAFETY_BLOCKED, machine.state)
    }

    // ---- row tap -----------------------------------------------------------

    @Test
    fun `a failed row tap stops in execution failed`() {
        val machine = readyMachine()
        machine.onRows(listOf(candidate("row:2", level = 13, price = 300_000)))
        val action = machine.onRowTapResult(false)
        assertTrue(action is AutoWallsMachine.Action.Stop)
        assertEquals(AutoWallsState.EXECUTION_FAILED, machine.state)
        assertEquals(WallEventStatus.FAILED, machine.events.last().status)
        assertTrue(machine.events.last().detail.contains("row tap failed"))
    }

    @Test
    fun `a successful row tap asks for the confirmation to be read`() {
        val machine = readyMachine()
        val c = candidate("row:2", level = 13, price = 300_000)
        machine.onRows(listOf(c))
        val action = machine.onRowTapResult(true)
        assertTrue(action is AutoWallsMachine.Action.ReadConfirmation)
        assertEquals(c, (action as AutoWallsMachine.Action.ReadConfirmation).candidate)
        assertEquals(AutoWallsState.VALIDATING_CONFIRMATION, machine.state)
    }

    // ---- confirmation validation ------------------------------------------

    @Test
    fun `a valid confirmation is validated and only then confirmed`() {
        val machine = readyMachine()
        val c = candidate("row:2", level = 17, price = 2_500_000)
        machine.onRows(listOf(c))
        machine.onRowTapResult(true)
        confirmCurrency = "gold"
        val action = machine.onConfirmation(confirmationLines(17, "2 500 000", "5 500 000")) { currencyAt(it) }
        assertTrue(action is AutoWallsMachine.Action.TapConfirm)
        assertEquals(AutoWallsState.EXECUTING_UPGRADE, machine.state)
        assertEquals(
            listOf(
                WallEventStatus.DETECTED,
                WallEventStatus.SELECTED,
                WallEventStatus.CONFIRMATION_VALIDATED,
                WallEventStatus.ATTEMPTED
            ),
            machine.statuses()
        )
    }

    @Test
    fun `the discounted confirmation price validates while the original is ignored`() {
        val machine = readyMachine()
        machine.onRows(listOf(candidate("row:2", level = 17, price = 1_000_000)))
        machine.onRowTapResult(true)
        confirmCurrency = "gold"
        val action = machine.onConfirmation(confirmationLines(17, "1 000 000", "2 500 000")) { currencyAt(it) }
        assertTrue(action is AutoWallsMachine.Action.TapConfirm)
    }

    @Test
    fun `a cost mismatch stops before the confirm tap`() {
        val machine = readyMachine()
        machine.onRows(listOf(candidate("row:2", level = 17, price = 3_000_000)))
        machine.onRowTapResult(true)
        confirmCurrency = "gold"
        val action = machine.onConfirmation(confirmationLines(17, "2 500 000")) { currencyAt(it) }
        assertTrue(action is AutoWallsMachine.Action.Stop)
        assertEquals(AutoWallsState.EXECUTION_FAILED, machine.state)
        assertFalse(machine.statuses().contains(WallEventStatus.ATTEMPTED))
        assertTrue(machine.events.last().detail.contains("cost mismatch"))
    }

    @Test
    fun `a level mismatch stops before the confirm tap`() {
        val machine = readyMachine()
        machine.onRows(listOf(candidate("row:2", level = 17, price = 2_500_000)))
        machine.onRowTapResult(true)
        confirmCurrency = "gold"
        val action = machine.onConfirmation(confirmationLines(16, "2 500 000")) { currencyAt(it) }
        assertTrue(action is AutoWallsMachine.Action.Stop)
        assertEquals(AutoWallsState.EXECUTION_FAILED, machine.state)
        assertFalse(machine.statuses().contains(WallEventStatus.ATTEMPTED))
    }

    @Test
    fun `an insufficient-resource popup safe-stops without purchasing`() {
        val machine = readyMachine()
        machine.onRows(listOf(candidate("row:2", level = 17, price = 2_500_000)))
        machine.onRowTapResult(true)
        val action = machine.onConfirmation(
            listOf(
                line("You Need More Gold", 300, 100, 700, 140),
                line("Buy the missing 805739 Gold?", 300, 200, 800, 240)
            )
        ) { null }
        assertTrue(action is AutoWallsMachine.Action.Stop)
        assertEquals(AutoWallsState.INSUFFICIENT_RESOURCES, machine.state)
        assertEquals(WallEventStatus.INSUFFICIENT, machine.events.last().status)
        assertFalse(machine.statuses().contains(WallEventStatus.ATTEMPTED))
    }

    @Test
    fun `a premium control (wall ring) safe-stops`() {
        val machine = readyMachine()
        machine.onRows(listOf(candidate("row:2", level = 17, price = 2_500_000)))
        machine.onRowTapResult(true)
        val action = machine.onConfirmation(
            listOf(line("Upgrade Wall to Level 17?", 300, 20, 900, 60), line("Wall Ring", 760, 500, 880, 530))
        ) { null }
        assertTrue(action is AutoWallsMachine.Action.Stop)
        assertEquals(AutoWallsState.SAFETY_BLOCKED, machine.state)
        assertEquals(WallEventStatus.SAFETY_STOPPED, machine.events.last().status)
    }

    @Test
    fun `an empty confirmation means an unknown screen`() {
        val machine = readyMachine()
        machine.onRows(listOf(candidate("row:2", level = 17, price = 2_500_000)))
        machine.onRowTapResult(true)
        val action = machine.onConfirmation(emptyList())
        assertTrue(action is AutoWallsMachine.Action.Stop)
        assertEquals(AutoWallsState.UNKNOWN_SCREEN, machine.state)
        assertEquals(WallEventStatus.UNKNOWN_SCREEN, machine.events.last().status)
    }

    @Test
    fun `an unrelated popup stops as an unknown screen`() {
        val machine = readyMachine()
        machine.onRows(listOf(candidate("row:2", level = 17, price = 2_500_000)))
        machine.onRowTapResult(true)
        val action = machine.onConfirmation(
            listOf(
                line("Are you sure you want to quit?", 300, 100, 700, 140),
                line("Quit", 550, 300, 700, 340)
            )
        ) { null }
        assertTrue(action is AutoWallsMachine.Action.Stop)
        assertEquals(AutoWallsState.UNKNOWN_SCREEN, machine.state)
    }

    @Test
    fun `a confirm tap before validation is rejected, not executed`() {
        val machine = readyMachine()
        machine.onRows(listOf(candidate("row:2", level = 17, price = 2_500_000)))
        machine.onRowTapResult(true)
        val action = machine.onConfirmTapResult(true)
        assertTrue(action is AutoWallsMachine.Action.Stop)
        assertEquals(AutoWallsState.VALIDATING_CONFIRMATION, machine.state)
        assertFalse(machine.events.any { it.status == WallEventStatus.ATTEMPTED })
    }

    // ---- execution / verification -----------------------------------------

    private fun machineAtConfirmTap(level: Int = 17): AutoWallsMachine {
        val machine = readyMachine()
        machine.onRows(scan(level, 317, 4))
        machine.onRowTapResult(true)
        confirmCurrency = "gold"
        machine.onConfirmation(confirmationLines(level, "2 500 000")) { currencyAt(it) }
        assertEquals(AutoWallsState.EXECUTING_UPGRADE, machine.state)
        return machine
    }

    @Test
    fun `a successful confirm tap is not recorded as a verified upgrade`() {
        val machine = machineAtConfirmTap()
        val action = machine.onConfirmTapResult(true)
        assertTrue(action is AutoWallsMachine.Action.Verify)
        assertEquals(AutoWallsState.VERIFYING_RESULT, machine.state)
        assertFalse(machine.statuses().contains(WallEventStatus.VERIFIED))
        assertEquals(WallEventStatus.ATTEMPTED, machine.events.last().status)
    }

    @Test
    fun `a verified upgrade records VERIFIED and cools down`() {
        val machine = machineAtConfirmTap()
        machine.onConfirmTapResult(true)
        val action = machine.onVerification(scan(17, 316, 5))
        assertEquals(AutoWallsMachine.Action.Cooldown, action)
        assertEquals(AutoWallsState.COOLDOWN, machine.state)
        assertEquals(WallEventStatus.VERIFIED, machine.events.last().status)
        assertTrue(machine.events.last().detail.contains("317->316"))
    }

    @Test
    fun `an unverified attempt is recorded as failed, never verified`() {
        val machine = machineAtConfirmTap()
        machine.onConfirmTapResult(true)
        machine.onVerification(scan(17, 317, 4))
        assertEquals(AutoWallsState.COOLDOWN, machine.state)
        assertFalse(machine.statuses().contains(WallEventStatus.VERIFIED))
        assertEquals(WallEventStatus.FAILED, machine.events.last().status)
        assertTrue(machine.events.last().detail.contains("unverified"))
        assertTrue(machine.events.last().detail.contains("unchanged"))
    }

    @Test
    fun `verification uses the scan that selected the wall, not a stored position`() {
        val machine = machineAtConfirmTap()
        machine.onConfirmTapResult(true)
        // The upgraded group scrolled off-screen; only the next level is visible.
        val action = machine.onVerification(scan(18, 5))
        assertEquals(AutoWallsMachine.Action.Cooldown, action)
        assertEquals(WallEventStatus.VERIFIED, machine.events.last().status)
        assertTrue(machine.events.last().detail.contains("4->5"))
    }

    @Test
    fun `a resource change contradicting the price keeps the attempt unverified`() {
        val machine = machineAtConfirmTap()
        machine.onConfirmTapResult(true)
        machine.onVerification(
            scan(17, 316, 5),
            resourcesBefore = WallCounters.Reading(gold = 5_000_000),
            resourcesAfter = WallCounters.Reading(gold = 5_000_000)
        )
        assertEquals(WallEventStatus.FAILED, machine.events.last().status)
        assertTrue(machine.events.last().detail.contains("resource"))
        assertFalse(machine.statuses().contains(WallEventStatus.VERIFIED))
    }

    @Test
    fun `verification outside the verifying state is refused`() {
        val machine = readyMachine()
        machine.onRows(scan(17, 317, 4))
        // A candidate exists, but the confirm tap has not happened yet.
        assertEquals(AutoWallsState.VALIDATING_CONFIRMATION, machine.state)
        assertTrue(machine.onVerification(scan(17, 316, 5)) is AutoWallsMachine.Action.Stop)
        assertEquals(AutoWallsState.VALIDATING_CONFIRMATION, machine.state)
        assertFalse(machine.statuses().contains(WallEventStatus.VERIFIED))
    }

    @Test
    fun `a failed confirm tap stops in execution failed`() {
        val machine = machineAtConfirmTap()
        val action = machine.onConfirmTapResult(false)
        assertTrue(action is AutoWallsMachine.Action.Stop)
        assertEquals(AutoWallsState.EXECUTION_FAILED, machine.state)
        assertEquals(WallEventStatus.FAILED, machine.events.last().status)
        assertTrue(machine.events.last().detail.contains("confirm tap failed"))
    }

    @Test
    fun `cooldown returns to waiting and the next trigger rescans`() {
        val machine = machineAtConfirmTap()
        machine.onConfirmTapResult(true)
        machine.onVerification(scan(17, 316, 5))
        assertEquals(AutoWallsMachine.Action.WaitForTrigger, machine.onCooldownElapsed())
        assertEquals(AutoWallsState.WAITING_FOR_TRIGGER, machine.state)
        assertEquals(AutoWallsMachine.Action.ScanRows, machine.onTrigger(passEvaluation()))
        assertEquals(AutoWallsState.SCANNING_WALLS, machine.state)
    }

    // ---- repeat upgrade (PRD §5.5) -----------------------------------------

    @Test
    fun `a verified upgrade can repeat straight into a fresh scan`() {
        val machine = machineAtConfirmTap()
        machine.onConfirmTapResult(true)
        machine.onVerification(scan(17, 316, 5))
        assertEquals(AutoWallsState.COOLDOWN, machine.state)
        val action = machine.onRepeatUpgrade()
        assertEquals(AutoWallsMachine.Action.ScanRows, action)
        assertEquals(AutoWallsState.SCANNING_WALLS, machine.state)
        assertNull(machine.snapshot().candidate)
        assertEquals(0, machine.snapshot().scrolls)
    }

    @Test
    fun `a repeat rescans fresh rows and never replays the stale candidate`() {
        val machine = machineAtConfirmTap()
        machine.onConfirmTapResult(true)
        machine.onVerification(scan(17, 316, 5))
        machine.onRepeatUpgrade()
        // A different wall is now cheapest; the repeat must select it, not the old one.
        val action = machine.onRows(scan(13, 230))
        assertTrue(action is AutoWallsMachine.Action.TapRow)
        assertEquals(13, (action as AutoWallsMachine.Action.TapRow).candidate.level)
    }

    @Test
    fun `repeat outside cooldown is refused without changing state`() {
        val machine = readyMachine()
        assertEquals(AutoWallsState.SCANNING_WALLS, machine.state)
        val action = machine.onRepeatUpgrade()
        assertTrue(action is AutoWallsMachine.Action.Stop)
        assertEquals(AutoWallsState.SCANNING_WALLS, machine.state)
    }

    // ---- timeouts and unexpected steps -------------------------------------

    @Test
    fun `a timeout while reading the dialog fails the execution`() {
        val machine = readyMachine()
        machine.onRows(listOf(candidate("row:2", level = 17, price = 2_500_000)))
        machine.onRowTapResult(true)
        val action = machine.onTimeout("confirmation dialog timed out")
        assertTrue(action is AutoWallsMachine.Action.Stop)
        assertEquals(AutoWallsState.EXECUTION_FAILED, machine.state)
        assertEquals(WallEventStatus.FAILED, machine.events.last().status)
    }

    @Test
    fun `a timeout before a candidate is known is an unknown screen`() {
        val machine = readyMachine()
        val action = machine.onTimeout("scan timed out")
        assertTrue(action is AutoWallsMachine.Action.Stop)
        assertEquals(AutoWallsState.UNKNOWN_SCREEN, machine.state)
    }

    @Test
    fun `an out-of-order result stops without changing state`() {
        val machine = AutoWallsMachine(AutoWallsConfig(enabled = true))
        val action = machine.onRows(listOf(candidate("row:0", level = 10, price = 1_000)))
        assertTrue(action is AutoWallsMachine.Action.Stop)
        assertEquals(AutoWallsState.WAITING_FOR_TRIGGER, machine.state)
        assertEquals("unexpected step while WAITING_FOR_TRIGGER", (action as AutoWallsMachine.Action.Stop).reason)
        assertTrue(machine.events.isEmpty())
    }

    @Test
    fun `a stopped run never replays its stale candidate`() {
        val machine = readyMachine()
        machine.onRows(listOf(candidate("row:9", level = 12, price = 200_000)))
        machine.onRowTapResult(false)
        assertEquals(AutoWallsState.EXECUTION_FAILED, machine.state)
        // Any further step on the dead run is refused, so nothing is tapped twice.
        assertTrue(machine.onConfirmTapResult(true) is AutoWallsMachine.Action.Stop)
        assertTrue(machine.onVerification(emptyList()) is AutoWallsMachine.Action.Stop)
        assertEquals(AutoWallsState.EXECUTION_FAILED, machine.state)
        assertFalse(machine.statuses().contains(WallEventStatus.VERIFIED))
    }

    @Test
    fun `a stopped run recovers through disable and enable`() {
        val machine = readyMachine()
        machine.onRows(listOf(candidate("row:9", level = 12, price = 200_000)))
        machine.onRowTapResult(false)
        assertTrue(machine.state.isTerminal)
        machine.disable()
        machine.enable()
        assertEquals(AutoWallsState.WAITING_FOR_TRIGGER, machine.state)
        assertEquals(AutoWallsMachine.Action.ScanRows, machine.onTrigger(passEvaluation()))
    }

    // ---- end to end --------------------------------------------------------

    @Test
    fun `the full workflow records detected, selected, validated, attempted and verified in order`() {
        val c = candidate("row:4", level = 14, price = 500_000, listIndex = 4, currency = "elixir", count = 12)
        val run = readyMachine()
        run.onRows(listOf(c))
        run.onRowTapResult(true)
        confirmCurrency = "elixir"
        run.onConfirmation(confirmationLines(14, "500 000")) { currencyAt(it) }
        run.onConfirmTapResult(true)
        run.onVerification(listOf(c.copy(count = 11)))
        run.onCooldownElapsed()

        assertEquals(
            listOf(
                WallEventStatus.DETECTED,
                WallEventStatus.SELECTED,
                WallEventStatus.CONFIRMATION_VALIDATED,
                WallEventStatus.ATTEMPTED,
                WallEventStatus.VERIFIED
            ),
            run.statuses()
        )
        assertEquals(1, run.statuses().count { it == WallEventStatus.VERIFIED })
        assertEquals(AutoWallsState.WAITING_FOR_TRIGGER, run.state)
    }
}

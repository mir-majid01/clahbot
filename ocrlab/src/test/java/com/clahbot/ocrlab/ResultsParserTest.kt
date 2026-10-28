package com.clahbot.ocrlab

import com.clahbot.ocr.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultsParserTest {

    private fun line(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): OcrLine = OcrLine(text, OcrBox(left, top, right, bottom), emptyList())

    private fun victoryPage(
        resultText: String = "VicoRY",
        darkRow: String = "3 007",
        sourPanel: Boolean = false
    ): OcrPage {
        val lines = mutableListOf(
            line("Total damage:", 1100, 80, 1400, 140),
            line(resultText, 1000, 240, 1300, 310),
            line("You got:", 1050, 380, 1300, 430),
            line("S03 859", 1100, 440, 1300, 490),
            line("603 605", 1100, 525, 1300, 575),
            line(darkRow, 1100, 610, 1300, 660),
            line("Troops expended:", 1000, 700, 1400, 750),
            line("100% Bonus:", 1550, 430, 1800, 480),
            line("37000o", 1550, 490, 1750, 540),
            line("+370000", 1550, 550, 1750, 600),
            line("+2750", 1550, 610, 1750, 660),
            line("18 389 880", 1700, 30, 1950, 80),
            line("19 074 837", 1700, 90, 1950, 140),
            line("279 811", 1700, 150, 1950, 200)
        )
        if (sourPanel) {
            lines += line("Sour Elixir", 600, 580, 900, 620)
            lines += line("300", 700, 620, 800, 650)
        }
        return OcrPage(2576, 1159, lines)
    }

    private fun defeatPage(): OcrPage = OcrPage(
        2576,
        1159,
        listOf(
            line("Total damage:", 1100, 80, 1400, 140),
            line("Defeat", 1000, 240, 1300, 310),
            line("You got:", 1050, 380, 1300, 430),
            line("363 609", 1100, 440, 1300, 490),
            line("241 528", 1100, 525, 1300, 575),
            line("4 959", 1100, 610, 1300, 660),
            line("Troops expended:", 1000, 700, 1400, 750),
            line("16 584 G26", 1700, 30, 1950, 80),
            line("17 O06 S62", 1700, 90, 1950, 140),
            line("263 839", 1700, 150, 1950, 200)
        )
    )

    private fun sampler(): IconColorSampler = IconColorSampler { box ->
        when (box.top) {
            440, 490 -> ResourceKind.GOLD
            500, 550 -> ResourceKind.ELIXIR
            560, 610 -> ResourceKind.DARK
            else -> null
        }
    }

    private fun readings(inspection: Inspection): List<RowReading> {
        val result = mutableListOf<RowReading>()
        fun add(rows: List<RowCandidate>, role: RowRole) {
            for ((index, row) in rows.withIndex()) {
                val value = when (role) {
                    RowRole.BONUS -> NumberParser.parseBonus(row.rawText)
                    else -> NumberParser.parseLoot(row.rawText)
                }
                result += RowReading(
                    role = role,
                    index = index,
                    resource = row.resource,
                    box = row.box,
                    reads = emptyList(),
                    combined = value,
                    sources = if (value != null) listOf(Readers.CONSENSUS) else emptyList(),
                    status = if (value != null) "consensus" else "not_captured"
                )
            }
        }
        add(inspection.lootRows, RowRole.LOOT)
        add(inspection.bonusRows, RowRole.BONUS)
        add(inspection.balanceRows, RowRole.BALANCE)
        return result
    }

    @Test
    fun bonusTitlePercentageDoesNotMatterAndBonusStaysSeparate() {
        val page = OcrPage(
            2576,
            1159,
            listOf(
                line("Total damage:", 1100, 80, 1400, 140),
                line("VICTORY", 1000, 240, 1300, 310),
                line("You got:", 1050, 380, 1300, 430),
                line("51 824", 1100, 440, 1300, 490),
                line("373 643", 1100, 525, 1300, 575),
                line("1 731", 1100, 610, 1300, 660),
                line("Troops expended:", 1000, 700, 1400, 750),
                line("80% Bonus:", 1550, 380, 1800, 430),
                line("+200000", 1550, 440, 1750, 490),
                line("+200000", 1550, 500, 1750, 550),
                line("+1280", 1550, 560, 1750, 610),
                line("18 389 880", 1700, 30, 1950, 80),
                line("19 074 837", 1700, 90, 1950, 140),
                line("279 811", 1700, 150, 1950, 200)
            )
        )
        val inspection = requireNotNull(ResultsScreenParser(sampler()).inspect(page))
        assertTrue(inspection.bonusPanel)
        assertEquals(3, inspection.bonusRows.size)
        val read = ResultsAssembler.assemble(inspection, readings(inspection))
        assertEquals(51_824L, read.gold)
        assertEquals(373_643L, read.elixir)
        assertEquals(1_731L, read.darkElixir)
        assertEquals(200_000L, read.bonusGold)
        assertEquals(200_000L, read.bonusElixir)
        assertEquals(1_280L, read.bonusDarkElixir)
        assertFalse(read.needsReview)
    }

    @Test
    fun findsOnlyLootRowsInTheValueColumnAndExcludesTheSourPanel() {
        val inspection = requireNotNull(
            ResultsScreenParser(sampler()).inspect(victoryPage(sourPanel = true))
        )

        assertEquals(3, inspection.lootRows.size)
        assertTrue(inspection.lootRows.none { it.rawText.contains("300") })
        assertEquals(
            listOf(ResourceKind.GOLD, ResourceKind.ELIXIR, ResourceKind.DARK),
            inspection.lootRows.map { it.resource }
        )
        assertEquals(3, inspection.bonusRows.size)
        assertEquals(3, inspection.balanceRows.size)
    }

    @Test
    fun valuesAreParsedWithSoftGroupingAndLetterCandidates() {
        val inspection = requireNotNull(ResultsScreenParser(sampler()).inspect(victoryPage()))
        val read = ResultsAssembler.assemble(inspection, readings(inspection))

        assertEquals(ResultsOutcome.VICTORY, read.outcome)
        assertEquals(503_859L, read.gold)
        assertEquals(603_605L, read.elixir)
        assertEquals(3_007L, read.darkElixir)
        assertEquals(370_000L, read.bonusGold)
        assertEquals(370_000L, read.bonusElixir)
        assertEquals(2_750L, read.bonusDarkElixir)
        assertEquals(18_389_880L, read.balanceGold)
        assertEquals(19_074_837L, read.balanceElixir)
        assertEquals(279_811L, read.balanceDarkElixir)
        assertFalse(read.needsReview)
        assertNull(read.stars)
    }

    @Test
    fun aNumberSplitAcrossTwoLinesKeepsTheLeadingDigit() {
        val page = OcrPage(
            2576,
            1159,
            listOf(
                line("Total damage:", 1100, 80, 1400, 140),
                line("VICTORY", 1000, 240, 1300, 310),
                line("You got:", 1050, 380, 1300, 430),
                line("1", 1080, 440, 1095, 490),
                line("163 088", 1100, 440, 1300, 490),
                line("962 498", 1100, 525, 1300, 575),
                line("2 796", 1100, 610, 1300, 660),
                line("Troops expended:", 1000, 700, 1400, 750),
                line("18 389 880", 1700, 30, 1950, 80),
                line("19 074 837", 1700, 90, 1950, 140),
                line("279 811", 1700, 150, 1950, 200)
            )
        )
        val inspection = requireNotNull(ResultsScreenParser(sampler()).inspect(page))
        assertEquals("1 163 088", inspection.lootRows[0].rawText)
        val read = ResultsAssembler.assemble(inspection, readings(inspection))
        assertEquals(1_163_088L, read.gold)
        assertEquals(962_498L, read.elixir)
        assertEquals(2_796L, read.darkElixir)
    }

    @Test
    fun aWarResultWithTotalDestructionIsStillAResultScreen() {
        val page = victoryPage()
        val war = OcrPage(
            page.width,
            page.height,
            page.lines.map { line ->
                if (line.text.contains("Total damage")) line.copy(text = "Total destruction:") else line
            }
        )
        assertTrue(ResultsScreenParser(sampler()).inspect(war) != null)
    }

    @Test
    fun lootRowsFollowTheFixedOrderEvenWhenIconColoursDisagree() {
        val swapped = IconColorSampler { box ->
            when (box.top) {
                440, 490 -> ResourceKind.ELIXIR
                500, 550 -> ResourceKind.GOLD
                560, 610 -> ResourceKind.DARK
                else -> null
            }
        }
        val inspection = requireNotNull(ResultsScreenParser(swapped).inspect(victoryPage()))
        assertEquals(
            listOf(ResourceKind.GOLD, ResourceKind.ELIXIR, ResourceKind.DARK),
            inspection.lootRows.map { it.resource }
        )

        val read = ResultsAssembler.assemble(inspection, readings(inspection))
        assertEquals(503_859L, read.gold)
        assertEquals(603_605L, read.elixir)
        assertEquals(3_007L, read.darkElixir)
    }

    @Test
    fun slotSelectionIgnoresANarrowGarbageNumberInTheLootColumn() {
        val page = victoryPage()
        val noisy = OcrPage(
            page.width,
            page.height,
            page.lines + line("85", 1397, 525, 1405, 539)
        )
        val inspection = requireNotNull(ResultsScreenParser(sampler()).inspect(noisy))
        assertEquals(3, inspection.lootRows.size)
        assertTrue(inspection.lootRows[0].rawText.contains("859"))
        val read = ResultsAssembler.assemble(inspection, readings(inspection))
        assertEquals(503_859L, read.gold)
    }

    @Test
    fun zeroLootRowsAreSynthesizedFromTheAnchorsWhenOcrHasNoNumberLine() {
        val page = victoryPage()
        val noLoot = OcrPage(
            page.width,
            page.height,
            page.lines.filterNot { it.text.contains("859") || it.text.contains("605") || it.text.contains("007") }
        )
        val inspection = requireNotNull(ResultsScreenParser(sampler()).inspect(noLoot))
        assertEquals(3, inspection.lootRows.size)
        assertEquals(
            listOf(ResourceKind.GOLD, ResourceKind.ELIXIR, ResourceKind.DARK),
            inspection.lootRows.map { it.resource }
        )
        assertTrue(inspection.lootRows.all { it.rawText.isEmpty() })
    }

    @Test
    fun ungroupedLootIsAcceptedNowThatGroupingIsSoft() {
        val inspection = requireNotNull(
            ResultsScreenParser(sampler()).inspect(victoryPage(darkRow = "4959"))
        )
        val read = ResultsAssembler.assemble(inspection, readings(inspection))
        assertEquals(4_959L, read.darkElixir)
        assertFalse(read.needsReview)
    }

    @Test
    fun duplicateIconColoursFallBackToTheFixedLootRowOrder() {
        val duplicateDark = IconColorSampler { box ->
            when (box.top) {
                440, 490 -> ResourceKind.GOLD
                500, 550 -> ResourceKind.DARK
                560, 610 -> ResourceKind.DARK
                else -> null
            }
        }
        val inspection = requireNotNull(ResultsScreenParser(duplicateDark).inspect(victoryPage()))
        assertEquals(
            listOf(ResourceKind.GOLD, ResourceKind.ELIXIR, ResourceKind.DARK),
            inspection.lootRows.map { it.resource }
        )
        val read = ResultsAssembler.assemble(inspection, readings(inspection))
        assertEquals(503_859L, read.gold)
        assertEquals(603_605L, read.elixir)
        assertEquals(3_007L, read.darkElixir)
    }

    @Test
    fun defeatHasLootButNoBonusRows() {
        val inspection = requireNotNull(ResultsScreenParser(sampler()).inspect(defeatPage()))
        val read = ResultsAssembler.assemble(inspection, readings(inspection))

        assertEquals(ResultsOutcome.DEFEAT, read.outcome)
        assertEquals(363_609L, read.gold)
        assertEquals(241_528L, read.elixir)
        assertEquals(4_959L, read.darkElixir)
        assertNull(read.bonusGold)
        assertNull(read.bonusElixir)
        assertNull(read.bonusDarkElixir)
        assertEquals(16_584_626L, read.balanceGold)
        assertEquals(17_006_562L, read.balanceElixir)
        assertEquals(263_839L, read.balanceDarkElixir)
        assertTrue(inspection.bonusRows.isEmpty())
    }

    @Test
    fun aScreenWithReturnHomeButNoAnchorsIsNotAResult() {
        val page = OcrPage(
            2576,
            1159,
            listOf(
                line("Searching for opponents", 900, 400, 1700, 470),
                line("Return Home", 200, 900, 500, 980)
            )
        )
        val parser = ResultsScreenParser(sampler())
        assertNull(parser.inspect(page))
        assertTrue(parser.ignoredReason(page).contains("You got"))
    }

    @Test
    fun geometryKeepsLootRowsWithoutAnIconSamplerAndDropsBonusRows() {
        val inspection = requireNotNull(
            ResultsScreenParser { null }.inspect(victoryPage())
        )
        assertEquals(3, inspection.lootRows.size)
        assertEquals(
            listOf(ResourceKind.GOLD, ResourceKind.ELIXIR, ResourceKind.DARK),
            inspection.lootRows.map { it.resource }
        )
        assertTrue(inspection.bonusRows.isEmpty())
        assertTrue(inspection.issues.any { it.contains("icon colour unknown") })

        val read = ResultsAssembler.assemble(inspection, readings(inspection))
        assertEquals(503_859L, read.gold)
        assertEquals(603_605L, read.elixir)
        assertEquals(3_007L, read.darkElixir)
        assertTrue(read.needsReview)
    }

    @Test
    fun missingLootStaysNullNotZero() {
        val page = OcrPage(
            2576,
            1159,
            listOf(
                line("Total damage:", 1100, 80, 1400, 140),
                line("VICTORY", 1000, 240, 1300, 310),
                line("You got:", 1050, 380, 1300, 430),
                line("Troops expended:", 1000, 650, 1400, 700)
            )
        )
        val inspection = requireNotNull(ResultsScreenParser(sampler()).inspect(page))
        val read = ResultsAssembler.assemble(inspection, readings(inspection))
        assertNull(read.gold)
        assertNull(read.elixir)
        assertNull(read.darkElixir)
        assertTrue(read.needsReview)
    }
}

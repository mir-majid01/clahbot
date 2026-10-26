package com.clahbot.ocrlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RowConsensusTest {

    private fun read(reader: String, vararg values: Long, error: String? = null): EngineRowRead =
        EngineRowRead(
            reader = reader,
            text = values.joinToString(" "),
            candidates = values.toList(),
            chosen = values.singleOrNull(),
            error = error
        )

    @Test
    fun twoIndependentReadersAgree() {
        val result = RowConsensus.resolve(
            listOf(
                read(Readers.MLKIT_FULL, 417_994),
                read(Readers.MLKIT_CROP, 417_994)
            )
        )
        assertEquals(417_994L, result.value)
        assertEquals("consensus", result.status)
        assertEquals(2, result.sources.size)
    }

    @Test
    fun aSingleReaderIsNeverAccepted() {
        val result = RowConsensus.resolve(
            listOf(read(Readers.MLKIT_CROP, 417_994))
        )
        assertNull(result.value)
        assertEquals("single_reader", result.status)
    }

    @Test
    fun twoDisagreeingPairsAreAConflict() {
        val result = RowConsensus.resolve(
            listOf(
                read(Readers.MLKIT_FULL, 503_859),
                read(Readers.MLKIT_CROP, 603_859),
                read(Readers.RAPIDOCR_CROP, 503_859),
                read(Readers.TEMPLATE_CROP, 603_859)
            )
        )
        assertNull(result.value)
        assertEquals("conflict", result.status)
    }

    @Test
    fun aStrictMajorityBeatsACorrelatedPair() {
        val result = RowConsensus.resolve(
            listOf(
                read(Readers.MLKIT_FULL, 3_100_000),
                read(Readers.MLKIT_CROP, 3_100_000),
                read(Readers.TESSERACT_CROP, 310_000),
                read(Readers.RAPIDOCR_CROP, 310_000),
                read(Readers.TEMPLATE_CROP, 310_000)
            )
        )
        assertEquals(310_000L, result.value)
        assertEquals("consensus", result.status)
        assertEquals(3, result.sources.size)
    }

    @Test
    fun aTieBetweenTwoPairsIsAConflict() {
        val result = RowConsensus.resolve(
            listOf(
                read(Readers.MLKIT_FULL, 3_100_000),
                read(Readers.MLKIT_CROP, 3_100_000),
                read(Readers.TESSERACT_CROP, 310_000),
                read(Readers.RAPIDOCR_CROP, 310_000)
            )
        )
        assertNull(result.value)
        assertEquals("conflict", result.status)
    }

    @Test
    fun aMultiCandidateReaderCanConfirmAnotherReader() {
        val result = RowConsensus.resolve(
            listOf(
                read(Readers.MLKIT_CROP, 200_960, 1_200_960),
                read(Readers.RAPIDOCR_CROP, 1_200_960)
            )
        )
        assertEquals(1_200_960L, result.value)
        assertEquals("consensus", result.status)
    }

    @Test
    fun balanceMathCanConfirmAValueOnlyOneReaderSaw() {
        val result = RowConsensus.resolve(
            listOf(read(Readers.MLKIT_FULL, 503_859)),
            expectedFromMath = 503_859
        )
        assertEquals(503_859L, result.value)
        assertEquals("balance_math", result.status)
        assertEquals(listOf(Readers.MLKIT_FULL, Readers.BALANCE_MATH), result.sources)
    }

    @Test
    fun balanceMathCannotInventAValueNoReaderSaw() {
        val result = RowConsensus.resolve(
            listOf(read(Readers.MLKIT_FULL, 603_859)),
            expectedFromMath = 503_859
        )
        assertNull(result.value)
        assertEquals("single_reader", result.status)
    }

    @Test
    fun readersWithErrorsDoNotVote() {
        val result = RowConsensus.resolve(
            listOf(
                read(Readers.MLKIT_FULL, 417_994),
                read(Readers.TESSERACT_CROP, error = "failed")
            )
        )
        assertNull(result.value)
        assertEquals("single_reader", result.status)
    }
}
